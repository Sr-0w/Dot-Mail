package app.dot.mail.ui.avatar

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FreeMailTest {
    @Test fun `mailbox providers are free mail`() {
        for (d in listOf("gmail.com", "Outlook.com", "outlook.fr", "hotmail.co.uk", "yahoo.com", "yahoo.co.jp", "icloud.com", "gmx.ch", "ik.me", "proton.me", "web.de")) {
            assertThat(FreeMail.isFreeMail(d)).isTrue()
        }
    }

    @Test fun `organisations are not`() {
        for (d in listOf("snyders.xyz", "paypal.com", "infomaniak.com", "livemint.com", "yahooinc.com.evil.example", "shop.example.org")) {
            assertThat(FreeMail.isFreeMail(d)).isFalse()
        }
    }
}
