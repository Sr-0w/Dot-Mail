package app.dot.mail

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dot.mail.demo.DemoMailGraph
import app.dot.mail.widget.WidgetRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetDataTest {
    @Test fun unreadCountAndNewestUnreadThreads() = runBlocking {
        val graph = DemoMailGraph()
        val data = WidgetRepository.load(graph, includeThreads = true)
        // Demo inbox: 3 unread messages in account 1 + 1 in account 2.
        assertThat(data.unread).isEqualTo(4)
        assertThat(data.threads.map { it.subject }).containsExactly(
            "Saturday hike — final plan", "Moodboard v3", "Photos from the weekend",
        ).inOrder()
    }

    @Test fun privateModeLoadsNoThreads() = runBlocking {
        val data = WidgetRepository.load(DemoMailGraph(), includeThreads = false)
        assertThat(data.unread).isEqualTo(4)
        assertThat(data.threads).isEmpty()
    }
}
