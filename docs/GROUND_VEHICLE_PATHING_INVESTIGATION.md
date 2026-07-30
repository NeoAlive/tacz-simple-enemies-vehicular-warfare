# Ground vehicle pathing diagnosis

This stage adds **instrumentation only** for the approved investigation. No
pathing policy or movement fix is in this change.

## Active stack

Ground hull movement is:

`DriveVehicleGoal` -> `VehicleDriver` -> `PathFinder` -> `GroundVehicleNodeEvaluator`

Two separate systems can veto motion:

- **Path search**: `GroundVehicleNodeEvaluator`
- **Live steering / whiskers**: `GroundTerrainSensor` via shared `TerrainSensor`

That split is the main reason the ramp wiggle and water behavior may look
similar in-game while still coming from different code paths.

## What is custom vs inherited

`GroundVehicleNodeEvaluator` customizes:

- hull-sized footprint in `prepare(...)`
- node classification in both `getBlockPathType(...)` overrides
- shoreline rejection through `WATER_MARGIN`
- removal of vanilla's 26-neighbor hazard scan

`GroundVehicleNodeEvaluator` does **not** override vanilla's vertical neighbor
acceptance. Step-up / step-down / downward-open handling still comes from the
inherited `WalkNodeEvaluator` methods, especially `getNeighbors(...)` and
`findAcceptedNode(...)`.

Practical implication: a 2-block descent can be legal or illegal for A*
independently of what the live whisker sensor thinks is clear in front of the
hull.

## Instrumentation added

### Log channels

- `[sewv-diag][pathing]` for path reuse, path drops, blocked headings, and
  steer-target changes
- `[sewv-diag][water]` for evaluator-side water-margin decisions

### `VehicleDriver`

Added logs for:

- repath start / result
- path-node advance
- current steer target changing from one node to another or to direct-dest
- full whisker block causing `currentPath = null`
- facing-clear failure causing `holdAtEdge(...)`
- stuck recovery forcing an unstick episode

These logs answer whether a wiggle is:

- **path churn**: repeated repaths or route-node flips
- **steering churn**: same route or no route, but local avoidance keeps
  turning / blocking

### `GroundTerrainSensor`

Added a throttled blocked-heading log with:

- reason: `hull`, `fluid`, or `step`
- sampled trace entries such as `drop@x,z`, `wall@x,z step=n`, `hazard@x,z`
- `floor` plus `floorKind` (`HAZARD` / `NO_SURFACE` / `Y`) so the
  Integer.MAX_VALUE sentinel is not misread as "no floor"
- `climbHeight`
- `amphibious`
- `inWater`
- `waterHazard`

This is the core ramp/water discriminator. It shows whether a descent lip or
shoreline is being rejected as:

- a real water/lava hazard
- a wall / step-up
- an allied/wrecked hull obstruction

### `GroundVehicleNodeEvaluator`

Added logs for:

- per-search `prepare(...)` state, including `inWater` and hull footprint
- first `WATER_MARGIN` candidate-node rejection per search

This is the path-search side of the water diagnosis. It answers whether A*
itself is refusing shoreline nodes before steering ever gets a chance.

### Shoreline mismatch (`[sewv-diag][water] shoreCenter`)

Once-per-tick (or on HAZARD flicker) hull-center snapshot:

- `rawX` / `rawZ` / `floorX` / `floorZ` / `blockY` — continuous pose vs the
  whole-block column the probe actually samples
- `fluidBaseY` / `fluidBaseY-1` — raw fluid label (`WATER` / `LAVA` / `EMPTY`)
  at the early fluid check cells, before the final probe classification
- `probeFloor` / `floorKind` — final center result (`HAZARD` /
  `NO_SURFACE` / `Y`)
- `effectiveFloor` / `effectiveKind` — the center value actually fed into
  `headingClear()` after the shoreline debounce
- `isInWater` vs `probeHazard` — SBW wet-state vs probe hazard, with
  `mismatch=true` when they disagree

This is the log that should confirm whether a riverbank faceplant is the
center column grazing water while `isInWater()` is still false, which of
`floorX/Z` vs `blockY` flips when `floorKind` flaps, and whether the debounce
is successfully holding `effectiveKind` on the last stable non-hazard value.

### Bank-lip reverse recovery (`[sewv-diag][water] bankLip reverse`)

