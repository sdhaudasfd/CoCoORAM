import csv
from pathlib import Path

import matplotlib.pyplot as plt


ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "results" / "applications"

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


def read_series(path, throughput_key, latency_key):
    with path.open(newline="", encoding="utf-8") as handle:
        rows = [row for row in csv.DictReader(handle) if row["status"] == "OK"]
    rows.sort(key=lambda row: int(row["clients"]))
    return (
        [int(row["clients"]) for row in rows],
        [float(row[throughput_key]) for row in rows],
        [float(row[latency_key]) for row in rows],
    )


def draw(metric_index, ylabel, output, show_legend):
    coco = read_series(RESULTS / "coco_sse.csv", "searchThroughputPerSec", "avgSearchLatencyMs")
    block = read_series(RESULTS / "blocksse.csv", "throughputSearchesPerSec", "latencyMs")
    fig, ax = plt.subplots(figsize=(4.1, 2.65))
    ax.plot(coco[0], coco[metric_index], "-o", color="#2878b5", label="CoCo-SSE")
    ax.plot(block[0], block[metric_index], "--D", color="#ef6a32", label="BlockSSE")
    ax.set_xlabel(r"$\#$Clients ($n$)")
    ax.set_ylabel(ylabel)
    ax.set_xticks(coco[0])
    ax.set_ylim(bottom=0)
    ax.grid(True, linestyle="dashdot", linewidth=0.25, color="0.75")
    if show_legend:
        ax.legend(frameon=True)
    fig.tight_layout(pad=0.25)
    fig.savefig(RESULTS / output, bbox_inches="tight")
    plt.close(fig)


draw(1, "Throughput (searches/s)", "sse_throughput.pdf", False)
draw(2, "Latency (ms)", "sse_latency.pdf", True)
