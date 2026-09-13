"""Verify the compiled APK and its asset allowlist. Android SDK aapt2 required."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--sdk", default=os.environ.get("ANDROID_SDK_ROOT"), required=not os.environ.get("ANDROID_SDK_ROOT"))
parser.add_argument("--build-tools", default="35.0.0")
parser.add_argument("--apk", type=Path, default=ROOT / "build/release/nova-calibration.apk")
args = parser.parse_args()
aapt = Path(args.sdk) / "build-tools" / args.build_tools / ("aapt2.exe" if os.name == "nt" else "aapt2")


def run(*values):
    return subprocess.check_output([str(aapt), *map(str, values)], encoding="utf8")


manifest = run("dump", "xmltree", args.apk, "--file", "AndroidManifest.xml")
permissions = run("dump", "permissions", args.apk)
assert "uses-permission" not in permissions and "uses-permission" not in manifest
for flag in ("debuggable", "allowBackup", "usesCleartextTraffic"):
    assert re.search(r"android:" + flag + r"[^\n]*=false", manifest), flag
assert manifest.count("E: activity (") == 1
assert all("E: " + component + " (" not in manifest for component in ("service", "receiver", "provider"))
assets = {"gamma22.npatch", "srgb.npatch", "profile-manifest.json", "nova-system-profile.sh"}
hashes = {}
with zipfile.ZipFile(args.apk) as apk:
    names = apk.namelist()
    actual = {name.removeprefix("assets/profiles/") for name in names
              if name.startswith("assets/profiles/") and not name.endswith("/")}
    assert actual == assets, actual
    assert not any(name.startswith("lib/") for name in names)
    for name in assets:
        value = apk.read("assets/profiles/" + name)
        assert value == (ROOT / "sourceprofiles" / name).read_bytes()
        assert b"Proprietary and Confidential" not in value
        hashes[name] = hashlib.sha256(value).hexdigest()
    dex = apk.read("classes.dex")
    def u32(offset):
        return struct.unpack_from("<I", dex, offset)[0]
    strings = []
    for i in range(u32(56)):
        offset = u32(u32(60) + i * 4)
        while dex[offset] & 128:
            offset += 1
        offset += 1
        strings.append(dex[offset:dex.index(b"\0", offset)].decode("utf8", errors="replace"))
    types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]
    classes = [types[u32(u32(100) + i * 32)] for i in range(u32(96))]
    prefixes = tuple("Llocal/nova/diagnostic/" + name for name in ("AeroTheme", "CalibrationActivity", "ProfilePayload", "R"))
    assert all(name.startswith(prefixes) for name in classes)
    assert all(not any(token in value for token in ("ProfileWakeService", "DrmProbe", "ColorHalProbe", "com/sun/jna", "MainActivity", "PatchActivity")) for value in strings)
    assert not any(value.startswith(("http://", "https://")) for value in strings)
base = {node.get("name"): node.text for node in ET.parse(ROOT / "res/values/strings.xml").getroot()}
locales = {}
for locale in ("it", "de", "es", "fr", "pt"):
    values = {node.get("name"): node.text for node in ET.parse(ROOT / f"res/values-{locale}/strings.xml").getroot()}
    assert set(values) == set(base)
    for name, text in values.items():
        assert re.findall(r"%\d+\$[sd]", text) == re.findall(r"%\d+\$[sd]", base[name])
        assert "\ufffd" not in text
    locales[locale] = len(values)
report = {"apk": args.apk.name, "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
          "bytes": args.apk.stat().st_size, "permissions": [], "debuggable": False,
          "backup": False, "services_receivers_providers": 0, "native_libraries": 0,
          "exported_components": ["CalibrationActivity launcher"], "classes": classes,
          "assets": hashes, "default_language": "en", "translations": locales,
          "factory_configuration_bundled": False}
output = ROOT / "docs/verification"
output.mkdir(parents=True, exist_ok=True)
(output / "apk-audit.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf8")
(output / "compiled-manifest.txt").write_text(manifest, encoding="utf8")
print(json.dumps({key: value for key, value in report.items() if key != "classes"}, indent=2))
