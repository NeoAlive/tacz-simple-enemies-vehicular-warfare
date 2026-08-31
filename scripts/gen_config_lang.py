import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
bootstrap = (root / "src/main/java/com/neoalive/tacz_sewv/config/ConfigRegistryBootstrap.java").read_text(encoding="utf-8")
lang_path = root / "src/main/resources/assets/tacz_sewv/lang/en_us.json"

keys = set()
for line in bootstrap.splitlines():
    m = re.search(r'ConfigScope\.\w+,\s*"([^"]+)",\s*"([^"]+)"', line)
    if m:
        keys.add(m.group(2))
for m in re.finditer(r'"(doctrine\.[^"]+)"', bootstrap):
    keys.add(m.group(1))

cats = set(re.findall(r'ConfigScope\.\w+,\s*"([a-z_]+)"', bootstrap))


def parse_config_comments(*paths: Path) -> dict[str, str]:
    comments: dict[str, str] = {}
    pattern = re.compile(
        r"=\s*builder\s*\.comment\((.*?)\)\s*\.define(?:InRange|List)?\(\"([^\"]+)\"",
        re.DOTALL,
    )
    for path in paths:
        for m in pattern.finditer(path.read_text(encoding="utf-8")):
            parts = re.findall(r'"([^"]*)"', m.group(1))
            text = " ".join(p.strip() for p in parts if p.strip())
            if text:
                comments[m.group(2)] = text
    return comments


CONFIG_COMMENTS = parse_config_comments(
    root / "src/main/java/com/neoalive/tacz_sewv/config/SewvConfig.java",
    root / "src/main/java/com/neoalive/tacz_sewv/config/ClientConfig.java",
)

GAMERULE_LABELS = {
    "world_rules.ambient_spawns": "Ambient world spawns",
    "world_rules.ru_spawns": "RU vehicle spawns",
    "world_rules.us_spawns": "US vehicle spawns",
    "world_rules.pmc_ambient_spawns": "PMC ambient spawns",
    "world_rules.tanks_in_events": "Tanks in combat events",
    "world_rules.far_event_spawns": "Far event spawns",
    "world_rules.can_mobs_damage_vehicles": "Mobs damage vehicles",
    "world_rules.invasion_overrides": "Invasion overrides",
}

GAMERULE_TOOLTIPS = {
    "world_rules.ambient_spawns": (
        "Master switch for all automatic spawns: SEM events, village garrisons, berezka structure "
        "crews, and SEWV world events. Spawn eggs and /sewv spawn are not affected."
    ),
    "world_rules.ru_spawns": "Allow SEWV to spawn RU vehicles from events and structures when ambient spawns are on.",
    "world_rules.us_spawns": "Allow SEWV to spawn US vehicles from events and structures when ambient spawns are on.",
    "world_rules.pmc_ambient_spawns": (
        "Allow ownerless PMC crews from berezka structures and some events. "
        "Player-recruited PMC units are unaffected."
    ),
    "world_rules.tanks_in_events": "Add rare RU/US tanks when Simple Enemy Mod's far_combat event fires.",
    "world_rules.far_event_spawns": "Multiply SEM event spawn distance by 2.5 for wider, more spread-out fights.",
    "world_rules.can_mobs_damage_vehicles": "Let mob melee use SEWV's score-based damage against vehicle hulls.",
    "world_rules.invasion_overrides": "Extermination compat: pod avoidance and related invasion tweaks.",
}

DOCTRINE_AXIS = {
    "aggression": "Aggression",
    "discipline": "Discipline",
    "support": "Support reliance",
    "recon": "Recon emphasis",
    "indirect": "Indirect fire",
    "mobility": "Mobility",
    "survival": "Survival",
    "coordination": "Coordination",
}


