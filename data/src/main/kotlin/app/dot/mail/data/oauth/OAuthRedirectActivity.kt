package app.dot.mail.data.oauth

import android.app.Activity
import android.os.Bundle
import android.content.Intent

/** Receives browser redirects, then hands the callback only to our explicit launcher Activity. */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri != null && uri.scheme in setOf("app.dot.mail", app.dot.mail.data.BuildConfig.OAUTH_GOOGLE_REDIRECT_SCHEME) && uri.authority == null && uri.path == "/oauth2redirect" && uri.fragment == null && uri.toString().length <= 16_384) {
            packageManager.getLaunchIntentForPackage(packageName)?.let { launcher ->
                launcher.action = "app.dot.mail.action.OAUTH_REDIRECT"
                launcher.data = uri
                launcher.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(launcher)
            }
        }
        finish()
    }
}
