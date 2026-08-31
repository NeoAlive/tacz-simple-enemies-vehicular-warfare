import re
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "src/main/java/com/neoalive/tacz_sewv/config/ConfigRegistryBootstrap.java"
text = path.read_text(encoding="utf-8")

pattern = re.compile(
    r"(\(\) -> (?:ClientConfig|SewvConfig)\.[\w\[\]]+(?:\[[^\]]+\])?\.get\(\)),\s*"
    r"v -> ((?:ClientConfig|SewvConfig)\.[\w\[\]]+(?:\[[^\]]+\])?)\.set\(v\)\)",
    re.MULTILINE,
)

def repl(m):
    getter = m.group(1)[6:-6]  # strip "() -> " and ".get()"
    setter = m.group(2)
    return f"{getter}, {setter}::set)"

new_text, count = pattern.subn(repl, text)
path.write_text(new_text, encoding="utf-8")
print(f"Replaced {count} config value lambdas")
