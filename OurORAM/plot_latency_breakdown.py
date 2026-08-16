import argparse
import csv
from pathlib import Path

import matplotlib.pyplot as plt


CLIENTS = [1, 5, 10, 20]
BLOCK_SIZES = [64, 256, 1024, 4096]


plt.rcParams.update({
    "font.family": "serif",
    "font.serif": ["Times New Roman", "Times", "DejaVu Serif"],
    "mathtext.fontset": "stix",
    "pdf.fonttype": 42,
    "axes.labelsize": 14,
    "xtick.labelsize": 13,
    "ytick.labelsize": 13,
    "legend.fontsize": 12,
})


def read_results(path):
    results = {}
    with path.open("r", newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if row["status"] != "OK":
                continue
            key = (int(row["blockSize"]), int(row["clients"]))
            results[key] = (
                float(row["round1Ms"]),
                float(row["round2Ms"]),
                float(row["round3Ms"]),
            )
    return results


def plot(results, output):
    fig, ax = plt.subplots(figsize=(6.5, 3.8))
    width = 0.18
    centers = list(range(len(BLOCK_SIZES)))
    colors = ["#d8ebf7", "#e3f2dc", "#f8ddd2"]
    hatches = ["....", "////", "...."]

    for client_index, clients in enumerate(CLIENTS):
        xs = [center + (client_index - 1.5) * width for center in centers]
        round1 = [results[(block_size, clients)][0] for block_size in BLOCK_SIZES]
        round2 = [results[(block_size, clients)][1] for block_size in BLOCK_SIZES]
        round3 = [results[(block_size, clients)][2] for block_size in BLOCK_SIZES]
        bottom2 = round1
        bottom3 = [a + b for a, b in zip(round1, round2)]

        ax.bar(xs, round1, width, color=colors[0], edgecolor="0.25",
               linewidth=0.7, hatch=hatches[0], label="Round 1" if client_index == 0 else None)
        ax.bar(xs, round2, width, bottom=bottom2, color=colors[1], edgecolor="0.25",
               linewidth=0.7, hatch=hatches[1], label="Round 2" if client_index == 0 else None)
        ax.bar(xs, round3, width, bottom=bottom3, color=colors[2], edgecolor="0.25",
               linewidth=0.7, hatch=hatches[2], label="Round 3" if client_index == 0 else None)

    ax.set_xticks(centers)
    ax.set_xticklabels(["64B", "256B", "1KB", "4KB"])
    ax.set_xlabel("Block size")
    ax.set_ylabel("Latency (ms)")
    ax.set_ylim(bottom=0)
    ax.grid(True, axis="y", linestyle="dashdot", linewidth=0.25, color="0.75")
    ax.set_axisbelow(True)
    ax.legend(frameon=True, borderpad=0.35, handlelength=1.5)
    fig.tight_layout(pad=0.3)
    fig.savefig(output, bbox_inches="tight")
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("csv", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    results = read_results(args.csv)
    missing = [key for key in ((b, c) for b in BLOCK_SIZES for c in CLIENTS) if key not in results]
    if missing:
        raise RuntimeError("Missing successful results for: {}".format(missing))
    plot(results, args.output)
    print("[INFO] Figure: {}".format(args.output))


if __name__ == "__main__":
    main()