def sanitize_prose(text: str) -> str:
    text = text.replace(" — ", ", ")
    text = text.replace("—", ", ")
    text = re.sub(r",\s*,", ",", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def humanize(k: str) -> str:
    if k in GAMERULE_LABELS:
        return GAMERULE_LABELS[k]
    if k.startswith("doctrine."):
        parts = k.split(".")
        if len(parts) == 3:
            faction = parts[1].upper()
            axis = DOCTRINE_AXIS.get(parts[2], parts[2].replace("_", " ").title())
            return f"{faction} {axis}"
    s = k.replace("world_rules.", "")
    s = re.sub(r"([a-z])([A-Z])", r"\1 \2", s)
    s = s.replace("_", " ")
    s = re.sub(r"\bru\b", "RU", s, flags=re.I)
    s = re.sub(r"\bus\b", "US", s, flags=re.I)
    s = re.sub(r"\bpmc\b", "PMC", s, flags=re.I)
    s = re.sub(r"\bnvg\b", "NVG", s, flags=re.I)
    s = re.sub(r"\bai\b", "AI", s, flags=re.I)
    s = re.sub(r"\bat\b", "AT", s, flags=re.I)
    s = re.sub(r"\btow\b", "TOW", s, flags=re.I)
    s = re.sub(r"\bcas\b", "CAS", s, flags=re.I)
    s = re.sub(r"\bhud\b", "HUD", s, flags=re.I)
    s = re.sub(r"\bifv\b", "IFV", s, flags=re.I)
    s = re.sub(r"\btacz\b", "TaCZ", s, flags=re.I)
    return s[:1].upper() + s[1:] if s else k


def tooltip_for(k: str) -> str:
    if k in GAMERULE_TOOLTIPS:
        return GAMERULE_TOOLTIPS[k]
    if k.startswith("doctrine."):
        parts = k.split(".")
        if len(parts) == 3:
            faction = parts[1].upper()
            axis = DOCTRINE_AXIS.get(parts[2], parts[2])
            return f"{faction} bias on {axis} (-{10} cautious to +{10} aggressive). Used by the AI doctrine scorer."
    base = k.replace("world_rules.", "")
    if base in CONFIG_COMMENTS:
        return sanitize_prose(CONFIG_COMMENTS[base])
    return sanitize_prose(humanize(k) + ".")


data = json.loads(lang_path.read_text(encoding="utf-8"))
preserved = {k: v for k, v in data.items() if ".option." in k or k.startswith("gui.tacz_sewv.config.reset")}

data["gui.tacz_sewv.config.title"] = "Combined Arms Configuration"
data["gui.tacz_sewv.config.confirm"] = "Confirm"
data["gui.tacz_sewv.config.scope.client"] = "Client"
data["gui.tacz_sewv.config.scope.server"] = "Server"
data["message.tacz_sewv.config.saved"] = "Settings saved."
data["message.tacz_sewv.config.no_permission"] = "You do not have permission to change server settings."
data["message.tacz_sewv.config.invalid"] = "Invalid value for %s."

for c in sorted(cats):
    label = humanize(c)
    if c == "crew_ai":
        label = "Crew AI"
    data[f"gui.tacz_sewv.config.category.{c}"] = label

for k in sorted(keys):
    data[f"config.tacz_sewv.{k}"] = humanize(k)
    data[f"config.tacz_sewv.{k}.tooltip"] = tooltip_for(k)

data["config.tacz_sewv.player_doctrine_info.tooltip"] = (
    "Per-player doctrine is set via the Doctrine Ledger or TDT Identity tab."
)

data["gamerule.sewvAmbientSpawns"] = "SEWV ambient world spawns"
data["gamerule.sewvAmbientSpawns.description"] = GAMERULE_TOOLTIPS["world_rules.ambient_spawns"]

data.update(preserved)

for k in list(data.keys()):
    if k.startswith("config.tacz_sewv.") and (k.endswith(".tooltip") or ".option." in k):
        if isinstance(data[k], str):
            data[k] = sanitize_prose(data[k])

lang_path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(f"Updated {lang_path} with {len(keys)} config keys ({len(CONFIG_COMMENTS)} tooltips from config comments)")
