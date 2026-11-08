package app.dot.design.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing

/**
 * Filled, borderless-until-focused text field with the label above it (never floating).
 * [contentType] wires Android autofill (passwords, emails).
 */
@Composable
fun DotTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supporting: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    contentType: ContentType? = null,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    val c = Dot.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var reveal by rememberSaveable { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        when {
            isError -> c.danger
            focused -> c.borderStrong
            else -> c.surface
        },
        label = "field-border",
    )
    Column(modifier) {
        if (label != null) {
            Text(label, style = Dot.type.label, color = c.textSecondary)
            Spacer(Modifier.height(DotSpacing.s))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .clip(DotShapes.medium)
                .background(c.surface)
                .border(1.dp, borderColor, DotShapes.medium)
                .padding(horizontal = DotSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 13.dp)) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, style = Dot.type.body, color = c.textTertiary, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    textStyle = Dot.type.body.copy(color = c.textPrimary),
                    cursorBrush = SolidColor(c.accent),
                    interactionSource = interaction,
                    visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else keyboardType,
                        imeAction = imeAction,
                        autoCorrectEnabled = !password && keyboardType == KeyboardType.Text,
                    ),
                    keyboardActions = KeyboardActions(onAny = { onImeAction?.invoke() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .semantics {
                            // The visible label is a sibling Text; say it on the field itself for screen readers.
                            (label ?: placeholder)?.let { contentDescription = it }
                            if (isError && supporting != null) error(supporting)
                        }
                        .then(if (contentType != null) Modifier.semantics { this.contentType = contentType } else Modifier),
                )
            }
            if (password) {
                DotIconButton(
                    if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    if (reveal) "Hide password" else "Show password",
                    onClick = { reveal = !reveal },
                    tint = c.textSecondary,
                )
            }
        }
        if (supporting != null) {
            Spacer(Modifier.height(6.dp))
            Text(supporting, style = Dot.type.caption, color = if (isError) c.danger else c.textTertiary)
        }
    }
}

/** Search input in the style of a chat composer: soft filled rounded rectangle. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search mail",
    focusRequester: FocusRequester? = null,
    onSearch: () -> Unit = {},
) {
    val c = Dot.colors
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(DotShapes.medium)
            .background(c.surface)
            .padding(start = DotSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = c.textTertiary)
        Spacer(Modifier.width(DotSpacing.s))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = Dot.type.body, color = c.textTertiary, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Dot.type.body.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = placeholder }
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            )
        }
        if (value.isNotEmpty()) {
            DotIconButton(Icons.Outlined.Close, "Clear search", onClick = { onValueChange("") }, tint = c.textSecondary)
        } else {
            Spacer(Modifier.width(DotSpacing.m))
        }
    }
}

/**
 * Switch re-skinned to the monochrome palette. Pass a null [onCheckedChange] when the switch sits in a
 * row that is itself `toggleable` (the row then carries the semantics, the switch is purely visual).
 */
@Composable
fun DotSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Dot.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onInverse,
            checkedTrackColor = c.inverse,
            checkedBorderColor = c.inverse,
            uncheckedThumbColor = c.textTertiary,
            uncheckedTrackColor = c.surfaceRaised,
            uncheckedBorderColor = c.borderStrong,
            disabledCheckedThumbColor = c.onInverse.copy(alpha = 0.6f),
            disabledCheckedTrackColor = c.textTertiary.copy(alpha = 0.5f),
            disabledCheckedBorderColor = Color.Transparent,
            disabledUncheckedThumbColor = c.borderStrong,
            disabledUncheckedTrackColor = c.surface,
            disabledUncheckedBorderColor = c.border,
        ),
    )
}
