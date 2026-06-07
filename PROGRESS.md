# NetSurvey — Build Progress / Session Handoff

Snapshot of where the implementation stands against [IMPLEMENTATION.md](IMPLEMENTATION.md).
Source of truth for *what/why* is [SPEC.md](SPEC.md); this file tracks *what's been
built and what's left*. Read this first when resuming in a new session.

**Last updated:** 2026-06-07 (Phase 7 code complete — all phases built)

---

## Status at a glance

| Phase | Title | State |
|-------|-------|-------|
| 0 | Project scaffold & tooling | ✅ Done — builds, installs |
| 1 | Permissions & telephony probe (spike) | ✅ Done (code) — **needs phone verify** |
| 2 | Data layer (Room + Settings) | ✅ Done — unit tests pass |
| 3 | Sampling engine (the core) | ✅ Done (code) — **needs phone verify** |
| 4 | Plans & the map canvas | ✅ Done (code) — **needs phone verify** |
| 5 | Wire measurement flow into the map | ✅ Done (code) — **needs phone verify** |
| 6 | Primary metric, colouring & thresholds | ✅ Done (code) — **needs phone verify** |
| 7 | Export / import & polish | ✅ Done (code) — **needs phone verify** |

**All seven phases are code-complete.** What's left is the cross-cutting
**hardware-verify pass** (radio/speed/colour paths on a real phone with a SIM) and
the on-device edge-case checks listed under Phase 7 below.

Build verification: last `:app:assembleDebug` was **BUILD SUCCESSFUL** (after the
Phase-7 export/import + polish work). All unit tests pass: 12 `AggregationTest` +
11 `MetricScaleTest`.

> **Hardware not yet tested.** Phases 1 & 3 are code-complete but every radio/speed
> path must be confirmed on a real phone with a SIM (emulators fake telephony).
> The user is setting up a phone for this.

---

## Project facts

- Native Android, **Kotlin 2.2.0** + Jetpack Compose (Material 3), single `:app`
  module, package **`com.rvthak.netsurvey`**.
- Gradle **8.13** (wrapper bootstrapped; no system gradle/Android Studio CLI on
  this machine — wrapper was fetched to `/tmp` during setup), AGP **8.13.0**,
  KSP **2.2.0-2.0.2**, version catalog at `gradle/libs.versions.toml`.
- `minSdk 29`, `compileSdk/targetSdk 36`, Java 17.
- `local.properties` → `sdk.dir=/Users/rvthak/Library/Android/sdk` (gitignored).
- Stable `app/debug.keystore` (store/key password `android`, alias
  `androiddebugkey`) so reinstalls don't break — keep it.
- Key libs: Room 2.7.2, DataStore-Preferences 1.1.7, kotlinx-serialization 1.8.1,
  coroutines 1.10.2, OkHttp 4.12.0, Navigation-Compose 2.9.0, **Vico 2.1.3**
  (charts, used in Phase 5), Compose BOM 2025.06.00.

### Build commands

```bash
# from /Users/rvthak/netsurvey
./gradlew :app:assembleDebug      # compile check
./gradlew :app:testDebugUnitTest  # run unit tests (Aggregation)
./gradlew installDebug            # install on a connected device
```

Background-build pattern used in this project (CLI can be slow):
`Bash run_in_background` then poll the output file with
`until grep -qE "BUILD SUCCESSFUL|BUILD FAILED|FAILURE:" out.log`.

---

## What each completed phase delivered (key files)

### Phase 5 — measurement flow wired into the map
- `ui/run/MeasurementRunScreen.kt` (route `run/{typeId}`) — setup form (duration,
  data cap **prefilled from `SettingsRepository.defaultDataCap`** once it loads,
  notes) → drives the Phase-3 `MeasurementEngine.run(...)` with live progress →
  `repo.saveRun(typeId, cap, notes, run)` → pops back. Permission-gated
  (`rememberPermissionController`); **aborts on `ON_STOP`** (lifecycle observer),
  nothing persisted on abort.
- `ui/type/TypeDetailScreen.kt` (route `type/{typeId}`, replaces the Phase-4 stub)
  — equal-weight aggregate header via `TypeAggregate.from(summaries)` over all six
  `Metric`s + measurement list (tap → detail) + "New measurement" FAB.
