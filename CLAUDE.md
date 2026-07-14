# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`tacz_sewv` ("SBW: Combined Arms") is a **Minecraft Forge 1.20.1 bridge mod** that lets NPC units from
**TACZ: Simple Enemy Mod (SEM)** crew and fight from vehicles of **Superb Warfare (SW/SBW)**. It owns no
entities or vehicles of its own — it wires two third-party mods together via Mixins, AI goals injected onto
SEM units, and a small network channel for player commands.

## Build & run

```
./gradlew build            # compile + reobfuscate jar into build/libs/
./gradlew runClient        # launch dev client
./gradlew runServer        # launch dev dedicated server (--nogui)
./gradlew runGameTestServer# run registered gametests, then exit
./gradlew --offline build  # gradle.properties sets org.gradle.daemon=false; offline avoids re-resolving
```

- **Java 17** toolchain, Forge `47.2.0`, Parchment mappings `2023.08.13-1.20.1` → **source uses Mojmap names**.
- There is **no test suite**; verification is done in-game (the README's spawn/mount flow) or via gametests.
- Dependencies (`libs/*.jar`: `superbwarfare`, `simpleenemymod`, `tacz`, `curios`, `geckolib`, `kotlin`) are
  **local flat-dir deobf jars** referenced as `blank:<name>:1.0`. They are not published artifacts — inspect
  their actual bytecode with `javap` against these jars when you need to confirm a signature (mod-declared
  fields/methods are `remap = false`; vanilla targets are remapped).

## Integration model — how the two mods are glued

Because this mod extends classes it doesn't own, almost every interaction point is either a **Mixin** or a
**Goal added to an SEM unit at runtime**. Key seams:

- **`entity/ai/`** — the AI. `VehicleAiGoals.addDriveGoals(unit)` is the single wiring point that installs the
  drive/gun/target goals onto any crewing unit. `DriveVehicleGoal` (ground/ship) and `DriveHelicopterGoal`
  (flight) are **both** registered on every crew and self-gate on the mounted hull's engine type, so exactly
  one runs. `VehicleTargeting` holds the shared "where should this crew go?" logic; `VehicleWeapons` holds the
  shared weapon-selection/firing logic. `BoardVehicleGoal` is **only** on player-owned `PmcUnitEntity` (it
  needs the network bridge); RU/US crews are placed into their vehicle directly at spawn.

- **`bridge/`** — interfaces mixed onto SEM unit classes to carry per-unit state the goals read:
  - `IVehicleBoarder` — a **transient** pending board order (targets an entity by network id, dropped on reload).
  - `IHelicopterPilot` — flight command state stored in the entity's **Forge `getPersistentData()`** (so a
    `LANDED` helicopter stays parked across world save/load). Default methods are the whole implementation;
    unit mixins only `implements`.

- **`mixin/`** — behavior injected into SW/SEM/vanilla. Targets worth knowing:
  - `MixinPmcUnitEntity`/`MixinRUunitEntity`/`MixinUSunitEntity` — implement the bridge interfaces + call `addDriveGoals`.
  - `MixinCombatEvent` — tail-injects SEM's `far_combat` event to (rarely, config-gated) spawn crewed tanks.
  - `MixinRangedGunAttackGoal` — cancels SEM's rifle-fire goal for units that control a vehicle weapon (so a
    driver/gunner works the turret instead of leaning out with a rifle); pure passengers keep it.
  - `MixinVehicleFireCooldown` — AI fire rate-limit + line-of-fire/smoke gating on SW's `VehicleEntity`.
  - `MixinVehicleInteractLock` — blocks players from interacting with a vehicle that has enemy RU/US passengers.
  - Mixin config: `src/main/resources/tacz_sewv.mixins.json` — **every new mixin must be listed here** (common
    vs `client` vs `server` section).

- **`network/`** — `NetworkHandler` `SimpleChannel` (bump `PROTOCOL_VERSION` on any wire-format change).
  Client keybinds (`client/BoardKeybind`, `client/HelicopterKeybind`) send board/dismount/heli-command packets;
  the goals run **server-side** off the state those packets set.

- **`config/SewvConfig`** — Forge COMMON config; spawn chances, faction **vehicle pools** (entity-id lists,
  one picked at random per spawn), crew AI tuning (fire cooldowns, target-scan cylinder, terrain look-ahead),
  and helicopter/flight parameters. `util/TankSpawner` spawns a pooled vehicle + a full faction crew (one unit
  per seat, seat 0 = driver). `command/SewvCommand` (`/sewv spawn ...`, op-only) is the manual spawn entry point.

## Runtime gotchas (verified against the dependency jars)

These bit us before and are easy to reintroduce:

- **SBW's AI fire path** goes through `VehicleEntity.canShoot(LivingEntity)` + `vehicleShoot(LivingEntity, UUID, Vec3)`.
  The `vehicleShoot(LivingEntity, String)` overload is a **separate** player-only implementation — an AI gate must
  hook `canShoot` or **both** `vehicleShoot` overloads (with explicit descriptors).
- **Decoy/smoke input is latched**: `decoyInputDown` is never auto-cleared by SBW, and a volley fires whenever the
  reload elapses while it's held. Any goal that raises it **must** also release it.
- **Goals tick every other game tick** by default (vanilla `serverAiStep`, which SEM's `AbstractUnit` does not
  override). Drive goals return `true` from `requiresUpdateEveryTick()`; scan/board/min-range goals stay at half
  rate on purpose (their tick constants are ~2× wall clock).
- **Vehicle weapon slots have no reliable type metadata** — `GunProp.GUN_TYPE` always defaults to `SPECIAL` for
  vehicle weapons, and `setWeaponIndex(seat, idx)` does **not** bounds-check. `VehicleWeapons` classifies each
  physical slot at selection time (shell type / projectile id / ammo consumer) rather than assuming slot order.

More detail lives in the project memory note `sbw-sem-runtime-facts`.
