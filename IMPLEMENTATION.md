# NetSurvey — Implementation Plan

Build order for the app specified in [SPEC.md](SPEC.md). Phases are
**dependency-ordered**: each builds on the last and ends in something runnable
and verifiable on a real device. An emulator cannot produce real radio data, so
**every signal/cell/speed phase must be verified on a physical phone with a SIM.**

Legend: 🧪 = how to verify the phase is done.

---

## Phase 0 — Project scaffold & tooling

**Goal:** an empty Compose app that installs and launches.

- Create a standard Android Studio project: Kotlin, Jetpack Compose, single
  `:app` module, package e.g. `com.rvthak.netsurvey`.
- Gradle (Kotlin DSL): Compose BOM, Material 3, Navigation-Compose, Room
  (+ KSP), DataStore-Preferences, Coroutines, OkHttp (speed/latency probes),
  a charting lib (e.g. Vico) for the time-series chart.
- `minSdk = 29`, `targetSdk = current`.
- Set up a debug signing config and a one-command install
  (`./gradlew installDebug`).
- App theme (dark, mirroring FloorSketch's palette is a nice touch but optional).

🧪 App installs via `installDebug` and shows a placeholder screen.

---

## Phase 1 — Permissions & telephony probe (spike)

**Goal:** prove the device exposes the data the whole app depends on. Do this
*before* building real features — it de-risks everything.

- Runtime permission flow for `ACCESS_FINE_LOCATION` + `READ_PHONE_STATE`.
- A throwaway debug screen that, on a button press, reads and dumps to the UI:
  - `SignalStrength` → RSRP/RSRQ/SINR for the serving cell.
  - `getAllCellInfo()` (+ `requestCellInfoUpdate()`) → serving cell identity
    (ECI/NCI, PCI, TAC, EARFCN/NRARFCN, band) and neighbor list.
  - Current radio type, carrier, NSA vs SA.
- Note which fields come back `unavailable` on **your actual phone** — this
  calibrates the `cellDataQuality` logic and confirms the 5G-NSA behaviour.

🧪 On your phone: RSRP shows a plausible dBm value; serving cell ID is present;
record what neighbor info (if any) your device reports. **This phase decides how
much of §5 is real on your hardware.**

---

## Phase 2 — Data layer (Room + Settings)

**Goal:** persistence for everything in SPEC §7, with no UI beyond debug.

- Room entities & DAOs: `Plan`, `MeasurementType`, `Measurement`, `Sample`,
  embedded `servingCells[]` / `neighborCells[]` (own tables with FKs).
- Type converters for enums / small lists.
- Settings via DataStore: `primaryMetric`, threshold bands (with industry
  defaults), `defaultDataCap`.
- Repository layer exposing `Flow`s for plans, types, measurements.
- Aggregation functions (pure, unit-tested): per-measurement median + p10/p90;
  per-type equal-weight average.

🧪 Unit tests: insert fake measurements → assert medians, p10/p90, and the
equal-weight type rollup are correct. DB survives app restart.

---

## Phase 3 — Sampling engine (the core)

**Goal:** run a real timed measurement and persist a complete `Measurement`.
No map UI yet — drive it from a debug screen.

- A foreground-scoped coroutine job that, for `durationSec`:
  - acquires a **wakelock**, aborts cleanly if the app is backgrounded;
  - polls signal + cell info at ~1 Hz → `Sample(kind=signal)` rows;
  - fires latency probes to Cloudflare at ~2 Hz → `Sample(kind=probe)` rows
    (record `latencyMs` / `ok`);
  - tracks distinct serving cells (with dwell) and the neighbor set;
  - computes `cellDataQuality` / `nrIdentityUnavailable`.
- **Speed burst** (once): download then upload against Cloudflare endpoints,
  honouring the data/time cap; record `downloadMbps` / `uploadMbps`.
- On completion: compute the summary (Phase 2 functions) and persist the whole
  `Measurement` + samples.
- Live progress state (elapsed, current RSRP, probe success so far).

🧪 On your phone: run a 30 s test from the debug screen → a `Measurement` with
sensible RSRP median, latency, jitter, success %, real Mbps, and serving-cell
history lands in the DB. Cross-check RSRP against your phone's field-test screen.

---

## Phase 4 — Plans & the map canvas

**Goal:** the map-centric shell (SPEC §8), pins not yet wired to measurements.

- First-run / empty state → add a plan (pick PNG from storage + name it);
  copy the image into app storage, store `imageUri`.
- Plan selector for multiple plans.
- Pan/zoom image canvas (reuse the interaction model conceptually from
  FloorSketch). Map image-space ↔ screen-space so pins stick to the plan.
- Render `MeasurementType` pins at `(pinX, pinY)`.
- **Long-press empty space** → name dialog → create a `MeasurementType` pinned
  there. **Tap a pin** → (stub) detail.

🧪 Import a FloorSketch PNG, drop several named pins, pan/zoom — pins stay glued
to the right spots; plans and pins persist across restart.

---

## Phase 5 — Wire measurement flow into the map

**Goal:** the real loop from SPEC §8 end to end.

- Long-press → create type → **measurement setup sheet** (duration, data cap
  prefilled from settings, notes) → run Phase 3 engine with a live progress UI →
  save under that type.
- Tap pin → **type detail**: aggregate header (equal-weight) + list of
  individual measurements; "New measurement" re-tests the same type.
- **Measurement detail screen:** summary cards, the **signal-over-time chart**
  (from raw samples), and the **Cells section** (serving-cell timeline, neighbor
  count, data-quality badge).

🧪 Full path on phone: long-press → run → see it averaged into the type; run a
second time → both appear in the list and the aggregate updates.

---

## Phase 6 — Primary metric, colouring & thresholds

**Goal:** the map becomes a readable comparison view.

- Pins **display the value** of the configured primary metric and are **coloured**
  green→red by it using the editable threshold bands.
- Settings screen: pick primary metric, edit threshold bands, edit default data
  cap, manage plans (rename/delete).
- Switching the primary metric recolours/relabels every pin live.

🧪 With ≥2 measured spots, switch primary metric in Settings → all pins relabel
and recolour; tweak a threshold → colours shift accordingly.

---

## Phase 7 — Export / import & polish

**Goal:** data longevity (SPEC §9) and rough edges.

- **Export** a JSON bundle (plans, types, measurements, samples, cells) +
  embedded/zipped plan PNGs to a user-chosen location.
- **Import** restores from a bundle.
- Edge cases: no permission, airplane mode / no service mid-test, test aborted by
  backgrounding, deleting a plan/type/measurement, very fast (5G) links hitting
  the time cap before the data cap.
- Empty states, error toasts, a brief "what the metrics mean" help blurb.

🧪 Export → uninstall → reinstall → import → all data and pins return intact.

---

## Cross-cutting notes

- **Verify on hardware, every radio phase.** Emulators fake telephony.
- **Watch mobile-data cost** while testing the speed engine — the cap is what
  protects you; test it deliberately.
- **5G NSA** is the main unknown; Phase 1 tells you exactly how your phone
  behaves so later phases set the quality flags honestly.
- Keep aggregation logic pure and unit-tested (Phase 2) — it's the part most
  likely to be silently wrong.

---

## Suggested milestones

1. **Spike done** (Phases 0–1): we know the data is real on your phone.
2. **Core works** (Phases 2–3): real measurements persist correctly.
3. **App is usable** (Phases 4–6): the full map-centric loop with colouring.
4. **Shippable to yourself** (Phase 7): backup/restore + polish.
