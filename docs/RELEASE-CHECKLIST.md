# L-Shell Orbit release checklist

Target release: `0.1.0-beta` (`versionCode` 1)  
Future tag: `v0.1.0-beta`

## Source validation

- [ ] Unit tests pass.
- [ ] Debug build passes from the Gradle Wrapper.
- [ ] Release build passes from the Gradle Wrapper.
- [ ] Release APK behavior is checked on supported hardware.
- [ ] `isMinifyEnabled = false` and `isShrinkResources = false` remain unchanged.
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
- [ ] F-Droid metadata URLs completed after publication.
- [ ] F-Droid build recipe validated from the public tag.
- [ ] Submission to `fdroiddata` prepared.

## Final privacy and licensing review

- [ ] `LICENSE`, `PRIVACY.md` and `THIRD_PARTY_NOTICES.md` reviewed in the release commit.
- [ ] Secret and personal-data scan repeated on the exact files to be committed.
- [x] No real equipment capture is present.
- [x] Independent/unofficial project disclaimer remains visible.
