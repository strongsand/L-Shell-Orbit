package io.github.strongsand.lshell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Shared UI tokens; semantic green/amber are intentionally independent of wallpaper colors. */
object DashDesign {
    val gap = 12.dp
    val inset = 20.dp
    val section = RoundedCornerShape(24.dp)
    val hero = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 12.dp, bottomStart = 32.dp)
    val download = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 8.dp)
    val upload = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 8.dp, bottomStart = 28.dp)
    val pill = RoundedCornerShape(50)
    const val motionMillis = 220
    val cookie = GenericShape { size, _ ->
        val radius = minOf(size.width, size.height) / 2f
        for (step in 0..120) {
            val angle = step * 2.0 * PI / 120
            val r = radius * (0.91 + 0.09 * cos(8 * angle))
            val x = size.width / 2 + (cos(angle) * r).toFloat()
            val y = size.height / 2 + (sin(angle) * r).toFloat()
            if (step == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    val typography = Typography(
        displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
        headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.7).sp),
        headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
        titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    )
}

enum class HealthTone { GOOD, CAUTION, DANGER, UNKNOWN }
data class HealthColors(val container: Color, val foreground: Color)

/** Large panels stay neutral on OLED; accent roles remain available to small controls. */
@Composable
internal fun amoledCardColor(color: Color): Color =
    if (LocalExperience.current.theme == OrbitTheme.AMOLED) MaterialTheme.colorScheme.surfaceContainerLow else color

@Composable
internal fun amoledCardContent(color: Color): Color =
    if (LocalExperience.current.theme == OrbitTheme.AMOLED) MaterialTheme.colorScheme.onSurface else color

/** Shared semantic roles for Dishy power cards and charts. */
data class EnergyMetricColors(
    val container: Color,
    val onContainer: Color,
    val chartLine: Color,
    val chartFill: Color
)

@Composable
fun energyMetricColors() = EnergyMetricColors(
    container = amoledCardColor(MaterialTheme.colorScheme.tertiaryContainer),
    onContainer = amoledCardContent(MaterialTheme.colorScheme.onTertiaryContainer),
    chartLine = MaterialTheme.colorScheme.tertiary,
    chartFill = MaterialTheme.colorScheme.tertiaryContainer
)

@Composable
fun healthColors(tone: HealthTone): HealthColors {
    val dark = LocalOrbitDark.current
    if (LocalExperience.current.monochrome) {
        val palette = MaterialTheme.colorScheme
        return HealthColors(if (tone == HealthTone.UNKNOWN) palette.surfaceContainerLow else palette.surfaceContainerHighest,
            if (tone == HealthTone.UNKNOWN) palette.onSurfaceVariant else palette.onSurface)
    }
    return when (tone) {
        HealthTone.GOOD -> if (dark) HealthColors(Color(0xFF153B2B), Color(0xFFA4EBBC))
            else HealthColors(Color(0xFFD8F3E0), Color(0xFF175333))
        HealthTone.CAUTION -> if (dark) HealthColors(Color(0xFF473719), Color(0xFFFFDB95))
            else HealthColors(Color(0xFFFFEDC4), Color(0xFF664700))
        HealthTone.DANGER -> HealthColors(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        HealthTone.UNKNOWN -> HealthColors(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HealthChip(label: String, tone: HealthTone, modifier: Modifier = Modifier) {
    val colors = healthColors(tone)
    val container by animateColorAsState(colors.container, OrbitMotion.spec(OrbitMotionRole.STATE, LocalOrbitMotion.current), label = "health container")
    val foreground by animateColorAsState(colors.foreground, OrbitMotion.spec(OrbitMotionRole.STATE, LocalOrbitMotion.current), label = "health foreground")
    Surface(modifier = modifier.animateContentSize(
        OrbitMotion.spec(OrbitMotionRole.STATE, LocalOrbitMotion.current)),
        shape = DashDesign.pill, color = container, contentColor = foreground) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(when (tone) {
                HealthTone.GOOD -> OrbitSymbols.Check
                HealthTone.CAUTION, HealthTone.DANGER -> OrbitSymbols.Warning
                HealthTone.UNKNOWN -> OrbitSymbols.Info
            }, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun SectionHeading(title: String, supporting: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.semantics { heading() }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun ExpressiveIcon(icon: ImageVector, container: Color, tint: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).background(container, DashDesign.cookie), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Values remain readable on narrow cards and at larger system font scales. */
@Composable
fun MetricValue(value: String, unit: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = LocalDensity.current.fontScale
        val size = (maxWidth.value / (value.length.coerceAtLeast(3) * 0.64f * scale)).coerceIn(18f, 42f)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            // The received number is exact; only its drawing emphasis animates.
            MetricDetailValueText(value, MaterialTheme.typography.displaySmall.copy(
                fontSize = size.sp, lineHeight = (size + 4).sp, fontFeatureSettings = "tnum"),
                color = color, modifier = Modifier.semantics { contentDescription = "$value $unit" })
            if (unit.isNotBlank()) Text(unit, style = MaterialTheme.typography.labelLarge, color = color.copy(alpha = 0.82f))
        }
    }
}

fun splitMetric(value: String): Pair<String, String> {
    val cleaned = value.trim()
    return when {
        cleaned.endsWith(" W") -> cleaned.removeSuffix(" W") to "W"
        cleaned.endsWith(" Mbps") -> cleaned.removeSuffix(" Mbps") to "Mbps"
        cleaned.endsWith(" ms") -> cleaned.removeSuffix(" ms") to "ms"
        cleaned.endsWith("%") -> cleaned.removeSuffix("%") to "%"
        else -> cleaned to ""
    }
}
