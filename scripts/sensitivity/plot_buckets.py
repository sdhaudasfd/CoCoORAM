import csv
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D


ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "results" / "sensitivity" / "buckets"
CLIENTS = [1, 5, 10, 15, 20, 30, 40, 50]
STYLES = {
    1: ("#b000e6", "s", "-"),
    2: ("#00a88f", "D", "--"),
    3: ("#52ace0", "^", "-."),
    4: ("#e99a00", "o", ":"),
}

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


def load_means(path):
    values = defaultdict(list)
    with path.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if row["status"] != "OK":
                continue
            zeta = None if row["zeta"] == "NA" else int(row["zeta"])
            key = (row["scheme"], zeta, int(row["clients"]))
            values[key].append((float(row["throughputOpsPerSec"]), float(row["latencyMs"])))
    return {
        key: (sum(v[0] for v in samples) / len(samples), sum(v[1] for v in samples) / len(samples))
        for key, samples in values.items()
    }


def plot(z, metric, ylabel):
    means = load_means(RESULTS / f"bucket_Z{z}.csv")
    fig, ax = plt.subplots(figsize=(4.1, 2.65))
    for zeta in (1, 2, 3, 4):
        points = [(c, means[("C2ORAM", zeta, c)][metric]) for c in CLIENTS if ("C2ORAM", zeta, c) in means]
        color, marker, line = STYLES[zeta]
        ax.plot(*zip(*points), color=color, marker=marker, linestyle=line, label=rf"$\zeta={zeta}$")
    points = [(c, means[("MVPORAM", None, c)][metric]) for c in CLIENTS if ("MVPORAM", None, c) in means]
    if points:
        ax.plot(*zip(*points), color="0.35", marker="x", linestyle="--", label="MVP-ORAM")
    ax.set_xlabel(r"$\#$Clients ($n$)")
    ax.set_ylabel(ylabel)
    ax.set_xticks(CLIENTS)
    ax.set_ylim(bottom=0)
    ax.grid(True, linestyle="dashdot", linewidth=0.25, color="0.75")
    fig.tight_layout(pad=0.25)
    fig.savefig(RESULTS / f"bucket_Z{z}_{'throughput' if metric == 0 else 'latency'}.pdf", bbox_inches="tight")
    plt.close(fig)


def main():
    for z in (2, 3, 4):
        plot(z, 0, "Throughput (ops/s)")
        plot(z, 1, "Latency (ms)")

    handles = [Line2D([], [], color="0.35", marker="x", linestyle="--", label="MVP-ORAM")]
    handles.extend(
        Line2D([], [], color=color, marker=marker, linestyle=line, label=rf"$\zeta={zeta}$")
        for zeta, (color, marker, line) in STYLES.items()
    )
    fig = plt.figure(figsize=(7.2, 0.55))
    fig.legend(handles=handles, loc="center", ncol=5, frameon=True)
    fig.savefig(RESULTS / "bucket_legend.pdf", bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)


if __name__ == "__main__":
    main()
