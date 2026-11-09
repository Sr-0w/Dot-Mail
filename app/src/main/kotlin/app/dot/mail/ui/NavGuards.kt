package app.dot.mail.ui

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController

/**
 * Back and navigation actions come from taps, gestures and coroutine events that can fire twice, or after the screen has
 * already started leaving (double Back, Back during a swipe, an archive event racing the back arrow). Only the screen that
 * is still the top of the back stack may act: once it has been popped (even while its exit animation plays) a second pop
 * would remove the screen underneath, leaving an empty back stack, and a late "open next" would resurrect a screen the
 * user just left. This holds in the background too, where a lifecycle check would wrongly drop a legitimate close.
 */
internal fun NavController.isOnTop(entry: NavBackStackEntry): Boolean = currentBackStackEntry?.id == entry.id

/** Pops [entry] once; repeated or late calls do nothing. Returns whether a pop happened. */
internal fun NavController.popFrom(entry: NavBackStackEntry): Boolean = if (isOnTop(entry)) popBackStack() else false
