package io.github.strongsand.lshell

/** Shared by Glance text, surfaces and the generated obstruction diagram. */
internal fun widgetAppearanceColor(resource: Int, settings: ExperienceSettings, original: Int): Int {
    if (!settings.monochrome) return original
    return when (resource) {
        R.color.widget_background -> 0xFF000000.toInt()
        R.color.widget_inner, R.color.widget_primary -> 0xFF101010.toInt()
        R.color.widget_muted -> 0xFFBDBDBD.toInt()
        R.color.widget_secondary -> 0xFF858585.toInt()
        else -> 0xFFFFFFFF.toInt()
    }
}
