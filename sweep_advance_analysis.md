# Sweep & Advance: Command Logic & Battle Implications

## Overview

**Sweep & Advance** is a faction-battle command that directs PMC units (mounted and on-foot) to methodically clear and claim a rectangular terrain area. It combines three independent systems:

1. **Area coverage** — mounted/on-foot units sweep assigned sectors of a chunk rectangle
2. **Contact dynamics** — threat assessment and quiet-counting before territory claim
3. **OpenPac integration** — claims chunks through the OpenPac mod when quiet threshold is met

The command is **one-time** (not endless like Patrol), **contested** (territory surrounded by enemy presence extends the operation), and **asynchronous** (each unit works its own sector while the claim process runs server-side).

---

## Initialization: PacketSweepAndAdvance

### Entry Point
Player selects units on the Xaero map and draws a rectangular selection. The packet carries:
- **Chunk AABB** — `(left, top, right, bottom)` in chunk coordinates
- **Selected PMC unit IDs** — which of the player's units should execute the sweep
- **Dimension** — world location

### Unit Classification (line 100–123)
Units are split into two execution paths:

**MOUNTED** (Drivers of ground vehicles)
```
Condition: isVehicle() ∧ getFirstPassenger() == unit 
         ∧ ¬isHelicopterHull() ∧ ¬isPlaneHull()
```
- Drivers of tanks, APCs, trucks, IFVs (any ground hull)
- **Excluded:** Gunners/passengers, helicopter pilots, plane pilots

**ON-FOOT** (Walking infantry)
```
Condition: ¬isPassenger()
```
- Units with no vehicle at all
- Rejected if riding as gunner, passenger, or pilot

### Execution Split (line 135–149)

**Mounted crews:** Assigned **sector-based sweep**
```java
PatrolSupport.beginSweep(pmc, rect.left, rect.top, rect.right, rect.bottom,
                         i, mounted.size());
// i = sector index, mounted.size() = total sectors
```
- Each vehicle gets a fraction of the rectangle
- Indexed `0..N-1` where N = count of mounted crews
- Rectangle is **horizontally divided** across sectors

**On-foot infantry:** Assigned **free-roam sweep**
```java
((ISweepInfantry) pmc).sewv$setInfantrySweep(
    rect.left, rect.top, rect.right, rect.bottom);
```
- No sector assignment; wanders entire rectangle
- Uses `SweepInfantryGoal` (priority 1, flag MOVE)

### Prerequisites
- **OpenPac must be loaded** — else returns with RED feedback
- **Selection area ≤ `SWEEP_MAX_CHUNK_AREA`** — default 100 chunks
- **All units must be owned by the commanding player**
- **Dimension must match player's current world**

---

## Mounted Sweep Execution: Sector Zig-Zag Pattern

### Mode: MODE_SWEEP (IVehiclePatrol.MODE_SWEEP = 3)

Each mounted crew is stored with:
- **Sweep rectangle** — `(left, top, right, bottom)` in chunk coordinates
- **Sector index** — its assigned slice (0..sectorCount-1)
- **Sector count** — total crews sweeping
- **Radius** — derived from rectangle size for mutual-assist range

### Rectangle-to-Sector Mapping (PatrolSupport.rectSweepPoint, line 415–428)

```
Chunk Space:   left ──────────────── right
               ↓                       ↓
             [0.0]                   [1.0]  (normalized)
               ├─────┼─────┼─────┤         (sectors)
        Sector 0     1     2     3

Per-sector U coordinate:
  u0 = sectorWidth × sector
  u = u0 + sectorWidth × ((step + 0.5) / SWEEP_STEPS)
  
  x = left + (right - left) × u
```

**Zig-zag legs** (5 steps per sector):
- **Even steps** (0, 2, 4): Deep zone, `v = 0.85` → 85% toward bottom
- **Odd steps** (1, 3): Shallow zone, `v = 0.35` → 35% toward bottom

Visual pattern for 2 sectors over 5 steps:

```
        Step 0 (deep)     Step 1 (shallow)   Step 2 (deep)
Sector 0: ●                                   ●
         [.............................................................]
Sector 1:                                                 ●

         (continues alternating north/south across each sector's width)
```

