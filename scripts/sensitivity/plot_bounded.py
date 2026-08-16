import csv
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt


ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "results" / "sensitivity" / "bounded"

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


def means(path):
    grouped = defaultdict(list)
    with path.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if row["status"] == "OK":
                grouped[int(row["clients"])].append(row)
    result = {}
    for clients, rows in grouped.items():
        result[clients] = {
            "throughput": sum(float(row["throughputOpsPerSec"]) for row in rows) / len(rows),
            "latency": sum(float(row["latencyMs"]) for row in rows) / len(rows),
        }
    return result


def draw(metric, ylabel, output, legend):
    fig, ax = plt.subplots(figsize=(4.1, 2.65))
    for filename, label, color, marker, line in (
        ("coco.csv", "CoCo-ORAM", "#405de6", "o", "-"),
        ("mvp.csv", "MVP-ORAM", "#00b77a", "s", "--"),
    ):
        data = means(RESULTS / filename)
        clients = sorted(data)
        ax.plot(
            clients,
            [data[n][metric] for n in clients],
            color=color,
            marker=marker,
            linestyle=line,
            label=label,
        )
    ax.set_xlabel(r"$\#$Clients ($n$)")
    ax.set_ylabel(ylabel)
    ax.set_xticks([1, 5, 10, 15, 20, 30, 40, 50])
    ax.set_ylim(bottom=0)
    ax.grid(True, linestyle="dashdot", linewidth=0.25, color="0.75")
    if legend:
        ax.legend(frameon=True)
    fig.tight_layout(pad=0.25)
    fig.savefig(RESULTS / output, bbox_inches="tight")
    plt.close(fig)


draw("throughput", "Throughput (ops/s)", "bounded_throughput.pdf", False)
draw("latency", "Latency (ms)", "bounded_latency.pdf", True)
