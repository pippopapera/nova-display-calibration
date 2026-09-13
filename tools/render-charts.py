"""Render the public English charts from the frozen analysis data; no image editing."""
import json
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
data = ROOT / "docs/data"
images = ROOT / "docs/images"
plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 11, "axes.spines.top": False,
                     "axes.spines.right": False, "axes.titleweight": "bold", "figure.facecolor": "white"})
boundary = json.loads((data / "boundary-comparison.json").read_text(encoding="utf8"))
fig, ax = plt.subplots(figsize=(9, 3.8), layout="constrained")
positions = np.arange(2)
for i, (key, label, fill) in enumerate((("factory", "Original", "#d8783e"), ("native_gamma22", "Gamma 2.2", "#168a83"))):
    stats = boundary[key]["color_deltaE00"]
    bars = ax.bar(positions + (i - .5) * .32, [stats["mean"], stats["max"]], .32, label=label, color=fill)
    ax.bar_label(bars, fmt="%.3f", padding=4)
ax.set_xticks(positions, ["Mean error", "Maximum error"])
ax.set(ylabel="CIEDE2000 (ΔE00), lower is better", ylim=(0, 10.6), title="Same 36 gamut-boundary colors · one Nova unit")
ax.legend(frameon=False, loc="upper left")
ax.grid(axis="y", alpha=.15); ax.set_axisbelow(True)
fig.text(.5, -.02, "sRGB / D65 / Gamma 2.2 target · normalized to each measured white Y · generic OLED CCSS", ha="center", fontsize=9, color="#53606b")
fig.savefig(images / "color-error.png", dpi=180, bbox_inches="tight"); plt.close(fig)

results = json.loads((data / "brightness-analysis.json").read_text(encoding="utf8"))["results"]
sweep = [item for item in results if item["slider"].get("percent") is not None]
x = [item["slider"]["percent"] for item in sweep]
fig, axes = plt.subplots(2, 2, figsize=(11, 7.2), layout="constrained")
fig.suptitle("Gamma 2.2 across brightness settings", fontsize=18, weight="bold")
axes[0, 0].plot(x, [r["white_Y"] for r in sweep], "o-", color="#137dab")
axes[0, 0].axhspan(200, 250, color="#168a83", alpha=.12, label="200–250 nit reference range")
axes[0, 0].set(ylabel="Full-screen white luminance (cd/m²)", ylim=(0, 660))
axes[0, 0].legend(fontsize=9)
axes[0, 1].plot(x, [r["gamma_fit_gray_32_to_224"] for r in sweep], "o-", color="#168a83")
axes[0, 1].axhline(2.2, linestyle="--", color="#657383", label="Target 2.2")
axes[0, 1].set(ylabel="Fitted gamma, gray codes 32–224", ylim=(2.1, 2.3)); axes[0, 1].legend(fontsize=9)
axes[1, 0].plot(x, [r["colors_deltaE00"]["mean"] for r in sweep], "o-", label="Mean, 41 colors", color="#168a83")
axes[1, 0].plot(x, [r["colors_deltaE00"]["max"] for r in sweep], "o-", label="Maximum", color="#d8783e")
axes[1, 0].set(ylabel="Color error (ΔE00)", ylim=(0, 3.3)); axes[1, 0].legend(fontsize=9)
axes[1, 1].plot(x, [r["gray_rmse_percentage_points"] for r in sweep], "o-", color="#7866a7")
axes[1, 1].set(ylabel="Tone-curve RMSE (percentage points)", ylim=(0, .5))
for ax in axes.flat:
    ax.set(xlabel="Brightness slider (%)", xticks=list(range(0, 101, 20)))
    ax.grid(alpha=.18)
fig.supxlabel("One unit · generic OLED CCSS · 60 full-screen readings per setting", fontsize=10)
fig.savefig(images / "brightness-validation.png", dpi=160, bbox_inches="tight"); plt.close(fig)
print("Rendered 2 English charts from the published analysis data.")
