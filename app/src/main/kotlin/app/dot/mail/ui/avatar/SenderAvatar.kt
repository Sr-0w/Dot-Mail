package app.dot.mail.ui.avatar

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import app.dot.design.Dot
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dot.design.components.Avatar
import app.dot.design.components.LocalAvatarColors
import app.dot.mail.LocalSettings
import app.dot.mail.data.model.EmailAddress

/**
 * The avatar for a sender: coloured initials by default; a contact photo, Gravatar, brand logo or website icon
 * when the user enabled those sources (Settings → Appearance → Sender images). Pictures load off the main thread and
 * replace the initials once ready; a picture already in memory is shown on the first frame.
 */
@Composable
fun SenderAvatar(address: EmailAddress, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val prefs by LocalSettings.current.ui.collectAsStateWithLifecycle()
    val sources = remember(prefs.contactPhotos, prefs.gravatar, prefs.libravatar, prefs.brandLogos, prefs.websiteIcons) {
        AvatarSources(prefs.contactPhotos, prefs.gravatar, prefs.brandLogos, prefs.websiteIcons, libravatar = prefs.libravatar)
    }
    val repo = AvatarRepository.get(LocalContext.current)
    val image by produceState(if (sources.any) repo.peek(address, sources) else null, address.email, sources) {
        value = if (sources.any) repo.load(address, sources) else null
    }
    val img = image
    if (img == null) {
        CompositionLocalProvider(LocalAvatarColors provides prefs.avatarColors) {
            Avatar(address.displayName, address.email, modifier, size)
        }
    } else {
        // Same circle, size and hairline in both themes. Photos fill the circle; logos (wordmarks included) are drawn
        // inset on a neutral white plate so nothing is cropped.
        Box(modifier.size(size).clip(CircleShape).background(if (img.plated) Color.White else Dot.colors.surface)) {
            Image(
                bitmap = img.bitmap.asImageBitmap(),
                contentDescription = null, // decorative: the sender's name is already in the row
                contentScale = if (img.plated) ContentScale.Fit else ContentScale.Crop,
                modifier = if (img.plated) Modifier.align(Alignment.Center).fillMaxSize(LOGO_FRACTION) else Modifier.matchParentSize(),
            )
            Box(Modifier.matchParentSize().border(1.dp, Dot.colors.border, CircleShape))
        }
    }
}

/** Share of the circle's diameter a logo may use (a square logo inscribed in the circle is ~70%). */
private const val LOGO_FRACTION = 0.72f
