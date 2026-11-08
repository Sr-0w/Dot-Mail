package app.dot.mail.ui.compose

import app.dot.mail.data.aliases.AliasRepository
import app.dot.mail.data.aliases.AliasService
import app.dot.mail.data.aliases.EmailAlias
import app.dot.mail.demo.DemoMailGraph
import app.dot.mail.ui.ComposeRoute
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
class ComposeAliasTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setMain() = Dispatchers.setMain(dispatcher)
    @After fun resetMain() = Dispatchers.resetMain()

    private class FakeAliasRepository : AliasRepository {
        override suspend fun savedKey(service: AliasService): String? = null
        override suspend fun saveKey(service: AliasService, key: String) = Unit
        override suspend fun removeKey(service: AliasService) = Unit
        override suspend fun list(service: AliasService, key: String) = emptyList<EmailAlias>()
        override suspend fun create(service: AliasService, key: String) = error("unused")
        override suspend fun setEnabled(service: AliasService, key: String, alias: EmailAlias, enabled: Boolean) = alias.copy(active = enabled)
    }

    private fun model(graph: DemoMailGraph) = ComposeViewModel(
        graph = graph,
        route = ComposeRoute(),
        undoSeconds = { 0 },
        aliasRepository = FakeAliasRepository(),
    )

    private fun CoroutineScope.outcome(model: ComposeViewModel) = async(start = CoroutineStart.UNDISPATCHED) {
        model.aliasOutcomes.first()
    }

    @Test fun `useAlias reuses matching identity creates a new one once and copies aliases from other domains`() = runTest(dispatcher) {
        val graph = DemoMailGraph()
        val model = model(graph)
        advanceUntilIdle()

        var event = outcome(model)
        model.useAlias(EmailAlias("shop", "SHOP@example.org", true))
        advanceUntilIdle()
        assertThat(event.await()).isEqualTo(AliasOutcome.Selected("SHOP@example.org"))
        val existing = graph.accounts.accounts.first().first { it.id == 1L }.identities
        assertThat(existing.count { it.email.equals("shop@example.org", true) }).isEqualTo(1)
        assertThat(model.state.value.sender?.identityId).isEqualTo(existing.first { it.email == "shop@example.org" }.id)

        val newAlias = EmailAlias("new", "sales@example.org", true)
        event = outcome(model)
        model.useAlias(newAlias)
        advanceUntilIdle()
        assertThat(event.await()).isEqualTo(AliasOutcome.Selected(newAlias.email))
        event = outcome(model)
        model.useAlias(newAlias)
        advanceUntilIdle()
        assertThat(event.await()).isEqualTo(AliasOutcome.Selected(newAlias.email))
        val identities = graph.accounts.accounts.first().first { it.id == 1L }.identities
        assertThat(identities.count { it.email.equals(newAlias.email, true) }).isEqualTo(1)
        assertThat(model.state.value.sender?.email).isEqualTo(newAlias.email)

        val external = EmailAlias("external", "alias@outside.example.net", true)
        event = outcome(model)
        model.useAlias(external)
        advanceUntilIdle()
        assertThat(event.await()).isEqualTo(AliasOutcome.CopiedNeedsReplyTo(external.email))
        assertThat(graph.accounts.accounts.first().first { it.id == 1L }.identities).hasSize(3)
        assertThat(model.state.value.sender?.email).isEqualTo(newAlias.email)
    }
}
