package app.dot.mail.data

import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.InboxSort
import app.dot.mail.data.model.SmartFolder
import app.dot.mail.data.model.ThreadFilters
import app.dot.mail.engine.text.Category
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThreadQueryTest {
    @Test fun `no filters means no clause`(): Unit {
        assertThat(ThreadQuery.filters(ThreadFilters(), emptyList())).isNull()
    }

    @Test fun `quick filters combine with AND and bind own addresses escaped`(): Unit {
        val q = ThreadQuery.filters(ThreadFilters(unread = true, starred = true, attachments = true, toMe = true), listOf("Me@Example.org", "me@example.org", "a_b@x.org"))!!
        assertThat(q.text).contains("t.unread>0 AND t.starred=1 AND t.attachments=1 AND EXISTS")
        assertThat(q.args).containsExactly("%me@example.org%", "%a!_b@x.org%").inOrder()
        assertThat(q.text.count { it == '?' }).isEqualTo(q.args.size)
    }

    @Test fun `to me with no identities matches nothing`(): Unit {
        assertThat(ThreadQuery.filters(ThreadFilters(toMe = true), emptyList())!!.text).isEqualTo("0")
    }

    @Test fun `smart folder defaults exclude trash spam and drafts`(): Unit {
        val q = ThreadQuery.smartFolder(SmartFolder("1", "All"))
        assertThat(q.text).contains("f.role NOT IN ('TRASH','SPAM','DRAFTS')")
        assertThat(q.args).isEmpty()
    }

    @Test fun `smart folder conditions bind in order`(): Unit {
        val q = ThreadQuery.smartFolder(
            SmartFolder("1", "x", accountIds = setOf(2, 3), roles = setOf(FolderRole.INBOX), from = "Boss", subject = "50%", category = Category.BILL,
                unreadOnly = true, hasAttachment = true, afterMillis = 10, beforeMillis = 20),
        )
        assertThat(q.args).containsExactly(2L, 3L, "INBOX", "%boss%", "%50!%%", "%\"category\":\"BILL\"%", 10L, 20L).inOrder()
        assertThat(q.text.count { it == '?' }).isEqualTo(q.args.size)
        assertThat(q.text).contains("m.unread=1")
        assertThat(q.text).contains("m.hasAttachments=1")
        assertThat(q.text).doesNotContain("NOT IN")
    }

    @Test fun `custom role is ignored and and() joins with parentheses`(): Unit {
        val folder = SmartFolder("1", "x", roles = setOf(FolderRole.CUSTOM))
        assertThat(ThreadQuery.smartFolder(folder).text).contains("NOT IN")
        val joined = ThreadQuery.and(Sql("a=?", listOf(1)), null, Sql("b=?", listOf(2)))
        assertThat(joined.text).isEqualTo("(a=?) AND (b=?)")
        assertThat(joined.args).containsExactly(1, 2).inOrder()
        assertThat(ThreadQuery.and().text).isEqualTo("1")
    }

    @Test fun `every sort has a stable tie-break`(): Unit {
        InboxSort.entries.forEach { assertThat(ThreadQuery.orderBy(it)).endsWith("id DESC") }
        assertThat(ThreadQuery.orderBy(InboxSort.UNREAD_FIRST)).startsWith("(unread>0) DESC")
    }
}
