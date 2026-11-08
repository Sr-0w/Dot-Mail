package app.dot.mail.ui.account

import app.dot.mail.data.model.OAuthError
import app.dot.mail.data.model.VerifyResult
import app.dot.mail.engine.ConnectionFailure
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VerifyReasonTest {
    @Test fun `every oauth code maps to its own reason and null kind equals OTHER`(): Unit {
        assertThat(OAuthError.entries.map { oauthErrorRes(it) }.toSet()).hasSize(OAuthError.entries.size)
        assertThat(verifyReasonRes(VerifyResult.ConnectionFailed("x", null))).isEqualTo(verifyReasonRes(VerifyResult.ConnectionFailed("y", ConnectionFailure.OTHER)))
        assertThat(verifyReasonRes(VerifyResult.AuthFailed("x"))).isNotEqualTo(verifyReasonRes(VerifyResult.CertificateProblem("x")))
    }
}
