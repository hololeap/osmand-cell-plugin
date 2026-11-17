# Handoff: a cell-coverage companion app for OsmAnd

You are picking up a project that an earlier session planned with the user. That session did research and settled the design decisions but wrote no code. Read this whole brief before acting.

Facts marked **[verified]** were checked against source or documentation on 2026-11-16. Facts marked **[unverified]** come from memory; check them before you rely on them.

## What the user wants

An extension for OsmAnd on Android that records, maps and exports:

1. Which cell towers the phone connects to.
2. Signal strength, radio generation (2G, 3G, LTE, 5G), and whether voice calls are possible on the connected cell.
3. Measured network quality: bandwidth, latency and packet loss, and above all where the internet connection drops out.
4. Bonus: an estimated location for each tower, inferred from signal strength versus position, that keeps improving as data accumulates. Where a public database lists the tower, show a link to it and a button that switches that tower between the inferred location and the online one (the location from the last network fetch). A tower shows one or the other, never both.

The result is geospatial data: visible on the OsmAnd map and exportable. The user works in QGIS.

## Decisions already made (do not reopen them)

- **A separate app that plugs into stock OsmAnd through its AIDL API.** The user does not want to maintain an OsmAnd fork. The app must work with the F-Droid build of OsmAnd they already have.
- **Port Tower Collector's collection code.** The app collects cell data itself, using logic ported from Tower Collector. Tower Collector does not need to be installed at runtime.
- **Work in a cloud session.** The user's own machine (Gentoo, JDK 25, `adb`) has no Android SDK. They can install an APK with `adb`.

## What is in this repository

- `osmand-api/` is OsmAnd's client API module (the AIDL interface and its parcelables), copied from `osmandapp/OsmAnd` at commit f4f96bb, GPLv3. Its `build.gradle` is OsmAnd's own and will not work here. Replace it with a minimal Android library build: namespace `net.osmand.aidlapi`, AIDL enabled, `src` as both the Java and the AIDL source directory, and a dependency on `androidx.annotation`.
- `reference/osmand/` holds OsmAnd-side sources for reading only (GPLv3; never compile them): `OsmandAidlApi.java` (what each API call does), `AidlPermissionGroup.java`, `ConnectedApp.java`, `AidlMapLayer.java` (how points are drawn) and `SQLiteTileSource.java` (the tile-file format). `OsmAndAidlHelper-from-api-demo.java` is OsmAnd's MIT-licensed sample client and shows binding and calls.
- `reference/towercollector/` holds the Tower Collector sources to port (MPL-2.0, `zamojski/TowerCollector` at commit e31296b, version 3.0.2): `CollectorService.java`, `collector/`, `model/`, `enums/`, `MobileUtils.java`, `CsvExportFormatter.java`, `GeneralCellUtils.java`, its `AndroidManifest.xml` and `LICENSE.txt`.

## Setup the user was asked to do before starting this session

- Push this repository to GitHub and start the session on it, with the Claude GitHub App installed so you can push.
- Add `dl.google.com` to the cloud environment's allowed domains (the Android SDK and Google's Maven repository live there). `opencellid.org`, `api.beacondb.net` and `speed.cloudflare.com` are optional, for testing those endpoints.
- Android SDK license: I (the user) agree to you accepting it through `sdkmanager` on my behalf. *(User: delete this line if you would rather be asked.)*

If any of this is missing, say exactly what is missing and what to change. Do not work around it.

## Cloud environment facts [verified in the Claude Code docs]

- The VM has 4 vCPUs, 16 GB RAM and 30 GB disk, with OpenJDK 21, Gradle and Maven preinstalled. There is no Android SDK; install the command-line tools, a platform and build-tools yourself. `developer.android.com` is on the default allowlist, so you can look up the current download URL there.
- The default allowlist includes `maven.google.com`, `services.gradle.org`, `plugins.gradle.org`, `repo.maven.apache.org` and `raw.githubusercontent.com`. It does not include `dl.google.com`.
- GitHub access is scoped to the repositories attached to the session. Single files from other public repositories are reachable through `raw.githubusercontent.com`.
- A foreground command gets 2 minutes by default and 10 at most, then moves to the background. Run long Gradle builds in the background.
- An idle VM pauses and may later be reclaimed, which loses anything not pushed. Once the SDK install works, give the user a setup-script snippet for the environment (it must finish in about five minutes to be cached).
- There is no phone or emulator. Radio code can be compiled here but not exercised. Device testing belongs to the user.

## Project shape

