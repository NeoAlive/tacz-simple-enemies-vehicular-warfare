# SBW: Combined Arms

A mil-sim bridge and fortifications pack for **Minecraft Forge 1.20.1**, built on
[Superb Warfare](https://github.com/Mercurows/SuperbWarfare) and
[TACZ: Simple Enemy Mod](https://github.com/NekoYuni/Simple-Enemy-Mod-Public).

## Description

NPCs from Simple Enemy Mod (PMC / RU / US) crew and fight from Superb Warfare vehicles, man
mortars and TOWs, and dig into field fortifications:

- **Vehicles**: AI drivers, gunners, helicopters, planes, and ships; player board / dismount /
  heli commands via the Tactical Data Terminal and keybinds.
- **Fortifications**: trenches, foxholes, sandbag fighting positions, and emplacement ammo pads;
  ENTRENCHED orders (and RU/US auto-seek) put infantry into cells and seats.
- **Support**: handheld radio fire missions, doctrine ledger, map markers (Xaero World Map,
  optional), invasion / capture points, and faction spawn events.

## Configuration

Most gameplay and client settings are edited in-game via **Combined Arms Configuration**:

- Press **ESC** → **Combined Arms Configuration** at the top of the pause menu, or run `/sewv configui`.
- **Client** tab: map markers, faction overlay colours, order feedback (saved locally, no restart).
- **Server** tab: operators only (permission level 2): events, AI tuning, invasion, and the rest.
  Changes apply live and are written back to `config/tacz_sewv-server.toml`.
- **Shortcuts** category: opens the vehicle pool, misc cues/armor, and target-priority editors.
- **World rules** category: live toggles for key gamerules (`sewvAmbientSpawns`, `sewvRuSpawns`, `sewvTanksInEvents`, …).

Forge `client` / `server` config files remain for modpack defaults and dev-only keys (debug logging,
Komodo render fix, armor list data edited via the misc editor). Delete a config file to pick up code
defaults on next launch.

**WARNING: The project is in its early stages. If you encounter bugs during your playthrough,
PLEASE report them on GitHub.**

## Dependencies

Hard requirements: Forge 1.20.1, Superb Warfare, TACZ, Simple Enemy Mod (see `mods.toml` for
version ranges). Soft / optional: Xaero's World Map, berezka_api, Open Parties and Claims,
Configured, and selected vehicle addons.

## Credits

This project depends on the following projects:

- [TACZ: Simple Enemy Mod](https://github.com/NekoYuni/Simple-Enemy-Mod-Public) by NekoYunii
  - Licensed under the GNU General Public License v3.0.

- [Superb Warfare](https://github.com/Mercurows/SuperbWarfare) by Mercurows
  - Code licensed under the GNU General Public License v3.0.
  - Assets licensed separately under the CC BY-NC-SA 3.0 License.

- [Default642172](https://www.planetminecraft.com/member/default642172/) and his outstanding skins, used in this project. 

This project was also developed with reference to and inspiration from
[SBW: Superb Recruits](https://github.com/LogicalMaximus/SuperbRecruitz) by **LogicalMaximus**,
which is licensed under the MIT License. While this project does not include any assets from
Superb Recruits, portions of its code structure and implementation approaches were used as a
reference during development. The original project and its contributors retain all rights to
their work. A copy of the MIT License can be found in the original repository.
