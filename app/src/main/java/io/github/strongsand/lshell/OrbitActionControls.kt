package io.github.strongsand.lshell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp

internal enum class OrbitActionStyle { PRIMARY, SECONDARY, COMMAND }

/** Visual variants only. Callers retain their existing actions, haptics and enabled state. */
@Composable
internal fun OrbitActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: OrbitActionStyle = OrbitActionStyle.PRIMARY,
    selected: Boolean? = null,
    content: @Composable RowScope.() -> Unit
) {
    val palette = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val radius by animateDpAsState(
        if (pressed && enabled) 12.dp else if (selected == true) 28.dp else 20.dp,
        OrbitMotion.spec(OrbitMotionRole.STATE, LocalOrbitMotion.current),
        label = "action shape"
    )
    val colors = when (style) {
        OrbitActionStyle.PRIMARY -> ButtonDefaults.buttonColors()
        OrbitActionStyle.SECONDARY -> ButtonDefaults.buttonColors(
            containerColor = if (selected == true) palette.primaryContainer else palette.secondaryContainer,
            contentColor = if (selected == true) palette.onPrimaryContainer else palette.onSecondaryContainer
        )
        OrbitActionStyle.COMMAND -> ButtonDefaults.buttonColors(
            containerColor = palette.surfaceContainerHigh, contentColor = palette.onSurface
        )
    }
    Button(onClick = onClick, enabled = enabled,
        modifier = modifier.pressMotion(interaction).heightIn(min = if (style == OrbitActionStyle.PRIMARY) 52.dp else 48.dp)
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier),
        shape = RoundedCornerShape(radius), colors = colors, interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = if (style == OrbitActionStyle.PRIMARY) 20.dp else 16.dp,
            vertical = 12.dp), content = content)
}

@Composable
internal fun OrbitFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    val palette = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val container by androidx.compose.animation.animateColorAsState(
        if (selected) palette.primaryContainer else palette.surfaceContainerHigh,
        OrbitMotion.spec(OrbitMotionRole.STATE, LocalOrbitMotion.current), label = "filter surface")
    val radius by animateDpAsState(if (selected) 16.dp else 28.dp,
        OrbitMotion.spec(OrbitMotionRole.STATE, LocalOrbitMotion.current),
        label = "filter shape")
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = label,
        modifier = modifier.pressMotion(interaction).heightIn(min = 48.dp), interactionSource = interaction,
        shape = RoundedCornerShape(radius), border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = container, labelColor = palette.onSurfaceVariant,
            selectedContainerColor = container, selectedLabelColor = palette.onPrimaryContainer,
            selectedLeadingIconColor = palette.onPrimaryContainer),
        leadingIcon = leadingIcon ?: if (selected) {
            { Icon(OrbitSymbols.Check, null, Modifier.size(18.dp)) }
        } else null)
}

@Composable
internal fun SingleChoiceSegmentedButtonRowScope.OrbitSegmentedButton(
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable () -> Unit
) {
    val palette = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val container by androidx.compose.animation.animateColorAsState(
        if (selected) palette.primaryContainer else palette.surfaceContainerHigh,
        OrbitMotion.spec(OrbitMotionRole.STATE, LocalOrbitMotion.current), label = "segment surface")
    SegmentedButton(selected = selected, onClick = onClick, shape = shape, enabled = enabled,
        modifier = modifier.pressMotion(interaction).heightIn(min = 48.dp), interactionSource = interaction,
        colors = SegmentedButtonDefaults.colors(
            activeContainerColor = container, activeContentColor = palette.onPrimaryContainer,
            activeBorderColor = palette.surface,
            inactiveContainerColor = container, inactiveContentColor = palette.onSurfaceVariant,
            inactiveBorderColor = palette.surface), label = label)
}