- Three Gradle modules: `:core` (pure Kotlin on the JVM: data model, parsers, estimator, tile math, GeoPackage encoding, with unit tests), `:osmand-api` (the vendored module) and `:app` (Android). Keep as much logic as possible in `:core`, because `./gradlew :core:test` is the one fast loop that needs no SDK.
- OsmAnd's own API sample builds with AGP 8.10.1, Kotlin 2.1.20, Gradle 8.11.1 and compileSdk 36 **[verified in its build files]**. Start from that combination.
- Default to minSdk 29, which has `requestCellInfoUpdate` and the 5G cell classes. Say so in your first report in case the user's phone is older.
- License the app GPL-3.0-or-later, because it links the GPLv3 API module. Ported Tower Collector files keep their MPL-2.0 header and sit together in one `collector` package. Add `LICENSE` and a `NOTICE` that lists both origins.

## How the app plugs into OsmAnd [verified in `reference/osmand/`]

- **Connection.** Bind to the service action `net.osmand.aidl.OsmandAidlServiceV2` in package `net.osmand.plus` (the F-Droid build; `net.osmand` is the free build and `net.osmand.dev` the nightly). Declare those packages in the manifest's `<queries>`. Detect which are installed.
- **Listing and permissions.** A connected app appears on OsmAnd's Plugins screen with an on/off switch. Current OsmAnd master gates calls by permission groups the user grants per app (`AidlPermissionGroup.java`): MAP covers layers, points, widgets and context-menu buttons; SETTINGS covers `showSqliteDbFile` and `hideSqliteDbFile`; SYSTEM covers `copyFile`. Which groups are granted by default, and whether the user's installed release has this gating at all, is **[unverified]**. Treat a `false` return as "not permitted or not available" and tell the user what to enable in OsmAnd.
- **Points.** Call `addMapLayer`, then `addMapPoint`, `updateMapPoint` and `removeMapPoint`. A point has an id, a short name drawn on the map, a full name, a type name, a color, a location, a list of detail rows and a params map that can carry an image URI for a custom icon. Each layer gets a toggle in Configure map. Points are the only geometry; there are no lines or polygons.
- **Buttons.** `addContextMenuButtons` attaches a left and a right button to a layer's points. Taps arrive through `IOsmAndAidlCallback.onContextMenuButtonClicked(buttonId, pointId, layerId)`.
- **Raster overlay.** `copyFile` accepts a `.sqlitedb` tile file in parts of at most 256 kB and stores it in OsmAnd's tiles folder (`OsmandAidlApi.java` near line 3300). `showSqliteDbFile(name)` makes it the map overlay and `hideSqliteDbFile` clears it (near line 1783). OsmAnd has a single overlay slot, drawn by its "Online maps" plugin, which probably has to be enabled **[unverified]**.
- **Tile file format** (`SQLiteTileSource.java`): a table `tiles (x int, y int, z int, s int, image blob, time long, PRIMARY KEY (x,y,z,s))` and a table `info (tilenumbering, minzoom, maxzoom)`. Set `tilenumbering` to `'simple'` so that `z` is the plain zoom level. If that column is missing or says `BigPlanet`, OsmAnd reads `z` as inverted.
- **Background starts.** Android stops background apps from opening activities, and the "open listing" button fires while OsmAnd is in front. Starting a browser from the callback may be refused **[unverified]**. Fall back to a notification that carries the link, and put the URL in the point's detail rows too.

## Collecting: the Tower Collector port

- `CollectorService.java` gives the service shape to reproduce: a foreground service of type location, GPS updates from `LocationManager`, a `TelephonyManager` per SIM (`createForSubscriptionId`), a phone-state listener re-registered periodically, `getAllCellInfo()` on each callback, and a wake lock.
- Port the plain Android path: `collector/parsers/MeasurementParser.java` and `Api17PlusMeasurementParser.java`, `collector/converters/CellIdentityConverter.java` and `CellSignalConverter.java`, `collector/validators/` including `specific/`, and the fields of `model/Cell.java` and `model/Measurement.java`. Tower Collector prefers the NetMonster Core library when it returns cells; leave that out for now and tell the user it is the place to look if a device reports junk.
- Rules to keep: location accuracy of 40 m or better; a fix no older than 5 minutes; system and GPS time within 2 days of each other; a new sample only after moving 50 m (with 10% tolerance), unless the registered cell changed, in which case save immediately; drop duplicate and invalid cells.
- Import Tower Collector's CSV export as a second data source. It gives the user their existing data and gives you a way to exercise the estimator and the tiles without a radio. The header is in `CsvExportFormatter.java` at line 46.

## What to record

Android API names in this section are **[unverified]**; the compiler will settle them. Guard calls newer than minSdk with an SDK check.

