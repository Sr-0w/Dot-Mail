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
    header:@Composable ()->Unit,
    content:@Composable (contentPadding:PaddingValues)->Unit,
) {
    val density=LocalDensity.current
    val initial=with(density) { WindowInsets.statusBars.getTop(this).toDp() }+64.dp
    var headerHeight by remember(density) { mutableStateOf(initial) }
    Box(modifier.fillMaxSize()) {
        content(PaddingValues(top=headerHeight))
        TopEdgeFade(backdrop,1f,Modifier.align(Alignment.TopCenter),height=headerHeight+56.dp)
        CompositionLocalProvider(LocalHeaderGlass provides HeaderGlass(backdrop)) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { size ->
                with(density) { size.height.toDp() }.let { if(headerHeight!=it) headerHeight=it }
            }) { header() }
        }
    }
}
