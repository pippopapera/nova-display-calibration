"""Color calculations used in the measurement campaign; no device access."""

from __future__ import annotations

import math

import numpy as np

WHITE_XY = (0.3127, 0.3290)

PRIMARY_XY = {"red": (0.6400, 0.3300), "green": (0.3000, 0.6000), "blue": (0.1500, 0.0600)}

WHITE_Y = 225.0

def fail(message: str) -> None:
    raise ValueError(message)

def number(value: object, context: str) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError):
        fail(f"{context}: expected numeric value")
    if not math.isfinite(result):
        fail(f"{context}: non-finite value")
    return result

def eotf(code: int, transfer: str) -> float:
    v = code / 255.0
    if transfer == "srgb":
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    if transfer == "gamma22":
        return v ** 2.2
    fail(f"unsupported transfer: {transfer}")

def xy_xyz_unit_y(xy: tuple[float, float]) -> np.ndarray:
    x, y = xy
    if y <= 0 or x < 0 or x + y > 1.0000001:
        fail(f"invalid chromaticity {xy}")
    return np.array([x / y, 1.0, (1.0 - x - y) / y])

def srgb_matrix() -> tuple[np.ndarray, np.ndarray]:
    primaries = np.column_stack([xy_xyz_unit_y(PRIMARY_XY[c]) for c in ("red", "green", "blue")])
    white_unit = xy_xyz_unit_y(WHITE_XY)
    scales = np.linalg.solve(primaries, white_unit)
    return primaries * scales[np.newaxis, :] * WHITE_Y, white_unit * WHITE_Y

def target_xyz(rgb: tuple[int, int, int], transfer: str, matrix: np.ndarray) -> np.ndarray:
    linear = np.array([eotf(v, transfer) for v in rgb])
    return matrix @ linear

def xyz_to_lab(xyz: np.ndarray, white: np.ndarray) -> np.ndarray:
    epsilon, kappa = 216.0 / 24389.0, 24389.0 / 27.0
    ratio = xyz / white
    f = np.where(ratio > epsilon, np.cbrt(ratio), (kappa * ratio + 16.0) / 116.0)
    return np.array([116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2])])

def ciede2000(lab1: np.ndarray, lab2: np.ndarray) -> float:
    """CIEDE2000 with kL=kC=kH=1; equations follow Sharma, Wu & Dalal (2005)."""
    l1, a1, b1 = map(float, lab1)
    l2, a2, b2 = map(float, lab2)
    c1, c2 = math.hypot(a1, b1), math.hypot(a2, b2)
    cb = (c1 + c2) / 2.0
    g = 0.5 * (1.0 - math.sqrt(cb**7 / (cb**7 + 25.0**7)))
    ap1, ap2 = (1 + g) * a1, (1 + g) * a2
    cp1, cp2 = math.hypot(ap1, b1), math.hypot(ap2, b2)

    def hue(ap: float, b: float) -> float:
        if ap == 0.0 and b == 0.0:
            return 0.0
        h = math.degrees(math.atan2(b, ap))
        return h + 360.0 if h < 0.0 else h

    hp1, hp2 = hue(ap1, b1), hue(ap2, b2)
    dl, dc = l2 - l1, cp2 - cp1
    if cp1 * cp2 == 0.0:
        dh_angle = 0.0
    else:
        raw = hp2 - hp1
        dh_angle = raw if abs(raw) <= 180 else raw - 360 if raw > 180 else raw + 360
    dh = 2.0 * math.sqrt(cp1 * cp2) * math.sin(math.radians(dh_angle / 2.0))
    lb, cbar = (l1 + l2) / 2.0, (cp1 + cp2) / 2.0
    if cp1 * cp2 == 0.0:
        hb = hp1 + hp2
    elif abs(hp1 - hp2) <= 180.0:
        hb = (hp1 + hp2) / 2.0
    elif hp1 + hp2 < 360.0:
        hb = (hp1 + hp2 + 360.0) / 2.0
    else:
        hb = (hp1 + hp2 - 360.0) / 2.0
    t = (1 - 0.17 * math.cos(math.radians(hb - 30))
         + 0.24 * math.cos(math.radians(2 * hb))
         + 0.32 * math.cos(math.radians(3 * hb + 6))
         - 0.20 * math.cos(math.radians(4 * hb - 63)))
    theta = 30 * math.exp(-(((hb - 275) / 25) ** 2))
    rc = 2 * math.sqrt(cbar**7 / (cbar**7 + 25.0**7))
    sl = 1 + 0.015 * (lb - 50) ** 2 / math.sqrt(20 + (lb - 50) ** 2)
    sc, sh = 1 + 0.045 * cbar, 1 + 0.015 * cbar * t
    rt = -math.sin(math.radians(2 * theta)) * rc
    dl, dc, dh = dl / sl, dc / sc, dh / sh
    return math.sqrt(max(0.0, dl * dl + dc * dc + dh * dh + rt * dc * dh))

def xy(xyz: np.ndarray) -> list[float] | None:
    total = float(np.sum(xyz))
    return None if total <= 0 else [float(xyz[0] / total), float(xyz[1] / total)]