### Waypoint Selection (line 391–401)

Each leg attempts to find **drivable ground**:
```java
BlockPos next = rectSweepPoint(level, task, sector, sectorCount, step, random);
```

If drivable (via `GroundVehicleNodeEvaluator`):
- Set as current waypoint
- Schedule deadline: `gameTime + 1200 ticks` (60s)
- Return to drive goal

If **no drivable ground**, skip to next step (avoid stalling on water/cliff).

### Contact Alert (line 370–373)

When a mounted crew **acquires a target within the sweep rectangle**:
```java
alertGroup(pmc, task, target);
```

- **Does NOT abandon the sweep** (loads bearing: DriveVehicleGoal.holdsCourseThroughContact)
- Shares target with **all same-owner units** in area (range = `areaReach()`)
- Weapon selection + fire assistance run normally
- **Only movement stays on the sector path**

### Sector Exhaustion (line 404–410)

Once all 5 steps are done (or skipped for lacking ground):
```java
clear(pmc);  // ends area task only
return null; // crew goes idle/FREE_FIRE
```

**Critical:** This clears the **area task** (movement destination), **not** the sweep operation membership. The crew is still an `assignee` in `SweepAdvancement` for claim purposes.

---

## On-Foot Sweep Execution: Wandering Infantry

### Mode: ISweepInfantry

On-foot units use a simpler, fully-persistent approach:
- **Chunk AABB** stored as `(left, top, right, bottom)` integers
- **Current waypoint** stored as packed BlockPos
- **Deadline** for waypoint rotation (game time)

### SweepInfantryGoal (Priority 1, Flag MOVE)

```java
canUse() → !isPassenger() && hasInfantrySweep()
```

Each tick:
1. Check if current waypoint is within **16-block arrival radius** (144 blocks²)
2. If arrived OR deadline passed:
   - Pick random walkable column inside rectangle (16 attempts)
   - Set new waypoint
   - Schedule rotation deadline: `gameTime + 100 ticks`
3. Move to waypoint via pathfinding

**Unlike mounted:** No zig-zag pattern, no step counter. Just continuous random patrolling within bounds.

---

## Quiet-Based Claim: SweepAdvancement Server Loop

### State Machine: Three Paths

`SweepAdvancement` runs **one per commanding player** (stored in `ACTIVE` map by UUID).

Each path has different claim outcomes:

```
BEGIN (PacketSweepAndAdvance)
  ↓
  [Every 20 ticks, one loop per operation]
  ├─ CHEAP PATH (always runs)
  │  ├─ Prune dead/unowned assignees
  │  ├─ Check: do any assignees hold targets in-rect?
  │  │  YES → reset quietSeconds=0, continue (no claim yet)
  │  │  NO  → quietSeconds++
  │  └─ Quiet threshold met? → continue to expensive path
  │
  └─ EXPENSIVE PATH (once per quiet threshold)
     ├─ Full AABB scan for hostile AbstractUnit / crewed VehicleEntity
     ├─ Hostile found? → reset quiet=0, continue (no claim)
     └─ No hostiles → **COMPLETE_CLAIM_PATH** (enter once per operation)
        ├─ Trim ally-owned edges (diplomatic filter)
        ├─ Iterate each chunk in trimmed rect
        ├─ Call OpenPac.claim(partyOwner, chunk_x, chunk_z)
        └─ End operation
```

### Cheap Path: Assignee Pruning (line 265–269)

```java
op.assigneeIds.removeIf(id -> {
    if (!(level.getEntity(id) instanceof PmcUnitEntity pmc) || !pmc.isAlive()) 
        return true;
    return !pmc.isOwnedBy(player);
});
```

**Removes:** Dead units, units with changed owner.
**Keeps:** Any unit that walked away from the sweep (still owned).

### Assignee Target Check (line 272–294)

```
For each assignee:
  ├─ Is target null or dead? → skip
  ├─ Is target outside rect? → skip (frees quiet to advance)
  ├─ Is target instanceof AbstractUnit? → check hostility
  ├─ Hostility: !isNonHostile(probe, unit)
  │  (includes faction + SEM friendly-flag + creative/spectator shield)
  └─ Hostile? → LOG, return true (contact active)
```

