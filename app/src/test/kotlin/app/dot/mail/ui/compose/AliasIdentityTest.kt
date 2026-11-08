package app.dot.mail.ui.compose

import app.dot.mail.data.model.Identity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AliasIdentityTest {
    @Test fun `alias selection reuses an identity with matching email regardless of case`() {
        val identity = Identity(id = 7, accountId = 3, name = "Alex", email = "alias@example.com")
        assertThat(existingIdentity(listOf(identity), "ALIAS@EXAMPLE.COM")).isEqualTo(identity)
        assertThat(existingIdentity(listOf(identity), "new@example.com")).isNull()
    }
}
