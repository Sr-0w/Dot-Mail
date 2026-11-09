package app.dot.mail.ui.settings

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.mail.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The reviewed build-generated bundle is local and available without network permission. */
@Composable
internal fun LicenceNotices() {
    val assets = LocalContext.current.assets
    val paragraphs by
        produceState<List<String>?>(null, assets) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                            assets
                                .open("third-party-notices.txt")
                                .bufferedReader(Charsets.UTF_8)
                                .use { it.readText().split("\n\n").filter(String::isNotBlank) }
                        }
                        .getOrDefault(emptyList())
                }
        }
    if (paragraphs?.isEmpty() == true) {
        Text(
            stringResource(R.string.settings_licences_unavailable),
            color = Dot.colors.textSecondary,
        )
    } else {
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(paragraphs.orEmpty()) { paragraph ->
                SelectionContainer {
                    Text(
                        paragraph + "\n",
                        style = Dot.type.caption,
                        color = Dot.colors.textSecondary,
                    )
                }
            }
        }
    }
}