**Key:** Only **AbstractUnit** inside the rect counts. Vanilla mobs, players, uncrewed vehicles are ignored.

### Quiet Counting (line 240–244)

```
quietSeconds < quietNeed → continue (not ready yet)
quietSeconds >= quietNeed → proceed to defensive scan
```

Default `SWEEP_QUIET_SECONDS = 60` (config). Ticks every 20 game ticks = **1 tick per real second**.

### Expensive Defensive Scan (line 310–344)

Runs **once per quiet threshold**, logs every hit.

**AABB from chunk rect:**
```
minX = left << 4        maxX = (right << 4) + 16
minZ = top << 4         maxZ = (bottom << 4) + 16
minY = levelMinBuild    maxY = levelMaxBuild
```

**Contestant check:**
```
AbstractUnit & alive & !isOwnAssignee & !isMedic & isHostile
VehicleEntity carrying such crew
```

**IsHostile definition:**
```java
!VehicleTargeting.isNonHostile(probe, unit)
  ├─ SEM faction hostile (RU/US vs others if not friendly-toggled)
  ├─ Diplomacy check (own PMC is always friendly)
  ├─ Creative/spectator immune
  └─ Medic-role refusal (engineer working on vehicles not engaged)
```

If ANY contestant found → reset quiet, **no claim attempt yet**.

### Ally Edge Trim (line 385–401)

```
Before claiming, shrink each edge if it borders ALLY-owned chunks.

Loop until stable:
  ├─ left--  if chunk at (left-1, ...) is ALLY-owned
  ├─ right++ if chunk at (right+1, ...) is ALLY-owned
  ├─ top++   if chunk at (..., top-1) is ALLY-owned
  └─ bottom-- if chunk at (..., bottom+1) is ALLY-owned
```

**Ally definition:**
```java
diplomacy.relation(cmdFaction, neighborFaction) == ALLY
  ∧ neighborFaction != cmdFaction  // same faction = own claim, not trimmed
```

**Outcome:** Rectangle shrinks inward until no ALLY border remains. If rect collapses → abort with YELLOW feedback "empty after trim".

### Claim Execution (line 409–436)

```
partyOwner = player's party owner (or player if solo)

For each chunk (cx, cz) in trimmed rect:
  ├─ Check existing owner
  ├─ If owned by distinct ALLY → skip, blocked++
  ├─ Else → call OpenPac.claim(partyOwner, cx, cz)
  │         verify readback (chunk now owned by partyOwner)
  │         ├─ YES → claimed++
  │         └─ NO → blocked++
  └─ attempted++

Feedback:
  ├─ blocked == 0 → GREEN (perfect claim)
  └─ blocked > 0  → YELLOW (partial, explain why)
```

---

## Contact Dynamics & Territory Logic

### Four Key Behaviors

**1. Assignees hold course through contact**
```
DriveVehicleGoal.tick():
  If holdsCourseThroughContact(pmc) && contactAcquired:
    ├─ selectWeaponForTarget()
    ├─ fireAssistIfSpecial()
    ├─ steer toward area destination (NOT chase-flee)
    └─ continue sector pattern
```
*(Mechanics in CLAUDE.md "Cruise is also the one task a contact does not take the wheel away from")*

**2. Mutual-assist reaches across entire rectangle**
```
assistPos(pmc, vehicle):
  radius = areaReach(patrolRadius)
  If MODE_SWEEP || MODE_SEARCH:
    Scan entire area for wounded ally
    Return reinforcement point (within cooldown)
```
*(Blocks scrum formation, cooldown prevents single contact collapsing whole sweep)*

**3. Out-of-area targets are refused**
```
PatrolSupport.refusesOutOfAreaTarget(pmc, target):
  If in sweep && target outside rect:
    return true → VehicleTargeting never sets the lock
```
*(Prevents crews chasing horizon zombies)*

**4. Quiet resets instantly on in-rect hostiles**
```
anyAssigneeHasTarget(op, level):
  If any assignee holds target ∈ rect && isHostile:
    → quietSeconds = 0
    → busy re-scanning every tick
    (expensive path never runs)
```

---

## Failure & Cancellation Paths

