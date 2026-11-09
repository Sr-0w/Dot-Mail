package app.dot.mail.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EncryptionHintTest {
    @Test fun `classifies only explicit encrypted mime containers`() {
        assertThat(encryptionHintFromMime("multipart/encrypted; protocol=\"application/pgp-encrypted\"; boundary=x"))
            .isEqualTo(EncryptionHint.OPENPGP)
        assertThat(encryptionHintFromMime("multipart/encrypted; protocol=\"application/x-unknown\"; boundary=x"))
            .isEqualTo(EncryptionHint.UNSUPPORTED)
        assertThat(encryptionHintFromMime("application/pkcs7-mime; smime-type=enveloped-data; name=smime.p7m"))
            .isEqualTo(EncryptionHint.SMIME)
        assertThat(encryptionHintFromMime("application/pkcs7-mime; smime-type=authEnveloped-data; name=smime.p7m"))
            .isEqualTo(EncryptionHint.SMIME)
        assertThat(encryptionHintFromMime("application/pkcs7-mime; smime-type=signed-data"))
            .isEqualTo(EncryptionHint.NONE)
        assertThat(encryptionHintFromMime("text/plain"))
            .isEqualTo(EncryptionHint.NONE)
    }
}
