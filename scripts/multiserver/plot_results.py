import csv
from pathlib import Path

import matplotlib.pyplot as plt


ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "results" / "multiserver"
STYLES = {
    1: ("#b000e6", "s", "-"),
    4: ("#08aa91", "D", "--"),
    7: ("#54ace0", "^", "-."),
    10: ("#ed9b00", "o", ":"),
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


def read_rows(path):
    with path.open(newline="", encoding="utf-8") as handle:
        return [row for row in csv.DictReader(handle) if row["status"] == "OK"]


def plot_scheme(rows, metric, ylabel, output):
    fig, ax = plt.subplots(figsize=(4.1, 2.65))
    for replicas in (1, 4, 7, 10):
        selected = sorted(
            (row for row in rows if int(row["replicas"]) == replicas),
            key=lambda row: int(row["clients"]),
        )
        if not selected:
            continue
        color, marker, line = STYLES[replicas]
        ax.plot(
            [int(row["clients"]) for row in selected],
            [float(row[metric]) for row in selected],
            linestyle=line,
            color=color,
            marker=marker,
            label=rf"$m={replicas}$",
        )
    ax.set_xlabel(r"$\#$Clients ($n$)")
    ax.set_ylabel(ylabel)
    ax.set_xticks([1, 5, 10, 15, 20, 30, 40, 50])
    ax.set_ylim(bottom=0)
    ax.grid(True, linestyle="dashdot", linewidth=0.25, color="0.75")
    ax.legend(frameon=True, ncol=2)
    fig.tight_layout(pad=0.25)
    fig.savefig(RESULTS / output, bbox_inches="tight")
    plt.close(fig)


def main():
    RESULTS.mkdir(parents=True, exist_ok=True)
    for stem, label in (("coco", "coco"), ("mvp", "mvp")):
        rows = read_rows(RESULTS / f"{stem}.csv")
        plot_scheme(rows, "throughputOpsPerSec", "Throughput (ops/s)", f"{label}_throughput.pdf")
        plot_scheme(rows, "latencyMs", "Latency (ms)", f"{label}_latency.pdf")


if __name__ == "__main__":
    main()
