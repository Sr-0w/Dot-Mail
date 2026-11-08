package app.dot.mail.ui.avatar

/** Which places an avatar picture may come from. Everything but initials is opt-in (see Settings → Appearance). */
data class AvatarSources(
    val contacts: Boolean = false,
    val gravatar: Boolean = false,
    val brandLogos: Boolean = false,
    val websiteIcons: Boolean = false,
    val libravatar: Boolean = false,
) {
    val any: Boolean get() = contacts || gravatar || libravatar || brandLogos || websiteIcons
}

/** A loaded picture. [plated] images (logos) are shown contained on a light plate; photos fill the circle. */
class AvatarImage(val bitmap: android.graphics.Bitmap, val plated: Boolean)

/** Domains of mailbox providers: the sender is a person there, not an organisation, so a site icon would be wrong. */
internal object FreeMail {
    private val exact = setOf(
        "gmail.com", "googlemail.com", "icloud.com", "me.com", "mac.com", "msn.com", "ymail.com", "rocketmail.com",
        "proton.me", "protonmail.com", "pm.me", "protonmail.ch", "ik.me", "ikmail.com", "etik.com", "mailbox.org",
        "fastmail.com", "fastmail.fm", "hey.com", "tuta.com", "tutanota.com", "tutamail.com", "tuta.io", "zoho.com",
        "web.de", "t-online.de", "freenet.de", "free.fr", "orange.fr", "wanadoo.fr", "sfr.fr", "laposte.net",
        "bluewin.ch", "hispeed.ch", "sunrise.ch", "gmx.net", "gmx.com", "gmx.de", "mail.com", "mail.ru", "qq.com",
        "163.com", "126.com", "yandex.com", "yandex.ru", "seznam.cz", "libero.it", "virgilio.it", "aol.com", "aim.com",
        "comcast.net", "verizon.net", "att.net", "sbcglobal.net", "btinternet.com", "cox.net", "posteo.de", "posteo.net",
    )
    private val families = listOf("outlook.", "hotmail.", "live.", "yahoo.", "gmx.", "posteo.", "icloud.")

    fun isFreeMail(domain: String): Boolean {
        val d = domain.lowercase().trim('.')
        return d in exact || families.any { d.startsWith(it) && d.count { c -> c == '.' } <= 2 }
    }
}
