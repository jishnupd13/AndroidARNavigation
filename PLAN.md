# Native ARCore Android App — Indoor Positioning & AR Navigation Plan

Source: *Integrated Visitor App* requirements (MoCIT / Visit Qatar), 26 pp.
Existing project: Kotlin + Jetpack Compose skeleton (`com.app.androidnativearcore`, minSdk 27, targetSdk 37, AGP 9.4.1, Kotlin 2.2.10) — no AR code yet.

## 1. Scope: what the PDF means for this app

The PDF covers four modules. For an **ARCore indoor-position-tracking app**, only these apply:

| Module | In scope for this app | Notes |
|---|---|---|
| **1. AR Navigation & Wayfinding** | **Yes (core)** | Indoor/outdoor positioning, AR arrows/paths/labels, accessibility routing, rerouting, offline maps, feedback |
| **2. Gamified Tours** | **Yes (phase 2)** | Clue-driven AR hunts, marker scanning, geofence triggers, rewards |
| 3. Digital Wallet | Integration stub only | Pearl Points, Camino blockchain, ticketing are separate/vendor-owned. Expose a `RewardsGateway` interface; do not build the ledger |
| 4. Itinerary Builder | Integration stub only | App consumes saved stops + deep links from the host Visit Qatar app |

The PDF says the AR module is the **shared backbone exposed via SDK/APIs** to other entities, so structure it as an **Android library module** consumed by a thin demo app.

### Key requirements extracted (Modules 1 & 2, visitor + partner side)
- AR overlays: directional arrows, animated path, floating labelled POIs, distance indicators, contextual tips, "You have arrived" animation.
- Mini-map preview of the route; start-point confirmation.
- Accessibility routing (wheelchair/stroller/step-free) with live recalculation; accessibility rendering modes (large indicators, high contrast).
- **Sub-meter indoor positioning, low-latency** (BLE / UWB / Wi-Fi RTT or equivalent) plus markers.
- Seamless indoor↔outdoor transition with consistent overlays.
- Real-time path recalculation on deviation or closure; live route updates.
- **Offline AR map caching**; disconnected mode with auto-resume and queued sync (gamification).
- Proximity push alerts with app closed → deep link opens map and readies AR; seat-specific guidance for ticketed events.
- Geofence triggers; scan AR markers/objects to unlock content; multi-stage clues with progressive difficulty; leaderboard; anti-cheat (GPS-spoof detection, completion verification).
- Multilingual (incl. Arabic/RTL), on-route feedback ("route blocked", "marker missing").
- iOS **and** Android parity (this plan is Android only); adaptive performance across device tiers; 99.9% backend SLA; analytics (path completion, accessibility usage, session duration).
- Content (POIs, markers, challenges) must be **admin-configurable without app rebuild** (the "dynamic AR builder").

## 2. Key technical decision: how to get indoor position

ARCore alone gives **relative** 6-DoF motion tracking (VIO), which drifts, and no absolute indoor position. GPS is unusable indoors. The sub-meter requirement therefore needs a **fusion** approach:

| Layer | Technique | Role |
|---|---|---|
| Relative pose | ARCore VIO (`Session`, `Frame.camera.pose`) | Smooth, low-latency motion between fixes |
| Absolute anchor #1 | **Augmented Images** (printed markers at venue entrances/corridors) and/or QR | Re-localise & drift-correct; matches the PDF's "AR markers" |
| Absolute anchor #2 | **BLE beacons** (RSSI trilateration/fingerprint) | Coarse room/zone fix (1–3 m), works without looking at a marker |
| Absolute anchor #3 | **Wi-Fi RTT** (`WifiRttManager`, Android 9+, needs RTT-capable APs) | ~1–2 m, optional |
| Absolute anchor #4 | **ARCore Geospatial API (VPS)** | Outdoor/street-level and some indoor-adjacent areas; **not indoor-reliable**, outdoor only here |
| Fusion | Extended Kalman / particle filter over a **venue floor-plan graph** | Combine VIO deltas + absolute fixes, snap to walkable graph |

Recommendation: **Phase 1 = markers + ARCore VIO + floor-plan graph** (cheapest, accurate, needs no venue hardware beyond posters). Add BLE next. UWB/Wi-Fi RTT only if the venue provides hardware (the PDF assigns beacon/marker install to venue operators).

