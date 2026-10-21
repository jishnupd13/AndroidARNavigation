# Project State, Claude Session Handoff & Reference Image Diagnosis

**Last Updated:** 2026-10-21  
**Active Branch:** `feature/admin_user`

---

## 1. Important Discovery: Two Diverged Project Folders

During the recent Claude sessions on 2026-10-21, work happened across **two separate local clones** of the same project:

| Folder | Target Device Used | Branch / HEAD | What Exists Only Here |
|---|---|---|---|
| `/Users/vishnusunilkumar/Desktop/azuretest/AndroidARNavigation` *(Current Workspace)* | OPPO (`CPH2689`) | `feature/admin_user` (`89c4c01`) | `WayFinderLocateScreen.kt` (User scan-first flow), `NearbyScreen.kt` ("You're at …" nearest reachable list), `FloorMap.kt` (interactive 2D Canvas map with pan/zoom/route), faster floor detection (`floorHeightByRay`). |
| `/Users/vishnusunilkumar/Desktop/Projects/AndroidNativeARcore` *(Where Claude hit the session limit at 16:59)* | Samsung Galaxy M34 (`SM-M346B` / `RZCW705MLWT`) | `feature/admin_user` (`1ba2c2c` + uncommitted HUD changes) | **11 newer commits + uncommitted HUD fixes**: `DataTransfer.kt` (Save/Load `.zip`), portable relative image paths in `CapturedMarkerStore`, `Auto points` switch + green capture flash, destination Edit/Rename dialog, `Clear reference points` + per-sign delete, User `Camera` FAB, Admin `+ Add sign` (Camera/Gallery + EXIF rotation fix), `⊕ Start here (no sign)` plane-anchored bootstrap, `ReferencePointScene.kt` (green 3D pins for reference points in camera), and **uncommitted** Admin HUD overlap fix + `Points` / `Objects` list dialogs with tap-to-direction. |

---

## 2. Chronological Summary of Claude Requests & Status

### Completed in `AndroidNativeARcore` (Commits `3fca184` → `1ba2c2c`)
1. **App flow diagram (`3fca184`)**: Added `docs/flows.mmd` documenting User and Admin flows.
2. **Save / Load Data (`df7ab75`)**: Added `DataTransfer.kt` to export/import `paths/` and `captured-markers/` as a `.zip` bundle; updated `CapturedMarkerStore` to store relative image filenames (`<id>.png`) resolved on load.
3. **Auto Reference Points & Green Notification (`6f81aad`)**: Added `Auto points` toggle (default OFF) at top-right of capture camera and green `✓ Reference point added` flash banner.
4. **Edit / Rename / Delete Destinations (`2749de7`)**: Long-pressing a custom destination in Admin mode opens an Edit dialog (rename or delete).
5. **Clear Reference Points & Per-Item Delete (`9472a49`)**: Added "Clear reference points" row in Admin Data section and per-item Delete button in AR Signs gallery.
6. **Direct Camera FAB in User Mode (`ddc71d4`)**: Added `Camera` extended FAB on the User destination list.
7. **Add Sign in AR Signs Gallery (`2fcbf69` & `25aedf7`)**: Added `+ Add sign` FAB in Admin AR Signs gallery with **Camera** or **Gallery** chooser, name, and printed width (cm) input.
8. **Start Capture Without a Sign (`dd08901`)**: Added `⊕ Start here (no sign)` button in Admin capture when floor/tracking is ready so admins can bootstrap a venue from scratch (`startMarkerId = "origin-bootstrap"`).
9. **Show Reference Points in Camera + EXIF Rotation + Steady Capture (`1ba2c2c`)**:
   - Added `ReferencePointScene.kt` to render captured reference points as green named 3D pins in the camera view.
   - Fixed EXIF orientation when decoding Camera/Gallery sign images.
   - Waited for a steady frame before grabbing manual `◎` reference captures.

### Left Uncommitted When Claude Hit Session Limit (at 16:59 IST)
10. **Admin Camera HUD Overlap Fix + Reference Points & Objects List Buttons**:
    - Modified `WayFinderCaptureScreen.kt` (in `AndroidNativeARcore`, installed to Samsung M34 right before the limit hit, **not yet committed**):
      - Consolidated top and bottom HUD panels so `Auto points`, the guidance banner, `✕`/`◎`, and `Save destination here` no longer overlap.
      - Added `📍 Points (N)` and `📦 Objects (N)` buttons in the capture HUD.
      - Tapping any point or object in those dialogs calls `guideToSession(...)` to show distance and relative direction (`straight ahead`, `ahead, on your left/right`, `behind you — turn around`).

### Completed in `AndroidARNavigation` (Commit `89c4c01`)
11. **Fast Floor Detection + User Scan-First Flow + 2D Canvas Map**:
    - Added `WayFinderLocateScreen.kt` (User Mode opens camera to scan any reference point).
    - Added `NearbyScreen.kt` (lists all reachable destinations and reference points sorted by walking distance, with List/Map segmented toggle).
    - Added `FloorMap.kt` (2D Compose `Canvas` map drawing walked paths, markers, destinations, user position, and route with pinch-to-zoom and drag-to-pan).

