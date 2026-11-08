package app.dot.mail.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dot.mail.R
import app.dot.mail.data.aliases.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun AliasesPage(onBack:()->Unit) {
 val ctx= LocalContext.current
 val store= remember { AliasSettings.create(ctx) }
 var provider by remember { mutableStateOf("simplelogin") }; var key by remember { mutableStateOf("") }; var aliases by remember { mutableStateOf(emptyList<EmailAlias>()) }; var error by remember { mutableStateOf<String?>(null) }
 val scope= rememberCoroutineScope()
 fun client(): AliasProvider=if(provider=="addy") AddyClient() else SimpleLoginClient()
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
  TextButton(onClick=onBack){Text(stringResource(R.string.aliases_back))}
  Text(stringResource(R.string.aliases_title),style=MaterialTheme.typography.headlineSmall)
  Row { FilterChip(provider=="simplelogin",{provider="simplelogin"},label={Text("SimpleLogin")}); Spacer(Modifier.width(8.dp)); FilterChip(provider=="addy",{provider="addy"},label={Text("addy.io")}) }
  OutlinedTextField(key,{key=it},label={Text(stringResource(R.string.aliases_api_key))},singleLine=true)
  Row { Button(onClick={scope.launch { try { store.save(provider,key); aliases=withContext(Dispatchers.IO){client().list(key)}; error=null } catch(e:Exception){error=e.message} }}){Text(stringResource(R.string.aliases_connect))}; TextButton(onClick={scope.launch {try { aliases=withContext(Dispatchers.IO){client().create(key)}.let{aliases+it} }catch(e:Exception){error=e.message}}}){Text(stringResource(R.string.aliases_create))} }
  error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
  aliases.forEach { a -> ListItem(headlineContent={Text(a.email)}, trailingContent={TextButton(onClick={scope.launch { try { val updated=withContext(Dispatchers.IO){client().setEnabled(key,a.id,!a.active)}; aliases=aliases.map{if(it.id==a.id)updated else it} }catch(e:Exception){error=e.message}}}){Text(if(a.active)stringResource(R.string.aliases_disable) else stringResource(R.string.aliases_enable))}}) }
 }
}
