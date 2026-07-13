package com.ivy.data.sync

import android.content.Context
import androidx.core.net.toUri
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.ivy.base.legacy.SharedPrefs
import com.ivy.data.backup.BackupDataUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleDriveSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val sharedPrefs: SharedPrefs,
    private val backupDataUseCase: BackupDataUseCase,
    private val json: Json
) {
    companion object {
        const val SYNC_FILE_NAME = "ivy_wallet_sync.zip"
        const val PREF_GOOGLE_DRIVE_SYNC_ENABLED = "google_drive_sync_enabled"
        const val PREF_GOOGLE_ACCOUNT_EMAIL = "google_account_email"
        const val PREF_LAST_LOCAL_CHANGE_TIME = "last_local_change_time"
        const val PREF_LAST_SYNC_TIME = "last_sync_time"
    }

    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
            val scope = "oauth2:https://www.googleapis.com/auth/drive.appdata"
            GoogleAuthUtil.getToken(context, account.account ?: return@withContext null, scope)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get Google Drive access token")
            null
        }
    }

    fun isGoogleAccountConnected(): Boolean {
        return GoogleSignIn.getLastSignedInAccount(context) != null &&
                sharedPrefs.getBoolean(PREF_GOOGLE_DRIVE_SYNC_ENABLED, false)
    }

    fun getConnectedEmail(): String? {
        return GoogleSignIn.getLastSignedInAccount(context)?.email
    }

    fun disconnect() {
        sharedPrefs.putBoolean(PREF_GOOGLE_DRIVE_SYNC_ENABLED, false)
        sharedPrefs.remove(PREF_GOOGLE_ACCOUNT_EMAIL)
        sharedPrefs.remove(PREF_LAST_SYNC_TIME)
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope("https://www.googleapis.com/auth/drive.appdata"))
                .build()
            val signInClient = GoogleSignIn.getClient(context, gso)
            signInClient.signOut()
        } catch (e: Exception) {
            Timber.e(e, "Failed to sign out Google client")
        }
    }

    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        if (!isGoogleAccountConnected()) {
            return@withContext false
        }
        val token = getAccessToken() ?: return@withContext false
        try {
            val searchResponse = httpClient.get("https://www.googleapis.com/drive/v3/files") {
                header("Authorization", "Bearer $token")
                parameter("q", "name = '$SYNC_FILE_NAME' and 'appDataFolder' in parents")
                parameter("spaces", "appDataFolder")
                parameter("fields", "files(id, name, modifiedTime, properties)")
            }

            if (searchResponse.status.value !in 200..299) {
                Timber.e("Search failed: ${searchResponse.bodyAsText()}")
                return@withContext false
            }

            val searchBody = searchResponse.bodyAsText()
            val searchJson = json.parseToJsonElement(searchBody).jsonObject
            val files = searchJson["files"]?.jsonArray ?: JsonArray(emptyList())

            val lastLocalChange = sharedPrefs.getLong(PREF_LAST_LOCAL_CHANGE_TIME, 0L)

            if (files.isEmpty()) {
                uploadNewBackup(token, lastLocalChange)
            } else {
                val fileObj = files[0].jsonObject
                val fileId = fileObj["id"]?.jsonPrimitive?.content ?: return@withContext false
                val properties = fileObj["properties"]?.jsonObject
                val remoteModifiedTimeStr = properties?.get("last_local_modified")?.jsonPrimitive?.content
                val remoteModifiedTime = remoteModifiedTimeStr?.toLongOrNull() ?: 0L

                if (remoteModifiedTime > lastLocalChange) {
                    downloadAndImportBackup(token, fileId, remoteModifiedTime)
                } else if (lastLocalChange > remoteModifiedTime) {
                    updateBackup(token, fileId, lastLocalChange)
                } else {
                    Timber.d("Local and remote databases are in sync.")
                }
            }

            sharedPrefs.putLong(PREF_LAST_SYNC_TIME, System.currentTimeMillis())
            true
        } catch (e: Exception) {
            Timber.e(e, "Google Drive Sync failed")
            false
        }
    }

    private suspend fun uploadNewBackup(token: String, lastLocalChange: Long) {
        val tempZipFile = File(context.cacheDir, SYNC_FILE_NAME)
        if (tempZipFile.exists()) tempZipFile.delete()

        backupDataUseCase.exportToFile(tempZipFile.toUri())

        try {
            val metadataBody = buildJsonObject {
                put("name", SYNC_FILE_NAME)
                putJsonArray("parents") {
                    add(json.parseToJsonElement("\"appDataFolder\""))
                }
                putJsonObject("properties") {
                    put("last_local_modified", lastLocalChange.toString())
                }
            }.toString()

            val metadataResponse = httpClient.post("https://www.googleapis.com/drive/v3/files") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(metadataBody)
            }
            if (metadataResponse.status.value !in 200..299) {
                Timber.e("Metadata creation failed: ${metadataResponse.bodyAsText()}")
                return
            }

            val fileId = json.parseToJsonElement(metadataResponse.bodyAsText()).jsonObject["id"]?.jsonPrimitive?.content ?: return

            val mediaResponse = httpClient.patch("https://www.googleapis.com/upload/drive/v3/files/$fileId") {
                header("Authorization", "Bearer $token")
                parameter("uploadType", "media")
                contentType(ContentType.Application.Zip)
                setBody(tempZipFile.readBytes())
            }
            if (mediaResponse.status.value !in 200..299) {
                Timber.e("Media upload failed: ${mediaResponse.bodyAsText()}")
            } else {
                Timber.d("New backup successfully uploaded to Google Drive.")
            }
        } finally {
            if (tempZipFile.exists()) tempZipFile.delete()
        }
    }

    private suspend fun updateBackup(token: String, fileId: String, lastLocalChange: Long) {
        val tempZipFile = File(context.cacheDir, SYNC_FILE_NAME)
        if (tempZipFile.exists()) tempZipFile.delete()

        backupDataUseCase.exportToFile(tempZipFile.toUri())

        try {
            val mediaResponse = httpClient.patch("https://www.googleapis.com/upload/drive/v3/files/$fileId") {
                header("Authorization", "Bearer $token")
                parameter("uploadType", "media")
                contentType(ContentType.Application.Zip)
                setBody(tempZipFile.readBytes())
            }
            if (mediaResponse.status.value !in 200..299) {
                Timber.e("Update media upload failed: ${mediaResponse.bodyAsText()}")
                return
            }

            val metadataBody = buildJsonObject {
                putJsonObject("properties") {
                    put("last_local_modified", lastLocalChange.toString())
                }
            }.toString()

            val metadataResponse = httpClient.patch("https://www.googleapis.com/drive/v3/files/$fileId") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(metadataBody)
            }
            if (metadataResponse.status.value !in 200..299) {
                Timber.e("Update metadata properties failed: ${metadataResponse.bodyAsText()}")
            } else {
                Timber.d("Backup successfully updated on Google Drive.")
            }
        } finally {
            if (tempZipFile.exists()) tempZipFile.delete()
        }
    }

    private suspend fun downloadAndImportBackup(token: String, fileId: String, remoteModifiedTime: Long) {
        val tempZipFile = File(context.cacheDir, "downloaded_sync.zip")
        if (tempZipFile.exists()) tempZipFile.delete()

        try {
            val response = httpClient.get("https://www.googleapis.com/drive/v3/files/$fileId") {
                header("Authorization", "Bearer $token")
                parameter("alt", "media")
            }
            if (response.status.value !in 200..299) {
                Timber.e("Failed to download media: ${response.bodyAsText()}")
                return
            }

            val bytes = response.body<ByteArray>()
            FileOutputStream(tempZipFile).use { it.write(bytes) }

            backupDataUseCase.importBackupFile(tempZipFile.toUri()) { progress ->
                Timber.d("Import progress: $progress")
            }

            sharedPrefs.putLong(PREF_LAST_LOCAL_CHANGE_TIME, remoteModifiedTime)
            Timber.d("Google Drive database imported successfully.")
        } finally {
            if (tempZipFile.exists()) tempZipFile.delete()
        }
    }
}
