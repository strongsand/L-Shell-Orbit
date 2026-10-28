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
enum class HapticLevel { OFF, SUBTLE, STANDARD, EXPRESSIVE }
data class ExperienceSettings(
    val theme: OrbitTheme = OrbitTheme.SYSTEM,
    val haptics: HapticLevel = HapticLevel.STANDARD,
    val technical: Boolean = false
)

object ExperiencePreferences {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("orbit_experience", Context.MODE_PRIVATE)
    fun read(context: Context): ExperienceSettings {
        val prefs = prefs(context)
        return ExperienceSettings(
            OrbitTheme.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: OrbitTheme.SYSTEM,
            HapticLevel.entries.firstOrNull { it.name == prefs.getString("haptics", null) } ?: HapticLevel.STANDARD,
            prefs.getBoolean("technical", false)
        )
    }
    fun write(context: Context, settings: ExperienceSettings) {
        prefs(context).edit().putString("theme", settings.theme.name)
            .putString("haptics", settings.haptics.name).putBoolean("technical", settings.technical).apply()
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