- **Sample** (one per saved fix per registered cell, neighbors optional): time, position, accuracy, speed; radio type, MCC, MNC, TAC or LAC, cell id, PCI or PSC, channel number, bands; signal (RSRP for LTE, SS-RSRP for NR, RSCP for WCDMA, RSSI for GSM), RSRQ, SINR, timing advance; registered or neighbor.
- **Service state** with each sample: voice registration (`ServiceState.getState()`, which needs `READ_PHONE_STATE`), data network type, the 5G display override (`TelephonyDisplayInfo`), roaming, and the services listed in `NetworkRegistrationInfo.getAvailableServices()`. Derive "calls possible" as yes (voice in service), emergency only, or no. On LTE-only and 5G networks voice depends on IMS registration, which public APIs expose only partly, so store the raw fields and document the heuristic.
- **Latency and loss probe**: cheap and frequent, by default every 30 seconds or 250 m. Send a burst of about ten small probes and record sent, received, minimum, mean and maximum round-trip time, and the failure reason. Offer methods behind one interface: TCP connect time to port 443, UDP DNS queries (these give a real loss figure), and the system `ping` binary if it is usable. A failed probe is data. Store it with its position, because that is the "where does the internet drop out" layer.
- **Throughput probe**: expensive, so off by default. Download a fixed size over HTTPS with a time cap from a configurable URL (Cloudflare's `https://speed.cloudflare.com/__down?bytes=N` is a candidate). Enforce a daily data budget and show the data used. Record the modem's own estimate from `NetworkCapabilities.getLinkDownstreamBandwidthKbps()` with every sample, since it costs nothing.
- Run probes over cellular only. Either bind to the cellular `Network` or skip them while Wi-Fi is the default network.
- **Connectivity events**: `ConnectivityManager.NetworkCallback` lost, available and validated transitions, each with a position.

## Storage and export

Make the app's SQLite file a valid GeoPackage so QGIS opens it directly: the three `gpkg_*` metadata tables, `PRAGMA application_id = 0x47504B47`, and a point geometry column on each feature table (GeoPackage binary header followed by a WKB point, SRS 4326). Skip the R-tree index, because it needs SQL functions Android's SQLite lacks. Tables: `samples`, `probes`, `conn_events`, `towers`, plus `cell_bins` for the estimator. Keep the file where the user can reach it with `adb` or a file manager, and also export GeoJSON and GPX through the share sheet. If the GeoPackage route fights you, fall back to plain tables plus GeoJSON export and say so.

## What shows in OsmAnd

- **Heatmap overlay, for the full history.** Render 256-pixel PNG tiles with transparency from the binned samples and write one tile file per metric: signal, generation, call availability, latency, loss, throughput. Push them all, and switch metric by calling `showSqliteDbFile` on a different file, which needs no re-render. Track which tiles each changed bin dirties, and refresh on demand and after a drive, not per sample. Cover zooms of about 8 to 15. Whether OsmAnd stretches tiles past `maxzoom`, and whether it notices a replaced file without a hide and show, are **[unverified]**: test both and report.
- **Point layers, for things worth tapping.** Towers, with different looks for estimated and online positions, and detail rows for identifiers, sample count, estimate quality, error radius and the distance between the two positions. Recent samples (the last few hundred). Dropouts (failed probes and lost-connectivity events). Keep each layer to a few hundred points.
- **Tower buttons.** One switches the location source; the other opens the public listing.
- The legend, the metric choice, a tower list, settings, import and export live in the app's own screens. Do not rely on red versus green alone.

## Tower estimator

Keep this in `:core` and test it on the JVM.

**Data reduction.** For each cell, bin samples on a grid of about 30 m in a local flat projection and keep the count, mean dBm and median timing advance per bin. One bin is one observation, which removes the bias from sitting parked in one spot. Bins are updated in O(1) per sample and stored in `cell_bins`. Every estimate is recomputed from all bins, so it improves with data and cannot drift.

**Site grouping.** LTE sectors on one site share an eNB id (cell id shifted right 8 bits). Estimate one position per site from all of its sectors. Leave NR, WCDMA and GSM cells ungrouped for now.

**Model.** For a candidate site position `x` and bin `i` of sector `k` at position `p_i` with mean signal `s_i`:

```
d_i  = sqrt(|p_i - x|^2 + h^2)        h = 30 m, keeps the log finite
G_i  = min(12 * (angle_i / 65deg)^2, 20)   dB, 3GPP TR 36.814 sector pattern;
       angle_i = bearing(x -> p_i) minus the sector azimuth az_k
s_i  = P_k - 10 * n * log10(d_i) - G_i + noise,   noise ~ N(0, sigma^2), sigma about 8 dB
```

**Fit by profile-likelihood grid search.** For each candidate `x` on a grid:

- Set `az_k` to the circular mean of the bearings from `x` to sector `k`'s bins.
- With `y_i = s_i + G_i` and `u_i = -10 * log10(d_i)` the model is linear, so solve it in closed form with a shared exponent and one intercept per sector, then clamp `n` to [2, 5]:

```
n   = sum_k sum_i (u_i - mean_k(u)) * (y_i - mean_k(y)) / sum_k sum_i (u_i - mean_k(u))^2
P_k = mean_k(y) - n * mean_k(u)
r_i = y_i - P_k - n * u_i
```

- Score the candidate, adding a timing-advance ring for bins that have one (LTE: one-way distance of about `(TA + 0.5) * 78.1 m`, `sigma_TA` about 150 m; check the units):

```
score(x)  = sum_i huber(r_i / sigma) + sum_j ((d_j - dTA_j) / sigma_TA)^2
weight(x) = exp(-score(x) / 2)
```

Search a 48 by 48 grid over the bins' bounding box grown by the larger of 2 km and its diagonal, then refine twice by a factor of 4 around the two best modes.

**Output.** The posterior mean within the best mode, its covariance as an error radius, `n`, `P_k`, `az_k`, residual RMS, the bin count, the angular span of the bins seen from the estimate, and an `ambiguous` flag when a second separate mode holds more than 20% of the weight (the mirror-image case when all samples lie along one straight road). Publish nothing below 8 bins or 300 m of spatial extent. Recompute in the background once a site gains about 10% more bins.

Build the isotropic version first (`G_i = 0`), then add the sector term behind a switch and keep whichever does better in tests.

**Tests.** Generate synthetic sites with three sectors, path-loss exponents of 2.7 and 3.5, and shadowing of 4 and 8 dB, sampled along a straight road, an L-shaped route and a loop. Assert error bounds, that the straight road raises the ambiguity flag, that timing advance tightens the result, and that duplicate samples at one spot do not move the estimate.

Tell the user plainly what to expect: with signal strength alone in mountain terrain, errors of several hundred metres to kilometres until samples surround the site.

## Online tower data

- OpenCellID **[verified in its API docs]**: `GET https://opencellid.org/cell/get?key=KEY&mcc=..&mnc=..&lac=..&cellid=..&radio=LTE&format=json`. The response has `lat`, `lon`, `range`, `samples`, `changeable` and `averageSignalStrength`. The limit is 1,000 requests a day per key, some errors arrive with HTTP 200, and the data is CC BY-SA 4.0, so show attribution. The user enters their own key in settings. Cache each result in `towers`.
- beaconDB **[verified on its site]**: `https://api.beacondb.net/v1/geolocate`, compatible with the Ichnaea API, no key, and it asks clients to set an identifying User-Agent. It is coarse; treat it as an optional second provider.
- These databases hold crowd-sourced estimates, not surveyed coordinates. Label the option "Online (OpenCellID)", never "true location".
- Each tower has a location source of ESTIMATED or ONLINE, defaulting to ESTIMATED.
- The link formats are **[unverified]**: `https://opencellid.org/#zoom=16&lat=..&lon=..` and `https://www.cellmapper.net/map?MCC=..&MNC=..&type=LTE&latitude=..&longitude=..&zoom=15`. Verify them or make the link template a setting.

## Milestones

Finish each with a build, tests where they exist, a commit and a push, then a short report. The user is watching usage limits closely, so prefer the narrowest Gradle task, avoid subagents, do not re-derive what this brief already states, and stop at a milestone boundary instead of mid-way if the budget looks thin.

- **M0 Skeleton.** Install the SDK, create the wrapper and the three modules, and get `:core:test` and `:app:assembleDebug` passing. Set up a way to get the APK to the user: a GitHub Actions artifact if they enable Actions, otherwise a commit on a separate `apk` branch (the APK is small). Record the exact commands.
- **M1 Pure logic.** Data model, CSV import, GeoPackage encoding, tile math and the estimator in `:core`, with tests.
- **M2 Collector.** The ported collector service, storage and a plain status screen.
- **M3 OsmAnd connection.** Tower and recent-sample layers and the tower buttons.
- **M4 Heatmap.** Tile rendering, the push and the overlay switch.
- **M5 Probes.** Latency, loss and throughput probes, connectivity events and the dropout layer.
- **M6 Online data and notes.** Lookup, the source switch, the link, exports, and a README: how to install, what to grant in OsmAnd, what the app sends over the network, and the estimator math in plain language.

## Working with this user

- They run Gentoo, write Haskell and know GIS well, but the Android toolchain is new to them. Explain in plain prose, big picture first.
- When a choice is theirs, lay out the options and trade-offs and let them decide; do not just recommend.
- Check facts before stating them and say what is unverified.
- Pushing to your working branch is expected. Ask before anything else outward-facing.
