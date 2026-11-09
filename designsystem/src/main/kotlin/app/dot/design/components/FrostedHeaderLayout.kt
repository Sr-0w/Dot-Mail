package app.dot.design.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

internal data class HeaderGlass(val backdrop:Backdrop?)
/** Short ease-out below the header: everything under the header itself is fully frosted. */
private val FADE=40.dp
/** Extra top inset for the list, so at rest its first row sits past most of the fade. */
private val LIST_INSET=24.dp
/** The blur band reaches this far past the screen's left, right and top edges, so blur edge effects happen off screen. */
private val BLEED=48.dp
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
        content(PaddingValues(top=(if(reserveExpandedHeight) expandedHeight else headerHeight)+LIST_INSET))
        // The overlay keeps the expanded size (a stable blur texture: no reallocation, no flicker); only its mask follows
        // the header actually on screen, so collapsing filters shrink the strong band smoothly instead of leaving it stale.
        val total=expandedHeight+FADE+BLEED
        val visible by androidx.compose.animation.core.animateDpAsState(headerHeight,androidx.compose.animation.core.tween(if(app.dot.design.rememberReducedMotion()) 0 else 220),label="headerBlur")
        TopEdgeFade(backdrop,1f,Modifier.align(Alignment.TopCenter).bleed(BLEED),height=total,solidUntil=((visible+BLEED)/total).coerceIn(0f,1f),fadeLength=FADE/total,edgeScrim=BLEED+visible,edgeScrimSolid=BLEED+with(density) { WindowInsets.statusBars.getTop(this).toDp() })
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

/** Lays the child out [amount] wider on each side and [amount] taller at the top, centred on this slot (off-screen bleed). */
private fun Modifier.bleed(amount:androidx.compose.ui.unit.Dp):Modifier=this.then(androidx.compose.ui.Modifier.layout { measurable,constraints ->
    val b=amount.roundToPx()
    val w=constraints.maxWidth+2*b
    val placeable=measurable.measure(constraints.copy(minWidth=w,maxWidth=w,maxHeight=androidx.compose.ui.unit.Constraints.Infinity))
    layout(constraints.maxWidth,(placeable.height-b).coerceAtLeast(0)) { placeable.place(-b,-b) }
})