### Pending / Next Tasks Left Off
- **Merge the two repos** so `AndroidARNavigation` has all 11 commits + the uncommitted HUD fixes from `AndroidNativeARcore` (and vice versa).
- **Fix User Mode Reference Point Scanning & Continuous Relocalization** (see Section 3 below).
- **Git Push to `https://github.com/wishnu213/AndroidARNavigation.git`** on branch `feature/admin_user`.

---

## 3. Why the Bottle Reference Image (`"ashiq bitle"`) Was Not Recognized in User Mode

We inspected the actual files pulled from your Samsung M34 (`RZCW705MLWT`):
- Marker JSON: `files/captured-markers/ref-8233f50d-c4cb-4468-b72e-ec45d77c42f5.json` (`"name": "ashiq bitle"`, `"physicalWidth": 0.45359707`)
- Marker Image: `files/captured-markers/ref-8233f50d-c4cb-4468-b72e-ec45d77c42f5.png`
- Saved Path: `files/paths/ashiq-to-front-door.json` (`"startMarkerId": "origin-bootstrap"`)

There are **3 distinct reasons** why User Mode did not match the bottle:

### Cause 1: Code Bug — `NavigationController` Explicitly Ignores Every Marker Except `startMarkerId`
In `NavigationController.kt` (line 162):
```kotlin
if (recordedFromMarkerId.isNotEmpty() && marker.id != recordedFromMarkerId) {
    if (!alignmentFilter.isAligned) showAlignmentPrompt(recordedSignPrompt())
    return
}
```
- When you recorded `"ashiq to front door"`, you started via **`⊕ Start here (no sign)`**, which saved `"startMarkerId": "origin-bootstrap"`.
- When you recorded `"centre"`, it saved `"startMarkerId": "sign-entrance"`.
- In User Mode, when navigating to `"ashiq to front door"`, `recordedFromMarkerId` is `"origin-bootstrap"`. Because the bottle's ID is `"ref-8233f50d-..."` (`!= "origin-bootstrap"`), **`NavigationController.onMarkerDetected()` immediately `return`s and discards any sighting of the bottle!**
- Even worse, `"origin-bootstrap"` is a synthetic in-memory marker with no image, so any path started with `Start here (no sign)` can **never** be aligned unless `NavigationController` is allowed to align against captured `ref-*` markers!
- And if you tapped the User Mode **`Camera` FAB** (`onOpenCamera`), it opens `Screen.Navigate` for `pkg.venue.pois.firstOrNull()` (a bundled DemoMall shop), which doesn't load your walked path or placed objects at all.

### Cause 2: Code Bug — Thumbnail Strip Only Takes the First 6 Markers (`take(6)`)
In `WayFinderNavigationScreen.kt` (line 75) and `WayFinderCaptureScreen.kt` (line 213):
```kotlin
val signs = remember(pkg) {
    pkg.venue.markers.take(6).mapNotNull { marker -> ... }
}
```
Because `DemoMall.venue` already has 6 bundled signs (`sign-entrance`, `sign-lift-l0`, `sign-lift-l1`, `sign-food-court`, `sign-escalator-l0`, `sign-escalator-l1`), `withExtraMarkers(extraMarkers)` appends your captured bottle as marker **#7**. Calling `.take(6)` cuts off all captured reference points, which is why your screenshot only shows the 6 colorful DemoMall signs in the top prompt banner!

### Cause 3: ARCore Computer Vision — Why a 3D Cylindrical Checkered Bottle Fails 2D Planar Matching
Comparing the saved image (`ref-8233f50d-c4cb-4468-b72e-ec45d77c42f5.png`) with your User Mode screenshot reveals why ARCore's `AugmentedImageDatabase` cannot match it:
1. **Background Dominates the Saved Crop (75% Background vs. 25% Bottle)**:
   - `captureReferencePoint()` crops the center 60% square of the camera frame (`CameraImage.centerSquare(full, 0.6f)`) and estimates `physicalWidth = 0.454 m` (45.4 cm) from the surface hit distance.
   - In the saved PNG, the bottle occupies a narrow vertical strip in the middle, while **~75% of the image is the background behind it** (monitors, desk edge, black cables on the white table, office chair).
   - In your User Mode screenshot, you held the phone much closer (~25 cm away) so the bottle fills the entire height of the frame (slightly out of focus), and the background monitors/cables are at a completely different scale and parallax angle.
2. **ARCore `AugmentedImage` Requires a Flat 2D Surface, Not a 3D Cylinder**:
   - ARCore's `AugmentedImageDatabase` computes a **2D planar homography** (expecting all features to lie on a single flat plane of width `45.4 cm`).
   - A round bottle is **cylindrical** (3D curvature + specular reflections that move when you shift the camera), and its carbon-fiber **checkered pattern is repetitive** (every dark/grey square looks identical to its neighbor, which produces ambiguous feature descriptors).
   - Most of the unique keypoints ARCore accepted when scoring the image (`Quality / accepted = true`) actually came from the **monitors, cables, and desk edges in the background**, not the bottle itself.
3. **Strict Multi-View Lock-In (`AlignmentFilter`)**:
   - Even if a frame briefly matches, `AlignmentFilter` requires **3 close frontal sightings across at least 12 cm of side-to-side camera movement** (`MIN_VIEW_SPREAD_M = 0.12f`) before setting `aligned = true`. Moving the camera 12 cm sideways in front of a 3D bottle shifts the bottle against the background (parallax), breaking the 2D planar homography.
