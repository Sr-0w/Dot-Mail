package app.dot.mail.ui.pgp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenKeychainMicalgTest {
    @Test fun `hash names become PGP-MIME micalg values`(): Unit {
        assertThat(OpenKeychain.micalg("SHA256")).isEqualTo("pgp-sha256")
        assertThat(OpenKeychain.micalg(" pgp-SHA512 ")).isEqualTo("pgp-sha512")
        assertThat(OpenKeychain.micalg("")).isNull()
        assertThat(OpenKeychain.micalg(null)).isNull()
    }
}
