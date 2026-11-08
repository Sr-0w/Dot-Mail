package app.dot.mail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.DotSpacing
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val c = Dot.colors
    Column(
        Modifier.fillMaxSize().background(c.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(c.accent))
        Spacer(Modifier.height(DotSpacing.xl))
        Text("Dot Mail is locked", style = Dot.type.title, color = c.textPrimary)
        Spacer(Modifier.height(DotSpacing.xl))
        DotButton("Unlock", onUnlock, style = ButtonStyle.Secondary)
    }
}