When the hull has a **post-debounce** dry bank-lip center hazard
(`effectiveFloor=HAZARD`, `isInWater=false`, not amphibious), the full whisker
fan is blocked, and there is no positional progress for
`BANK_LIP_BLOCK_TICKS` (40), `VehicleDriver` reverses off the lip instead of
pivoting forever in `holdAtEdge`.

Look for:

- `bankLip reverse START` — recovery armed
- `bankLip reverse END` — reverse finished, pathing resumes
- `bankLip reverse ABORT wet` — SBW wet-state took over (escape hatch), recovery cancelled

This must not fire for genuinely wet / amphibious crossings.

### Fan summary (`[sewv-diag][pathing] fan BLOCKED`)

When every whisker offset fails in one call, ground sensing logs a single
summary instead of only the first failed heading:

- `offsets=7 reasons=[hull,hull,...] hull=5/7 hullDominated=true|false`
- `rule=hullCount*2>n` — **hull-dominated** means strictly more than half of
  the failed offsets are `hull` (for 7 offsets: need ≥4 hull rejects)

This is what makes a full-fan pile-up distinguishable from a single frozen
preferred bearing.

### Hull-fan reverse recovery (`[sewv-diag][pathing] hullFan reverse`)

Parallel to bank-lip, not merged. When the fan is hull-dominated
(`hullCount*2>n`), bank-lip does **not** own the tick, and there is no
positional progress for `HULL_FAN_BLOCK_TICKS` (40), the driver probes retreat
bearings in order `-desired`, then ±25°. First `headingClear` success arms a
24-tick reverse facing opposite that retreat (so translation follows the
cleared bearing). If all three retreats fail, it does **not** reverse.

Look for:

- `hullFan reverse START ... retreat=... face=... rule=hullCount*2>n`
- `hullFan reverse END`
- `hullFan reverse SKIP allRetreatBlocked ... probed=-desired,+25,-25`

## Expected signatures

### Ramp wiggle is path churn

Look for frequent:

- `repath START`
- `repath RESULT`
- `steerTarget pathNode ...` switching between different nodes

That would mean the actual route is moving around underneath the hull.

### Ramp wiggle is steering churn

Look for:

- stable or absent `steerTarget`
- repeated `bearing BLOCKED`
- repeated `forward BLOCKED`
- `headingClear BLOCKED ... trace=...`

That means the route is stable enough, but local avoidance keeps refusing the
same lip or edge.

### Water failure is evaluator-side

Look for:

- `[water] prepare ... inWater=false`
- `[water] waterMargin BLOCKED ...`
- little or no corresponding `[pathing] headingClear BLOCKED ... reason=fluid`

That means A* is already refusing shoreline nodes.

### Water failure is whisker-side

Look for:

- successful repaths or direct-dest steering near shore
- `[pathing] headingClear BLOCKED ... reason=fluid`
- repeated `forward BLOCKED` / `bearing BLOCKED`

That means the live sensor is the layer preventing or oscillating at the edge.

### Water escape hatch firing too early

Look for a suspicious sequence:

- `[water] prepare ... inWater=true` even though the hull is only grazing the bank
- `[pathing] headingClear BLOCKED ... waterHazard=false`

That would mean the already-wet escape hatch is letting a non-amphibious hull
stop treating water as hazardous too early.

## Repro matrix

Minimum cases to run before any fix:

1. Tracked tank on a clean 2-block descent
2. Wheeled ground vehicle on the same 2-block descent
3. Non-amphibious ground hull on the shoreline / riverside case that matches
   the reported bad-water behavior
4. Buoyant / amphibious ground hull, if available, to verify the intentional
   escape path separately from non-amphibious exclusion
5. Map 2 shoreline / ship-cover case once available

For each case, capture logs with:

- terrain avoidance `ON`
- terrain avoidance `OFF`

For water, also compare:

- static pond / shoreline
- flowing river or other dynamic-water geometry, if that is the reproducer

## What this stage should decide

Before any fix, the logs should let us answer:

1. Does the ramp wiggle come from path-node changes or local steering refusal?
2. Does the bad-water behavior come from path search, live whiskers, early
   wet-state escape, or shoreline disagreement between those systems?
3. Is water policy itself wrong, or is the current policy just being applied
   incorrectly at runtime?
