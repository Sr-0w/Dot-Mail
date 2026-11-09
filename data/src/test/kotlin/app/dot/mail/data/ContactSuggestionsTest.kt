package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.ContactRow
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class MergeSuggestionsTest {
    private fun a(email: String, name: String? = null) = EmailAddress(email, name)

    @Test fun `history comes first, the address book fills the rest without duplicates`(): Unit {
        val merged = mergeSuggestions(listOf(a("ann@x.org", "Ann"), a("al@x.org")), listOf(a("ALEX@x.org", "Alex"), a("ann@x.org", "Ann Book"), a("al@x.org", "Al Smith")), 10)
        assertThat(merged.map { it.email }).containsExactly("ann@x.org", "al@x.org", "ALEX@x.org").inOrder()
        assertThat(merged[0].name).isEqualTo("Ann") // history's name wins
        assertThat(merged[1].name).isEqualTo("Al Smith") // a missing name is filled in
    }

    @Test fun `the limit applies to the merged list and history is never pushed out`(): Unit {
        val merged = mergeSuggestions(listOf(a("h1@x.org"), a("h2@x.org")), listOf(a("b1@x.org"), a("b2@x.org")), 3)
        assertThat(merged.map { it.email }).containsExactly("h1@x.org", "h2@x.org", "b1@x.org").inOrder()
        assertThat(mergeSuggestions(emptyList(), emptyList(), 5)).isEmpty()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContactSuggestionsTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private val config = AccountConfig("me@example.org", "Me", ServerConfig("127.0.0.1", 1, Security.NONE, "me"), ServerConfig("127.0.0.1", 1, Security.NONE, "me"))
    private var searches = 0

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MailDatabase::class.java).allowMainThreadQueries().build()
        graph = RealMailGraph(ApplicationProvider.getApplicationContext(), db, object : CredentialVault {
            override fun encrypt(accountEmail: String, secret: String) = secret
            override fun decrypt(accountEmail: String, encrypted: String) = encrypted
        }, scheduling = false)
        graph.addressBook.searcher = DeviceContactSearcher { prefix, _ -> searches++; listOf(EmailAddress("ann.book@x.org", "Ann Book"), EmailAddress("ann@x.org", "Ann Hist")) }
    }
    @After fun close() { graph.scope.cancel(); db.close() }

    private suspend fun history() { val account = graph.addAccount(config, "pw"); db.mail().putContact(ContactRow(account, "ann@x.org", "Ann Hist", 5)) }

    @Test fun withPermissionAddressBookMatchesFollowHistoryAndAreDeduplicated() = runBlocking<Unit> {
        history()
        graph.addressBook.hasPermission = { true }
        val result = graph.suggestContacts("ann", 8)
        assertThat(result.map { it.email }).containsExactly("ann@x.org", "ann.book@x.org").inOrder()
        assertThat(searches).isEqualTo(1)
    }

    @Test fun withoutPermissionOnlyHistoryIsUsedAndTheAddressBookIsNeverQueried() = runBlocking<Unit> {
        history()
        graph.addressBook.hasPermission = { false }
        assertThat(graph.suggestContacts("ann", 8).map { it.email }).containsExactly("ann@x.org")
        assertThat(searches).isEqualTo(0)
    }

    @Test fun aFailingProviderDegradesToHistory() = runBlocking<Unit> {
        history()
        graph.addressBook.hasPermission = { true }
        graph.addressBook.searcher = DeviceContactSearcher { _, _ -> throw SecurityException("revoked mid-query") }
        assertThat(graph.suggestContacts("ann", 8).map { it.email }).containsExactly("ann@x.org")
    }

    @Test fun fullHistoryDoesNotTouchTheAddressBook() = runBlocking<Unit> {
        history()
        graph.addressBook.hasPermission = { true }
        assertThat(graph.suggestContacts("ann", 1).map { it.email }).containsExactly("ann@x.org")
        assertThat(searches).isEqualTo(0)
    }

    @Test fun permissionRevokedMidSessionFallsBackToHistoryOnTheNextKeystroke() = runBlocking<Unit> {
        history()
        var granted = true
        graph.addressBook.hasPermission = { granted }
        assertThat(graph.suggestContacts("ann", 8).map { it.email }).containsExactly("ann@x.org", "ann.book@x.org").inOrder()
        assertThat(searches).isEqualTo(1)
        granted = false // the user withdrew it in system settings while the composer was open
        assertThat(graph.suggestContacts("ann", 8).map { it.email }).containsExactly("ann@x.org")
        assertThat(searches).isEqualTo(1) // the address book was not queried again
    }

    @Test fun duplicatesAreMatchedOnTheNormalizedAddress() = runBlocking<Unit> {
        history()
        graph.addressBook.hasPermission = { true }
        graph.addressBook.searcher = DeviceContactSearcher { _, _ -> listOf(EmailAddress(" ANN@X.ORG ", "Ann Book"), EmailAddress("new@x.org", "New")) }
        assertThat(graph.suggestContacts("a", 8).map { it.email.trim().lowercase() }).containsExactly("ann@x.org", "new@x.org").inOrder()
    }
}
