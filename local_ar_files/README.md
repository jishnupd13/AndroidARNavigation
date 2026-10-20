# local_ar_files

`paths/` holds one JSON file per recorded path, named after its destination (`gaming-room.json`). Each file is
complete in itself:

| Key | Contents |
|---|---|
| `venueId` | The venue the coordinates belong to |
| `destination` | Name, level, x, z, floorY of where the path ends |
| `walkedPoints` | The points of the walk (grouped by `run`), venue coordinates in meters |
| `objects` | Objects placed along the path (`isReference` is a leftover of an earlier automatic start star; it is not drawn) |
| `startMarkerId` | The sign the path was recorded from. Scan the same one for the best accuracy |
| `alignmentSpreadCm` | How well the sign sightings agreed while recording (smaller is better; over ~5 means a poor alignment) |
| `driftCorrectedCm` | How much drift re-scanning a sign during the walk corrected (null if none was re-scanned) |

On the phone they live in `/data/data/com.app.androidnativearcore/files/paths/`. Deleting a destination in the app
deletes its file. Copy one file to share one path.

## Use them on another phone
1. Install the **debug** build of the app (`./gradlew :app:installDebug`) and open it once.
2. Connect the phone by USB with USB debugging on, then run `./push_to_phone.sh` (add `--replace` to remove the
   phone's own paths first).
3. Use the **same printed sign** (see below) in the **same place**. A path is saved relative to the sign, so it only
   lines up with the real world when the sign is physically where it was when you recorded.

`./pull_from_phone.sh` copies the phone's path files into `paths/`.
A release build can't be written to this way (no `run-as`): that would need an export/import feature in the app.

## Signs: why they decide the accuracy
Every position the app shows is measured from a sign. ARCore works out how far away a sign is from how big it looks,
so **the sign must really be as big as the app thinks** (20 cm wide): a sign that is 10% too large puts everything
about 10% too far away, and the error differs between phones, cameras and scanning distances. A laptop screen is a
poor sign: its size is arbitrary and its tilt and position change from session to session.

- Print `printable_signs/<sign>.pdf` at **100% (Actual size)**. Check the black bar on the page with a ruler: it must be
  exactly 10 cm.
- Stick it **flat and rigid** (card or board, not curled paper), on a wall, at the height written on the sheet, and
  don't move it. The path depends on its exact place.
- Scan from 1–2.5 m, facing it. The app shows `sign ±N cm` while recording: a number above ~5 means the
  sightings disagree (wrong size, sign moving, or a bad angle).
- On long paths, **scan a sign again at the end**. The app then removes the drift ARCore built up while you walked
  (it says "N cm of drift corrected").

## Earlier versions
They saved everything in `ar_data.json` (and before that in three files). The current app splits those into per-path
files the first time it starts and keeps the old file as `ar_data.json.migrated`.
