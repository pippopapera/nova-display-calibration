"""Generate calibration-only deltas from private, locally obtained QDCM files.

Factory and reconstructed firmware files must never be committed. The delta
copies unchanged bytes from the device and contains only changed numeric values.
Python standard library only.
"""
import argparse
import copy
import difflib
import hashlib
import json
from pathlib import Path
import re
import struct

FACTORY_SHA256 = "26a56de100c2a27ecac2fe7ed4d30ba5bcb935865c7470775dcfda744d0173af"
PROFILE_SHA256 = {
    "gamma22": "c6e20079ef58220960e9511d5641bc02f1bcfb0f90c5bb0a842e5b2ed00c9afd",
    "srgb": "8be527253edcc712006e73a26cbadd4f3aa1ade3bd8d2d00ae38587b3f393b25",
}
PANEL = "il97680a_amoled_panel_without_DSC"
FIELDS = ("PostBlendGC", "PostBlendIGC", "PostBlendPCC", "PostBlendPa")
LINES = (36, 39, 40, 41, 76, 79, 80, 81)
TOKEN = re.compile(r'"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null|\s+|.', re.S)


def swapped(data):
    data = bytearray(data)
    for i in range(len(data) % 2, len(data) - 1, 2):
        data[i], data[i + 1] = data[i + 1], data[i]
    return bytes(data)


def decode(line):
    return swapped(bytes.fromhex(line.split(': "', 1)[1].rsplit('"', 1)[0]))


def measured_profile(base, profile):
    """Regenerate the private full output in memory from published measured data."""
    folder = Path(__file__).resolve().parents[1] / "sourceprofiles"
    config = json.loads((folder / "profile-manifest.json").read_text(encoding="utf8"))["profiles"][profile]
    igc_bytes = (folder / config["igc_asset"]).read_bytes()
    gc_bytes = (folder / config["gc_asset"]).read_bytes()
    assert hashlib.sha256(igc_bytes).hexdigest() == config["igc_sha256"]
    assert hashlib.sha256(gc_bytes).hexdigest() == config["gc_sha256"]
    igc_values, gc_values = struct.unpack("<771I", igc_bytes), struct.unpack("<3075I", gc_bytes)
    assert gc_values[:3] == (0, 1024, 0)
    result = copy.deepcopy(base)
    mode = result[PANEL]["NATIVE"]
    values = {field: json.loads(swapped(bytes.fromhex(mode[field]))) for field in FIELDS}
    igc, gc, pcc, pa = (values[field] for field in ("PostBlendIGC", "PostBlendGC", "PostBlendPCC", "PostBlendPa"))
    igc.update(enable=True, ditherEnable=False, ditherStrength=0)
    gc.update(enable=True); pcc.update(enable=True)
    for c, channel in enumerate("RGB"):
        igc["lut" + channel] = list(igc_values[c * 257:(c + 1) * 257])
        gc["lut" + channel] = list(gc_values[3 + c * 1024:3 + (c + 1) * 1024])
        for key in pcc[channel]:
            pcc[channel][key] = 0.0
        for d, input_channel in enumerate("rgb"):
            pcc[channel][input_channel] = config["surfaceflinger_matrix"][c * 3 + d]
    for key in ("cont", "hue", "sat", "satThresh", "val"):
        pa[key] = 0.0
    for field, value in values.items():
        encoded = swapped(json.dumps(value, separators=(",", ":"), ensure_ascii=True).encode("ascii")).hex().upper()
        mode[field] = encoded
        result[PANEL]["sRGB"][field] = encoded
    return (json.dumps(result, indent=2) + "\n").replace("\n", "\r\n").encode("ascii")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--factory", required=True, type=Path)
    parser.add_argument("--profiles", type=Path, help="Optional private directory containing frozen *-system.json; otherwise use the published measured LUTs/matrix")
    parser.add_argument("--output", default=Path(__file__).resolve().parents[1] / "sourceprofiles", type=Path)
    args = parser.parse_args()
    original = args.factory.read_bytes()
    assert hashlib.sha256(original).hexdigest() == FACTORY_SHA256, "Unexpected factory file"
    base = json.loads(original)
    normalized = "\n".join(" " * ((len(line) - len(line.lstrip(" "))) // 2) + line.lstrip(" ")
                           for line in original.decode("ascii").splitlines()) + "\n"
    assert normalized == json.dumps(base, indent=2) + "\n"
    args.output.mkdir(parents=True, exist_ok=True)
    for profile, digest in PROFILE_SHA256.items():
        target_bytes = ((args.profiles / (profile + "-system.json")).read_bytes() if args.profiles
                        else measured_profile(base, profile))
        assert hashlib.sha256(target_bytes).hexdigest() == digest
        target = json.loads(target_bytes)
        restored = json.loads(target_bytes)
        for mode in ("NATIVE", "sRGB"):
            for field in FIELDS:
                restored[PANEL][mode][field] = base[PANEL][mode][field]
        assert restored == base, "Changes outside the calibrated SDR fields"
        before = normalized.splitlines()
        after = target_bytes.decode("ascii").splitlines()
        assert len(before) == len(after) == 87
        assert tuple(i for i, (a, b) in enumerate(zip(before, after)) if a != b) == LINES
        patch = bytearray(b"NOVAPCH1" + struct.pack(">I", len(LINES)))
        literal_bytes = 0
        operations = 0
        for index in LINES:
            a, b = decode(before[index]), decode(after[index])
            a_tokens, b_tokens = TOKEN.findall(a.decode("ascii")), TOKEN.findall(b.decode("ascii"))
            offsets = [0]
            for token in a_tokens:
                offsets.append(offsets[-1] + len(token))
            entries = []
            check = bytearray()
            for kind, i, j, k, l in difflib.SequenceMatcher(None, a_tokens, b_tokens, autojunk=False).get_opcodes():
                if kind == "equal":
                    start, length = offsets[i], offsets[j] - offsets[i]
                    entries.append(b"\x00" + struct.pack(">II", start, length))
                    check.extend(a[start:start + length])
                elif kind in ("replace", "insert"):
                    value = "".join(b_tokens[k:l]).encode("ascii")
                    # No OEM strings, identifiers, unrelated tables, or copyright text.
                    assert re.fullmatch(rb"[0-9eE+.,:\[\]{}\-\s]*(?:(?:true|false|null)[0-9eE+.,:\[\]{}\-\s]*)*", value), value[:80]
                    entries.append(b"\x01" + struct.pack(">I", len(value)) + value)
                    literal_bytes += len(value)
                    check.extend(value)
            assert bytes(check) == b
            patch.extend(struct.pack(">II", index, len(entries)))
            patch.extend(b"".join(entries))
            operations += len(entries)
        path = args.output / (profile + ".npatch")
        path.write_bytes(patch)
        print(json.dumps({"profile": profile, "patch_bytes": len(patch), "literal_bytes": literal_bytes,
                          "operations": operations, "patch_sha256": hashlib.sha256(patch).hexdigest(),
                          "reconstructed_sha256": digest}))


if __name__ == "__main__":
    main()
