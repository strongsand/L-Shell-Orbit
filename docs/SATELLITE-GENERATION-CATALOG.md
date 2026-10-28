# Starlink generation catalog

The AR classification layer is independent of propagation. It resolves sourced
exact NORAD identities first, exact normalized International Designators second,
and verified launch rules last. A conflict at the strongest matching level stays
Unknown. The production asset currently uses exact objects only: no numeric
ranges, altitude, inclination, launch-date guesses or live-name heuristics.

## Sources and derivation

Reviewed on 2026-09-30 using Jonathan McDowell's
[object index](https://planet4589.org/space/con/star/starindex.html)
(snapshot updated 2026-08-08) and
[family summary](https://planet4589.org/space/con/star/stats.html).

- V1: individual identities whose source payload designation is explicitly V1.0.
- V1.5: individual payload identities listed for Groups 2–5, whose hardware
  family is explicitly stated in the family summary. This does not classify a
  future satellite by its live name or orbital shell.
- V2 Mini: individual rows under explicit `Gen 2A` and `Gen 2A Opt` sections.
  The optimized version is retained in the V2 Mini family.
- Direct-to-Cell: only rows under `Gen 2A DTC`, including mixed-launch payloads.
- V3: the 26 individual payload designators `2026-225A` through `2026-225AB`
  listed as `Starlink V3` in [GCAT satcat100k](https://www.planet4589.org/space/gcat/data/cat/satcat100k.html)
  (2026-10-02 snapshot). This is an explicit list of payload identities, not a
  rule for every object from launch `2026-225`: `2026-225AC` is the Starship stage.
  [CelesTrak](https://celestrak.org/Norad/elements/table.php?FORMAT=xml&GROUP=last-30-days)
  independently confirms `STARLINK-40091` as `2026-225M` / NORAD `100866`.
  Prototypes, simulators and future unverified V3 objects remain excluded.

The reviewed sources provide 1,665 V1, 2,974 V1.5, 7,284 V2 Mini, 674 D2C
and 26 V3 exact International Designators: **12,623 identities**. 10,834 NORAD IDs are additionally
cross-checked between each index satellite link and the matching CelesTrak GP
record. Identity matching does not use the OMM object name to infer generation.

The asset contains a reviewed subset derived from saved public index and orbital
records, including the 26 explicitly reviewed V3 identities. No catalog-generation
tool is included in this source distribution. Future refreshes must retain those
identities and reject conflicting designators rather than guessing generations.
Preserve source links, attribution and review date in the asset. This is a
transformed subset of public catalog facts, not a complete/fresh orbital feed.
McDowell's constellation data page declares CC-BY without specifying a version:
https://planet4589.org/space/con/star/log.html
CelesTrak terms: https://celestrak.org/usage-policy.php

## Diagnosis and fixtures

The original asset contained four launch rules and six D2C objects. The live
SupGP feed retrieved on 2026-09-30 contained 11,154 records, all with OBJECT_ID
and numeric NORAD_CAT_ID. Formatting was canonical in this snapshot; the primary
cause of Unknown was missing catalog coverage, not a failed asset match.
Only 73 of those 11,154 identities matched the original four launches/six objects.
The original resolver also ignored NORAD, required unnormalized designators and
combined strong/weak matches without priority. The tracker labelled the regular
GP fallback as SupGP; download/cache provenance is now retained separately.

| Live object | NORAD | International Designator | Original | Revised |
|---|---|---|---|---|
| STARLINK-1114 | 44927 | 2020-001P | Unknown | V1 |
| STARLINK-3075 | 49409 | 2021-104B | Unknown | V1.5 |
| STARLINK-38128 | 100001 | 2026-160A | Unknown | V2 Mini |
| STARLINK-11087 [DTC] | 59421 | 2024-065A | Unknown | V2 Mini D2C |
| STARLINK-37697 | 100272 | 2026-183A | Unknown | Unknown |

Each canonical NORAD/designator equals the live value above. None matched the
original rules. Four now match exact reviewed identities; the fifth is outside
the reviewed index. These five public OMM records are small test fixtures in
`app/src/test/resources/ar/`, with source notes. They contain no terminal capture.

On that downloaded SupGP snapshot, the actual OMM/resolver pipeline loads all
11,154 records and classifies 10,857 (598 V1, 2,566 V1.5, 7,055 V2 Mini, 638 D2C);
**297 remain Unknown**. Exact designators alone would classify 10,856. One extra
V1 resolves by reviewed NORAD 46384 (STARLINK-1771): GP/index give 2020-062BM,
while SupGP gives 2020-062BN. This demonstrates the benefit of identity priority.
This is catalog-wide coverage,
not the number visible at a user's location. Future objects may remain Unknown
until reviewed. The shipped asset now asserts only the 26 individually listed
orbital V3 identities from the September 28 launch.

## Formatting and presentation

NORAD accepts integral Int/Long or decimal digit strings, removes leading zeros
and validates 1–999999999. International Designators normalize case, whitespace,
dash variants and launch-number zero padding, with whole-string validation.
Malformed/missing designators remain absent; a known NORAD can still classify.
Object-name normalization is display-only and never classification evidence.
Required orbital elements still must be present and finite.

AR reuses the existing Compose Material `SatelliteAlt` vector and caches the
painter/tints. It draws at one raster size and scales small icons to avoid
repeated vector-cache allocation. Markers are 18 dp, 24 dp in focus, with a small
tonal backplate. At most one focus label is drawn. Generation colors use Material
primary/secondary/tertiary and deterministic theme blends; Unknown is neutral.
The filter menu doubles as the icon/color/text legend; All includes Unknown.
Classification and JSON IO occur at catalog loading, never in the drawing loop.

JVM parsing tests use test-only JSON-java 20240303 (Public Domain); the APK still
uses Android's org.json and gains no JSON runtime dependency. Physical validation
must check live sky visibility, camera/heading, dense marker legibility, filters,
dynamic colors and release behavior.
