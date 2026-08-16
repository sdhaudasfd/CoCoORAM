import argparse
import csv
import math
from pathlib import Path

import matplotlib.pyplot as plt


BID_EXPONENTS = [14, 16, 18, 20]
BUCKET_SIZE = 3
COMPETITION_BUCKET_SIZE = 1
PROBABILITY_COLUMN = "p(rootDemand>1)"


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
    csv_path = directory / "results" / "overflow_by_logn.csv"
    if not csv_path.exists():
        raise FileNotFoundError("Missing results/overflow_by_logn.csv; run ./run_overflow.sh logn first")
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
    with csv_path.open("r", newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def build_index(rows):
    index = {}
    for row in rows:
        key = (
            int(row["bidExponent"]),
            int(row["c"]),
            int(row["bucketSize"]),
            int(row["competitionBucketSize"]),
        )
        index[key] = row
    return index


def plot(index, output_dir, lambda_cap):
    fig, ax = plt.subplots(figsize=(6.1, 4.25))

    all_c = sorted({
        c
        for (bid_exponent, c, bucket_size, competition_bucket_size) in index.keys()
        if bid_exponent in BID_EXPONENTS
        and bucket_size == BUCKET_SIZE
        and competition_bucket_size == COMPETITION_BUCKET_SIZE
    })

    markers = {
        14: "o",
        16: "s",
        18: "D",
        20: "^",
    }
    colors = {
        14: "#0894F7",
        16: "#14e614",
        18: "#f008e8",
        20: "#fc0707",
    }
    fill_colors = {
        14: "#E8ECF0",
        16: "#E5EBE5",
        18: "#ECE9E5",
        20: "#E8E5EA",
    }
    line_styles = {
        14: "-",
        16: "--",
        18: "-.",
        20: ":",
    }


    for bid_exponent in BID_EXPONENTS:
        xs = []
        ys = []
        capped_points = []

        for c in all_c:
            row = index.get((bid_exponent, c, BUCKET_SIZE, COMPETITION_BUCKET_SIZE))
            if row is None:
                continue
            probability = parse_float(row[PROBABILITY_COLUMN])
            lam, capped = lambda_from_probability(probability, lambda_cap)
            xs.append(c)
            ys.append(lam)
            capped_points.append(capped)

        if not xs:
            continue

        ax.plot(
            xs,
            ys,
            marker=markers[bid_exponent],
            color=colors[bid_exponent],
            linestyle=line_styles[bid_exponent],
            linewidth=1.7,
            markersize=10.2,
            markerfacecolor=fill_colors[bid_exponent],
            markeredgecolor=colors[bid_exponent],
            markeredgewidth=1.2,
            label=r"$N = 2^{{{}}}$".format(bid_exponent),
        )

        capped_x = [x for x, capped in zip(xs, capped_points) if capped]
        capped_y = [y for y, capped in zip(ys, capped_points) if capped]
        if capped_x:
            ax.scatter(
                capped_x,
                capped_y,
                marker=markers[bid_exponent],
                s=54,
                facecolors=fill_colors[bid_exponent],
                edgecolors=colors[bid_exponent],
                linewidths=1.2,
            )

    ax.axhline(lambda_cap, linestyle="--", linewidth=1.1, color="black", alpha=0.85)
    ax.set_xlabel(r"$\#$ Clients ($n$)")
    ax.set_ylabel(r"$\lambda$")
    ax.set_ylim(bottom=0, top=lambda_cap + 2)
    ax.set_xticks(all_c)

    yticks = [tick for tick in ax.get_yticks() if 0 <= tick < lambda_cap]
    yticks.append(lambda_cap)
    ax.set_yticks(yticks)
    ax.set_yticklabels(
        [str(int(tick)) if float(tick).is_integer() else str(tick) for tick in yticks[:-1]]
        + [r"$\infty$"]
    )
    ax.grid(True, linestyle="dashdot", linewidth=0.25, color="0.75")

    # ax.grid(True, linestyle=":", linewidth=0.75, alpha=0.7)
    ax.legend(frameon=True, borderpad=0.35, handlelength=2.0)
    fig.tight_layout()

    output = output_dir / "overflow_lambda_by_logn_bucket3_comp1.pdf"
    fig.savefig(output, bbox_inches="tight")
    plt.close(fig)
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("csv", nargs="?", help="CSV result file. Defaults to results/overflow_by_logn.csv")
    parser.add_argument("--lambda-cap", type=float, default=32.0, help="Y value used for p=0")
    parser.add_argument("--out-dir", default="plots", help="Output directory")
    args = parser.parse_args()

    base_dir = Path.cwd()
    csv_path = Path(args.csv) if args.csv else latest_csv(base_dir)
    output_dir = Path(args.out_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    rows = read_rows(csv_path)
    index = build_index(rows)
    output = plot(index, output_dir, args.lambda_cap)

    print("Read {}".format(csv_path))
    print("Wrote {}".format(output))


if __name__ == "__main__":
    main()
