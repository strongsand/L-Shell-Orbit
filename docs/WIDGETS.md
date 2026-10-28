# Home-screen widgets

## Product and implementation

The three existing providers are ObstructionWidgetReceiver, ReportWidgetReceiver
and BeaconWidgetReceiver. Their identities are unchanged, including installed
widget IDs and manual refresh callbacks. There are no additional registered widgets.

Each widget answers one primary question:

- Obstruction: how much sky is obstructed, and how much of the map was surveyed?
- Report: what was availability during observation, and how much of the period
  was actually observed? Availability and period coverage are separate values.
- Beacon: is the collector available/recording, and how many records are pending?

The previous layouts used SizeMode.Exact with one compact boolean. The obstruction
map was only 76/108 dp, headers competed with readings, refresh used a text glyph
and a small touch target, picker descriptions/previews were absent and the outer
corner radius was fixed at 28 dp. A cached obstruction reading also received the
time of a newer failed query. The new presentation preserves the cached reading's
timestamp and explicitly identifies it as a last reading.

## Layout contracts

Glance remains at **1.1.1**; no dependency change is required. All three widgets
use SizeMode.Responsive with six bounded size contracts. On Android 12+, the launcher
selects the layout that fits. Below Android 12 Glance handles its compatible size
selection. These are usable dp, not assumptions about launcher cell counts.

| Contract | Width × height | Obstruction | Report | Beacon |
| --- | --- | --- | --- | --- |
| Glance | 144 × 96 | State and percentage | Availability and label | Pending count and state |
| Compact | 160 × 208 | Small centered map, reading, state | Tonal availability, date, observed coverage | State, tonal pending count, sync time |
| Horizontal | 300 × 132 | Map and reading side by side | Availability and date/coverage side by side | State and pending count side by side |
| Primary | 260 × 208 | Large proportional map left, reading/state/surveyed coverage right | Unboxed availability hero, date and small coverage capsule | Tonal operational state left, backlog right, sync footer |
| Hero | 240 × 288 | Larger map, large reading, state, coverage, time | Availability, date, coverage, short summary | State, larger pending count, sync time |
| Expanded | 320 × 352 | Same primary composition, map capped at 176 dp | Same primary composition | Same primary composition |

If increased font scale leaves too little height, the presentation reveals less
context instead of scaling every body label down. Root surfaces fill the host
bounds with only internal 12 dp padding. The outer corner radius is the Android
system AppWidget radius on API 31+; 16 dp is the pre-31 drawable fallback.

Default targets are 3×2 with 260×208 dp legacy minimum dimensions. Resize minimums
are 144×96 dp; the picker/launcher grid can offer different dimensions on different
devices. Resize maxima are 480×400 dp to constrain excessive growth. They are not a promise that
a 3×2 cell widget has a particular dp size.

## Visual language and accessibility

The primary 3×2 family shares a discreet title/refresh header, large readings and
12 dp internal margins. Obstruction devotes over half of its content width to the
real square map, with a plain text column alongside it. Beacon uses one rounded
tonal state surface, an unboxed pending count and a sync footer. Report leaves its
availability hero unboxed and reserves a small neutral capsule for observed
coverage. Larger wide hosts reuse this composition; existing small/narrow contracts
remain accessibility fallbacks, not new visual variants. The illustration's
oversized overlapping/clipped map is adapted to a proportional Fit image so the
actual sky is preserved, without custom clipping or decorative map data.
Short layouts omit the full header. Refresh is a real vector with a 48×48 dp independent target;
tapping the remaining body opens the relevant existing app destination.

Android 12+ color resources resolve from the system dynamic neutral/accent/error
palettes. Tonal containers use their corresponding on-container color, with light
and dark resource variants and a static app palette on older devices. Widgets
follow the home-screen/system theme rather than forcing the Activity's AMOLED
preference. Map cells supplement color with shapes: usable circles, blocked
squares and subdued unsurveyed dots. Text labels and image descriptions remain
available; color alone does not convey the overall state.

No emoji, glyph icons, animations, nested mini dashboards or custom gestures are
used. PT, EN and ES have the same resource keys. Headlines take priority over
context; secondary text has deliberate line limits. Large backlogs use explicitly approximate
k/M/G units; exact counts remain available inside the app. Narrow headline sizing
uses a conservative text-width budget, without changing stored values. Large-font/launcher clipping
still requires actual device validation.

## Data and states

Obstruction uses the same existing Dishy map/status RPCs and bounded recent local
reading fallback. Both RPCs failing yields an unavailable state. A successful
status can still show a real percentage when the map is absent. Cached values
retain their original time and are not labelled as fresh. No map or percentage is
fabricated when missing. Zero is a real reading; null/non-finite/out-of-range values
are absent. Under 0.1% is labelled free, 0.1–<5% light obstruction and >=5% relevant;
these are presentation bands, not a service guarantee. An incomplete map takes
precedence over claiming a completely surveyed sky. Coverage counts finite valid
cells inside the circular footprint; negative/missing cells remain unsurveyed.

The real map is generated outside composition once per update, shared by every
responsive layout. It is square, displayed with Fit and capped at 384×384 pixels
(576 KiB ARGB), proportional to the largest 176 dp map and display density.
Invalid dimensions cannot cause division/modulo by zero or fabricated coverage.

Report reads the latest existing saved DailyReport JSON. It does not regenerate
reports or alter coverage/availability calculations. Empty reports show an
intentional open-reports state. Source records, statistics and DB remain unchanged.

