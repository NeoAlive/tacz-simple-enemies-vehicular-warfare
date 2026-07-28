# Helicopter combat — firing run (shipped)

The INGRESS→ATTACK→BREAK→REPOSITION state machine is implemented in
`DriveHelicopterGoal`. Guided AG still uses the v1 high-standoff path.

## Discriminator (`HeliArmament`)

- Pilot-seat only for fire. Seat-1 cannon (mi_28) remains `UNREACHABLE` until
  `TurretGunnerGoal` heli exclusion is revisited.
- Air-only proxy: `SeekWeaponInfo.minTargetHeight > 0`, else ammo `anti_air` /
  projectile `ru_9m336`/`igla`.
- Vs armor: prefer ground-usable **guided** (DriverMissile), latching that slot
  even while reloading — do not fall through to ready rockets against armor.
- Vs soft: prefer ready **unguided**.
- Empty ground-usable set → no dive (cruise hold).

### SPECIAL ≠ guided

`GunProp.GUN_TYPE` / the vehicle-weapon role classifier's `SPECIAL` does **not**
mean guided. An `ah_6` Rocket is SPECIAL but unguided (`SmallRocketEntity`).
Guidance detection must use `VehicleMissileAim.modeOfSelected` / projectile
class (or `modeOfProjectileId` for headless checks) — never `WEAPON_SPECIAL`.
This bit us once; do not reintroduce the shortcut.

## Coverage gap (phase-2 vs airframes)

| Hull | Pilot (seat 0) | Seat 1 | Firing-run coverage |
|------|----------------|--------|---------------------|
| `ah_6` | Rockets (unguided) | — | Fully served by the unguided run |
| `mi_28` | Rocket / DriverMissile / DriverAA | Cannon (+ passenger missiles) | Rockets + guided AG only; **cannon unreachable** |

`mi_28` seat-1 cannon is **SBW-native**, not pilot-controlled: SBW's per-seat
loop can still fire it for a passenger, but `TurretGunnerGoal` **excludes
helicopters** (landing-approach destabilization regression). Pilot doctrine in
`HeliArmament` / `DriveHelicopterGoal` only drives seat 0. Re-enabling heli
seat-1 gunners is out of scope and historically regressive.

## Engine notes (still true)

- `hoverMode` OFF in ATTACK/BREAK (auto-level kills depression).
- Yaw stick rolls — BREAK climbs while turning with capped yaw.
- `forwardInputDown` is collective, not plane throttle.
- Whiskers force BREAK; do not disable them on the pass.
- ATTACK does **not** call `flyToward` (velocity-error crab yaw fails the
  fire-assist cone for hull-fixed rockets). Collective + `aimNoseOnly` only.
  INGRESS / transit / REPOSITION keep `flyToward` unchanged.

## Unguided rocket CONE misfires (accepted residual)

Hull-fixed rockets (slot 0) fail `tryAiFireAssist` with `NOFIRE gate=CONE`
when the airframe is not nose-on-target. Partially addressed by the ATTACK-only
`aimNoseOnly` change (no crab `flyToward` on the pass). Residual CONE misfires
are accepted.

**Tension / future work:** locking the nose on the target through the whole
pass can induce an orbit instead of a clean straight run. A future fix should
nose-track through the pass **without** turning the pass into a circle around
the target.

## Open question — `NOFIRE gate=CANNOT_SHOOT` on AI helis

Unresolved: is a high `CANNOT_SHOOT` rate **reload cadence** (temporary,
expected — `GunData.canShoot` false mid-reload / between shots) or **empty
magazines** (permanent until restock — a provisioning bug)?

Where to look:

- Gate stack: `VehicleEntity.canShoot` → `MixinVehicleFireCooldown` (CEASE_FIRE,
  AI cooldown, LOS/smoke) then SBW ammo/reload on the selected slot.
- AI heli ammo vs player hull: RU/US crews use issued / virtual ammo paths
  (`IIssuedAmmo`, `GunData.virtualAmmo`, faction infinite-ammo config on spawn
  via `TankSpawner`); PMC/player hulls reload from inventory / creative box.
  Compare whether a freshly spawned AI `mi_28`/`ah_6` actually has backup ammo
  for Rocket / DriverMissile after the first volley, or only a loaded magazine.
- Debug: `heliCombatDebug` `NOFIRE gate=CANNOT_SHOOT` lines — if they stop
  forever after N shots with `PICK` still on that slot, suspect empty; if they
  alternate with `FIRE` on a fixed interval, suspect reload.

## Debug

- `heliCombatDebug` (**SERVER** config `tacz_sewv-server.toml` under the world,
  not the stale `config/tacz_sewv-common.toml`): phase changes, `ABANDON` /
  `HANDOFF` / `PICK` / `FIRE` / `NOFIRE`.
- Hull NBT `sewv:heli_run_phase` (also read by mounted-lock custody).
- Client hover label via `heliShowRunPhase` + `PacketHeliRunPhase`.
