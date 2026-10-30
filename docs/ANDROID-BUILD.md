# Android build notes

Orbit uses JDK 17, the committed Gradle wrapper and the dependency versions in
`gradle/libs.versions.toml`. Release builds retain R8 and the Protobuf Lite keep
rule in `app/proguard-rules.pro`; official signing is a separate local step.

## Android SDK platform identity

The application explicitly declares `compileSdk = 37` and `compileSdkMinor = 0`.
The SDK package is `platforms;android-37.0`, installed under
`platforms/android-37.0`. Build Tools 35.0.0 are used by the existing AGP release.
For a clean environment, install these components before Gradle starts:

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;35.0.0'
```

An integer compile SDK without its minor version can request `android-37`
instead of the installed `android-37.0`. Preinstallation and an explicit minor
version address platform resolution; neither is proof of a reproducible APK.
The SDK minor-version property is available in the committed AGP 8.13.2 API.
AGP still warns that API 37.0 is above its tested API range; this change does not
upgrade AGP, Gradle, Kotlin or other dependencies or suppress that warning.

See the [AGP 8.13 DSL reference](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/dsl/CommonExtension)
and [Android build configuration](https://developer.android.com/build).

## Local validation and artifacts

Run relevant JVM tests and release lint before an unsigned release build:

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

No release signing configuration is committed. The expected technical output is
`app/build/outputs/apk/release/app-release-unsigned.apk`; keep its matching
`app/build/outputs/mapping/release/mapping.txt`. A dirty development build is not
the final publication artifact. Freeze and review the public source commit before
building the final release with verifiable Git provenance.

## F-Droid

Keep the official signing-certificate allowlist and the existing upstream binary
URL pattern. A future version-7 recipe must reference the final public source
commit and version `0.2.0-beta.3`, with version code `7`. The release asset name is
`L-Shell-Orbit-0.2.0-beta.3.apk`. Do not retarget or replace the version-6 tag/APK.

Local compilation does not validate F-Droid's Linux environment or binary
reproducibility. Both require a successful F-Droid build and an actual comparison
against the newly signed upstream APK. Until that happens the update is pending.
