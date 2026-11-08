package app.dot.design.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

internal data class HeaderGlass(val backdrop:HazeState?)
internal val LocalHeaderGlass=staticCompositionLocalOf<HeaderGlass?> { null }

/**
 * Content scrolls behind a transparent header. The blur/gradient starts at the screen's top,
 * fades below the measured controls, and never includes the controls themselves in its source.
 * Apply [contentPadding] inside the scrolling content (LazyColumn.contentPadding or a leading
 * Spacer), never as padding on the viewport. A null backdrop is the safe WebView fallback.
 */
@Composable
fun FrostedHeaderLayout(
    backdrop:HazeState?,
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
        content(PaddingValues(top=if(reserveExpandedHeight) expandedHeight else headerHeight))
        TopEdgeFade(backdrop,1f,Modifier.align(Alignment.TopCenter),height=expandedHeight+72.dp,fadeStart=(expandedHeight/(expandedHeight+72.dp)).coerceIn(0.4f,0.85f))
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