- `ui/measurement/MeasurementDetailScreen.kt` (route `measurement/{measurementId}`)
  — summary cards (signal / latency+reliability / speed, with p10/p90), the
  **signal-over-time chart (Vico 2.1.3)** from raw `SIGNAL` samples
  (`CartesianChartModelProducer` + `lineSeries { series(tSec, rsrp) }`,
  `CartesianChartHost`), and the **Cells section** (data-quality badge,
  `nrIdentityUnavailable` note, neighbour count, ordered serving-cell rows).
- `ui/common/Format.kt` — shared display formatting (`metric`, `number`,
  `timestamp`, `duration`); null → "—", never a fake 0.
- `ui/NetSurveyApp.kt` — added `run/{typeId}` + `measurement/{measurementId}`
  routes; type/measurement nav wired.
- **Convention note:** the three detail screens read Room flows directly in-composable
  (`remember { repo.observeX() }.collectAsStateWithLifecycle()`) rather than via a
  ViewModel — they're read-mostly; `MapViewModel` stays the one stateful VM.

> Code compiles & unit tests green. **Phone-verify:** long-press a spot → run →
> it averages into the type; run again → both rows + updated aggregate; open a
> measurement → chart + cells render; cross-check RSRP vs the phone's field-test.

### Phase 6 — primary metric, colouring & thresholds
- `stats/MetricScale.kt` — **pure, Android-free** `MetricScale.severity(value, band)`
  → 0..1 (0 = "great" end, 1 = "poor" end). One formula serves every metric because
  the band's orientation (`greatAt` vs `poorAt`) already encodes higher/lower-is-better
  (RSRP's great end is the larger number, latency's the smaller — both map to 0).
  Clamps past either end; `null` value → `null` (keeps "not measured" ≠ "measured & bad").
  Tested by `stats/MetricScaleTest.kt` (11 tests, green).
- `ui/map/MetricColor.kt` — `metricColor(value, band): Color?` interpolates the
  five green→red theme stops (`MetricGreat…MetricBad`) by the severity fraction;
  `null` → caller falls back to the neutral pin colour. Only the lerp lives here;
  the math is the unit-tested `MetricScale`.
- `ui/map/MapViewModel.kt` — `PinUi` gains `valueLabel: String?` + `color: Color?`.
  The `pins` flow now `combine`s `settings` as a third input, so switching the
  primary metric or editing a band **recolours/relabels every pin live**. Per-type
  value = `TypeAggregate.from(...).value(primaryMetric)`; both fields stay `null`
  until a spot has a measured value for the current metric.
- `ui/map/MapCanvas.kt` — pin dot drawn with `pin.color ?: pinColor`; label shows
  `"name  value"` when measured, else just the name.
- `ui/settings/SettingsViewModel.kt` + `ui/settings/SettingsScreen.kt` — replaces the
  `settings` stub. Sections: **primary metric** (FilterChips → `setPrimaryMetric`),
  **colour thresholds** (per-metric Great/Poor `DecimalField`s → `setThreshold`),
  **default data cap** (four `IntField`s → `setDefaultDataCap`), **plans** (rename
  dialog → `repo.renamePlan`; delete with a cascade-warning confirm → `repo.deletePlan`).
  Edits write straight through to DataStore/Room. Numeric fields self-buffer
  (commit-on-valid-parse, resync only when upstream differs) so committing your own
  edit never resets the cursor; threshold fields use the phone keypad so the minus
  sign (RSRP/dBm) is reachable.
- `ui/NetSurveyApp.kt` — `settings` route now hosts `SettingsScreen`; the old
  `StubScreen` helper removed.

> Code compiles & unit tests green (12 `AggregationTest` + 11 `MetricScaleTest`).
> **Phone-verify:** with ≥2 measured spots, switch the primary metric in Settings →
> every pin relabels + recolours; tweak a threshold → colours shift; rename/delete a
> plan from Settings and confirm the map reflects it.

