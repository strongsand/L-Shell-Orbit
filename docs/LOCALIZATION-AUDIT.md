# Localization audit

Audit date: 2026-09-27

## Current state

The application is primarily written in Brazilian Portuguese. It currently has partial English and Spanish localization, concentrated on the L-Shell Beacon flow.

- `values/beacon_strings.xml`: 93 default Portuguese strings.
- `values-en/beacon_strings.xml`: the same 93 keys in English.
- `values-es/beacon_strings.xml`: the same 93 keys in Spanish.
- XML layouts and manifests do not contain user-facing hardcoded text.
- Widget names are resources, but currently have only the default Portuguese values.
- A conservative source scan found about 281 Kotlin UI-literal locations. This is an approximate count of source locations, not a count of unique sentences.

## Screen coverage

No complete top-level screen can currently be described as fully localized across Portuguese, English and Spanish.

The L-Shell Beacon screen is substantially localized and is the closest to complete coverage. Four dynamic status/count strings remain directly in Kotlin, so it is classified as partially localized. The Beacon entry in the functions menu also uses localized resources.

Home, antenna metrics, history, reports, diagnostics, speed tests, router, alignment, AR, settings, credits and widget content remain predominantly Portuguese because most of their text is still declared directly in Kotlin.

The accurate public classification for this release is **Brazilian Portuguese with partial English and Spanish localization**. Store metadata may be provided in English and Portuguese without implying that the whole in-app interface is translated.

## Follow-up

A future localization pass should move remaining user-facing Kotlin literals into Android string resources, preserve formatting placeholders and plurals, and then translate complete screens instead of isolated labels.
