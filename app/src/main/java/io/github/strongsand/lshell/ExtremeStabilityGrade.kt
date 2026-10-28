package io.github.strongsand.lshell

/** Derived from runs only; never serialized in session files or backups. */
internal enum class ExtremeStabilityGrade { EXCELLENT, GOOD, VARIABLE, UNSTABLE }

internal fun ExtremeStabilityGrade.label(): Int = when (this) {
    ExtremeStabilityGrade.EXCELLENT -> R.string.test_quality_excellent
    ExtremeStabilityGrade.GOOD -> R.string.test_quality_good
    ExtremeStabilityGrade.VARIABLE -> R.string.test_quality_variable
    ExtremeStabilityGrade.UNSTABLE -> R.string.test_quality_unstable
}
