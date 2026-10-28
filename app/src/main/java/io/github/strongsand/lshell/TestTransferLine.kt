package io.github.strongsand.lshell

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Indeterminate traffic illustration. Its moving marker is NOT a completion percentage. */
@Composable
internal fun TestTransferLine(state: ConnectionTestState) {
    val mode = TestTransferPresentation.mode(state)
    if (mode == TestTransferMode.NONE) return
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val animate = TestTransferPresentation.animate(state, LocalOrbitMotion.current, lifecycle.isAtLeast(Lifecycle.State.STARTED))
    val fraction: State<Float> = key(state.id, mode, animate) {
        if (animate) rememberInfiniteTransition(label = "test traffic").animateFloat(0f, 1f,
            infiniteRepeatable(tween(if (mode == TestTransferMode.PREPARING) 1100 else 1400, easing = LinearEasing),
                if (mode == TestTransferMode.PREPARING) RepeatMode.Reverse else RepeatMode.Restart), label = "traffic marker")
        else rememberUpdatedState(.5f)
    }
    val description = stringResource(when (mode) {
        TestTransferMode.DOWNLOAD -> R.string.test_flow_download_description
        TestTransferMode.UPLOAD -> R.string.test_flow_upload_description
        else -> R.string.test_flow_wait_description
    })
    val color = amoledCardContent(MaterialTheme.colorScheme.onSecondaryContainer)
    Row(Modifier.fillMaxWidth().height(48.dp).semantics(mergeDescendants = true) {
        contentDescription = description
    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(R.drawable.ic_test_flow_satellite), null, Modifier.size(28.dp), tint = color)
        Canvas(Modifier.weight(1f).height(32.dp).clearAndSetSemantics {}) {
            val margin = 3.dp.toPx()
            val start = margin
            val end = (size.width - margin).coerceAtLeast(start)
            val y = size.height / 2f
            drawLine(color.copy(alpha = .18f), Offset(start, y), Offset(end, y), 3.dp.toPx(), StrokeCap.Round)
            val x = start + (end - start) * TestTransferPresentation.marker(mode, fraction.value)
            val tail = (end - start) * .18f
            val tailX = if (mode == TestTransferMode.UPLOAD) (x + tail).coerceAtMost(end) else (x - tail).coerceAtLeast(start)
            drawLine(color.copy(alpha = .85f), Offset(tailX, y), Offset(x, y), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(color, 3.5.dp.toPx(), Offset(x, y))
            // Direction remains readable when animation is reduced or disabled.
            if (mode == TestTransferMode.DOWNLOAD || mode == TestTransferMode.UPLOAD) {
                val sign = if (mode == TestTransferMode.UPLOAD) -1f else 1f
                val tip = x + sign * 4.dp.toPx()
                drawLine(color, Offset(tip - sign * 5.dp.toPx(), y - 4.dp.toPx()), Offset(tip, y), 2.dp.toPx(), StrokeCap.Round)
                drawLine(color, Offset(tip - sign * 5.dp.toPx(), y + 4.dp.toPx()), Offset(tip, y), 2.dp.toPx(), StrokeCap.Round)
            } else if (!animate) drawCircle(color, 5.dp.toPx(), Offset(x, y), style = Stroke(1.5.dp.toPx()))
        }
        Icon(painterResource(R.drawable.ic_test_flow_person), null, Modifier.size(28.dp), tint = color)
    }
}
