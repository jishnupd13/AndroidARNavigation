# WayFinder AR — Enhancement Plan (Admin/User modes, graph reference points, iOS-minimal UI)

**Status:** Proposed · **Branch:** `feature/vsk` · **Date:** 2026-10-20
**Scope rule:** The working core (positioning, alignment, routing, AR rendering) is **not modified**. All work is **additive** — new app-layer authoring + a new UI layer on top of the existing engine.

---

## 1. Goal

Turn the app from a single-path *record-and-replay* demo into a tool where:

- An **admin** walks an entire floor with the camera, dropping **reference points** and **tagged objects**, connecting points as they go, and saving/loading/resuming **multiple places**.
- A **user** scans any reference point and immediately sees **all reachable destinations sorted by shortest distance**, then navigates there in AR.
- The whole thing has a **minimal, uncrowded, iOS-style UI** — large rounded buttons, round icon buttons, short labels.
- Floor plans can be **exported** (image/PDF + raw data).

Target device for testing: Samsung Galaxy M34 (`SM-M346B`).

---

## 2. What already exists (and why most of the hard work is done)

A full read of `wayfinder-core` and `wayfinder-ar` confirms the engine already provides the difficult pieces. **We reuse these unchanged.**

| Capability | Where | Reuse for |
|---|---|---|
| Node/edge **graph** per venue, serializable | `core/Venue.kt` | Reference-point network |
| **A\* shortest-path** routing, multi-floor, step-free profiles, turn-by-turn maneuvers | `core/RoutePlanner.kt` | "Navigate from any point to any point, shortest distance" |
| Routing *along a recorded walk* | `core/WalkedPath.kt` (`WalkedPathRouter`), `RoutePlanner.routeAlong()` | Edges built from an admin's walk |
| Runtime **image recognition** via ARCore `AugmentedImageDatabase` built from bitmaps | `ar/ArMarkerTracker.kt` (`configure()`) | Image-based reference points |
| Robust marker→world **alignment + drift correction** | `core/MarkerAlignment.kt`, `AlignmentFilter.kt`, `AlignmentTrack.kt` | Re-localizing on any known reference point |
| Live **navigation state machine** (align → route → progress → arrive → reroute) | `ar/NavigationController.kt` | User navigation, unchanged |
| AR scene: arrows, pins, badges, placed objects | `ar/RouteScene.kt`, `ObjectScene.kt`, `Badges.kt` | Rendering, unchanged |
| **Writable venue model** — `Venue` is kotlinx-serializable with `validationIssues()` | `core/Venue.kt`, `VenuePackage.kt` | Admin save/load/edit |
| Per-destination recorded paths store | `core/ArData.kt` (`ArPathStore`) | Kept; complemented by graph store |
| Reusable round button (44dp translucent) | `ar/Hud.kt` (`CircleButton`) | Base for the iOS button kit |

### What does **not** exist yet (the gaps we fill)
- No **admin/user** role or mode gate.
- No **writable venue authoring** flow (venue currently loads read-only from the `DemoMall.venue` asset).
- No **"connect to existing point"**, no **list view** of captured points during capture.
- No **multiple places** management (one bundled venue today).
- No **resume/edit** of a saved place.
- No **"from here → nearest reachable destinations"** user screen.
- No **floor-plan export**.
- UI is functional but text-heavy and not iOS-styled.

---

## 3. Core concept: a reference point *is* a graph node

Everything maps onto the existing graph model, so the existing router "just works":

| User's term | Engine representation |
|---|---|
| Reference point | `Venue.Node` **+** a `Venue.Marker` holding its reference image and surveyed pose |
| Normal object / tag | `PlacedObject` (3D item) and/or `Venue.POI` (named destination) near a node |
| Walking A → B tagging points | Recorded polyline (`SessionPathRecorder`) committed as a `Venue.Edge` between two nodes |
| Connect to existing point | A new `Venue.Edge` from the current node to a chosen saved node |
| Navigate from a reference point, shortest distance | `RoutePlanner.route(fromNode, toPOI/Node, profile)` |

