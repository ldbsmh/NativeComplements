"""Check signal-merge and headset-route APIs in the archived PixelOS hosts."""
import hashlib
from pathlib import Path
import zipfile
import verify_signal_target as dex
from verify_navbar_target import ARCHIVES, dex_views

ROOT = Path(__file__).resolve().parents[1]
CONTAINER = "Lcom/android/systemui/statusbar/phone/StatusIconContainer;"
DISPLAYABLE = "Lcom/android/systemui/statusbar/StatusIconDisplayable;"
AUDIO = "Landroid/media/AudioManager;"
LISTENER = "Landroid/media/AudioManager$OnDevicesForAttributesChangedListener;"
METHODS = {
    CONTAINER: {"onMeasure": "(II)V", "onLayout": "(ZIIII)V"},
    DISPLAYABLE: {"getSlot": "()Ljava/lang/String;", "isIconVisible": "()Z", "isIconBlocked": "()Z"},
    AUDIO: {
        "getAudioDevicesForAttributes": "(Landroid/media/AudioAttributes;)Ljava/util/List;",
        "addOnDevicesForAttributesChangedListener": "(Landroid/media/AudioAttributes;Ljava/util/concurrent/Executor;" + LISTENER + ")V",
        "removeOnDevicesForAttributesChangedListener": "(" + LISTENER + ")V",
    },
}


def check():
    artifacts = ARCHIVES["current API37"]
    missing = [relative for relative, _ in artifacts if not (ROOT / relative).is_file()]
    if missing:
        print("SKIP: archived PixelOS files missing: " + ", ".join(missing))
        return
    dex.TARGETS = set(METHODS)
    classes, owners = {}, {}
    for relative, expected in artifacts:
        data = (ROOT / relative).read_bytes()
        assert hashlib.sha256(data).hexdigest() == expected, relative
        with zipfile.ZipFile(ROOT / relative) as archive:
            for name in archive.namelist():
                if not name.startswith("classes") or not name.endswith(".dex") or "/" in name:
                    continue
                for view in dex_views(archive.read(name)):
                    classes.update(view.classes)
                    owners.update({name: view for name in view.classes})
    for owner, methods in METHODS.items():
        for name, signature in methods.items():
            assert any(m["name"] == name and m["descriptor"] == signature and not m["access_flags"] & 8
                       for m in classes[owner]["methods"]), (owner, name, signature)
    assert any(f["name"] == "mIgnoredSlots" and f["type"] == "Ljava/util/Set;"
               for f in classes[CONTAINER]["fields"])
    native_measure = owners[CONTAINER].instructions(CONTAINER, "onMeasure", "(II)V")
    assert any(i.get("field", {}).get("name") == "mIgnoredSlots" for i in native_measure)
    print("PASS: archived PixelOS signal-container filtering and Bluetooth media-route APIs; device visuals/routes unverified")


if __name__ == "__main__":
    check()
