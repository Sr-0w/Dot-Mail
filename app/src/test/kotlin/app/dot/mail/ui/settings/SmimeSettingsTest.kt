package app.dot.mail.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import app.dot.mail.LocalMailGraph
import app.dot.mail.R
import app.dot.mail.data.AccountRepository
import app.dot.mail.data.MailGraph
import app.dot.mail.data.SmimeCertSource
import app.dot.mail.data.SmimeKeyStatus
import app.dot.mail.data.SmimeRepository
import app.dot.mail.data.StoredSmimeCert
import app.dot.mail.data.model.Account
import app.dot.mail.data.model.SmimeAccountConfig
import app.dot.mail.demo.DemoMailGraph
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h2000dp")
class SmimeSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun s(id: Int) = compose.activity.getString(id)

    private val saved = mutableListOf<SmimeAccountConfig>()
    private var current = SmimeAccountConfig()
    private val pins = mutableListOf<String>()
    private val certs = MutableStateFlow(listOf(
        StoredSmimeCert(1, "alice@example.org", "ab12cd34ef567890".repeat(4), "CN=Alice,O=Example", "CN=Test CA", 0, Long.MAX_VALUE / 2, 1, 1, SmimeCertSource.VERIFIED_SIGNATURE, pinned = false),
        StoredSmimeCert(1, "bob@example.org", "0011223344556677".repeat(4), "CN=Bob", "CN=Test CA", 0, 1_000, 1, 1, SmimeCertSource.MANUAL, pinned = true),
    ))
    private val graph = object : MailGraph by DemoMailGraph() {
        private val demo = DemoMailGraph()
        override val accounts: AccountRepository = object : AccountRepository by demo.accounts {
            override suspend fun updateSmime(accountId: Long, transform: (SmimeAccountConfig) -> SmimeAccountConfig) { saved += transform(current) }
        }
        override val smime = object : SmimeRepository {
            override fun certificates(accountId: Long) = certs
            override suspend fun pin(accountId: Long, mailbox: String, fingerprint: String): Boolean { pins += "pin:$mailbox"; return true }
            override suspend fun unpin(accountId: Long, mailbox: String, fingerprint: String) { pins += "unpin:$mailbox" }
            override suspend fun forget(accountId: Long, mailbox: String, fingerprint: String): Boolean { pins += "forget:$mailbox"; return true }
            override suspend fun pinnedFingerprint(accountId: Long, mailbox: String): String? = null
            override suspend fun certificatesFor(accountId: Long, mailboxes: List<String>) = emptyMap<String, List<java.security.cert.X509Certificate>>()
        }
    }
    private fun account(smime: SmimeAccountConfig = SmimeAccountConfig()) = Account(1, "Alice", "alice@example.org", 0, smime = smime)

    private fun show(account: Account, certCount: Int = 0) {
        compose.setContent { DotTheme(ThemeMode.LIGHT) { CompositionLocalProvider(LocalMailGraph provides graph) {
            androidx.compose.foundation.layout.Column { SmimeAccountSection(account, certCount, onOpenContacts = {}) }
        } } }
    }

    @Test fun anAccountWithoutSmimeAsksForACertificateAndOffersNoRemove() {
        show(account())
        compose.onNodeWithText(s(R.string.smime_signing_key)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.smime_decryption_key)).assertIsDisplayed()
        assertThat(compose.onAllNodesWithText(s(R.string.smime_key_not_set), useUnmergedTree = true).fetchSemanticsNodes()).hasSize(2) // one per slot
        compose.onNodeWithText(s(R.string.smime_remove)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.smime_sign_default)).assertIsNotEnabled() // nothing to sign with yet
    }

    @Test fun aVanishedAliasSaysPickAgainAndCanBeRemoved() {
        current = SmimeAccountConfig("gone-signing", "gone-decryption", signByDefault = true)
        show(account(current))
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithText(s(R.string.smime_key_unavailable), useUnmergedTree = true).fetchSemanticsNodes()).hasSize(2)
        compose.onNodeWithText(s(R.string.smime_remove)).performClick()
        compose.onNodeWithText(s(R.string.smime_remove_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.smime_remove_confirm)).performClick()
        compose.waitForIdle()
        assertThat(saved).containsExactly(SmimeAccountConfig()) // aliases and defaults cleared; KeyChain is untouched
    }

    @Test fun contactCertificatesCanBePinnedUnpinnedAndForgotten() {
        compose.setContent { DotTheme(ThemeMode.LIGHT) { CompositionLocalProvider(LocalMailGraph provides graph) { SmimeContactsPage(account(), onBack = {}) } } }
        compose.onNodeWithText("alice@example.org").assertIsDisplayed()
        compose.onNodeWithText(s(R.string.smime_cert_pinned)).assertIsDisplayed() // bob's
        compose.onNodeWithText(s(R.string.smime_cert_pin)).performClick()
        compose.onNodeWithText(s(R.string.smime_cert_unpin)).performClick()
        compose.waitForIdle()
        assertThat(pins).containsExactly("pin:alice@example.org", "unpin:bob@example.org").inOrder()
        compose.onAllNodesWithText(s(R.string.smime_cert_forget))[0].performClick()
        compose.onNodeWithText(s(R.string.smime_cert_forget_body)).assertIsDisplayed()
    }

    @Test fun anEmptyContactListExplainsWhereCertificatesComeFrom() {
        certs.value = emptyList()
        compose.setContent { DotTheme(ThemeMode.LIGHT) { CompositionLocalProvider(LocalMailGraph provides graph) { SmimeContactsPage(account(), onBack = {}) } } }
        compose.onNodeWithText(s(R.string.smime_contacts_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.smime_contacts_empty_body)).assertIsDisplayed()
    }
}