**Image-based reference points — feasibility:** Yes. `ArMarkerTracker.configure()` already does `database.addImage(id, bitmap, physicalWidth)`. An admin snaps a photo of a feature-rich spot (poster, door, signage, textured wall); we register it as a runtime augmented image → it becomes a reference point a user can re-recognize later. ARCore scores image quality; poor captures already land in `unusableMarkers`, so we warn "choose a more detailed spot."
**Caveat:** blank/repetitive surfaces will not track reliably — printed QR/markers remain the fallback for featureless corridors.

---

## 4. New data & persistence layer (additive; core untouched)

- **`EditableVenueStore`** *(new)* — loads a `Venue`, appends/edits nodes, edges, markers, POIs, placed objects; serializes back to a writable `venue.json` under `filesDir/places/<placeId>.venue/` (with a `markers/` image folder). Reuses existing `Venue` serialization and `validationIssues()` before save.
- **`PlacesRepository`** *(new)* — lists/creates/opens/deletes places, each its own `.venue` folder. This is "save/load all data for multiple places."
- **Export / import of a whole floor's captured data** *(priority, on-device only — no cloud sync)* — export a place as a single **`.venue` zip** (venue.json + marker/reference images) via the Android share sheet; import the same zip back (reusing `VenuePackage.load(zip, …)`). Shows the **export size**. The on-disk format is plain **kotlinx JSON + PNGs** — already the format ported from the iOS/ARKit prototype, so it is **cross-platform (iOS ↔ Android) analysable**; export is how we check size and compatibility.
- **Resume editing** — opening a place and scanning any of its known reference points re-aligns via the existing `AlignmentFilter`; admin keeps appending. No new alignment code.
- **Existing `ArPathStore` is kept** for recorded walks; graph edges reference walk polylines where useful.

Data integrity: every save runs `Venue.validationIssues()`; reject + show issues if dangling nodes/edges.

---

## 5. Admin mode — flows & screens

1. **Places** — list of places; round ＋ to create; open; export (image/PDF/zip); delete.
2. **Capture / Edit (AR, full-screen camera)** — minimal HUD, round iOS buttons:
   - ◎ **Add reference point** — snaps the reference image, drops a node at the current aligned pose, warns on low image quality.
   - ＋ **Tag object** — existing object palette (Mug/Gift/Star/Balloon/Message), trimmed; tap a surface to place.
   - 🔗 **Connect to existing point** — pick a saved node from a sheet → creates an edge (uses the just-walked polyline as the edge geometry when available).
   - ☰ **List** — everything captured so far (points + objects): jump to, rename, delete.
   - ✓ **Save place** — validates and writes via `EditableVenueStore`.
   Walking between points auto-records the segment (`SessionPathRecorder` + `AlignmentTrack`) so each leg becomes an edge with real length and drift correction.
   - 🧪 **Test point (inline validation)** — right after adding a reference point, the admin can tap **Test**: walk away, scan again, and get **immediate pass/fail** on whether the captured image re-recognizes and re-aligns. On fail, re-capture in place without leaving the screen. This is the photo-first quality gate; if a spot keeps failing, fall back to a printed marker.
3. **Floor-plan export** — render each level's nodes/edges/POIs/markers to a 2D Compose `Canvas` → export **PNG** and **PDF** (Android `PdfDocument`), plus the raw `venue.json` / zip for backup and transfer between devices.

---

## 6. User mode — flows & screens

1. **Open → scan a reference point** → aligned instantly (existing alignment).
2. **"From here"** — run `RoutePlanner` from the scanned node to every POI/node, **sort by `route.length`**, show the nearest reachable destinations; include objects and reference points in the list.
3. **Navigate** — hand off to the existing `WayFinderNavigationScreen` (unchanged).
4. Camera HUD exposes all user actions minimally: list view, destination picker, re-scan.

