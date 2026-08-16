import csv
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np


ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "results" / "network"
BANDWIDTHS = [("100mbit", "100 Mbps"), ("1gbit", "1 Gbps"), ("10gbit", "10 Gbps")]
SCHEMES = ["CoCo-ORAM", "ConcurORAM", "MVP-ORAM", "MVP-ORAM-S"]
COLORS = ["#1f77b4", "#d62728", "#2ca02c", "#ff7f0e"]
HATCHES = ["...", "xx", "...", "//"]

plt.rcParams.update({
    "font.family": "serif",
    "font.serif": ["Times New Roman", "Times", "DejaVu Serif"],
    "mathtext.fontset": "stix",
    "pdf.fonttype": 42,
    "axes.labelsize": 12,
    "xtick.labelsize": 11,
    "ytick.labelsize": 11,
    "legend.fontsize": 10,
})


def load():
    values = {}
    for slug, _ in BANDWIDTHS:
        path = RESULTS / f"WAN_{slug}_results.csv"
        with path.open(newline="", encoding="utf-8") as handle:
            for row in csv.DictReader(handle):
                if row["status"] == "OK":
                    values[(int(row["clients"]), slug, row["scheme"])] = float(row["throughputOpsPerSec"])
    return values


def draw(values, clients):
    x = np.arange(len(BANDWIDTHS))
    width = 0.19
    fig, ax = plt.subplots(figsize=(4.1, 2.65))
    for index, scheme in enumerate(SCHEMES):
        heights = [values[(clients, slug, scheme)] for slug, _ in BANDWIDTHS]
        ax.bar(
            x + (index - 1.5) * width,
            heights,
            width,
            facecolor="white",
            edgecolor=COLORS[index],
            hatch=HATCHES[index],
            linewidth=0.8,
            label=scheme,
        )
    ax.set_xticks(x, [label for _, label in BANDWIDTHS])
    ax.set_xlabel("Network bandwidth")
    ax.set_ylabel("Throughput (ops/s)")
    ax.set_yscale("log")
    ax.grid(True, axis="y", linestyle="dashdot", linewidth=0.25, color="0.75")
    if clients == 10:
        ax.legend(frameon=True)
    fig.tight_layout(pad=0.25)
    fig.savefig(RESULTS / f"network_n{clients}.pdf", bbox_inches="tight")
    plt.close(fig)


def main():
    values = load()
    draw(values, 10)
    draw(values, 50)


if __name__ == "__main__":
    main()
