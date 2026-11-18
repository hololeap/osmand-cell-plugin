# Neighbour cells without a cell id

## The problem

The tower estimator works far better when it sees a site's other sectors, not
only the one serving the phone: the back lobes are what pin a site down. But
Android usually reports a neighbour with only its radio, channel and
physical-layer id (PCI on LTE and NR, PSC on WCDMA, BSIC on GSM), not its cell
id. Tower Collector drops such neighbours as invalid. This app keeps them.

Physical ids are not unique: LTE has 504 PCIs and NR 1008, and operators reuse
them every few kilometres. So matching a PCI to a cell is a guess, and the
guess is stored as one, with its odds.

## How a reading is matched

Every sample whose cell id the phone reported, and which has a channel and a
PCI, is a *sighting*: "cell C uses physical id P and was heard here". Sightings
are kept per physical id on a grid of zoom-16 tiles (about 400 m at 47°N).

For a reading of P at position x, every cell seen with P within 15 km is a
candidate, weighted by

    w = exp(-d² / 2s²),   s = 2 km

where d is the distance from x to the nearest tile the cell was seen in. One
more candidate stands for "a cell using P that was never seen identified", with
the weight a seen cell would have at 4 km. The confidence of the best candidate
is its share of the total. Some reference points:

| situation                                    | confidence |
|----------------------------------------------|------------|
| one candidate, seen right here               | 0.88       |
| one candidate, seen 2 km away                | 0.82       |
| one candidate, seen 4 km away                | 0.50       |
| two candidates, equally close                | 0.47 each  |
| no candidate within 15 km                    | UNRESOLVED |

At 0.8 or above a match is RESOLVED, below it AMBIGUOUS.

## What is stored (columns of `samples`)

| column          | meaning |
|-----------------|---------|
| `id_source`     | REPORTED (the phone gave the id), RESOLVED, AMBIGUOUS, or UNRESOLVED |
| `id_confidence` | 1 for REPORTED, the match probability otherwise, 0 for UNRESOLVED |
| `cell`, `site`  | the reported id, or the best guess; NULL when UNRESOLVED |
| `phys`          | the physical id that was matched, e.g. `LTE-232-1-ch6300-pci123` |
| `resolve_n`     | how many candidate cells there were |
| `resolve_alts`  | every candidate, best first: `cellId:probability:distanceM;...` |
| `pci`, `channel`| the raw values, as reported |

So an ambiguous reading keeps its best guess *and* every alternative, and in
QGIS a filter on `id_source` or `id_confidence` shows or hides them.

Matches are derived data. As more cells are seen identified, an UNRESOLVED
reading can become RESOLVED, or a match can move to a different cell. The app's
"Re-match neighbour cells" button re-runs every non-reported reading against
everything learned so far and rebuilds the bins. `phys`, `pci` and `channel`
never change.

## How the estimator uses them

- REPORTED samples count fully.
- RESOLVED samples count with their confidence as a weight. A bin's weight is
  the mean confidence of its samples (`cell_bins.w_sum / n`), and each bin's
  term in the fit is multiplied by it.
- AMBIGUOUS samples are left out by default (`CellBins(includeAmbiguous = true)`
  puts them in, with their lower weight).
- UNRESOLVED readings have no cell and are never used.

## What the synthetic tests show

Three-sector site, loop route, steep path loss (n = 3.5) and 8 dB of correlated
shadowing, ten seeds, a phone that names only its serving cell:

- serving cells only: 80th-percentile error about 2.5 km
- plus neighbours matched by PCI: about 0.96 km, with every reading resolved

And two sites 8 km apart that reuse the same PCIs: a reading next to either
site resolves to it, and one halfway between is flagged AMBIGUOUS with both
candidates kept.

## Known limits

- The match assumes a neighbour belongs to the serving network (its MCC and
  MNC come from the SIM's registered network when the reading carries none).
- Sightings come from where a cell was *serving*, which is usually nearer the
  site than where it is heard as a neighbour. The 2 km scale absorbs that, but
  a cell only ever heard as a neighbour can never be matched.
- PCI planning often gives a site's sectors consecutive PCIs (PCI div 3 equal
  on LTE). That is a convention, not a rule, and the resolver does not use it.
