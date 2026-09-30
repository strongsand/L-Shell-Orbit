# L-Shell Orbit release checklist

> Historical first-beta checklist (2026-09-26), not the current release state. The public repository and pt-BR screenshots now exist, and subsequent local project validations have been completed. Current Android version is `0.1.1-beta` / code `2`; release R8 is enabled with the required Protobuf keep rule, while resource shrinking remains disabled. The current release checkpoint and signing procedure are recorded below. Unchecked historical boxes do not establish current blockers.

Target release: `0.1.0-beta` (`versionCode` 1)  
Future tag: `v0.1.0-beta`

## Source validation

- [ ] Unit tests pass.
- [ ] Debug build passes from the Gradle Wrapper.
- [ ] Release build passes from the Gradle Wrapper.
- [ ] Release APK behavior is checked on supported hardware.
- [ ] Original first-beta configuration was `isMinifyEnabled = false`, `isShrinkResources = false` (superseded: current release uses minify `true`, shrink resources `false`).
- [ ] No signing key, keystore, password, local property or generated APK is staged.
- [ ] `gradlew` is committed with Unix executable mode (`100755`).

The command-line validation attempted on 2026-09-26 was blocked by an environment-specific Windows/sandbox `javac` ZipFS `AccessDeniedException` while closing dependency JARs. No source or test failure was reported before that compiler failure. Repeat all three checks in a normal local terminal before marking them complete.

## Public repository

- [ ] Public repository created.
- [ ] First commit contents reviewed.
- [ ] Public repository, source, issue tracker and changelog URLs added.
- [ ] Final commit reviewed with no generated or private files.
- [ ] Tag `v0.1.0-beta` created from the exact release commit.
- [ ] GitHub release created, if desired.

## Store metadata and F-Droid

- [x] Fastlane metadata text prepared in English and Brazilian Portuguese.
- [ ] Real screenshots captured, reviewed and added.
- [ ] Separate store icon/feature graphic added if required.
- [x] F-Droid draft metadata contains the public source and issue-tracker URLs.
- [ ] F-Droid build recipe validated from the public tag.
- [ ] Submission to `fdroiddata` prepared.

## Final privacy and licensing review

- [ ] `LICENSE`, `PRIVACY.md` and `THIRD_PARTY_NOTICES.md` reviewed in the release commit.
- [ ] Secret and personal-data scan repeated on the exact files to be committed.
- [x] No real equipment capture is present.
- [x] Independent/unofficial project disclaimer remains visible.

## Current release checkpoint — 0.1.1-beta

Manually confirmed by the project maintainer on 2026-09-30:

- Android application ID/namespace: `io.github.strongsand.lshell`.
- `versionName = "0.1.1-beta"`, `versionCode = 2`; tag `v0.1.1-beta`.
- Release uses `isMinifyEnabled = true`, `isShrinkResources = false`.
- `app/proguard-rules.pro` keeps the generated Protobuf Lite messages required
  for local Dishy/router communication:

  ```proguard
  -keep class io.github.strongsand.lshell.proto.** extends com.google.protobuf.GeneratedMessageLite { *; }
  ```

- The signed upstream APK is published in [GitHub Release v0.1.1-beta](https://github.com/strongsand/L-Shell-Orbit/releases/tag/v0.1.1-beta)
  as `L-Shell-Orbit-0.1.1-beta.apk`.
- Seven pt-BR screenshots are included in Fastlane; see the
  [screenshot capture guide](../fastlane/metadata/android/SCREENSHOTS.md).

### Confirmed reproducible APK flow

1. Run `.\gradlew.bat clean assembleRelease`.
2. Use `app/build/outputs/apk/release/app-release-unsigned.apk`.
3. Align with `zipalign` from Android Build Tools `34.0.0`.
4. Sign with `apksigner` from Android Build Tools `34.0.0`, using the existing
   release key and alias `lshell-orbit`.
5. Verify with `apksigner verify --verbose --print-certs`. The published APK
   was validated with a valid signature, one signer, APK Signature Scheme v2
   and APK Signature Scheme v3.

Public signing certificate SHA-256:

`83441e95d2580ecfae237096a403e952a1e38aae6c4ebea0a7311a294e965868`

Never commit signing passwords, keystore contents or private keys. Preserve the
existing release key to maintain Android update compatibility; changing signing
tools requires reproducibility validation. Do not move published tags.

The live F-Droid reproducible-build setup uses the following values, independently
of the older local YAML example:

```yaml
Binaries: https://github.com/strongsand/L-Shell-Orbit/releases/download/v%v/L-Shell-Orbit-%v.apk
AllowedAPKSigningKeys: 83441e95d2580ecfae237096a403e952a1e38aae6c4ebea0a7311a294e965868
```

`%v` expands to versionName; the asset convention is `L-Shell-Orbit-<versionName>.apk`
under tag `v<versionName>`. F-Droid CI passed, including reproducible `check apk`,
but official MR [!50362](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50362)
still awaits manual testing/review and a conditional merge. The app is not yet
officially accepted or published on F-Droid. See the [F-Droid audit](F-DROID-AUDIT.md).
