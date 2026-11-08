package app.dot.mail.ui.thread

import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.Mailbox
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConversationOrderTest {
    private val inbox = Mailbox.Unified(FolderRole.INBOX)
    private val order = ConversationOrder(inbox, listOf(10, 20, 30))

    @Test fun `next and previous follow the list order`() {
        assertThat(order.next(20, inbox)).isEqualTo(30)
        assertThat(order.previous(20, inbox)).isEqualTo(10)
    }

    @Test fun `the ends of the list have no neighbour`() {
        assertThat(order.previous(10, inbox)).isNull()
        assertThat(order.next(30, inbox)).isNull()
    }

    @Test fun `a conversation that is not in the list has no neighbours`() {
        assertThat(order.next(99, inbox)).isNull()
        assertThat(order.previous(99, inbox)).isNull()
    }

    @Test fun `a reader opened from search or a notification never moves`() {
        assertThat(order.next(20, null)).isNull()
        assertThat(ConversationOrder.None.next(20, null)).isNull()
    }

    @Test fun `a list recorded for another mailbox is not used`() {
        assertThat(order.next(20, Mailbox.InFolder(5))).isNull()
        assertThat(order.previous(20, Mailbox.Smart("x"))).isNull()
    }

    @Test fun `recording replaces the shared order`() {
        ThreadOrder.record(inbox, listOf(1, 2))
        assertThat(ThreadOrder.current.next(1, inbox)).isEqualTo(2)
        ThreadOrder.record(Mailbox.InFolder(3), listOf(7))
        assertThat(ThreadOrder.current.next(1, inbox)).isNull()
    }

    @Test fun `only quick mostly horizontal long drags count as a swipe`() {
        val d = 100f
        assertThat(SwipeGuard.resolve(-300f, 20f, 200, d)).isTrue()
        assertThat(SwipeGuard.resolve(300f, 20f, 200, d)).isFalse()
        assertThat(SwipeGuard.resolve(-50f, 0f, 200, d)).isNull()
        assertThat(SwipeGuard.resolve(-300f, 200f, 200, d)).isNull()
        assertThat(SwipeGuard.resolve(-300f, 0f, 900, d)).isNull()
    }
}
