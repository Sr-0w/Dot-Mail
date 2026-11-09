package app.dot.mail.data

import app.dot.mail.data.model.EmailAddress
import app.dot.mail.data.model.Message
import app.dot.mail.data.model.MessageBody
import app.dot.mail.data.model.SmimeContent
import app.dot.mail.data.model.SmimeDecryption
import app.dot.mail.data.model.SmimeSignatureView
import app.dot.mail.engine.Mime
import app.dot.mail.engine.SmimeCertificatePolicy
import app.dot.mail.engine.SmimeCertificateUse
import app.dot.mail.engine.SmimeCertificateVerdict
import app.dot.mail.engine.SmimeDecryptionIdentity
import app.dot.mail.engine.SmimeEngine
import app.dot.mail.engine.SmimeMode
import app.dot.mail.engine.SmimeParts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Messages produced by independent implementations (OpenSSL `cms` / `smime`, see scripts/smime-interop/generate.py) in the
 * shapes real clients send, run through the receive path: Mime.cache -> verify / decrypt. Fixtures are test keys only.
 */
@RunWith(Parameterized::class)
class SmimeInteropTest(named: Any) {
    private val case: JsonObject = (named as Named).json
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.optStr(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
    companion object {
        private fun resource(path: String) = SmimeInteropTest::class.java.classLoader!!.getResourceAsStream("smime-interop/$path")!!.use { it.readBytes() }

        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): List<Array<Any>> {
            val manifest = Json.parseToJsonElement(String(resource("manifest.json"), Charsets.UTF_8)).jsonObject.getValue("cases").jsonArray
            return manifest.map { arrayOf<Any>(Named(it.jsonObject)) }
        }
    }
    /** JUnit shows the case name instead of the JSON. */
    class Named(val json: JsonObject) { override fun toString() = json.getValue("name").jsonPrimitive.content }

    @get:Rule val tmp = TemporaryFolder()

    private val trusted = object : SmimeCertificatePolicy {
        override fun validate(certificate: X509Certificate, mailbox: String, use: SmimeCertificateUse) = SmimeCertificateVerdict.Trusted
        override fun validateChain(certificates: Collection<X509Certificate>, mailbox: String, use: SmimeCertificateUse) = SmimeCertificateVerdict.Trusted
    }

    private fun certificate(name: String) = CertificateFactory.getInstance("X.509").generateCertificate(resource("keys/$name.crt.pem").inputStream()) as X509Certificate
    private fun privateKey(name: String): PrivateKey {
        val pem = String(resource("keys/$name.key.pem"))
        val der = Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
        val spec = PKCS8EncodedKeySpec(der)
        return runCatching { KeyFactory.getInstance("RSA").generatePrivate(spec) }.getOrElse { KeyFactory.getInstance("EC").generatePrivate(spec) }
    }

    private fun message(dir: File, from: String): Message {
        val smime = when {
            File(dir, SmimeParts.ENCRYPTED_FILE).isFile -> SmimeContent.Encrypted(File(dir, SmimeParts.ENCRYPTED_FILE).path)
            File(dir, SmimeParts.OPAQUE_FILE).isFile -> SmimeContent.Opaque(File(dir, SmimeParts.OPAQUE_FILE).path)
            File(dir, SmimeParts.SIGNED_FILE).isFile && File(dir, SmimeParts.SIGNATURE_FILE).isFile ->
                SmimeContent.Signed(File(dir, SmimeParts.SIGNED_FILE).path, File(dir, SmimeParts.SIGNATURE_FILE).path)
            else -> null
        }
        return Message(
            id = 1, accountId = 1, folderId = 1, threadId = 1, from = EmailAddress(from), to = listOf(EmailAddress("bob-rsa@example.test")),
            cc = emptyList(), bcc = emptyList(), replyTo = emptyList(), subject = case.str("name"), sentAt = 0, snippet = "", isUnread = false,
            isStarred = false, attachments = emptyList(), messageIdHeader = null, references = emptyList(), unsubscribe = null,
            body = MessageBody(null, null, false, smime = smime),
        )
    }

