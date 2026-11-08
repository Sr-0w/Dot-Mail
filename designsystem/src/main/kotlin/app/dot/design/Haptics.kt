package app.dot.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The few, light haptics Dot uses, so every meaningful action feels the same. The system "touch feedback"
 * setting still applies (Compose routes through the view), and nothing here vibrates continuously.
 */
@Immutable
class DotHaptics internal constructor(private val feedback: HapticFeedback) {
    /** A threshold was crossed (swipe armed, pull-to-refresh armed). */
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
    /** A switch changed. */
    fun toggle(on: Boolean) = feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    /** An action completed (archive, delete, snooze, send). */
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
    /** An action was refused. */
    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)
}

@Composable
fun rememberDotHaptics(): DotHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { DotHaptics(feedback) }
}
