package com.ivy.wallet.ui.edit.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.datamodel.Account
import com.ivy.ui.R
import com.ivy.wallet.ui.theme.components.ItemIconSDefaultIcon
import com.ivy.wallet.ui.theme.components.IvyIcon
import com.ivy.wallet.ui.theme.findContrastTextColor
import com.ivy.wallet.ui.theme.toComposeColor

@Composable
fun AccountDropdown(
    accounts: List<Account>,
    selectedAccount: Account?,
    onSelectedAccountChanged: (Account) -> Unit,
    onAddNewAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(UI.shapes.rFull)
                .border(2.dp, UI.colors.medium, UI.shapes.rFull)
                .background(
                    selectedAccount?.color?.toComposeColor() ?: UI.colors.pure,
                    UI.shapes.rFull
                )
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val textColor = selectedAccount?.color?.toComposeColor()?.let {
                findContrastTextColor(it)
            } ?: UI.colors.pureInverse

            if (selectedAccount != null) {
                ItemIconSDefaultIcon(
                    iconName = selectedAccount.icon,
                    defaultIcon = R.drawable.ic_custom_account_s,
                    tint = textColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = selectedAccount.name,
                    style = UI.typo.b2.style(
                        color = textColor,
                        fontWeight = FontWeight.ExtraBold
                    )
                )
            } else {
                Text(
                    text = stringResource(R.string.account),
                    style = UI.typo.b2.style(
                        color = textColor,
                        fontWeight = FontWeight.ExtraBold
                    )
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IvyIcon(
                icon = R.drawable.ic_expand_more,
                tint = textColor
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(UI.colors.pure)
        ) {
            accounts.forEach { account ->
                val accountColor = account.color.toComposeColor()
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(UI.shapes.rFull)
                                    .background(accountColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = account.name,
                                style = UI.typo.b2.style(
                                    color = UI.colors.pureInverse,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    },
                    onClick = {
                        onSelectedAccountChanged(account)
                        expanded = false
                    }
                )
            }
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IvyIcon(
                            icon = R.drawable.ic_plus,
                            tint = UI.colors.pureInverse
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.add_account),
                            style = UI.typo.b2.style(
                                color = UI.colors.pureInverse,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                },
                onClick = {
                    onAddNewAccount()
                    expanded = false
                }
            )
        }
    }
}
