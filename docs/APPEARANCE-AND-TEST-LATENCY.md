# Appearance and normal-test latency

## AMOLED variants

The existing System, Light, Dark and AMOLED choices remain. AMOLED alone exposes
Original and Black and white. `orbit_experience.amoled_colors` persists the choice
independently of the selected theme. Missing or unknown values select Original.

`AmoledPalette` supplies neutral Material color roles; Original preserves the
existing AMOLED surface overrides. Shared health roles and Glance widget colors
follow the selected variant. Multiple chart origins use distinct neutral
luminances and solid/dashed strokes. No screen-wide color filter is applied:
camera frames and real images retain their colors. Android-controlled colors are
not overridden.

The custom haptic selector and vibration dispatcher are retired. Existing gesture
callbacks remain compatible and still execute their actions. Legacy `haptics`
preferences are ignored and removed on the next preference write; old backup
entries are ignored by the settings allowlist. Android's normal controls and
accessibility feedback are unchanged.

## Normal speed test

The local `get_speedtest_status` contract provides throughput arrays, not a
latency field. Normal tests previously constructed `SpeedTestResult` without any
latency. Extreme rounds have a separate existing PoP-telemetry fallback.

Normal tests now observe the shared Dishy gRPC `telemetryFlow` while the router
test runs. A temporary lease reuses the existing polling loop at the same 2-second
interval used by connection testing, and is released on completion, failure or
cancellation. No second client, external endpoint or permanent polling job is
introduced. Extreme execution is unchanged.

A genuine speed-test latency has priority. Otherwise an online, uncached,
positive PoP response must have been received during this execution and remain no
older than 15 seconds. This is a local receipt timestamp, not a remote measurement
timestamp. Result and notification captions identify it as PoP
latency. Source and original sample timestamp are optional execution/history
metadata; the database schema is unchanged. Historical unknown sources stay
unknown.

The legacy status model maps absent scalar latency to zero, so zero cannot prove
a measured PoP value there. This fallback therefore rejects that ambiguous zero,
NaN, infinity and negative values. An explicitly supplied speed-test latency of
zero remains valid. Missing or expired telemetry stays unavailable; no value is
borrowed from a previous execution and no historical result is backfilled.

Device QA must cover persistence after process restart, large fonts, TalkBack,
widget updates, AR camera colors, and normal tests completed, cancelled or timed
out with the app foregrounded/backgrounded and Dishy connected/disconnected.

## Review limitations

The maintainer reports that the debug build works on a physical device; this does
not establish individual checks that were not documented. The final signed APK
requires a separate smoke test. The following review findings remain open:

- The compass detail uses white ink over a white terminal illustration in the
  monochrome variant.
- The Android 15 widget-picker preview cache key omits the AMOLED variant and may
  keep the previous preview after a variant change.
- Full result details preserve latency provenance, but the compact test-history
  model/display retains only the numerical latency and does not label it as PoP.
- `isOnline` means the Dishy gRPC endpoint responded. The normal latency filter
  does not independently reject outage states such as `NO_PINGS` if a positive
  latency is returned.
- Normal-test latency does not bind a sample to the tested router's Dishy identity
  or invalidate a change of terminal identity during execution.

These findings are not claimed as fixed by release preparation. No prior result
is rewritten or backfilled to supply missing provenance.

## Implementation map

Paths below are relative to the repository root. Kotlin files without a directory
prefix are under `app/src/main/java/io/github/strongsand/lshell/`.

| Files | Purpose |
| --- | --- |
| `AmoledPalette.kt`, `MainActivity.kt`, `ExpressiveDesign.kt` | Central neutral palette, unchanged Original palette and shared health colors. |
| `ExperiencePreferences.kt`, `ExperienceControls.kt` | Persist the independent AMOLED variant and show its selector only under AMOLED. |
| `DashInteractions.kt`, `HapticPolicy.kt` | Retire custom vibration while keeping gesture/action callbacks compatible. |
| `BackupCodecs.kt`, `BackupCoordinator.kt` | Back up the variant; ignore retired haptic settings and preserve other settings. |
| `WidgetAppearance.kt`, `OrbitWidgetContent.kt`, `LShellWidgets.kt` | Neutral Glance surfaces/text, preference updates and obstruction-map symbols. |
| `MetricDetailChart.kt` | Distinct line patterns for monochrome series without changing data or scales. |
| `AlignmentScreen.kt`, `ar/SkyARScreen.kt` | Neutral decorative ink; preserve alignment math, AR rendering and real camera imagery. |
| `NormalTestLatency.kt` | Validate and observe recent PoP telemetry, identify its source and clean up collection. |
| `SpeedTestClient.kt`, `ConnectionTestState.kt` | Optional latency provenance and transient live-reading state. |
| `ConnectionTestController.kt`, `ConnectionTestService.kt` | Bound the shared telemetry lease to normal execution; preserve existing extreme execution. |
| `ConnectionTestPersistence.kt` | Preserve optional source/sample-time metadata, compatible with older results. |
| `SpeedTestScreen.kt`, `TestResultComponents.kt`, `ConnectionTestExperience.kt` | Identified result caption and live PoP reading with unavailable/expired states. |
| `ConnectionTestResultNotification.kt` | Identify PoP latency in the normal-result notification without changing its layout or actions. |
| `app/src/main/res/values/amoled_strings.xml`, `app/src/main/res/values-en/amoled_strings.xml`, `app/src/main/res/values-es/amoled_strings.xml` | Portuguese, English and Spanish appearance choices. |
| `app/src/main/res/values/normal_latency_strings.xml`, `app/src/main/res/values-en/normal_latency_strings.xml`, `app/src/main/res/values-es/normal_latency_strings.xml` | Portuguese, English and Spanish PoP captions and explanation. |
| `app/src/test/java/io/github/strongsand/lshell/AmoledAppearanceTest.kt` | Persistence policy, old settings, unchanged accents, neutral roles, contrast and widget colors. |
| `app/src/test/java/io/github/strongsand/lshell/NormalTestLatencyTest.kt` | Valid/missing/invalid/expired readings, measured zero, updates, cancellation, provenance and history. |
| `app/src/test/java/io/github/strongsand/lshell/ExperienceTest.kt`, `app/src/test/java/io/github/strongsand/lshell/CardLayoutTest.kt` | Adapt settings construction and remove tests of the retired haptic policy; preserve existing search/layout coverage. |
| `docs/APPEARANCE-AND-TEST-LATENCY.md` | Technical behavior, limits, implementation map and device-QA checklist. |
