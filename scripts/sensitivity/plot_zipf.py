import csv
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt


ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "results" / "sensitivity" / "zipf"
SCHEMES = {
    "CoCo-ORAM": "coco",
    "ConcurORAM": "concur",
    "MVP-ORAM": "mvp",
    "MVP-ORAM-S": "mvpstrong",
}
STYLES = {
    0.0: ("#b000e6", "s", "-"),
    1.0: ("#00a88f", "D", "--"),
    2.0: ("#52ace0", "^", "-."),
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
    for path in RESULTS.glob("zipf_alpha*_repeat*.csv"):
        with path.open(newline="", encoding="utf-8") as handle:
            for row in csv.DictReader(handle):
                if row["status"] == "OK":
                    key = (row["scheme"], float(row["zipfParameter"]), int(row["clients"]))
                    values[key].append(float(row["throughputOpsPerSec"]))
    return {key: sum(samples) / len(samples) for key, samples in values.items()}


def main():
    means = load_means()
    for scheme, slug in SCHEMES.items():
        fig, ax = plt.subplots(figsize=(4.1, 2.65))
        for alpha in (0.0, 1.0, 2.0):
            points = sorted(
                (clients, value)
                for (name, current_alpha, clients), value in means.items()
                if name == scheme and current_alpha == alpha
            )
            if not points:
                continue
            color, marker, line = STYLES[alpha]
            ax.plot(
                [point[0] for point in points],
                [point[1] for point in points],
                color=color,
                marker=marker,
                linestyle=line,
                label=rf"$\alpha={int(alpha)}$",
            )
        ax.set_xlabel(r"$\#$Clients ($n$)")
        ax.set_ylabel("Throughput (ops/s)")
        ax.set_xticks([1, 5, 10, 15, 20, 30, 40, 50])
        ax.set_ylim(bottom=0)
        ax.grid(True, linestyle="dashdot", linewidth=0.25, color="0.75")
        ax.legend(frameon=True)
        fig.tight_layout(pad=0.25)
        fig.savefig(RESULTS / f"zipf_{slug}.pdf", bbox_inches="tight")
        plt.close(fig)


if __name__ == "__main__":
    main()
