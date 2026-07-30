# Offline Maps

Downloadable map coverage for use without a connection. Downloads run **only over
unmetered Wi-Fi**, and nothing is ever deleted without the user asking.

---

## 1. User-facing workflow

**Where:** Settings → Offline maps.

### Two ways to save coverage

| | Needs a map source? | Needs Wi-Fi? | Works today |
|---|---|---|---|
| **Save viewed areas** (import) | No | No | **Yes** |
| **Download missing areas** | Yes — set one in Settings | Yes, unmetered | Once a source is set |

**Save viewed areas** is the default path. The map already caches every tile you
look at, but it trims that cache to 500 MB *by age*, so areas you studied vanish on
a schedule you didn't choose. Importing copies them into managed storage where
nothing is deleted unless you ask. It needs no internet, no map source, and works on
any connection — the tiles are already on the phone.

**Download missing areas** fetches coverage you haven't viewed. It needs a tile URL
template under Settings → Offline map source, and runs only on unmetered Wi-Fi.

### Saving a map

1. Tap **Add an offline map**.
2. **Choose a radius first** — 90 NM, 150 NM or 250 NM. Nothing downloads until one
   is picked; the button stays disabled and says so.
3. Optionally choose detail: *Standard* (wider zoom steps, roughly a quarter the
   size) or *Detailed*.
4. A size estimate appears — a range, plus how many areas are already saved from a
   previous download and won't be fetched again.
5. Coverage is centred on your current position. If location is unavailable, a
   position can be supplied instead.
6. The map is named after where it was made — `Riverside`, `Vancouver`,
   `Penticton`. A second map at the same place becomes `Riverside (2)`, never
   overwriting the first.

While downloading you see percent complete, downloaded size against the estimate,
how many areas remain, and whether Wi-Fi is still connected. **Stop** keeps
everything downloaded so far.

### If you're not on Wi-Fi

The download does not start — not partially, not queued. You'll see: *"Offline maps
can only download or update over Wi-Fi. Connect to Wi-Fi and try again."* Retry once
connected. Metered Wi-Fi (a phone hotspot) counts as ineligible.

If Wi-Fi drops mid-download, it pauses immediately. Everything already saved is
kept, existing maps are untouched, and the map appears under **Unfinished
downloads** with a Resume button that lights up when Wi-Fi returns.

### Travel coverage

While you travel outside your saved maps, the app keeps a lightweight note of where
you went — a path, the areas it would need, and when. **No map data is downloaded
while travelling**, and it never uses cellular.

Once you're on Wi-Fi again you're offered:

- **Add coverage** — downloads only what's missing and adds it to the right saved map
- **Not now** — keeps the note, stops asking
- **Dismiss** — removes the note; no map data is touched

Which map receives the coverage is decided in a fixed order: the map containing your
destination, otherwise the map overlapping your route most, otherwise it asks you.
If nothing suitable exists it offers to create a new named map rather than filing it
somewhere unrelated.

### Deleting

Select one or more maps, then confirm. Before deleting you see each map's name,
location, size, dates, and whether it includes travel coverage — plus a note that
only downloaded map data on this device is removed.

Areas shared between two maps are kept until the last map using them is deleted.
**Nothing is ever deleted automatically to free space.**

---

## 2. Segment manifest schema

`filesDir/offline/manifest.json`, written temp-then-rename so a crash can never
leave a torn index.

```jsonc
{
  "version": 1,
  "segments": [
    {
      "id": "uuid",
      "displayName": "Riverside (2)",     // unique; what the user sees
      "locationName": "Riverside",        // geocoded, before any suffix
      "centerLat": 33.9524737,
      "centerLon": -117.3317861,
      "requestedRadiusNm": 150,           // null if not a radius download
      "createdAtMs": 1753776000000,
      "updatedAtMs": 1753779600000,
      "coverage": [
        {
          "id": "uuid",
          "source": "INITIAL_DOWNLOAD",   // or APPENDED_TRAVEL
          "tileKeys": ["8/43/103", "…"],  // requested
          "storedTileKeys": ["8/43/103"], // confirmed on disk
          "minZoom": 8,
          "maxZoom": 11,
          "createdAtMs": 1753776000000,
          "state": "COMPLETE",            // INCOMPLETE | PAUSED | FAILED
          "bytesStored": 4823192,
          "radiusNm": 150,                // initial downloads only
          "centerLat": 33.95,
          "centerLon": -117.33,
          "note": "Travel to Penticton"   // appends only
        }
      ]
    }
  ]
}
```

**Key invariants**

| Rule | Why |
|---|---|
| An append adds a `CoverageEntry`; it never rewrites an existing one | An append must not shrink or invalidate the download it was added to |
| A segment's state is the worst of its entries | One unfinished append means the segment is not complete |
| `storedTileKeys ⊆ tileKeys` | Progress is measured against what was requested |
| Reference counting is per **segment**, not per entry | Two appends covering the same area inside one segment must count once, or deleting it would orphan tiles |
| Tiles live at `filesDir/offline/tiles/{z}/{x}/{y}.png` | Separate from osmdroid's cache, which trims by age and would silently eat a deliberate download |

Travel notes live beside it in `travel.json`, same atomic write.

### Migration

`version` is `1`. The existing osmdroid tile cache is **untouched** — this feature
adds a new directory and never reads, writes or deletes the old one, so no migration
is needed and the current map keeps working exactly as before. A future schema change
must bump `version` and migrate on load; `ignoreUnknownKeys` is already on, so adding
fields is backward compatible.

