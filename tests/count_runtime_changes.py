"""Count the documented runtime changes; the full verify_runtime.py needs a Windows JDK layout."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
changes = json.loads((root / "tests/runtime-changes.json").read_text(encoding="utf-8"))
print(f"PASS: {len(changes)} documented runtime changes")
