package app.dot.mail.ui.thread

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.dot.mail.ReaderFont
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReaderStyleTest {
    private val base = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, fontFamily = FontFamily.Monospace)

    @Test fun `plain text style scales size, sets line height and picks the typeface`(): Unit {
        val serif = readerTextStyle(base, 1.2f, ReaderFont.SERIF, 180)
        assertThat(serif.fontSize.value).isWithin(0.01f).of(18f)
        assertThat(serif.lineHeight.value).isWithin(0.01f).of(18f * 1.8f)
        assertThat(serif.fontFamily).isEqualTo(FontFamily.Serif)
        assertThat(readerTextStyle(base, 1f, ReaderFont.SYSTEM, 155).fontFamily).isEqualTo(FontFamily.Default)
        assertThat(readerTextStyle(base, 1f, ReaderFont.INTER, 155).fontFamily).isEqualTo(FontFamily.Monospace)
    }

    @Test fun `line height is clamped to a readable range`(): Unit {
        assertThat(readerTextStyle(base, 1f, ReaderFont.SYSTEM, 10).lineHeight.value).isWithin(0.01f).of(15f * 1.2f)
        assertThat(readerTextStyle(base, 1f, ReaderFont.SYSTEM, 900).lineHeight.value).isWithin(0.01f).of(15f * 2f)
    }

    @Test fun `html font stacks and bundled Inter faces`(): Unit {
        assertThat(fontStack(ReaderFont.SERIF)).isEqualTo("serif")
        assertThat(fontStack(ReaderFont.SYSTEM)).isEqualTo("sans-serif")
        assertThat(fontStack(ReaderFont.INTER)).contains("DotInter")
        val faces = fontFaces(ReaderFont.INTER)
        assertThat(faces).contains("dotfont://inter_regular")
        assertThat(faces.lines().count { it.startsWith("@font-face") }).isEqualTo(4)
        assertThat(fontFaces(ReaderFont.SERIF)).isEmpty()
    }
}
