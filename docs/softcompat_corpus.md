# SOFTCOMPAT weapon-classification corpus

Reference artifact for `VehicleWeapons` score-based slot classification.
Not loaded at runtime — used to write / review self-check cases.

Source tree: `/home/wittgenstein/Progetti/SOFTCOMPAT`

## Namespace inventory

| Path | Kind | Weapon data? |
|------|------|----------------|
| `Frontline-Combat-Pack/` | Forge source + data (`fcp`) | Yes — weapons embedded in `data/fcp/sbw/vehicles/**/*.json` |
| `AshVehicle/` | Forge source + data (`ashvehicle`) | Yes — same shape |
| `MCSP/` | Forge source + data (`mcsp`) | Yes — same shape |
| `berezka_api-*.jar` | API jar | No vehicle weapons |
| `pmc_structures-*.jar` | Structure NBT | No |
| `russian_army_structures-*.jar` | Structure NBT | No |
| `us_army_structures-*.jar` | Structure NBT | No |

Weapons live inside vehicle JSON `Weapons` maps (no separate `sbw/guns/` packs).

## Placeholders (`isRealWeapon`, not role scoring)

FCP only — typically `{ Magazine:0, RPM:0, Damage:0 }`, often no usable projectile:

| Key | Notes |
|-----|--------|
| `Empty` | ~40 defs; seats that must never reach `classifySlot` |
| `Nothing` | ~38 defs; same |

Velocity defaults to 0 → `isRealWeapon` returns false. Not role-scoring fixtures.

## Doctrine anchors (human-decided expected roles)

| namespace | gun / name | projectile | shell | ammo | expected role | justification |
|----------|------------|------------|-------|------|---------------|---------------|
| mcsp | `weapon.mcsp.tos_1a_launcher` | `superbwarfare:medium_rocket` | — | `mcsp:mlrs_shells` | **SPECIAL** | Thermobaric rocket MLRS. Launched ordnance, not AP/HE/GS cannon path. `mlrs_shells` is naming noise. |
| fcp | `weapon.fcp.spg9` | `superbwarfare:small_cannon_shell` | — | `superbwarfare:rpg_rocket_standard` | **SPECIAL** | SPG-9 recoilless / PG-9 rocket HEAT. Projectile id lies (`*_cannon_shell`); ammo + weapon key `Rocket` are truth. Exact SPECIAL=CANNON score tie → `TIE_PRIORITY` → SPECIAL. |
| fcp | `weapon.fcp.grad` | `superbwarfare:medium_rocket` | `HE` | `superbwarfare:small_rocket` | **SPECIAL** | Dumb-fire rocket artillery. `ShellType: HE` is explosion flavor, not cannon identity. |
| fcp | `weapon.fcp.stryker_mortar` (also Hilux mortar) | `superbwarfare:mortar_shell` | `HE` | `superbwarfare:mortar_shell` | **SPECIAL** | Vehicle-tube mortar — launched HE, not cannon revolver. `mortar_shell` is one token (haystack single-role). |

## Other multi-signal / notable rows

| namespace | gun / name | projectile | shell | ammo | expected role | justification |
|----------|------------|------------|-------|------|---------------|---------------|
| fcp | `malyutka` / BMP ATGM | `fcp:malyutka` / wire-guide | — | varies | **SPECIAL** | Proper-noun hint; otherwise loses AT slot to low-pressure gun |
| fcp | `sidewinder` | `fcp:sidewinder` | — | sometimes AG missile ammo | **SPECIAL** | Proper-noun; ammo class name can disagree |
| fcp | `lock_on_hellfire` / hellfire | hellfire / related | — | — | **SPECIAL** | Proper-noun hint |
| mcsp | tank `Cannon` with AP/HE + TOW override | `cannon_shell` / missile on override | — | `125mm_*` / `tow_2` | **CANNON** (primary) / missile consumer is SPECIAL signal if selected | Multi-ammo slot; classifier sees first consumer |
| ashvehicle | various AA/AG missiles | `ashvehicle:*` | — | mixed AG/AA | **SPECIAL** | Missile names; watch ammo mismatch noise |

## Exact-tie survey

Under planned weights (SPECIAL needles 2.0, ShellType 1.0, CANNON shell\|cannon family +2 once, haystack single-role, unconditional ammoId):

- **Only exact SPECIAL=CANNON tie:** `weapon.fcp.spg9` → SPECIAL via `TIE_PRIORITY`.
- Grad / mortar win SPECIAL on score (not tiebreak).
- ashvehicle: no contested SPECIAL/CANNON exact ties found.
