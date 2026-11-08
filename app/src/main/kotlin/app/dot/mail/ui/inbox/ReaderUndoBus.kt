package app.dot.mail.ui.inbox

import app.dot.mail.data.model.UndoToken
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Hands an action taken in the reader (archive, delete, spam) to the inbox, which shows the same Undo snackbar a swipe
 * shows. The inbox ViewModel lives on the back stack under the reader, so it is still collecting when the reader closes.
 */
object ReaderUndoBus {
    class Entry(val text: String, val token: UndoToken, val at: Long)

    private val channel = Channel<Entry>(Channel.BUFFERED)

    /** Entries older than [MAX_AGE_MS] are dropped: an undo offered long after the action would be confusing. */
    fun events(now: () -> Long = System::currentTimeMillis): Flow<Pair<String, UndoToken>> =
        channel.receiveAsFlow().filter { now() - it.at <= MAX_AGE_MS }.map { it.text to it.token }

    fun post(text: String, token: UndoToken, at: Long = System.currentTimeMillis()) { channel.trySend(Entry(text, token, at)) }

    const val MAX_AGE_MS = 15_000L
}
