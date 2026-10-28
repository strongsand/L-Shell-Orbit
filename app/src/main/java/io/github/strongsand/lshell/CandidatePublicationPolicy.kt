package io.github.strongsand.lshell

/** Public candidate scope. Preferences, server responses and saved state cannot override it. */
internal object CandidatePublicationPolicy {
    const val POE_INPUT = true
    const val ESTIMATED_AR_LINK = false
}