---

## 3. Module layout

Pure logic sits in `:core:receiver` and is fully JVM-tested. Android touches nothing
but the adapters.

| Concern | Type | Module |
|---|---|---|
| Network eligibility | `NetworkEligibility`, `OfflineDownloadPolicy` | core |
| ↳ platform adapter | `AndroidNetworkEligibility` | app |
| Location naming | `LocationNamer`, `SegmentNaming` | core |
| ↳ platform adapter | `AndroidLocationNamer` (Geocoder) | app |
| Radius / tile geometry | `OfflineRadius`, `MapDetail`, `TileGeometry` | core |
| Travel collection | `TravelTracker`, `TravelRecord` | core |
| Append targeting | `AppendTargeting` | core |
| Segment model & manifest | `OfflineSegment`, `OfflineManifest` | core |
| ↳ storage adapters | `FileTileStore`, `FileManifestStore` | app |
| Tile downloading | `TileDownloader` | core |
| ↳ HTTP adapter | `OsmTileDownloader`, `LocalOnlyTileDownloader` | app |
| Orchestration | `OfflineMapManager` | core |
| Presentation | `OfflineMapsScreen`, `OfflineMapsViewModel` | app |

The manager takes every dependency as an interface, which is what lets 90 tests
exercise Wi-Fi loss, torn manifests and shared-tile deletion without a device.

### Structured logs

One `key=value` line per event under tag `OfflineMaps`: `eligibility`,
`download_start`, `download_progress`, `download_pause`, `download_complete`,
`download_failed`, `append_decision`, `segment_deleted`, `storage_error`.

---

## 4. Implementation notes and assumptions

### Map provider and tile licensing

**Import needs no provider and raises no licensing question** — it copies tiles the
map already fetched for viewing, which every tile service permits and most (OSM
included) actively require you to cache. This is why import is the default path and
why the feature is usable with nothing configured.

**Downloading is different.** `AppConfig.offlineTileUrlTemplate` is empty by default,
so `ConfigurableTileDownloader` falls back to a local-only source and downloads store
nothing until a URL is set in Settings.

The live map renders from `tile.openstreetmap.org` via osmdroid's `MAPNIK` source.
OSM's tile usage policy prohibits pre-emptive fetching beyond what a user is actively
viewing, names "pre-seeding large areas or multiple zoom levels" and "Download region
for offline use" specifically, and states offline use is not permitted on their
servers. Their servers are community funded and enforcement is blocking without
notice. Note that a VPN changes *who gets blocked*, not what the terms permit — the
Settings screen states plainly that the user should check the terms of whichever
service they point it at, and the decision is theirs.

Options for the download path:

- a **self-hosted** tile endpoint (no third-party terms involved)
- a **commercial provider** whose licence permits offline packaging
- a pre-built offline archive (osmdroid reads `.mbtiles`/`.sqlite` via
  `OfflineTileProvider`), sidestepping downloading entirely

`OsmTileDownloader` requires an explicit `urlTemplate` and `userAgent` with no
defaults, so enabling downloads is always a deliberate act.

### Storage limits

- Size estimates use **8–15 KB per tile**, presented as a range. Real raster tiles
  vary from ~1 KB (ocean) to 40 KB+ (dense urban), so the estimate is honest about
  being approximate and actual bytes are recorded per segment after download.
- Offline tiles are stored **outside** osmdroid's cache, which defaults to a 600 MB
  ceiling trimmed to 500 MB by age. Sharing that directory would let ordinary map
  browsing evict a deliberately downloaded region.
- **No storage ceiling is enforced** on offline segments. Nothing is auto-deleted, so
  a user can fill the device. The storage screen shows usage; enforcement would mean
  automatic deletion, which the spec forbids.
- Rough sizes at z8–z11 (Standard): 90 NM ≈ 20–40 MB, 150 NM ≈ 50–95 MB,
  250 NM ≈ 140–260 MB. Detailed (z8–z12) is roughly 4× those.

### Route-tracking precision

- Positions are sampled at a **5 NM floor**. Finer sampling would grow the travel log
  without changing a single tile requested, because the corridor is 30 NM wide.
- The travel corridor is **±30 NM** around the path.
- "Outside coverage" uses a **10 NM margin** beyond a segment's bounds, so sitting
  near a boundary doesn't open and close records repeatedly.
- Corridors are computed **per leg**, not as one box around the whole journey — a
  long diagonal's enclosing rectangle is far larger than the route flown. With only
  two sampled points this saving disappears (asserted by a test that documents it).
- Segment bounds are the **bounding box of the tile set**, so containment tests are
  tile-aligned and slightly generous at the edges.

### Other assumptions

- **Position source.** The screen centres coverage on the configured observer
  position. Travel tracking is driven from `PipelineService.onGpsFix`, so it records
  only while Follow GPS is active — in Fixed mode the observer never moves and there
  is nothing to track. It writes a note and never touches the network.
- **Geocoding** needs network, so a segment created offline falls back to a
  coordinate label. Naming never blocks a download.
- **No background work.** Downloads run in the ViewModel scope and stop with the
  screen. There is no `WorkManager` integration, so a pending Wi-Fi intent is not
  persisted as a task — the spec's requirement 8 fallback applies: the user retries
  when connected. Travel notes *are* persisted.
- **Idempotency** is by tile key. Re-running any operation requests only what is
  absent from disk, so an interrupted run costs the remainder and never a repeat.
