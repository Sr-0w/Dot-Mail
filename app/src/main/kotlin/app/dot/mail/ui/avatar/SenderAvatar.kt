package app.dot.mail.ui.avatar

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
        // Same circle, size and hairline in both themes, for photos and logos alike: logos are square artwork
        // (BIMI is designed to be circle-cropped), so they are cropped to the circle on a white plate, never shown as bare squares.
        Box(modifier.size(size).clip(CircleShape).background(if (img.plated) Color.White else Dot.colors.surface)) {
            Image(
                bitmap = img.bitmap.asImageBitmap(),
                contentDescription = null, // decorative: the sender's name is already in the row
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(Modifier.matchParentSize().border(1.dp, Dot.colors.border, CircleShape))
        }
    }
}
