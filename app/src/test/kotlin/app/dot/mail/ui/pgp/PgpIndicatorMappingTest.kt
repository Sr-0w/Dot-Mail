package app.dot.mail.ui.pgp

import app.dot.mail.data.model.PgpContent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PgpIndicatorMappingTest {
    @Test fun `encrypted message with signature verdict has two distinct indicators`() {
        assertThat(pgpIndicatorKinds(PgpContent.Encrypted("cipher"), OpenKeychain.Signature(OpenKeychain.Verdict.VALID_CONFIRMED, "Alice")))
            .containsExactly(PgpIndicatorKind.ENCRYPTION, PgpIndicatorKind.SIGNATURE).inOrder()
    }

    @Test fun `encrypted message without signature only reports encryption`() {
        assertThat(pgpIndicatorKinds(PgpContent.Encrypted("cipher"), null))
            .containsExactly(PgpIndicatorKind.ENCRYPTION)
    }

    @Test fun `signed message keeps a signature indicator before verification`() {
        assertThat(pgpIndicatorKinds(PgpContent.Signed("entity", "signature"), null))
            .containsExactly(PgpIndicatorKind.SIGNATURE)
    }

    @Test fun `unsupported encryption never maps to an OpenPGP verification state`() {
        assertThat(pgpIndicatorKinds(PgpContent.Unsupported, null))
            .containsExactly(PgpIndicatorKind.UNSUPPORTED_ENCRYPTION)
    }
}