class SmimeUiTest {
    @Test fun `fingerprints are shown short, grouped and upper case`() {
        assertThat(SmimeUi.shortFingerprint("ab12cd34ef5678901122")).isEqualTo("AB12 CD34 EF56 7890")
        assertThat(SmimeUi.shortFingerprint("ab")).isEqualTo("AB")
    }

    @Test fun `choosing an alias keeps the other slot and clearing one switches its default off`() {
        val c = SmimeAccountConfig("sign", "dec", signByDefault = true, encryptByDefault = true)
        assertThat(SmimeUi.withAlias(c, SmimeUi.Slot.SIGNING, "new")).isEqualTo(c.copy(signingKeyChainAlias = "new"))
        assertThat(SmimeUi.withAlias(c, SmimeUi.Slot.SIGNING, null)).isEqualTo(c.copy(signingKeyChainAlias = null, signByDefault = false))
        assertThat(SmimeUi.withAlias(c, SmimeUi.Slot.DECRYPTION, null)).isEqualTo(c.copy(decryptionKeyChainAlias = null, encryptByDefault = false))
    }

    @Test fun `defaults need a usable key and unusable states disable them`() {
        assertThat(SmimeUi.canDefaultToSign(SmimeKeyStatus.NotSet)).isFalse()
        assertThat(SmimeUi.canDefaultToSign(SmimeKeyStatus.Unavailable)).isFalse()
        val ok = SmimeKeyStatus.Available("A", "CA", 0, Long.MAX_VALUE / 2, setOf("a@x.org"))
        val expired = SmimeKeyStatus.Available("A", "CA", 0, 1, setOf("a@x.org"))
        assertThat(SmimeUi.canDefaultToSign(ok)).isTrue(); assertThat(SmimeUi.canDefaultToEncrypt(ok)).isTrue()
        assertThat(SmimeUi.canDefaultToSign(expired)).isFalse()
    }

    @Test fun `remove is offered only when something is configured`() {
        assertThat(SmimeUi.isConfigured(SmimeAccountConfig())).isFalse()
        assertThat(SmimeUi.isConfigured(SmimeAccountConfig(decryptionKeyChainAlias = "d"))).isTrue()
    }
}
