# Tower estimator: notes from M1

These are working notes on how the estimator in
`core/.../estimator/TowerEstimator.kt` differs from the plan in HANDOFF.md, and
why. The plain-language write-up for users belongs in the README (M6).

## What was built as planned

Bins of about 30 m per cell (zoom-20 map tiles, so a bin is also a heatmap
pixel), each one observation. LTE sectors grouped by eNB. The log-distance
model with h = 30 m, a closed-form exponent and intercepts for each candidate
position, a Huber loss with sigma = 8 dB, a timing-advance ring, a 48 x 48 grid
over the padded bounding box, two refinements by a factor of 4 around the two
best modes, the ambiguity flag at 20%, and nothing published below 8 bins or
300 m of extent.

## Where it differs, and why

**The sector pattern is on, and it is what makes the estimator work.** On
synthetic three-sector sites the isotropic model was off by several kilometres
on the loop route, because a real sector pattern puts up to 20 dB of
direction-dependent signal into the data, which an isotropic model can only
explain by moving the site. The handoff said to keep whichever variant does
better; the sector variant is better almost everywhere.

**Sector azimuths are fitted, not averaged.** The handoff suggested setting each
sector's azimuth to the circular mean of the bearings to its bins. That breaks
when a sector is also heard as a neighbour behind its back: its bins then
surround the candidate and their mean bearing is noise. Instead each candidate
scans the azimuth in 10 degree steps for the least squared residual (exponent
held at 3.2 for the scan) and refines with a parabola.

**One intercept per site, not per sector (switchable).** With an intercept per
sector, each sector's level floats freely, and a site seen only through its
serving cells is badly under-determined. Sectors of one site usually transmit
at the same power, so sharing the intercept ties them together. This cut the
worst cases from about 5 km to about 1 to 2.5 km. `sharedIntercept = false`
restores the plan's version.

**The posterior is tempered.** Shadowing is correlated over tens to hundreds of
metres, so neighbouring 30 m bins are not independent. Treating them as
independent made the error radius far too small (the truth was inside it in 1
to 3 runs of 10 in hard cases). The score is now divided by 3 before it becomes
a weight (`correlationBins`), which roughly doubles the radius in hard cases.

## What the synthetic tests show

Three sectors, correlated shadowing (50 m decorrelation distance), 80th
percentile error over 10 seeds, default settings, neighbour sectors reported:

| route       | n = 2.7, 4 dB | n = 3.5, 8 dB |
|-------------|---------------|---------------|
| loop        | ~50 m         | ~1 km         |
| L-shaped    | ~75 m         | ~1.7 km       |
| straight    | flagged ambiguous in most runs |  |

Run the full table (about 8 minutes) with

    ESTIMATOR_COMPARE=1 ./gradlew :core:test --tests '*compareModels*' -i

## Neighbours

The good numbers above assume the phone names the site's other sectors as
neighbours. Most phones give neighbours only a PCI and a channel. Since M2,
those readings are matched to cell ids by the neighbour resolver, and the
estimator weights each bin by the match confidence; see
[neighbour-resolution.md](neighbour-resolution.md).
