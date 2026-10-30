package io.github.strongsand.lshell

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

enum class OrbitTheme { SYSTEM, LIGHT, DARK, AMOLED;
    fun isDark(systemDark: Boolean) = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK, AMOLED -> true
    }
}
enum class AmoledColors { ORIGINAL, MONOCHROME }
data class ExperienceSettings(
    val theme: OrbitTheme = OrbitTheme.SYSTEM,
    val technical: Boolean = false,
    val amoledColors: AmoledColors = AmoledColors.ORIGINAL
) {
    val monochrome get() = theme == OrbitTheme.AMOLED && amoledColors == AmoledColors.MONOCHROME
}

/** Shared by persistence and restore tests. Retired haptic keys never affect behavior. */
internal object ExperienceSettingsCodec {
    fun decode(values: Map<String, *>) = ExperienceSettings(
        theme = OrbitTheme.entries.firstOrNull { it.name == values["theme"] } ?: OrbitTheme.SYSTEM,
        technical = values["technical"] as? Boolean ?: false,
        amoledColors = AmoledColors.entries.firstOrNull { it.name == values["amoled_colors"] } ?: AmoledColors.ORIGINAL
    )
    fun encode(settings: ExperienceSettings): Map<String, Any> = mapOf(
        "theme" to settings.theme.name, "technical" to settings.technical,
        "amoled_colors" to settings.amoledColors.name
    )
}

object ExperiencePreferences {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("orbit_experience", Context.MODE_PRIVATE)
    fun read(context: Context): ExperienceSettings {
        return ExperienceSettingsCodec.decode(prefs(context).all)
    }
    fun write(context: Context, settings: ExperienceSettings) {
        val editor = prefs(context).edit().remove("haptics")
        ExperienceSettingsCodec.encode(settings).forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.apply()
    }
    fun observe(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    fun stopObserving(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
}

// These values change while destinations and lazy items stay composed.
// Track each reader so preference callbacks invalidate the actual controls.
val LocalExperience = compositionLocalOf { ExperienceSettings() }
val LocalOrbitDark = staticCompositionLocalOf { false }
val LocalOrbitMotion = staticCompositionLocalOf { true }

/** One read per host, then preference callbacks. Never poll preferences during animation. */
@Composable
fun ExperienceProvider(initial: ExperienceSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    var settings by remember(context) { mutableStateOf(initial) }
    var motion by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(context, lifecycle) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settings = ExperiencePreferences.read(context) }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) motion = ValueAnimator.areAnimatorsEnabled()
        }
        ExperiencePreferences.observe(context, listener)
        lifecycle.addObserver(observer)
        onDispose {
            ExperiencePreferences.stopObserving(context, listener)
            lifecycle.removeObserver(observer)
        }
    }
    CompositionLocalProvider(LocalExperience provides settings,
        LocalOrbitDark provides settings.theme.isDark(isSystemInDarkTheme()), LocalOrbitMotion provides motion,
        content = content)
}
