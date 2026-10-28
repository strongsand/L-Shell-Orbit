package io.github.strongsand.lshell

/** Compatibility for older display-only callers; translated words are never a sentinel. */
internal fun infoRowValueMissing(value: String): Boolean =
    value.trim() in setOf("—", "--", "-- ms", "— ms", "— W", "—%")
