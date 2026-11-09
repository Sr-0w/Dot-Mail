package app.dot.design.components

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Directional hysteresis avoids oscillation when a disappearing filter row changes the list layout. */
@Stable
class HeaderControlsState(private val thresholdPx:Float) {
    var expanded by mutableStateOf(true)
        private set
    private var travel=0f
    fun show() { expanded=true; travel=0f }
    val connection:NestedScrollConnection=object:NestedScrollConnection {
        override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset {
            if(source!=NestedScrollSource.UserInput || !available.y.isFinite()) return Offset.Zero
            if((available.y>0 && travel<0) || (available.y<0 && travel>0)) travel=0f
            travel+=available.y
            if(travel < -thresholdPx) expanded=false
            else if(travel > thresholdPx) expanded=true
            return Offset.Zero // Never consume the message list's scroll or pull-to-refresh gesture.
        }
    }
}
@Composable
fun rememberHeaderControls():HeaderControlsState {
    val threshold=with(LocalDensity.current) { 16.dp.toPx() }
    return remember(threshold) { HeaderControlsState(threshold) }
}
