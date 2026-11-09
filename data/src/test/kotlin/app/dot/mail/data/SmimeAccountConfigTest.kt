package app.dot.mail.data

import app.dot.mail.data.model.AccountConfig
import app.dot.mail.data.model.AuthType
import app.dot.mail.data.model.Security
import app.dot.mail.data.model.ServerConfig
import app.dot.mail.data.model.SmimeAccountConfig
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmimeAccountConfigTest {
    private val imap = ServerConfig("imap.example.test", 993, Security.SSL_TLS, "account@example.test")
    private val smtp = ServerConfig("smtp.example.test", 465, Security.SSL_TLS, "account@example.test")

    @Test fun `old account JSON decodes with S MIME disabled`() {
        val legacy = JSONObject()
            .put("email", "account@example.test")
            .put("name", "Account")
            .put("imap", JSONObject().put("host", imap.host).put("port", imap.port).put("security", imap.security.name).put("user", imap.username))
            .put("smtp", JSONObject().put("host", smtp.host).put("port", smtp.port).put("security", smtp.security.name).put("user", smtp.username))
            .put("auth", AuthType.PASSWORD.name)
            .put("hint", JSONObject.NULL)
            .toString()

        assertThat(Codec.config(legacy).smime).isEqualTo(SmimeAccountConfig())
    }

    @Test fun `account JSON round trips only KeyChain aliases and protection defaults`() {
        val config = AccountConfig(
            email = "account@example.test",
            displayName = "Account",
            imap = imap,
            smtp = smtp,
            smime = SmimeAccountConfig(
                signingKeyChainAlias = "dotmail/account/signing",
                decryptionKeyChainAlias = "dotmail/account/decryption",
                signByDefault = true,
                encryptByDefault = false,
            ),
        )

        val json = Codec.config(config)
        assertThat(Codec.config(json)).isEqualTo(config)
        val smime = JSONObject(json).getJSONObject("smime")
        assertThat(smime.getString("signingKeyChainAlias")).isEqualTo("dotmail/account/signing")
        assertThat(smime.getString("decryptionKeyChainAlias")).isEqualTo("dotmail/account/decryption")
        assertThat(json).doesNotContain("PRIVATE KEY")
        assertThat(json).doesNotContain("certificateData")
    }

    @Test fun `malformed or incomplete optional S MIME JSON remains disabled`() {
        val encoded = Codec.config(AccountConfig("account@example.test", "Account", imap, smtp))
        val noAliases = JSONObject(encoded).put("smime", JSONObject().put("signByDefault", true)).toString()
        val decoded = Codec.config(noAliases)

        assertThat(decoded.smime.signingKeyChainAlias).isNull()
        assertThat(decoded.smime.decryptionKeyChainAlias).isNull()
        assertThat(decoded.smime.signByDefault).isTrue()
        assertThat(decoded.smime.encryptByDefault).isFalse()
        assertThat(Codec.config(JSONObject(encoded).put("smime", "invalid").toString()).smime)
            .isEqualTo(SmimeAccountConfig())
    }
}
