# First public commit inventory

This directory is not yet a Git repository. The first public commit should contain only reviewed source, build configuration, licenses, public documentation and store metadata.

## Include

- Root build files: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat` and `gradle/`.
- Android source: `app/build.gradle.kts`, `app/proguard-rules.pro` and `app/src/`.
- Beacon source: `firmware/lshell-beacon/`, including its license, README, PlatformIO configuration and `src/`.
- Public project files: `README.md`, `LICENSE`, `PRIVACY.md`, `THIRD_PARTY_NOTICES.md`, `.gitignore` and `.gitattributes`.
- Public documentation: `docs/`.
- Upstream store metadata: `fastlane/metadata/android/`.

## Exclude

- Generated and local state: `.gradle/`, `.idea/`, `.kotlin/`, `.android-validation/`, every `build/`, `local.properties`, `.vscode/` and every `.pio/`.
- Build and signing artifacts: `app/release/`, APK/AAB files, keystores, signing properties and private keys.
- Downloaded reference pages: `glance-layout.html` and `rowscope.html`.
- Internal working notes: `CORRECOES-TELEMETRIA.txt`, `LEIA-ME.txt` and `PROXIMAS-IDEIAS.md`.
- Assistant/session state: `.codex/`, `.agents/`, logs, captures and temporary files.

Before committing, inspect the dry-run list produced by Git and confirm that no excluded item appears. On Unix-compatible checkouts, `gradlew` must be executable. From a Git worktree this can be recorded with:

```sh
git add --chmod=+x gradlew
```

Do not add generated protobuf bindings: the Gradle build regenerates them from `app/src/main/proto/device.proto`.
