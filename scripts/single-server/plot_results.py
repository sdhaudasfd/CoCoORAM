import csv
from pathlib import Path

import matplotlib.pyplot as plt


ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "wan_results"
STYLES = {
    "CoCo-ORAM": ("#1f77b4", "o"),
    "ConcurORAM": ("#d62728", "^"),
    "MVP-ORAM": ("#2ca02c", "s"),
    "MVP-ORAM-S": ("#ff7f0e", "D"),
}
ALIASES = {
    "C2ORAM": "CoCo-ORAM",
    "MVPORAM": "MVP-ORAM",
    "MVPORAM-S": "MVP-ORAM-S",
}

plt.rcParams.update({
    "font.family": "serif",
    "font.serif": ["Times New Roman", "Times", "DejaVu Serif"],
    "mathtext.fontset": "stix",
    "pdf.fonttype": 42,
    "axes.labelsize": 11,
    "xtick.labelsize": 10,
    "ytick.labelsize": 10,
    "legend.fontsize": 9,
})


def read_rows(path):
    with path.open(newline="", encoding="utf-8") as handle:
        rows = []
        for row in csv.DictReader(handle):
            if row["status"] != "OK":
                continue
            row["scheme"] = ALIASES.get(row["scheme"], row["scheme"])
            rows.append(row)
        return rows


def line_plot(rows, x_key, y_key, xlabel, ylabel, output, legend=True):
    fig, ax = plt.subplots(figsize=(4.1, 2.65))
    for scheme, (color, marker) in STYLES.items():
        selected = sorted(
            (r for r in rows if r["scheme"] == scheme),
            key=lambda r: float(r[x_key]),
        )
        if selected:
            ax.plot(
                [float(r[x_key]) for r in selected],
                [float(r[y_key]) for r in selected],
                color=color,
                marker=marker,
                linewidth=1.2,
                markersize=5,
                label=scheme,
            )
    ax.set_xlabel(xlabel)
    ax.set_ylabel(ylabel)
    ax.grid(True, linestyle="dashdot", linewidth=0.25, color="0.75")
    if legend:
        ax.legend(frameon=True)
    fig.tight_layout(pad=0.3)
    fig.savefig(output, bbox_inches="tight")
    plt.close(fig)


def block_plot(rows, output):
    schemes = list(STYLES)
    sizes = sorted({int(r["blockSize"]) for r in rows})
    width = 0.19
    fig, ax = plt.subplots(figsize=(4.1, 2.65))
    for index, scheme in enumerate(schemes):
        values = {
            int(r["blockSize"]): float(r["throughputOpsPerSec"])
            for r in rows if r["scheme"] == scheme
        }
        color, _ = STYLES[scheme]
        xs = [i + (index - 1.5) * width for i in range(len(sizes))]
        ax.bar(xs, [values.get(size, 0) for size in sizes], width, label=scheme,
               facecolor="white", edgecolor=color, hatch="..")
    ax.set_xticks(range(len(sizes)), [f"{s}B" if s < 1024 else f"{s // 1024}KB" for s in sizes])
    ax.set_xlabel("Block size")
    ax.set_ylabel("Throughput (ops/s)")
    ax.grid(True, axis="y", linestyle="dashdot", linewidth=0.25, color="0.75")
    ax.legend(frameon=True)
    fig.tight_layout(pad=0.3)
    fig.savefig(output, bbox_inches="tight")
    plt.close(fig)


def main():
    RESULTS.mkdir(exist_ok=True)
    client_rows = read_rows(RESULTS / "WAN_10gbit_results.csv")
    block_rows = read_rows(RESULTS / "BlockSize_10gbit_results.csv")
    logn_rows = read_rows(RESULTS / "LogN_10gbit_results.csv")

    line_plot(client_rows, "clients", "throughputOpsPerSec", r"$\#$Clients ($n$)",
              "Throughput (ops/s)", RESULTS / "clients_throughput.pdf", legend=False)
    line_plot(client_rows, "clients", "latencyMs", r"$\#$Clients ($n$)",
              "Latency (ms)", RESULTS / "clients_latency.pdf")
    block_plot(block_rows, RESULTS / "block_size_throughput.pdf")
    line_plot(logn_rows, "logN", "throughputOpsPerSec", r"$\log_2 N$",
              "Throughput (ops/s)", RESULTS / "logn_throughput.pdf")


if __name__ == "__main__":
    main()
