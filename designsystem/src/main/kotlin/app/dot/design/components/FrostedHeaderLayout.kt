package app.dot.design.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

internal data class HeaderGlass(val backdrop:Backdrop?)
private val FADE=64.dp
/** The fade starts this far inside the header (behind its lowest controls), so at rest the list's first row only meets its faint tail. */
private val FADE_OVERLAP=24.dp
internal val LocalHeaderGlass=staticCompositionLocalOf<HeaderGlass?> { null }

/**
 * Content scrolls behind a transparent header. The blur/gradient starts at the screen's top,
 * fades below the measured controls, and never includes the controls themselves in its source.
 * Apply [contentPadding] inside the scrolling content (LazyColumn.contentPadding or a leading
 * Spacer), never as padding on the viewport. A null backdrop is the safe WebView fallback.
 */
@Composable
fun FrostedHeaderLayout(
    backdrop:Backdrop?,
    modifier:Modifier=Modifier,
    reserveExpandedHeight:Boolean=false,
    header:@Composable ()->Unit,
    content:@Composable (contentPadding:PaddingValues)->Unit,
) {
    val density=LocalDensity.current
    val initial=with(density) { WindowInsets.statusBars.getTop(this).toDp() }+64.dp
    val width=androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    var headerHeight by remember(density,width) { mutableStateOf(initial) }
    var expandedHeight by remember(density,width) { mutableStateOf(initial) }
    Box(modifier.fillMaxSize()) {
        content(PaddingValues(top=(if(reserveExpandedHeight) expandedHeight else headerHeight)+FADE_OVERLAP))
        // The overlay keeps the expanded size (a stable blur texture: no reallocation, no flicker); only its mask follows
        // the header actually on screen, so collapsing filters shrink the strong band smoothly instead of leaving it stale.
        val total=expandedHeight+FADE-FADE_OVERLAP
        val visible by androidx.compose.animation.core.animateDpAsState(headerHeight,androidx.compose.animation.core.tween(if(app.dot.design.rememberReducedMotion()) 0 else 220),label="headerBlur")
        TopEdgeFade(backdrop,1f,Modifier.align(Alignment.TopCenter),height=total,solidUntil=((visible-FADE_OVERLAP)/total).coerceIn(0f,1f),fadeLength=FADE/total)
        CompositionLocalProvider(LocalHeaderGlass provides HeaderGlass(null)) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { size ->
                with(density) { size.height.toDp() }.let {
                    if(headerHeight!=it) headerHeight=it
                    if(it>expandedHeight) expandedHeight=it
                }
            }) { header() }
        }
    }
}
