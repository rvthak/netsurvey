# NetSurvey — Specification

A personal Android app for benchmarking mobile-network signal, speed, latency,
and reliability across different areas of a home, visualised on a floor-plan map.

> Status: agreed design, pre-implementation. This document is the source of
> truth for *what* we are building and *why*. The build plan lives in
> [IMPLEMENTATION.md](IMPLEMENTATION.md).

---

## 1. Goals & non-goals

### Goals
- Measure, per spot in the home: **signal strength (RSRP)**, **latency**,
  **jitter**, **reliability (probe success %)**, and **download/upload speed**.
- Average repeated measurements per spot so numbers stabilise over time.
- Visualise results on an imported floor-plan image, one pin per spot, coloured
  by a configurable primary metric.
- Keep the data honest: never invent or over-claim values.

### Non-goals
- Not a Play Store product. **Personal sideload only.**
- No cloud sync, accounts, or telephony-grade certification.
- No interpolated heatmaps (they fabricate data between measured points).
- No reliance on GPS for indoor positioning.

---

## 2. Platform & distribution

| Decision | Choice |
|---|---|
| Platform | **Native Android** (only platform exposing true signal dBm) |
| Language / UI | **Kotlin + Jetpack Compose** |
| Persistence | **Room** (SQLite) |
| Concurrency | Kotlin coroutines / `Flow` |
| Distribution | **Personal sideload** (debug APK). No Play Store, no privacy policy. |
| minSdk | **29 (Android 10)** — required for `requestCellInfoUpdate()` and richer `getCellSignalStrengths()` |
| targetSdk | current stable |

### Permissions (runtime)
- `ACCESS_FINE_LOCATION` — **required by Android to read cell identity/signal**.
- `READ_PHONE_STATE` — telephony details.
- (No background-location; tests run only in the foreground.)

---

## 3. Why a browser can't do this

Recorded so the platform choice is never re-litigated: web browsers expose **no**
API for radio signal strength (dBm), cell identity, or radio technology. The
Network Information API only gives a coarse, spoofable `effectiveType` and an
*estimated* downlink. iOS also forbids signal-strength APIs. **Native Android is
the only option for the headline metric.**

---

## 4. The measurement

The user stands at a spot, the **screen is held awake (wakelock)**, and sampling
runs **only while the app is in the foreground** (the test aborts if the app is
backgrounded). The test runs for a user-set **duration** (seconds).

### 4.1 Inputs (set per measurement)
- **Type** — chosen implicitly by which pin/spot is being measured (see §6).
- **Duration** — seconds.
- **Data cap** — per-measurement ceiling for the speed burst. Prefilled default:
  *download 10 s or 100 MB; upload 5 s or 25 MB* (whichever first). Editable each
  run.
- **Notes** — optional free text (formerly "antenna orientation"; now a generic note).

### 4.2 Sampled metrics

| Metric | Source | Cadence | Stored |
|---|---|---|---|
| **RSRP (dBm)** — headline signal | `SignalStrength` / `CellSignalStrength*` | ~1 Hz | raw series + summary |
| RSRQ, SINR/SNR | same | ~1 Hz | raw series + summary |
| Radio type, band, carrier | `TelephonyManager` / `CellInfo` | ~1 Hz | per-sample |
| **Latency** | small timestamped round-trips to **Cloudflare** | ~2 Hz (every ~500 ms) | raw series + summary |
| **Jitter** | derived from latency series | — | summary |
| **Reliability (success %)** | fraction of probes succeeding within timeout | — | summary |
| **Download / Upload speed** | **one capped burst** vs Cloudflare endpoints | once per test | summary |
| **Cell / tower info** | `getAllCellInfo()` + `requestCellInfoUpdate()` | ~1 Hz | see §5 |

Notes:
- Latency uses small HTTP/TCP round-trips, **not ICMP** (Android can't ping
  without root). Failed probes (timeout) count against reliability.
- Speed is a single burst because throughput cannot be cheaply sampled — a
  throughput number is itself an average over a saturated interval.

---

## 5. Cell / tower tracking

Terminology mapped to Android's real hierarchy (we label data **"Cell"/"Tower,"
never "antenna"**, to avoid over-claiming):

- **Tower / site** → `eNodeB` (LTE) / `gNodeB` (5G), derived from the cell ID.
- **Cell / sector** → globally identified by **ECI** (LTE) / **NCI** (5G).
- A single physical sector can run several cells (bands / carrier aggregation).

### Captured per measurement
- `servingCells[]` — ordered list of **distinct serving cells** during the test
  (captures handovers): `{tech, globalId, derivedTowerId, pci, tac, earfcn,
  band, firstSeen, lastSeen, dwellMs}`. The serving cell is the one with
  `isRegistered() == true`.
- `distinctNeighborCount` + `neighborCells[]`:
  `{tech, pci, earfcn, globalId?, bestSignal}`. Aggregated as a set across the
  test, keyed by `tech + pci + earfcn`.