### Phase 7 — export / import & polish
- `data/backup/BackupModels.kt` — `@Serializable` **hierarchical** bundle:
  `BackupBundle(version, exportedAt, settings, plans[])` → `PlanBackup` (name,
  createdAt, `imageFile`, types[]) → `TypeBackup` → `MeasurementBackup`
  (summary + dataCap + samples/serving/neighbor). Hierarchy means **no DB ids are
  carried or remapped** — import recreates the tree and lets Room assign fresh ids.
  `BACKUP_VERSION = 1`, manifest entry name `manifest.json`. `ImportSummary` for an
  honest result message.
- Made the leaf types serializable: `@Serializable` on `MeasurementSummary`,
  `SampleEntity`, `ServingCellEntity`, `NeighborCellEntity`, and the enums
  `CellTech` / `CellDataQuality` / `SampleKind`. (Room is unaffected by the
  annotation; the entities are reused directly as bundle leaves, ids reset on import.)
- `data/backup/BackupManager.kt` — zip I/O over a SAF `Uri`. **Export:** gathers
  plans/types/details, pairs each plan with its PNG bytes (skips plans whose image
  has gone missing, so every `imageFile` in the manifest is present), writes
  `manifest.json` + `plans/N.png` entries. **Import:** reads all entries into memory
  (backups are small), parses + version-checks the manifest, then **replaces** —
  `repo.clearAllData()` (cascade wipe) + `settingsRepo.replaceAll(...)` + rebuild.
  Both wrapped in `Result` so the UI reports a message instead of crashing.
- `data/NetSurveyRepository.kt` — added export gather (`snapshotPlans/Types/Details`,
  `readPlanImageBytes`) and import restore (`clearAllData`, `importPlan/Type/Measurement`,
  leaf ids reset to 0 before `insertComplete`). `PlanDao.deleteAll()` added.
- `ui/settings/SettingsViewModel.kt` — `exportTo(uri)` / `importFrom(uri)` (off the
  main thread via `BackupManager`), a `busy` `StateFlow`, and a one-shot `messages`
  `SharedFlow` for snackbars; `suggestedBackupName()` → `netsurvey-backup-<stamp>.zip`.
