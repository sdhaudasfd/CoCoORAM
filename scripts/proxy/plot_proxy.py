import csv
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt


ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "results" / "proxy"
STYLES = {
    "CoCo-ORAM": ("#1f77b4", "o", "-"),
    "Opca": ("#00a88f", "s", "--"),
    "TaoStore": ("#3f4947", "D", "-."),
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


def load_means():
    values = defaultdict(list)
    with (RESULTS / "proxy_comparison.csv").open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if row["status"] == "OK":
                values[(row["scheme"], int(row["clients"]))].append(
                    (float(row["throughputOpsPerSec"]), float(row["latencyMs"]))
                )
    return {key: tuple(sum(v[i] for v in samples) / len(samples) for i in (0, 1)) for key, samples in values.items()}


def draw(means, metric, ylabel, filename, legend):
    fig, ax = plt.subplots(figsize=(4.1, 2.65))
    for scheme, (color, marker, line) in STYLES.items():
        points = sorted((clients, values[metric]) for (name, clients), values in means.items() if name == scheme)
        ax.plot(*zip(*points), color=color, marker=marker, linestyle=line, label=scheme)
    ax.set_xlabel(r"$\#$Clients ($n$)")
    ax.set_ylabel(ylabel)
    ax.set_xticks([1, 5, 10, 15, 20, 30, 40, 50])
    ax.set_ylim(bottom=0)
    ax.grid(True, linestyle="dashdot", linewidth=0.25, color="0.75")
    if legend:
        ax.legend(frameon=True)
    fig.tight_layout(pad=0.25)
    fig.savefig(RESULTS / filename, bbox_inches="tight")
    plt.close(fig)


def main():
    means = load_means()
    draw(means, 0, "Throughput (ops/s)", "proxy_throughput.pdf", False)
    draw(means, 1, "Latency (ms)", "proxy_latency.pdf", True)


if __name__ == "__main__":
    main()