    private fun names(array: JsonArray?) = array?.map { it.jsonPrimitive.content }.orEmpty().sorted()

    @Test fun receive() = runBlocking {
        val raw = resource(case.str("file"))
        val dir = tmp.newFolder()
        val parsed = jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()), raw.inputStream())
        val cached = Mime.cache(parsed, dir)
        val from = case.str("from_")
        val message = message(dir, from)
        val kind = case.str("kind")
        val expectedText = case.optStr("text")
        val expectedFiles = names(case["attachments"]?.jsonArray)

        when (kind) {
            "signed", "opaque" -> {
                val content = message.body!!.smime
                assertTrue("expected ${if (kind == "signed") "a detached" else "an opaque"} signature to be recognised, got $content", if (kind == "signed") content is SmimeContent.Signed else content is SmimeContent.Opaque)
                assertTrue("signed text should be shown: ${cached.text?.readText()}", cached.text?.readText().orEmpty().contains(expectedText))
                assertEquals(expectedFiles, cached.attachments.map { it.name }.sorted())
                val view = verifySmimeSignature(message, trusted, null)!!
                assertEquals(SmimeSignatureView.State.valueOf(case.str("signature")), view.state)
            }
            "encrypted" -> {
                assertTrue("expected an envelope to be recognised, got ${message.body!!.smime}", message.body!!.smime is SmimeContent.Encrypted)
                val who = case.str("decrypt_with")
                val result = decryptSmimeMessage(message, { SmimeDecryptionIdentity(certificate(who), privateKey(who)) }, trusted, null)
                assertTrue("decryption should succeed, was $result", result is SmimeDecryption.Done)
                result as SmimeDecryption.Done
                assertTrue("plaintext should be shown: ${result.body.plainText}", result.body.plainText.orEmpty().contains(expectedText))
                assertEquals(expectedFiles, result.body.decryptedAttachments.map { it.fileName }.sorted())
                if (case.containsKey("signature")) assertEquals(SmimeSignatureView.State.valueOf(case.str("signature")), result.signature?.state)
            }
            else -> error("unknown kind $kind")
        }
    }
}

/**
 * The other direction: what WE send, read by OpenSSL. Skipped where the `openssl` CLI (3.x) is not installed.
 */
class SmimeSendToOpenSslTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun resource(path: String) = SmimeInteropTest::class.java.classLoader!!.getResourceAsStream("smime-interop/$path")!!.use { it.readBytes() }
    private fun certificate(name: String) = CertificateFactory.getInstance("X.509").generateCertificate(resource("keys/$name.crt.pem").inputStream()) as X509Certificate
    private fun privateKey(name: String): PrivateKey {
        val der = Base64.getMimeDecoder().decode(String(resource("keys/$name.key.pem")).lines().filterNot { it.startsWith("-----") }.joinToString(""))
        val spec = PKCS8EncodedKeySpec(der)
        return runCatching { KeyFactory.getInstance("RSA").generatePrivate(spec) }.getOrElse { KeyFactory.getInstance("EC").generatePrivate(spec) }
    }
    private fun file(name: String, bytes: ByteArray) = tmp.newFile(name).also { it.writeBytes(bytes) }
    private fun key(name: String) = file("$name.key", resource("keys/$name.key.pem"))
    private fun crt(name: String) = file("$name.crt", resource("keys/$name.crt.pem"))

    private fun openssl(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder("openssl", *args).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.ISO_8859_1)
        return p.waitFor() to out
    }

    private val trusted = object : SmimeCertificatePolicy {
        override fun validate(certificate: X509Certificate, mailbox: String, use: SmimeCertificateUse) = SmimeCertificateVerdict.Trusted
        override fun validateChain(certificates: Collection<X509Certificate>, mailbox: String, use: SmimeCertificateUse) = SmimeCertificateVerdict.Trusted
    }

    private fun opensslAvailable() = runCatching { openssl("version").first == 0 }.getOrDefault(false)

    @Test fun `OpenSSL reads our signed and encrypted mail for RSA, P-256 and P-384 recipients`() {
        org.junit.Assume.assumeTrue("openssl CLI not installed", opensslAvailable())
        val mail = app.dot.mail.engine.OutgoingMail(
            from = app.dot.mail.engine.Address("alice-rsa@example.test", "Alice"),
            to = listOf("bob-rsa@example.test", "carol-p256@example.test", "dave-p384@example.test", "frank-bp256@example.test", "gina-bp384@example.test").map { app.dot.mail.engine.Address(it) },
            subject = "Sent by Dot Mail", text = "text from dot mail é",
            attachments = listOf(app.dot.mail.engine.OutgoingAttachment("n.txt", "text/plain", "n".toByteArray())),
        )
        val recipients = listOf("bob-rsa", "carol-p256", "dave-p384", "frank-bp256", "gina-bp384").map { app.dot.mail.engine.SmimeRecipientCertificate("$it@example.test", certificate(it)) }
        val variant = SmimeEngine(certificatePolicy = trusted).build(
            mail, SmimeMode.SIGN_AND_ENCRYPT, app.dot.mail.engine.SmimeSigningIdentity(certificate("alice-rsa"), privateKey("alice-rsa")), recipients, certificate("alice-rsa"),
        ).single()
        val message = file("ours.eml", variant.mimeBytes)
        for (who in listOf("bob-rsa", "carol-p256", "dave-p384", "frank-bp256", "gina-bp384", "alice-rsa")) {
            val (code, plain) = openssl("cms", "-decrypt", "-in", message.path, "-inkey", key(who).path, "-recip", crt(who).path)
            assertEquals("$who: $plain", 0, code)
            val signed = file("$who.signed", plain.toByteArray(Charsets.ISO_8859_1))
            val (vcode, verified) = openssl("cms", "-verify", "-noverify", "-in", signed.path, "-signer", File(tmp.root, "$who.out.crt").path)
            assertEquals("$who verify: $verified", 0, vcode)
            assertTrue(verified, verified.contains("text from dot mail"))
        }
        // somebody else's key must not open it
        assertTrue(openssl("cms", "-decrypt", "-in", message.path, "-inkey", key("erin-rsa").path, "-recip", crt("erin-rsa").path).first != 0)
    }

    @Test fun `OpenSSL verifies our detached signatures with RSA and ECDSA keys`() {
        org.junit.Assume.assumeTrue("openssl CLI not installed", opensslAvailable())
        for ((who, curveKey) in listOf("alice-rsa" to false, "alice-p256" to true)) {
            val mail = app.dot.mail.engine.OutgoingMail(from = app.dot.mail.engine.Address("$who@example.test"), to = listOf(app.dot.mail.engine.Address("bob-rsa@example.test")), subject = "signed", text = "signed text")
            val variant = SmimeEngine(certificatePolicy = trusted).build(mail, SmimeMode.SIGN, app.dot.mail.engine.SmimeSigningIdentity(certificate(who), privateKey(who))).single()
            val (code, out) = openssl("cms", "-verify", "-noverify", "-in", file("$who.eml", variant.mimeBytes).path, "-signer", File(tmp.root, "$who.signer").path)
            assertEquals("$who ($curveKey): $out", 0, code)
        }
    }

    @Test fun `a curve that only shares the field size with a supported one is refused, not guessed`() {
        // secp256k1 has a 256-bit field like P-256: encrypting on P-256 would make mail nobody can open.
        val mail = app.dot.mail.engine.OutgoingMail(
            from = app.dot.mail.engine.Address("alice-rsa@example.test"), to = listOf(app.dot.mail.engine.Address("heidi-k1@example.test")), subject = "k1", text = "x",
        )
        val failure = runCatching {
            SmimeEngine(certificatePolicy = trusted).build(
                mail, SmimeMode.ENCRYPT, null, listOf(app.dot.mail.engine.SmimeRecipientCertificate("heidi-k1@example.test", certificate("heidi-k1"))), certificate("alice-rsa"),
            )
        }.exceptionOrNull() as app.dot.mail.engine.SmimeProtectionException
        assertEquals(app.dot.mail.engine.SmimeFailure.UNSUPPORTED_CERTIFICATE_KEY, failure.failure)
    }
}
