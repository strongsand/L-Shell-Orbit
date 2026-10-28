package io.github.strongsand.lshell.ar

/** Presentation state retains resource identity, so a locale change never reuses old text. */
internal data class ArUiText(
    val resource: Int,
    val arguments: List<Any> = emptyList(),
    val diagnostic: String? = null
) {
    inline fun resolve(text: (Int, Array<out Any>) -> String): String =
        diagnostic?.takeIf { it.isNotBlank() } ?: text(resource, arguments.toTypedArray())
}