| Trigger | Path | Outcome |
|---------|------|---------|
| OpenPac not loaded | Reject at START | RED feedback, no claim attempted |
| Rect too large | Reject at START | RED feedback + size limit |
| Wrong dimension | Reject at START | Dimension mismatch error |
| All assignees die mid-sweep | Cheap path prune | Abandon (non-claim), YELLOW feedback |
| Mounted crew dismounts | Contact via goal mixin | `clearSweepMembership()` removes from op |
| On-foot crew boards vehicle | `SweepInfantryGoal.canUse()` false | Stops wander, but operation continues |
| Surrounded by hostiles | Expensive path scan | Quiet resets, claim deferred indefinitely |
| Player logs out | Event handler `onLogout` | Operation deleted (non-claim) |
| Player issues SEM order | `MixinPacketIssueOrder` | Area task cleared only, assignee stays |

---

## Multi-Faction Implications

### RU/US Cannot Initiate Sweep
Sweep is **PMC-only** (requires `PacketSweepAndAdvance` → `PmcUnitEntity` + player order).
- RU/US units have no order queue for player commands
- No xaero map integration for RU/US
- **Implication:** Only the player-commanded faction can opportunistically claim

### Ally Trimming is Diplomatic
```
If RU-player A sweeps a rect bordering RU-player B's claims:
  ├─ diplomacy.relation("ru", "ru") = ALLY
  ├─ But: B's faction NAME must be distinct
  └─ If same faction (both "ru") → NOT trimmed, overwrite allowed
```

**Critical:** Party membership, not faction, determines "same owner".
```java
UUID partyOwner = OpenPac.partyOwnerId(...) or player.UUID
```

### Enemy Faction Inside Rect Stalls Claim
```
If US-controlled squad caught mid-rect while RU sweeps:
  ├─ Quiet resets every tick
  ├─ Defensive scan keeps hitting
  ├─ Quiet climbs again only after squad leaves/dies
  └─ Claim waits indefinitely (or timeout if player gives up)
```

**Implication:** Sweep is **vulnerable to interference**. A small enemy squad can indefinitely block claim by occupying the rectangle.

### On-Foot Infantry Sweep Has No Faction
Infantry wander freely; **they do not block or enable claim by themselves**. Quiet depends on **assignee targets** (what they're shooting), not location.
- Mounted crew in rect with target → quiet resets
- Infantry in rect with target → quiet resets
- Infantry in rect with no target → neutral

---

## Performance & Tuning

### Cheap-Path Cost (every 20 ticks)
- Prune dead assignees: O(assigneeCount)
- Per-assignee target check: O(assigneeCount)
- **Total:** O(n) for n assignments, ~5–10 units typical

### Expensive-Path Cost (once per 60s of quiet)
- Full AABB scan: `level.getEntitiesOfClass(AbstractUnit, AABB)` + passenger iteration
- Logs every contestant found
- **Total:** O(living units in rect), typically 5–30 units in contested area

### Config Knobs
```
SWEEP_QUIET_SECONDS        → time to wait after last contact (default 60)
SWEEP_MAX_CHUNK_AREA       → max rectangle size in chunks (default 100)
```

Increasing `SWEEP_QUIET_SECONDS` makes claim take longer; decreasing makes enemy presence more disruptive.

---

## Summary: Battle Narrative

1. **Commander issues Sweep & Advance**, selecting territory chunk rectangle + own units
2. **Mounted crews divide rectangle into N sectors**, each zig-zagging their slice
3. **On-foot infantry roams entire rectangle**, opportunistically fighting enemies
4. **All units hold course on contact** — weapon selection runs normally, but movement stays area-focused
5. **Server counts seconds of quiet** — no hostile AbstractUnit in-rect in assignee targeting
6. **After N seconds, defensive scan runs** — AABB check for lingering threats (expensive)
7. **If clear, trim ally borders** — shrink edges if they touch ALLY claims (diplomacy-aware)
8. **Claim each chunk to party owner** — via OpenPac, with feedback on successes/blocks
9. **Result:** Territory under faction control, defendable against re-invasion

The command is **best used against isolated objectives** (FOBs, undefended structures) where quiet can accrue, and **worst against entrenched enemies** (contested area, respawn bases) where hostile presence perpetually resets the timer.