**Cloud Anchors** are an option for venue-specific persistent anchors, but ARCore Cloud Anchors persist up to 365 days and need hosting passes; weigh against marker-based relocalisation. Validate in the spike.

## 3. Architecture

Kotlin, single-activity, Compose UI, Clean Architecture + MVVM/UDF, modular.

```
:app                     demo host (navigation, DI, theme, deep links)
:ar-navigation-sdk       PUBLIC SDK surface (the "shared backbone")
 ├─ :core-model          Venue, Floor, Poi, Route, Marker, Challenge (pure Kotlin)
 ├─ :positioning         ARCore session, marker relocalisation, BLE/RTT/Geo providers, fusion filter
 ├─ :routing             Floor-plan graph, A*/Dijkstra, accessibility cost profiles, reroute
 ├─ :ar-render           AR scene: arrows, path ribbon, labels, arrival effect
 ├─ :gamification        Clues, stages, scoring, anti-cheat, offline queue
 ├─ :data                Retrofit/OkHttp API, Room cache, offline map packs, WorkManager sync
 └─ :feature-*           UI: navigate, minimap, tours, feedback, settings
```

Libraries (pin exact versions at setup):
- **ARCore SDK** (`com.google.ar:core`) — Session, Augmented Images, Geospatial.
- **Rendering**: SceneView (`io.github.sceneview:arsceneview`, Filament-based, Compose-friendly) — recommended over deprecated Sceneform; raw OpenGL/Filament is the fallback.
- **Maps**: 2D indoor mini-map drawn in Compose Canvas from the floor-plan graph; outdoor via Google Maps Compose or MapLibre.
- DI: Hilt. Async: Coroutines/Flow. Persistence: Room + DataStore. Network: Retrofit + OkHttp + kotlinx.serialization. Background: WorkManager. Push: FCM. Location: Fused Location + Geofencing API. i18n: Android resources (en/ar, RTL).
- Tests: JUnit, MockK, Turbine, Compose UI tests, Robolectric.

### Data model (served by backend / admin builder)
`Venue → Floors → {Walkable graph nodes/edges (with step-free, width, slope flags), POIs, Markers (image + pose in venue frame), Beacons, Geofences}`; `Route`; `Tour → Stages → Challenge (clue text, target POI/marker, reward spec)`. Versioned **offline map packs** (zip: graph JSON + marker images + low-res tiles + POI media).

## 4. Phased delivery

### Phase 0 — Discovery & de-risking (1–2 wks)
- Confirm with BA/PDF owner: pilot venue(s), who supplies floor plans (CAD/GeoJSON), hardware (markers/beacons), backend ownership (does Visit Qatar API already exist?), target devices, accuracy acceptance test, **Android-only vs. SDK for host app**.
- **Spike**: ARCore on a real device + Augmented Images in a corridor; measure drift over 50 m and relocalisation error. Decide go/no-go on marker-only vs. adding BLE.
- Check ARCore device support list; ARCore "AR Required" vs "AR Optional" in manifest (recommend *Optional* with a 2D-map fallback).

### Phase 1 — Foundation (2 wks)
- Gradle modularisation, version catalog, Hilt, navigation, theming (RTL/Arabic), CI (lint, unit tests, build).
- Manifest/permissions: `CAMERA`, `ACCESS_FINE_LOCATION`, `BLUETOOTH_SCAN` (+`NEARBY_WIFI_DEVICES`), `POST_NOTIFICATIONS`; permission rationale flows; ARCore install/availability handling (`ArCoreApk`).
- Core models + mock venue JSON fixture + local floor-plan loader.

