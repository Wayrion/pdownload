#!/usr/bin/env python3
"""Visualize benchmark JSON output with a JetBrains-inspired dark theme."""

from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict
from pathlib import Path
from statistics import mean, stdev

import matplotlib.pyplot as plt

# JetBrains brand-inspired palette for a bold dark theme
JETBRAINS_BACKGROUND = "#0A0A0A"
JETBRAINS_PANEL = "#121212"
JETBRAINS_COLORS = ["#FF318C", "#FF6E4A", "#FFC110", "#21D789", "#3DDCFF"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Plot downloader benchmark results from JSON.")
    parser.add_argument(
        "--input",
        default="benchmark-results.json",
        help="Path to benchmark JSON file (default: benchmark-results.json)",
    )
    parser.add_argument(
        "--output-dir",
        default="build/benchmark-plots",
        help="Directory where PNG charts are written (default: build/benchmark-plots)",
    )
    parser.add_argument(
        "--dpi",
        type=int,
        default=150,
        help="PNG DPI (default: 150)",
    )
    parser.add_argument(
        "--title-prefix",
        default="Parallel Downloader Benchmark",
        help="Optional title prefix for generated charts",
    )
    return parser.parse_args()


def configure_theme() -> None:
    plt.rcParams.update(
        {
            "figure.facecolor": JETBRAINS_BACKGROUND,
            "axes.facecolor": JETBRAINS_PANEL,
            "savefig.facecolor": JETBRAINS_BACKGROUND,
            "axes.edgecolor": "#2A2A2A",
            "axes.labelcolor": "#EAEAEA",
            "text.color": "#EAEAEA",
            "xtick.color": "#CFCFCF",
            "ytick.color": "#CFCFCF",
            "grid.color": "#303030",
            "grid.alpha": 0.65,
            "axes.grid": True,
            "font.size": 10,
            "legend.framealpha": 0.35,
            "legend.facecolor": JETBRAINS_PANEL,
            "legend.edgecolor": "#404040",
        }
    )


def load_report(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def aggregate_runs(report: dict) -> tuple[dict[str, dict[int, list[dict]]], list[int], list[str]]:
    runs = report.get("runs", [])
    grouped: dict[str, dict[int, list[dict]]] = defaultdict(lambda: defaultdict(list))

    for run in runs:
        if not run.get("success", False):
            continue
        mode = str(run.get("mode", "unknown"))
        thread_count = int(run.get("threadCount", 0))
        grouped[mode][thread_count].append(run)

    mode_order = report.get("benchmark", {}).get("modes") or sorted(grouped.keys())
    thread_counts = report.get("benchmark", {}).get("threadCounts")
    if not thread_counts:
        all_threads = {thread for mode_map in grouped.values() for thread in mode_map.keys()}
        thread_counts = sorted(all_threads)

    return grouped, [int(t) for t in thread_counts], [str(m) for m in mode_order]


def summarize_metric(values: list[float]) -> tuple[float, float]:
    if not values:
        return math.nan, 0.0
    if len(values) == 1:
        return values[0], 0.0
    return mean(values), stdev(values)


def build_series(grouped: dict[str, dict[int, list[dict]]], modes: list[str], threads: list[int], key: str) -> dict[str, dict[str, list[float]]]:
    series: dict[str, dict[str, list[float]]] = {}

    for mode in modes:
        means: list[float] = []
        deviations: list[float] = []
        for thread in threads:
            samples = [float(run[key]) for run in grouped.get(mode, {}).get(thread, []) if run.get(key) is not None]
            avg, spread = summarize_metric(samples)
            means.append(avg)
            deviations.append(spread)
        series[mode] = {"mean": means, "std": deviations}

    return series


def plot_metric(
    threads: list[int],
    mode_order: list[str],
    metric_series: dict[str, dict[str, list[float]]],
    title: str,
    y_label: str,
    output_path: Path,
    dpi: int,
) -> None:
    fig, ax = plt.subplots(figsize=(10, 6))

    for index, mode in enumerate(mode_order):
        color = JETBRAINS_COLORS[index % len(JETBRAINS_COLORS)]
        means = metric_series.get(mode, {}).get("mean", [])
        stds = metric_series.get(mode, {}).get("std", [])

        if not means:
            continue

        filtered = [
            (x, y, s)
            for x, y, s in zip(threads, means, stds)
            if not math.isnan(y)
        ]
        if not filtered:
            continue

        xs = [item[0] for item in filtered]
        ys = [item[1] for item in filtered]
        ss = [item[2] for item in filtered]

        ax.plot(xs, ys, marker="o", linewidth=2.1, color=color, label=mode)
        lower = [max(0.0, y - s) for y, s in zip(ys, ss)]
        upper = [y + s for y, s in zip(ys, ss)]
        ax.fill_between(xs, lower, upper, color=color, alpha=0.18)

    ax.set_title(title)
    ax.set_xlabel("Thread Count")
    ax.set_ylabel(y_label)
    ax.set_xscale("log", base=2)
    ax.set_xticks(threads)
    ax.get_xaxis().set_major_formatter(plt.ScalarFormatter())
    ax.legend(title="Mode")
    fig.tight_layout()
    fig.savefig(output_path, dpi=dpi)
    plt.close(fig)


def plot_success_rate(threads: list[int], mode_order: list[str], grouped: dict[str, dict[int, list[dict]]], all_runs: list[dict], title: str, output_path: Path, dpi: int) -> None:
    fig, ax = plt.subplots(figsize=(10, 6))

    for index, mode in enumerate(mode_order):
        color = JETBRAINS_COLORS[index % len(JETBRAINS_COLORS)]
        rates: list[float] = []

        for thread in threads:
            mode_thread_runs = [
                run
                for run in all_runs
                if str(run.get("mode")) == mode and int(run.get("threadCount", 0)) == thread
            ]
            if not mode_thread_runs:
                rates.append(math.nan)
                continue
            success_count = sum(1 for run in mode_thread_runs if run.get("success", False))
            rates.append((success_count / len(mode_thread_runs)) * 100.0)

        filtered = [(t, r) for t, r in zip(threads, rates) if not math.isnan(r)]
        if not filtered:
            continue

        xs = [item[0] for item in filtered]
        ys = [item[1] for item in filtered]
        ax.plot(xs, ys, marker="o", linewidth=2.1, color=color, label=mode)

    ax.set_title(title)
    ax.set_xlabel("Thread Count")
    ax.set_ylabel("Success Rate (%)")
    ax.set_ylim(0, 102)
    ax.set_xscale("log", base=2)
    ax.set_xticks(threads)
    ax.get_xaxis().set_major_formatter(plt.ScalarFormatter())
    ax.legend(title="Mode")
    fig.tight_layout()
    fig.savefig(output_path, dpi=dpi)
    plt.close(fig)


def main() -> None:
    args = parse_args()
    configure_theme()

    input_path = Path(args.input)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    report = load_report(input_path)
    grouped, threads, modes = aggregate_runs(report)

    if not threads:
        raise ValueError("No thread-count data found in benchmark JSON")

    throughput_series = build_series(grouped, modes, threads, key="throughputMiBps")
    elapsed_series = build_series(grouped, modes, threads, key="elapsedMillis")

    plot_metric(
        threads=threads,
        mode_order=modes,
        metric_series=throughput_series,
        title=f"{args.title_prefix} - Throughput vs Threads",
        y_label="Throughput (MiB/s)",
        output_path=output_dir / "throughput_vs_threads.png",
        dpi=args.dpi,
    )

    plot_metric(
        threads=threads,
        mode_order=modes,
        metric_series=elapsed_series,
        title=f"{args.title_prefix} - Elapsed Time vs Threads",
        y_label="Elapsed Time (ms)",
        output_path=output_dir / "elapsed_vs_threads.png",
        dpi=args.dpi,
    )

    all_runs = list(report.get("runs", []))
    plot_success_rate(
        threads=threads,
        mode_order=modes,
        grouped=grouped,
        all_runs=all_runs,
        title=f"{args.title_prefix} - Success Rate vs Threads",
        output_path=output_dir / "success_rate_vs_threads.png",
        dpi=args.dpi,
    )

    print(f"Wrote charts to {output_dir.resolve()}")


if __name__ == "__main__":
    main()