- `cellDataQuality` badge: `full` / `serving-only` / `pci-only` / `unavailable`.
- `nrIdentityUnavailable` flag for the 5G-NSA case.

### Reliability matrix (known limitations)

| Mode | Serving cell ID | Neighbor count |
|---|---|---|
| 4G LTE | Reliable (ECI) | Device-dependent |
| 5G SA | Reliable (NCI) | Device-dependent |
| **5G NSA** | **Anchor is LTE; NR cell ID often unavailable** | Usually poor |

- Neighbor cells are **measured candidates**, not guaranteed-usable
  alternatives; labelled "distinct neighbor cells observed."
- **5G NSA gotcha:** in non-standalone 5G the phone is registered on an LTE
  anchor while NR is a secondary carrier, so the NR cell identity is frequently
  hidden by the OS. We still record NR *signal* and set `nrIdentityUnavailable`
  so a missing tower ID reads as "OS hid it," not "no 5G."

### Where it surfaces
- **Measurement detail screen only** ("Cells" section: serving-cell timeline +
  neighbor count + data-quality badge).
- **Never** drives pin colour or the primary metric — too device-dependent to
  rank areas by.

---

## 6. Statistics (the honesty rules)

### Per-measurement summary
- Headline value of each sampled series = **median**, with **p10 / p90** shown as
  spread. The median is immune to momentary spikes **and** sidesteps the fact
  that dBm is logarithmic (so no special log/linear averaging, no filter knobs).
- Speed = the single burst's stable result.
- Reliability = probe success %; jitter = variation of the latency series.

### Per-type rollup
- A **measurement type** = one pinned spot. Its overall stats =
  **equal-weight average of its measurements' summaries** — every measurement is
  one vote regardless of duration ("the average of the measurements").

### Raw data
- **Raw per-sample series are stored** alongside summaries (a 60 s test is only
  ~60 signal rows + ~120 probe rows — trivial). Enables a signal-over-time chart
  per measurement and future re-analysis.

---

## 7. Data model (logical)

```
Plan
  id, name, imageUri (PNG), createdAt

MeasurementType            // == a pinned spot on a plan
  id, planId, name, pinX, pinY, createdAt
  (aggregate stats are computed from its Measurements, not stored)

Measurement
  id, typeId, startedAt, durationSec, dataCapConfig, notes
  // summary fields:
  rsrpMedian, rsrpP10, rsrpP90, rsrqMedian, sinrMedian,
  latencyMedian, latencyP10, latencyP90, jitter, successPct,
  downloadMbps, uploadMbps,
  cellDataQuality, nrIdentityUnavailable
  servingCells[]  (embedded / related)
  neighborCells[] (embedded / related), distinctNeighborCount

Sample                     // raw time series
  id, measurementId, tOffsetMs, kind {signal|probe},
  rsrp, rsrq, sinr, radioType, band, carrier,   // for signal samples
  latencyMs, ok                                  // for probe samples

Settings (single row / DataStore)
  primaryMetric (default RSRP),
  thresholds (per-metric green→red bands, editable),
  defaultDataCap
```

---

## 8. UI — map-centric

- **Home screen *is* the floor plan.** **Multiple named plans** (e.g. one per
  floor), each with its own PNG + pins, switchable via a selector.
- **Long-press an empty spot** → name it → creates a measurement type pinned
  there → set duration / data cap / notes → run.
- **Tap a pin** → type detail: aggregate header + list of individual
  measurements; each measurement opens its own detail (with the
  signal-over-time chart and the Cells section).
- **Pins display the value of a configurable "primary metric"** (default RSRP)
  and are **coloured** by it on a green→red scale. Changing the primary metric in
  Settings recolours/relabels every pin.
- **Colour bands = fixed industry thresholds, editable in Settings**
  (e.g. RSRP ≥ −80 great → ≤ −110 poor; latency < 30 ms great → > 100 ms poor;
  success % 100 → 95).
- **Settings:** primary metric, colour thresholds, default data cap, manage plans.
- **First run:** must add a plan (pick a PNG + name it) before measuring.

---

## 9. Data longevity

- Local Room DB is the source of truth.
- **Export / Import** a JSON bundle (including plan PNGs) — survives uninstall /
  new phone. Matches FloorSketch's save/load ethos. No cloud required.

---

## 10. Baked-in defaults

1. Carrier / network type / band / cell ID recorded automatically per measurement.
2. Dual SIM: uses the **default data SIM** only.
3. Radio switch mid-test: record the dominant radio type; flag the measurement if
   it changed (mixed-tech RSRP is noted, not hidden).
4. Sampling cadence: signal ~1 Hz, latency probes ~2 Hz.
5. Test runs foreground-only with a wakelock; aborts if backgrounded.

---

## 11. Relationship to FloorSketch

NetSurvey is a **separate Android app** in its own directory (`~/netsurvey`, a
sibling of `floorsketch`) — the two are independent projects. The only link is a
workflow synergy: **FloorSketch exports the home as a PNG**, which becomes
NetSurvey's floor-plan background image.
