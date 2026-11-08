package app.dot.mail.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.dot.mail.R
import app.dot.mail.data.aliases.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun AliasesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AliasSettings.create(context) }
    var provider by remember { mutableStateOf("simplelogin") }
    var key by remember(provider) { mutableStateOf(store.key(provider).orEmpty()) }
    var aliases by remember { mutableStateOf(emptyList<EmailAlias>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val client: AliasProvider = if (provider == "addy") AddyClient() else SimpleLoginClient()
    fun launchRequest(block: suspend () -> Unit) {
        scope.launch { busy = true; try { block(); message = null } catch (_: Exception) { message = context.getString(R.string.aliases_error) } finally { busy = false } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.aliases_back)) }
        Text(stringResource(R.string.aliases_title), style = MaterialTheme.typography.headlineSmall)
        Row {
            FilterChip(provider == "simplelogin", { provider = "simplelogin" }, label = { Text("SimpleLogin") })
            Spacer(Modifier.width(8.dp))
            FilterChip(provider == "addy", { provider = "addy" }, label = { Text("addy.io") })
        }
        OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.aliases_api_key)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Row {
            Button(enabled = !busy && key.isNotBlank(), onClick = { launchRequest { store.save(provider, key); aliases = withContext(Dispatchers.IO) { client.list(key) } } }) { Text(stringResource(R.string.aliases_test)) }
            TextButton(enabled = !busy && key.isNotBlank(), onClick = { launchRequest { aliases = withContext(Dispatchers.IO) { client.create(key) }.let { aliases + it } } }) { Text(stringResource(R.string.aliases_create)) }
            TextButton(onClick = { store.remove(provider); key = ""; aliases = emptyList() }) { Text(stringResource(R.string.aliases_remove_key)) }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        aliases.forEach { alias ->
            ListItem(headlineContent = { Text(alias.email) }, trailingContent = {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("email alias", alias.email)) }) { Text(stringResource(R.string.aliases_copy)) }
                    Switch(checked = alias.active, enabled = !busy, onCheckedChange = { enabled -> launchRequest { val updated = withContext(Dispatchers.IO) { client.setEnabled(key, alias.id, enabled) }; aliases = aliases.map { if (it.id == alias.id) updated else it.copy(active = enabled) } } })
                }
            })
        }
    }
}