- `ui/settings/SettingsScreen.kt` — **Backup** section (Export via
  `CreateDocument("application/zip")`, Import via `OpenDocument` + a "this replaces
  everything" confirm dialog, busy spinner, snackbar results) and a **"What the
  metrics mean"** help section (the SPEC §6 honesty rules + the §5 cells caveat).
- Other §1/§4 honesty/edge behaviour was already built earlier and is unchanged:
  permission-gating + abort-on-background in the run flow (Phase 5), failed probes →
  `null` (count against reliability, never a fake 0), `cellDataQuality` /
  `nrIdentityUnavailable` flags (Phases 1/3). These are **on-device verification
  items**, listed below.

> Code compiles & unit tests green. **Phone-verify:** export → uninstall → reinstall
> → import → all plans, pins, measurements, samples, and cells return intact.

### Phase 0 — scaffold
`gradle/libs.versions.toml`, `app/build.gradle.kts` (5 plugins: android-application,
kotlin-android, kotlin-compose, kotlin-serialization, ksp), `AndroidManifest.xml`
(perms: ACCESS_FINE_LOCATION, READ_PHONE_STATE, INTERNET, ACCESS_NETWORK_STATE,
WAKE_LOCK; telephony feature required), res/ (dark theme `Theme.NetSurvey`, app
icon), `ui/theme/{Color,Theme,Type}.kt` (dark-only palette, green→red metric scale),
`MainActivity.kt`.

### Phase 1 — telephony spike
- `telephony/RadioModels.kt` — enums `CellTech`, `CellDataQuality {FULL,
  SERVING_ONLY, PCI_ONLY, UNAVAILABLE}`; data classes `SignalReading`, `ServingCell`,
  `NeighborCell`, `RadioSnapshot` (all numeric fields **nullable** for honesty;
  UNAVAILABLE sentinels → null).
- `telephony/TelephonyReader.kt` — `suspend fun snapshot(): RadioSnapshot`. Uses
  `requestCellInfoUpdate` (2s timeout) → fallback `allCellInfo`. 5G-NSA heuristic:
  `nsaActive = serving.tech==LTE && nrSignal.rsrp!=null`; `nrIdentityUnavailable =
  nsaActive`. LTE eNodeB = `ci shr 8`; NR gNB id left null (operator split — kept
  honest).
- `ui/Permissions.kt` — `rememberPermissionController()`, `hasAllPermissions()`.
- `ui/debug/ProbeScreen.kt` — monospace dump of a `RadioSnapshot`.

### Phase 2 — data layer
- `model/` — `Metric` (RSRP/LATENCY/JITTER/SUCCESS/DOWNLOAD/UPLOAD w/ unit &
  higherIsBetter), `DataCapConfig` (down 10s/100MB, up 5s/25MB), `MeasurementSummary`
  (all stats nullable + quality flags), `SampleKind {SIGNAL,PROBE}`, `AppSettings`
  (`@Serializable`; primaryMetricKey, thresholds map, defaultDataCap) with
  `defaultThresholds()`.
- `stats/Aggregation.kt` — `object Stats { percentile (type-7), median, jitter
  (mean abs consecutive diff), equalWeightAverage }`; `TypeAggregate.from(summaries)`
  (equal-weight rollup). Tested by `stats/AggregationTest.kt` (12 tests, green).
- `data/db/` — `Entities.kt` (Plan, MeasurementType{pinX,pinY}, Measurement w/
  `@Embedded` dataCap+summary, Sample, ServingCell, NeighborCell; FK CASCADE),
  `Converters.kt`, `Relations.kt` (`MeasurementWithDetails`), `Daos.kt`
  (`MeasurementDao.insertComplete` @Transaction; observe* flows),
  `NetSurveyDatabase.kt` (v1, singleton `get(context)`).
- `data/SettingsRepository.kt` — DataStore, single JSON blob key `app_settings`.
- `data/NetSurveyRepository.kt` — wraps DAOs; plan PNGs stored at
  `filesDir/plans/<uuid>.png`; `createPlan(name,Uri)`, `insertPlanWithImageFile`
  (import), rename/delete plan & type, `createType(planId,name,pinX,pinY)`,
  observe*, `saveRun(...)`, `ensureDebugType()`.

### Phase 4 — plans & the map canvas
- `ui/map/MapViewModel.kt` — `AndroidViewModel`; `StateFlow`s `plans` (real plans
  only — hides the `__debug__` empty-image placeholder), `selectedPlan`
  (defaults to first), `pins` (`combine(types, measurementsForPlan)` → `PinUi`
  with per-type `measurementCount`). `selectPlan`, `addPlan(name,Uri)` (selects
  the new plan), `addType(name,xFrac,yFrac)`.
- `ui/map/MapCanvas.kt` — pan/zoom `Canvas`. Transform `screen = imgPx*effScale +
  offset`, `effScale = baseScale*userScale`, `baseScale = min(W/imgW,H/imgH)`;
  centroid-anchored pinch-zoom (clamp 0.5–8×), centres-to-fit on first layout,
  resets on plan switch (state keyed on `imagePath`). Pins drawn in the canvas
  (ring + dot + label w/ count) via `rememberTextMeasurer`/`drawText`. Tap →
  nearest pin within 24dp slop → `onPinTap`; long-press → `screenToFrac` (bounded
  0..1) → `onLongPressEmpty`. PNG decoded off-thread (`produceState` +
  `BitmapFactory.decodeFile`).
- `ui/map/MapScreen.kt` — `TopAppBar` with `PlanSelector` dropdown +
  add-plan (`GetContent("image/*")`) + overflow (Settings/Debug). Empty state when
  no plans. `NameDialog` for naming plans/spots. Bottom hint surface.
- `ui/NetSurveyApp.kt` — `NavHost` (`map`, `type/{typeId}`, `settings`, `debug`).
  `type`/`settings` are stubs (filled in Phases 5/6); `debug` wraps `DebugHost`
  with a back affordance so the Phase 1/3 spike stays reachable.
- `MainActivity` now hosts `NetSurveyApp()` instead of `DebugHost`.

> Code compiles & unit tests green. **Phone-verify:** import a PNG, drop named
> pins, pan/zoom (pins stay glued), switch/restart → plans + pins persist.

### Phase 3 — sampling engine
- `engine/Probes.kt` — Cloudflare probes (`speed.cloudflare.com`): `latencyProbe()`
  (GET `__down?bytes=0`), `downloadBurst(cap)` (uses `resp.use{}`, byte+time cap →
  Mbps), `uploadBurst(cap)` (streaming `CappedUploadBody`, self-timed). HTTP latency,
  not ICMP (no root). Timeout → null = counts against reliability.
- `engine/MeasurementEngine.kt` — `enum RunPhase {SAMPLING,DOWNLOAD,UPLOAD,DONE}`,
  `RunProgress`, `RunResult`. `suspend run(durationSec, dataCap, onProgress)`:
  acquires PARTIAL_WAKE_LOCK; signalJob @1Hz for whole run + latencyJob @2Hz during
  sampling window only (avoids bufferbloat skewing jitter); then download+upload
  bursts; builds samples/cells/summary in a synchronized `Accumulator`. Clean
  cancellation (nothing persisted; wakelock released in `finally`).
- `ui/debug/RunDebugScreen.kt` — drives the engine, live progress, saves under
  `ensureDebugType()`; aborts on `ON_STOP` (backgrounding).
- `ui/debug/DebugHost.kt` — TabRow "Probe" / "Run". Hosted by `MainActivity`.

---

## ▶ NEXT: on-device verification (all phases are built)

Every phase is code-complete and the app builds + unit tests pass. The remaining
work is **confirming it on a real phone with a SIM** — emulators fake telephony, so
nothing radio/speed/colour-related has been exercised for real yet. Suggested order
(each step is independent; do the radio ones where you have signal to compare):

1. **Install & permissions** — `./gradlew installDebug`; launch; grant
   `ACCESS_FINE_LOCATION` + `READ_PHONE_STATE`. Deny once to confirm the run flow
   is gated, not crashing.
2. **Telephony spike (Phase 1)** — overflow → Debug tools → Probe: RSRP shows a
   plausible dBm, serving-cell id present; note which fields read "unavailable"
   (calibrates `cellDataQuality` / the 5G-NSA `nrIdentityUnavailable` flag).
3. **A real run (Phases 3/5)** — import a floor-plan PNG, long-press → name a spot →
   run a ~30 s test. Watch mobile-data cost: the data cap is the guard, test it
   deliberately. Expect sensible RSRP median, latency, jitter, success %, real Mbps,
   serving-cell history. **Cross-check RSRP against the phone's field-test screen.**
4. **Aggregation & detail** — run a second time on the same spot → both rows + the
   equal-weight aggregate update; open a measurement → signal-over-time chart + the
   Cells section render.
5. **Colouring (Phase 6)** — with ≥2 measured spots, switch the primary metric in
   Settings → every pin relabels + recolours; tweak a threshold → colours shift.
6. **Backup round-trip (Phase 7)** — Settings → Export to a `.zip`; uninstall;
   reinstall; Import → all plans, pins, measurements, samples, and cells return.

**Edge cases to exercise while testing** (SPEC §10 / Phase 7 goals):
- No permission; airplane mode / no service mid-test (probes → `null`, success%
  drops, speeds blank — should read honestly, not as 0).
- Test aborted by backgrounding (nothing persisted).
- Deleting a plan/type/measurement (cascades).
- A very fast (5G) link hitting the **time** cap before the **data** cap.
- Import a non-backup file → friendly "not a NetSurvey backup" snackbar, no crash.

If anything reads wrong on hardware, the likely-culprit notes per phase are in the
"What each completed phase delivered" sections above.

---

## Cross-cutting reminders

- **Verify on hardware for every radio/speed phase** (1, 3, and the run path in 5).
- **Watch mobile-data cost** when testing speed — the data cap is the guard; test
  it deliberately.
- **5G NSA** is the main unknown — Phase 1's on-device dump calibrates the
  `cellDataQuality` / `nrIdentityUnavailable` flags honestly.
- Keep aggregation pure & unit-tested — it's the most likely thing to be silently
  wrong.