Beacon reads the existing configured identity, LAN status and stored sync time.
Configuration missing, offline, storage warning, Dishy unavailable, recording
and online are distinct. Offline does not mean zero pending records. Never synced
does not acquire an invented timestamp. Refresh checks status; it does not import
history or send ACKs. Backlog/data flow remain owned by the existing sync system. The primary footer
says Synced only when the real pending count is zero and a sync timestamp exists;
otherwise it labels the stored last-sync time. Full localized date/time is retained
so an older synchronization cannot be mistaken for one that happened today.

The initial XML loading layout shows a system progress indicator with no fake zero.
No timer-based freshness or connection continuity is inferred between updates.

## Actions and updates

- Body: Obstruction opens DISH; Report opens REPORTS with the saved report end;
  Beacon opens BEACON.
- Refresh: updates only the selected installed widget through its existing callback.
- Updates remain event/manual driven: app foreground, report completion and Beacon
  state events. Existing call sites and legacy work cancellation are preserved.
- All providers retain updatePeriodMillis=0. No widget polling worker, permanent
  service or high-frequency update loop was added.

## Picker and preview strategy

Each provider now supplies a description, previewLayout and previewImage.
The XML previews use localized resources, dynamic colors and the primary 3×2
composition. PNGs provide a synthetic static light-theme fallback for older hosts;
they are illustrations, not captured personal/device data.

On API 35+, the official AppWidgetManager.setWidgetPreview API publishes
RemoteViews composed by the installed Glance 1.1.1 compose API using the **same
content functions** as real widgets. No nonexistent providePreview helper is used
and no Glance upgrade/alpha library is required. One canonical 300×208 dp preview
per provider is chosen rather than many RemoteViews sizes/bitmaps. Registration is
triggered by the existing foreground update hook, not a new scheduler.

The registration key includes preview revision, language, UI mode and tonal color.
Accepted previews are not resubmitted unchanged. Platform rate limiting or errors
leave XML/image fallback intact; retry waits at least an hour and a later foreground
event. The platform's quota may defer the third provider. No data collection, RPC,
history access, identifiers or continuous updates are used for previews.

OrbitWidgetSamples provides isolated synthetic cases for zero, trace, 5%, relevant,
partial, missing, offline and loading obstruction; healthy, backlog, offline and
never-synced Beacon; good, bad, partial and empty report. OrbitWidgetPreview accepts
these choices and a compose size for development inspection. It is not registered
and cannot become a separate installed widget. These samples never serve as real
acquisition fallbacks.

## Platform limits and physical validation

Glance translates into RemoteViews. Compose-only Canvas, animated shapes, arbitrary
gesture/zoom effects and continuous motion are inappropriate here. The map is a
bounded bitmap; inner personality uses a drawable shape. Generated-preview quota,
widget selection dimensions, host clipping and corner radius remain controlled by
Android/the launcher. No claim of physical validation is made from JVM tests or
static preview inspection.

Requires physical validation on Nothing Phone (1):

- Install/resize each existing provider across low, square, wide, tall and large
  sizes (including available 2×1, 2×2, 4×1, 4×2, 4×3 grids).
- Light/dark, very light/dark wallpapers, dynamic palettes, system font enlargement,
  TalkBack, metric readability, touch targets and rounding/clipping.
- Real zero, null, incomplete map, no map, unavailable Dishy, cached timestamp,
  Beacon backlog/offline/never synced and partial/empty report.
- Body destinations, refresh independence, report identity, app/report/Beacon event
  updates, picker descriptions, generated previews and fallback after rate limiting.

Automated coverage belongs to WidgetPresentationTest and widget resource contracts:
breakpoints, font-scale fallback, source states, missing/zero distinction, circular
coverage, invalid grids, bitmap bounds, report coverage, deep-link route identities,
provider/update/preview declarations and translation parity. They do not test pixels
or pretend to validate launcher rendering.

## Official references reviewed

- [Android widget overview](https://developer.android.com/design/ui/mobile/guides/widgets)
- [Sizing and bounds](https://developer.android.com/design/ui/mobile/guides/widgets/sizing)
- [Widget style and typography](https://developer.android.com/design/ui/mobile/guides/widgets/style)
- [Canonical layouts](https://developer.android.com/design/ui/mobile/guides/widgets/layouts)
- [Widget quality checklist](https://developer.android.com/docs/quality-guidelines/widget-quality)
- [Glance layout and SizeMode](https://developer.android.com/develop/ui/compose/glance/build-ui)
- [Glance theme and drawable shapes](https://developer.android.com/develop/ui/compose/glance/theme)
- [Glance creation/loading/metadata](https://developer.android.com/develop/ui/compose/glance/create-app-widget)
- [Generated previews](https://developer.android.com/develop/ui/views/appwidgets/previews)
- [Glance preview APIs/version history](https://developer.android.com/jetpack/androidx/releases/glance)
- [Official platform samples](https://github.com/android/platform-samples/tree/main/samples/user-interface/appwidgets)
- [Material 3 Expressive](https://m3.material.io/blog/building-with-m3-expressive)
- [Material typography hierarchy](https://m3.material.io/styles/typography/applying-type)
- [Android color roles/accessibility](https://developer.android.com/design/ui/mobile/guides/styles/color)

The canonical Figma tooling was referenced through the official guides; no Figma
project was opened and no Google widget was copied pixel for pixel. Material's site
requires client-side rendering for some content; the usable engineering decisions
are supported by Android's widget style, color and quality documentation.
