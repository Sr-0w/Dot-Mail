package app.dot.mail.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.BackButton
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotIconButton
import app.dot.design.components.DotTextField
import app.dot.design.components.DotTopBar
import app.dot.design.components.Hairline
import app.dot.design.components.ListRow
import app.dot.design.components.SectionLabel
import app.dot.mail.LocalSettings
import app.dot.mail.data.model.Account

/** Settings → Privacy & security → Remote images: the global choice, the allowlist, and remembered messages. */
@Composable
internal fun RemoteImagesPage(accounts: List<Account>, onBack: () -> Unit) {
    val c = Dot.colors
    val settings = LocalSettings.current
    val prefs by settings.ui.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    val policy = remember { RemoteImagePolicy.get(appContext) }
    val rules by policy.rules.collectAsStateWithLifecycle()
    val approvals by policy.approvals.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize().background(c.background)) {
        DotTopBar(title = "Remote images", elevated = scroll.value > 0, navigation = { BackButton(onBack) })
        Column(Modifier.verticalScroll(scroll).navigationBarsPadding()) {
            Text(
                "Remote images are blocked by default. Senders can use them to see when, where and on what device you open a message.",
                style = Dot.type.callout, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
            )
            ToggleRow(
                "Always load remote images", "Everyone can then see that you read their mail. Prefer allowing individual senders below.",
                Icons.Outlined.Image, prefs.loadRemoteImages,
            ) { v -> settings.update { it.copy(loadRemoteImages = v) } }
            Hairline(Modifier.padding(vertical = DotSpacing.s))

            SectionLabel("Always load for")
            if (rules.isEmpty()) {
                Text("No senders or domains yet. Open a message and choose “Always for …” from its image banner.",
                    style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs))
            }
            rules.forEach { rule ->
                val scope = if (rule.accountId == 0L) "All accounts" else accounts.firstOrNull { it.id == rule.accountId }?.email ?: "Removed account"
                val title = if (rule.isDomain) "@${rule.value}" else rule.value
                ListRow(
                    title = title, subtitle = if (rule.isDomain) "Domain and its subdomains · $scope" else "Sender · $scope",
                    trailing = { DotIconButton(Icons.Outlined.Close, "Remove rule for $title", onClick = { policy.removeRule(rule) }, tint = c.textSecondary) },
                )
            }
            ListRow("Add sender or domain", icon = Icons.Outlined.Add, onClick = { adding = true })
            if (rules.isNotEmpty()) ListRow("Reset all rules", icon = Icons.Outlined.DeleteSweep, titleColor = c.danger, onClick = { confirmReset = true })
            Hairline(Modifier.padding(vertical = DotSpacing.s))

            SectionLabel("Remembered messages")
            ListRow(
                "Forget remembered messages", icon = Icons.Outlined.DeleteSweep,
                subtitle = if (approvals.isEmpty()) "No message has been approved yet." else "${approvals.size} messages are set to show their images.",
                titleColor = if (approvals.isEmpty()) c.textTertiary else c.textPrimary,
                onClick = if (approvals.isEmpty()) null else ({ policy.forgetAllMessages() }),
            )
            Spacer(Modifier.height(DotSpacing.xl))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = c.surface, shape = DotShapes.large,
            title = { Text("Reset all rules?", style = Dot.type.title, color = c.textPrimary) },
            text = { Text("Images will be blocked again for every sender and domain on the list.", style = Dot.type.callout, color = c.textSecondary) },
            confirmButton = { DotButton("Reset", style = ButtonStyle.Danger, compact = true, onClick = { confirmReset = false; policy.resetRules() }) },
            dismissButton = { DotButton("Cancel", { confirmReset = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
    if (adding) AddRuleDialog(accounts, onDismiss = { adding = false }) { isDomain, value, account -> policy.addRule(isDomain, value, account) }
}

@Composable
private fun AddRuleDialog(accounts: List<Account>, onDismiss: () -> Unit, onAdd: (Boolean, String, Long) -> Boolean) {
    val c = Dot.colors
    var text by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface, shape = DotShapes.large,
        title = { Text("Add sender or domain", style = Dot.type.title, color = c.textPrimary) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DotSpacing.m)) {
                DotTextField(
                    text, { text = it; error = null }, label = "Address or @domain", placeholder = "news@example.org or @example.org",
                    keyboardType = KeyboardType.Email, isError = error != null, supporting = error,
                )
                if (accounts.size > 1) {
                    Column(Modifier.selectableGroup()) {
                        listOf(0L to "All accounts").plus(accounts.map { it.id to it.email }).forEach { (id, label) ->
                            ListRow(
                                label, titleColor = if (account == id) c.textPrimary else c.textSecondary,
                                modifier = Modifier.selectable(selected = account == id, role = Role.RadioButton, onClick = { account = id }),
                                trailing = { if (account == id) Text("✓", style = Dot.type.label, color = c.textPrimary) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            DotButton("Add", enabled = text.isNotBlank(), compact = true, onClick = {
                val t = text.trim()
                val domain = t.startsWith("@") || '@' !in t
                if (onAdd(domain, t, account)) onDismiss() else error = if (domain) "Enter a domain like example.org." else "Enter a full address."
            })
        },
        dismissButton = { DotButton("Cancel", onDismiss, style = ButtonStyle.Ghost, compact = true) },
    )
}