### Phase 2 — Positioning MVP (3–4 wks)
- ARCore session lifecycle; pose stream as `Flow<Pose>`.
- Augmented Images database; marker detection → venue-frame transform (`T_venue_from_arworld`) → re-anchor on each marker sighting.
- Fusion filter v1 (VIO + marker fixes + graph snap); expose `PositionState(floor, x, y, headingDeg, accuracyM, confidence)`.
- Debug overlay: live position on 2D floor plan, drift/accuracy telemetry.
- **Exit criterion**: ≤1 m median error at marked checkpoints on pilot route (matches PDF's sub-meter ask).

### Phase 3 — Routing & AR navigation (3–4 wks)
- Graph router with cost profiles: default, **wheelchair/stroller/step-free** (exclude stairs/steep ramps/narrow edges).
- Reroute on deviation (> N m from path for T s) and on closure updates.
- AR rendering: arrows, animated path ribbon on ground plane (plane detection / depth API where supported), floating labelled POIs, distance chips, arrival animation; occlusion via Depth API where available.
- Mini-map preview + start-point confirmation; contextual tip pop-ups driven by POI proximity.
- Accessibility rendering mode (large/high-contrast); localisation en/ar.

### Phase 4 — Connectivity, offline & entry flows (2–3 wks)
- Venue/route API client; Room cache; **offline map packs** with versioning and background refresh.
- Geofencing + FCM proximity push; deep link `app://navigate?venue=&poi=` → opens map, preps AR.
- Live closure/diversion updates (FCM data messages or polling/WebSocket) → reroute.
- On-route feedback widget ("route blocked", "marker missing") with queued upload; analytics events (completion, accessibility usage, session length) via Firebase Analytics.
- Seat-specific guidance entry (parameterised target node from ticket data).

### Phase 5 — BLE (and optional Wi-Fi RTT) positioning (3 wks)
- Beacon scanning (foreground service where needed), calibrated RSSI→distance or fingerprinting; integrate as a fusion measurement; indoor↔outdoor handoff with Geospatial/Fused Location.
- Tune filter; battery profiling.

### Phase 6 — Gamified tours (3–4 wks)
- Tour/stage state machine, clue display with proximity meter, progressive markers.
- Scan-to-unlock (Augmented Images / object); animations and content reveal.
- Scoring, tiers, leaderboard client; `RewardsGateway` interface (Pearl Points/wallet integration deferred to Module 3 vendor APIs).
- Disconnected mode with auto-resume and sync queue; anti-cheat: mock-location detection, speed/teleport sanity checks, server-verified completion tokens.
- (Multiplayer/team modes: backlog.)

### Phase 7 — Hardening & release (2–3 wks)
- Device matrix tests (low/mid/high tier), adaptive quality (resolution, fps, depth on/off).
- Performance, battery, thermal; ANR/crash (Crashlytics); accessibility audit (TalkBack, contrast, font scale).
- Security: certificate pinning, encrypted storage, no PII in logs, privacy/consent for location and camera, per-PDF data-protection alignment; dependency scan.
- Field test at pilot venue; UAT with BA; Play internal → closed → production tracks.

Rough total: **~5–6 months for 1–2 Android developers**, excluding backend/admin builder.

## 5. Backend / admin (outside this repo, but required)
The PDF requires a self-service AR builder (POIs, markers, geofences, routes, challenges), staging→live publishing, analytics dashboards and 99.9% hosting. Decide early whether Visit Qatar already provides these APIs. Until then, build against a **mock server/JSON fixtures** behind repository interfaces so the app is not blocked.

## 6. Risks & open questions

| Risk / question | Mitigation |
|---|---|
| ARCore VIO drifts in featureless corridors / glass / crowds | Dense marker placement; BLE fusion; reloc prompts |
| ARCore only on supported devices; no ARCore on many low-end phones | "AR Optional" + 2D map navigation fallback |
| Sub-meter accuracy hinges on venue hardware the app doesn't control | Make operators' marker/beacon spec a Phase 0 deliverable |
| Geospatial/VPS unreliable indoors | Outdoor only |
| Background proximity alerts: battery + Android 12–15 background limits | Geofencing API + FCM, not continuous scanning |
| Floor-plan data quality/format unknown | Agree GeoJSON/graph schema with operators; tooling to validate |
| iOS/ARKit parity & host-app SDK integration (PDF Module 1) | Keep `core-model`, routing, fusion in pure Kotlin (KMP-ready) |
| Scope creep from Modules 3/4 | Interfaces only; revisit after Discovery |
| Data privacy (location, camera) under government rules | Consent screens, minimisation, retention policy |

**Questions for the BA:** pilot venue(s)? Does a backend/API already exist? Standalone app or SDK embedded in Visit Qatar app? Hardware available (markers/BLE/UWB)? Minimum Android/device list? Languages? Is gamification (Module 2) in the first release?

## 7. Immediate next steps (first sprint)
1. Run Phase 0 questions with BA; pick pilot venue.
2. Add ARCore + SceneView + Hilt to the version catalog; set `<meta-data com.google.ar.core value="optional"/>`.
3. Replace the Compose hello screen with an AR camera screen showing ARCore tracking state.
4. Build the Augmented-Images drift/accuracy spike and record numbers.
5. Create the module skeleton above with a mock venue JSON.
