package app.dot.mail.ui.compose

import app.dot.mail.demo.DemoMailGraph
import app.dot.mail.ui.ComposeRoute
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ComposeScheduleTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setMain() = Dispatchers.setMain(dispatcher)
    @After fun resetMain() = Dispatchers.resetMain()

    private fun vm() = ComposeViewModel(
        graph = DemoMailGraph(), route = ComposeRoute(to = listOf("bob@example.org")), undoSeconds = { 0 }, strings = { id, _ -> "res:$id" },
    )

    @Test fun `a past or too-near time is refused with a message and nothing is sent`() = runTest(dispatcher) {
        val model = vm(); runCurrent()
        val now = 1_000_000_000L
        model.scheduleSend(now - 5_000, now)
        model.scheduleSend(now + 30_000, now)
        advanceUntilIdle()
        assertThat(model.state.value.error).isEqualTo("res:${app.dot.mail.R.string.schedule_error_past}")
        assertThat(model.state.value.sending).isFalse()
        assertThat(model.closed.value).isFalse()
    }

    @Test fun `a valid time is sent with that schedule`() = runTest(dispatcher) {
        val model = vm(); runCurrent()
        val now = System.currentTimeMillis()
        model.scheduleSend(now + 2 * 3_600_000, now)
        advanceUntilIdle()
        assertThat(model.state.value.error).isNull()
        assertThat(model.closed.value).isTrue() // queued: the composer closes like a normal send
    }
}
