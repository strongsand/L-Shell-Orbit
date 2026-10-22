# L-Shell Orbit release checklist

## Stable 0.1.3-beta local finalization — 2026-10-22

- Worktree/branch: `Material_Link_v2` / `main`, preserving parent localization commit `88610ea702836268fdc7b1a1a24acf5d16709391`.
- Candidate: `0.1.3-beta`, code `4`, applicationId `io.github.strongsand.lshell`. Future tag `v0.1.3-beta`; asset `L-Shell-Orbit-0.1.3-beta.apk`.
- Authorized scope since `v0.1.2-beta`: Beacon setup/permission sheet localization; widget API-35 error colors with existing API-31–34 fallbacks; public Glance day/night color providers; EN/ES widget labels; version, changelogs and release preparation. No telemetry, permission flow, pairing, protocol, persistence, firmware or next changes.
- A local preparation commit and final-commit R8 build/signing are authorized. Push, tag, release, distribution and GitLab changes remain unauthorized in this round.

### Automated validation before the release commit

- [x] Localized XML/key uniqueness and PT-BR/EN/ES parity; version/changelog/example checks and `git diff --check`.
- [x] Debug/release resource processing and Kotlin compilation.
- [x] JVM tests: 26 passed, zero failures/errors/skips.
- [x] Full `lintRelease`: zero errors/fatals, 75 warnings and 7 hints. All 15 previously audited errors were corrected without suppressions, baseline or dependency changes.
- [x] Release/R8 and vital lint completed after the bounded lint fixes. Protobuf Lite keep rule and resource-shrinking policy preserved.
- [ ] Rebuild from the exact local release commit, verify embedded VCS revision, archive matching R8 mapping and source manifest. Pre-commit APKs are not final release artifacts.
- [ ] Verify official certificate before signing; then verify final APK alignment, v2/v3 signatures, manifest, version/code, hash and unchanged source tree. Record actual results in the ignored local release package.

### Signing and reproducibility gates

- Official alias: `lshell-orbit`; certificate SHA-256: `83441e95d2580ecfae237096a403e952a1e38aae6c4ebea0a7311a294e965868`.
- Use the existing external key and interactive local password entry. Do not copy the key or store credentials/private paths in public files. Reject a mismatching certificate before signing.
- Preserve Build Tools 34.0.0 alignment/signing, release R8 and generated Protobuf Lite keep rules. A successful local build/signature check does not establish F-Droid reproducibility.
- The final local package must identify its full source commit, unsigned/signed hashes, official signer and matching mapping. Never publish a pre-commit or subsequently changed candidate.

### Physical QA — pending, not claimed as passed

- [ ] Update over the officially signed `0.1.2-beta`; retain history, settings and Beacon configuration. Do not use a next installation as a stable downgrade test.
- [ ] Beacon discovery and permission sheets in PT-BR/EN/ES, including grant, deny, cancel and pairing paths.
- [ ] Widgets on API 31–34 (existing fallback warning colors) and API 35+ (system error colors), in light/dark launcher themes; verify text, icons and refresh.
- [ ] Small screens and enlarged fonts: wrapping, clipping, actions and readability.
- [ ] Signed R8 APK: startup, live Dishy telemetry, optional permissions and background monitoring smoke tests.
- [ ] Maintainer/reviewer device or emulator evidence is required before marking any physical item complete.

### Future publication — separate authorization required

1. Recheck GitHub remote alignment, local source cleanliness and absence of the future tag. Push the reviewed release commit and create/push immutable `v0.1.3-beta` at that exact commit; preserve older tags/assets.
2. Publish the verified signed APK as `L-Shell-Orbit-0.1.3-beta.apk` and its checksum. Verify the downloaded artifact matches the local hash and official signer.
3. Update the existing MR !50362 recipe to code `4`, version `0.1.3-beta` and the final full source commit, preserving `Binaries`, `AllowedAPKSigningKeys` and build configuration. This repository's example is not the live recipe.
4. Require a new passing F-Droid pipeline, including `fdroid build` and reproducible `check apk`, plus metadata/source/checkupdates checks. The earlier code-3 pipeline does not validate code 4.
5. Arrange reviewer retesting only when messaging is authorized. Merge remains the F-Droid maintainer's decision.
6. Preserve next; its code `4` needs a separately authorized increment to at least `5` before distribution. Review the broad F-Droid tag filter before publishing experimental tags.

Last external check during planning (2026-10-22): public stable `v0.1.2-beta`; MR !50362 open with code `3` at `b756c1b`, with a successful 2026-10-06 pipeline. This is historical evidence to refresh before publication.

## Historical first-beta checklist

> The sections below retain historical checkpoints and are not current preparation status. Release R8 remains enabled with the generated Protobuf keep rule, while resource shrinking remains disabled. The Build Tools 34.0.0 signing flow and public signing identity remain applicable. Unchecked historical boxes do not establish current blockers.

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

## Historical release checkpoint — 0.1.1-beta

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
