import argparse
import csv
import math
from pathlib import Path

import matplotlib.pyplot as plt


BUCKET_SIZES = [2, 3, 4]
COMPETITION_SIZES = [1, 2, 3, 4]
COMP_TO_THRESHOLD = {
    1: 1,
    2: 2,
    3: 3,
    4: 4,
}


plt.rcParams.update({
    "font.family": "serif",
    "font.serif": ["Times New Roman", "Times", "DejaVu Serif"],
    "mathtext.fontset": "stix",
    "pdf.fonttype": 42,
    "ps.fonttype": 42,
    "axes.labelsize": 17,
    "axes.titlesize": 17,
    "xtick.labelsize": 17,
    "ytick.labelsize": 17,
    "legend.fontsize": 17,
    "axes.linewidth": 1.0,
})


def latest_csv(directory):
    csv_path = directory / "results" / "overflow_by_zeta.csv"
    if not csv_path.exists():
        raise FileNotFoundError("Missing results/overflow_by_zeta.csv; run ./run_overflow.sh zeta first")
    return csv_path


def parse_float(value):
    if value is None or value == "":
        return 0.0
    return float(value)


def lambda_from_probability(probability, cap):
    if probability <= 0.0:
        return cap, True
    value = -math.log(probability, 2)
    return min(value, cap), value >= cap


def read_rows(csv_path):
    rows = []
    with csv_path.open("r", newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            rows.append(row)
    return rows


def row_key(row):
    return (
        int(row["bidExponent"]),
        int(row["c"]),
        int(row["bucketSize"]),
        int(row["competitionBucketSize"]),
    )


def build_index(rows):
    return {row_key(row): row for row in rows}


def plot_one_bucket(index, bid_exponent, bucket_size, output_dir, lambda_cap):
    fig, ax = plt.subplots(figsize=(6.1, 4.25))

    all_c = sorted({
        c
        for (be, c, bucket, comp) in index.keys()
        if be == bid_exponent and bucket == bucket_size and comp in COMPETITION_SIZES
    })

    markers = {
        1: "o",
        2: "s",
        3: "D",
        4: "^",
    }
    colors = {
        1: "#0894F7",
        2: "#14e614",
        3: "#f008e8",
        4: "#fc0707",
    }
    fill_colors = {
        1: "#a8c1e1",
        2: "#a0d797",
        3: "#e9aeae",
        4: "#f08984",
    }

    for comp in COMPETITION_SIZES:
        xs = []
        ys = []
        zero_points = []
        threshold = COMP_TO_THRESHOLD[comp]
        column = "p(rootDemand>{})".format(threshold)

        for c in all_c:
            row = index.get((bid_exponent, c, bucket_size, comp))
            if row is None:
                continue
            probability = parse_float(row[column])
            lam, capped = lambda_from_probability(probability, lambda_cap)
            xs.append(c)
            ys.append(lam)
            zero_points.append(capped)

        if not xs:
            continue

        ax.plot(
            xs,
            ys,
            marker=markers[comp],
            color=colors[comp],
            linewidth=1.7,
            markersize=10.2,
            markerfacecolor=fill_colors[comp],
            markeredgewidth=1.2,
            label=r"$\zeta={}$".format(comp),
        )

        capped_x = [x for x, capped in zip(xs, zero_points) if capped]
        capped_y = [y for y, capped in zip(ys, zero_points) if capped]
        if capped_x:
            ax.scatter(
                capped_x,
                capped_y,
                marker=markers[comp],
                s=54,
                facecolors=fill_colors[comp],
                edgecolors=colors[comp],
                linewidths=1.2,
            )

    ax.axhline(lambda_cap, linestyle="--", linewidth=1.1, color="black", alpha=0.85)

     # ax.set_title(r"$N = 2^{{{}}},\ B = {}$".format(bid_exponent, bucket_size), pad=8)
    ax.set_xlabel(r"$\#$ Clients ($n$)")
    ax.set_ylabel(r"$\lambda$")
    ax.set_ylim(bottom=0, top=lambda_cap + 2)
    ax.set_xticks(all_c)
    yticks = [tick for tick in ax.get_yticks() if 0 <= tick < lambda_cap]
    yticks.append(lambda_cap)
    ax.set_yticks(yticks)
    ax.set_yticklabels([str(int(tick)) if float(tick).is_integer() else str(tick) for tick in yticks[:-1]] + [r"$\infty$"])
    # ax.grid(True, linestyle=":", linewidth=0.75, alpha=0.7)
    ax.grid(True, linestyle="dashdot", linewidth=0.25, color="0.75")
    ax.legend(frameon=True, borderpad=0.35, handlelength=2.0)
    fig.tight_layout()

    output = output_dir / "overflow_lambda_bid{}_bucket{}.pdf".format(bid_exponent, bucket_size)
    fig.savefig(output, bbox_inches="tight")
    plt.close(fig)
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("csv", nargs="?", help="CSV result file. Defaults to results/overflow_by_zeta.csv")
    parser.add_argument("--lambda-cap", type=float, default=32.0, help="Y value used for p=0")
    parser.add_argument("--out-dir", default="plots", help="Output directory (default: plots)")
    args = parser.parse_args()

    base_dir = Path.cwd()
    csv_path = Path(args.csv) if args.csv else latest_csv(base_dir)
    output_dir = Path(args.out_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    rows = read_rows(csv_path)
    index = build_index(rows)
    bid_exponents = sorted({int(row["bidExponent"]) for row in rows})

    outputs = []
    for bid_exponent in bid_exponents:
        for bucket_size in BUCKET_SIZES:
            outputs.append(plot_one_bucket(index, bid_exponent, bucket_size, output_dir, args.lambda_cap))

    print("Read {}".format(csv_path))
    for output in outputs:
        print("Wrote {}".format(output))


if __name__ == "__main__":
    main()
