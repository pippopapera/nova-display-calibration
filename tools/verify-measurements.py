"""Recompute published color errors from the 921 exported RGB/XYZ readings.

Requires numpy. Uses the same published equations and normalization as the
original campaign; this is reproducibility, not an independent instrument test.
"""
import collections
import csv
import json
from pathlib import Path
import numpy as np
import color_math as color

ROOT = Path(__file__).resolve().parents[1]
groups = collections.defaultdict(list)
with (ROOT / "docs/data/readings.csv").open(encoding="utf8", newline="") as stream:
    rows = list(csv.DictReader(stream))
assert len(rows) == 921
for row in rows:
    rgb = tuple(int(row[k]) for k in "RGB")
    xyz = np.array([float(row[k]) for k in "XYZ"])
    assert all(0 <= code <= 255 for code in rgb)
    assert np.isfinite(xyz).all() and (xyz >= 0).all()
    groups[row["dataset"]].append((row["patch"], rgb, xyz))
assert len(groups) == 17
brightness = json.loads((ROOT / "docs/data/brightness-analysis.json").read_text(encoding="utf8"))["results"]
transfer = json.loads((ROOT / "docs/data/transfer-comparison.json").read_text(encoding="utf8"))
boundary = json.loads((ROOT / "docs/data/boundary-comparison.json").read_text(encoding="utf8"))
targets = [(item, "gamma22", "colors_deltaE00") for item in brightness]
targets += [(item, name, "colors_deltaE00") for name, item in transfer.items()]
targets += [(item, "gamma22", "color_deltaE00") for item in boundary.values()]
checked = []
for report, tone, stats_key in targets:
    name = report["dataset"].removeprefix("measurements/")
    samples = groups[name]
    assert len(samples) in (39, 60)
    xyz_by_name = {n: xyz for n, rgb, xyz in samples}
    white_y = float((xyz_by_name["white-start"][1] + xyz_by_name["white-end"][1]) / 2)
    matrix, white = color.srgb_matrix()
    matrix *= white_y / color.WHITE_Y
    white *= white_y / color.WHITE_Y
    errors = []
    for patch, rgb, xyz in samples:
        if len(set(rgb)) == 1:
            continue
        target = color.target_xyz(rgb, tone, matrix)
        errors.append(color.ciede2000(color.xyz_to_lab(xyz, white), color.xyz_to_lab(target, white)))
    expected = report[stats_key]
    assert len(errors) == expected["count"]
    assert abs(float(np.mean(errors)) - expected["mean"]) < 1e-10, name
    assert abs(float(np.max(errors)) - expected["max"]) < 1e-10, name
    assert abs(white_y - report["white_Y"]) < 1e-10
    checked.append({"dataset": name, "colors": len(errors), "mean_deltaE00": float(np.mean(errors)),
                    "max_deltaE00": float(np.max(errors)), "matches_published": True})
final = groups["native-final-white-20260913"]
summary = json.loads((ROOT / "docs/data/final-white.json").read_text(encoding="utf8"))
assert len(final) == 3 and np.allclose(np.mean([xyz for _, _, xyz in final], axis=0), summary["mean_XYZ"], rtol=0, atol=1e-10)
output = {"readings": len(rows), "datasets": len(groups), "color_statistics": checked,
          "final_white_matches": True, "all_passed": True}
(ROOT / "docs/verification/measurement-reproduction.json").write_text(json.dumps(output, indent=2) + "\n", encoding="utf8")
print("PASS: 921 readings, 17 datasets, all published color-error statistics and final white reproduced.")
