package app.dot.mail.ui.inbox

import app.dot.mail.data.model.UndoToken
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ReaderUndoBusTest {
    @Test fun `a reader action reaches the inbox once, with its text and token`(): Unit = runBlocking {
        val token = UndoToken(listOf(7L to 3L))
        ReaderUndoBus.post("Archived", token)
        val (text, got) = ReaderUndoBus.events().first()
        assertThat(text).isEqualTo("Archived")
        assertThat(got).isEqualTo(token)
    }

    @Test fun `an old entry is dropped instead of offering a surprising undo later`(): Unit = runBlocking {
        val now = 1_000_000L
        ReaderUndoBus.post("stale", UndoToken(listOf(1L to 1L)), at = now - ReaderUndoBus.MAX_AGE_MS - 1)
        ReaderUndoBus.post("fresh", UndoToken(listOf(2L to 1L)), at = now)
        assertThat(ReaderUndoBus.events { now }.first().first).isEqualTo("fresh")
    }
}