**Multiple destinations / stops:** the "from here" screen **displays multiple stops** (all reachable destinations, nearest-first), but **navigation routes to a single chosen place at a time** — no multi-stop chained route in v1. Pick one destination → navigate → return to the list for the next.

---

## 7. UI system — iOS, minimal, uncrowded

- **One reusable design kit** *(new, app module):* large rounded buttons (SF-style), filled primary + round icon button variants (enlarge existing `CircleButton` from 44dp → ~56dp), icon + **short** label, generous spacing/padding.
- **Trim text everywhere:** replace long paragraphs (step-free blurb, marker placement string, empty-state copy) with one-line labels + an info "ⓘ" affordance.
- **Mode gate:** a clean home screen → **Admin** / **User**. **No password/PIN gating** (decided) — both modes open.
- Keep Material3 (already integrated); theme it iOS-like — SF-ish type scale, rounded shapes, subtle translucency. No new UI framework.

---

## 8. File-by-file change outline

**New (app module):**
- `ui/designkit/` — buttons, icons, theme tokens (iOS styling).
- `HomeScreen` (mode gate), `PlacesScreen`.
- `admin/AdminCaptureScreen` (wraps/extends the AR capture HUD with add-point / connect / list).
- `user/FromHereScreen` (nearest-reachable list).
- `floorplan/FloorPlanRenderer` + export (PNG/PDF).

**New (core, pure Kotlin, additive):**
- `EditableVenueStore`, `PlacesRepository` (+ unit tests).

**Touched, additively (no behavior change to existing paths):**
- `WayFinderDemoApp.kt` — route to the new Home/mode gate; keep existing List/Capture/Navigate reachable.
- `ar/Hud.kt` — add enlarged iOS button variants (existing `CircleButton` kept).
- `wayfinder-ar` capture HUD — add the admin action buttons as optional controls behind a flag, so the existing single-destination capture is unaffected.

**Untouched:** `RoutePlanner`, `WalkedPath`, `MarkerAlignment`, `AlignmentFilter`, `AlignmentTrack`, `NavigationController`, `RouteScene`, `ObjectScene`, `ArMarkerTracker` detection logic.

---

## 9. Phasing (each phase independently shippable & testable on the M34)

| Phase | Deliverable | Engine risk |
|---|---|---|
| **1. UI refresh + mode gate** | iOS design kit, Home (Admin/User), restyled destination list, bigger round buttons, short text | None (pure UI) |
| **2. Admin graph authoring** | `EditableVenueStore` + add reference point (runtime image) + connect-to-existing + capture list view + multi-place save/load + resume | Low (additive) |
| **3. User "from here"** | scan → nearest-reachable sorted list → navigate over built graph | Low |
| **4. Floor-plan + data export** | per-level 2D render → **PNG**; whole-floor **.venue zip export/import** with size readout | None |

**Overall scope:** multi-week. Start with **Phase 1** (zero risk, immediately visible).

---

## 10. Decisions (signed off)

1. **Reference points:** **photo-first, printed fallback.** Capture screen includes an **inline Test** step — add a point, then move away and rescan for **immediate pass/fail** re-recognition, re-capture in place on failure.
2. **Admin gating:** **no password/PIN** — both modes open.
3. **Multi-stop:** **display multiple stops**, but **navigate to a single place at a time** (no chained multi-stop route in v1).
4. **Floor-plan export:** **PNG.**
5. **Sync:** **on-device only.** Provide whole-floor **export + import** (`.venue` zip) with a **size readout**; format is cross-platform (iOS ↔ Android) for compatibility analysis.

---

## 11. Risks & mitigations

- **Image-based points on plain walls fail to track** → quality gate on capture + printed-marker fallback.
- **Graph drift over a whole floor** → reuse `AlignmentTrack` loop-closure; require periodic reference-point rescans while authoring.
- **Scope creep** → strict phasing; Phase 1–3 deliver the core admin/user vision; 4–5 are additive.
- **Keeping the working core intact** → new controls gated/flagged; existing capture/navigate paths remain callable and tested.
