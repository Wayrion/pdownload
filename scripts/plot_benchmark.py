#!/usr/bin/env python3
"""Visualize benchmark JSON output with a JetBrains-inspired dark theme."""

from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict
from pathlib import Path
from statistics import mean

import matplotlib.pyplot as plt
from matplotlib.patches import Patch

# JetBrains brand-inspired palette for a bold dark theme
JETBRAINS_BACKGROUND = "#0A0A0A"
JETBRAINS_PANEL = "#121212"
JETBRAINS_COLORS = ["#FF318C", "#FF6E4A", "#FFC110", "#21D789", "#3DDCFF"]
HIGH_CONTRAST_BACKGROUND = "#000000"
HIGH_CONTRAST_PANEL = "#111111"
HIGH_CONTRAST_COLORS = ["#E60049", "#0BB4FF", "#50E991", "#E6D800", "#9B19F5"]
BAR_HATCHES = ["/", "\\", "x", ".", "-"]

ACTIVE_BACKGROUND = JETBRAINS_BACKGROUND
ACTIVE_PANEL = JETBRAINS_PANEL
ACTIVE_COLORS = JETBRAINS_COLORS[:]


def apply_palette(palette: str) -> None:
    global ACTIVE_BACKGROUND, ACTIVE_PANEL, ACTIVE_COLORS

    if palette == "high-contrast":
        ACTIVE_BACKGROUND = HIGH_CONTRAST_BACKGROUND
        ACTIVE_PANEL = HIGH_CONTRAST_PANEL
        ACTIVE_COLORS = HIGH_CONTRAST_COLORS[:]
        return

    ACTIVE_BACKGROUND = JETBRAINS_BACKGROUND
    ACTIVE_PANEL = JETBRAINS_PANEL
    ACTIVE_COLORS = JETBRAINS_COLORS[:]


def mode_style(mode: str, index: int) -> dict:
    """Return a stable style tuple for a benchmark mode."""
    # Keep common modes consistent even if mode ordering changes.
    fixed = {
        "naive": {"color": ACTIVE_COLORS[0], "hatch": BAR_HATCHES[0]},
        "optimized": {"color": ACTIVE_COLORS[3], "hatch": BAR_HATCHES[1]},
        "processes": {"color": ACTIVE_COLORS[1], "hatch": BAR_HATCHES[2]},
    }
    if mode in fixed:
        return fixed[mode]

    return {
        "color": ACTIVE_COLORS[index % len(ACTIVE_COLORS)],
        "hatch": BAR_HATCHES[index % len(BAR_HATCHES)],
    }


def warmup_pair_style() -> dict[str, dict[str, str]]:
    """Dedicated style pair for warmup chart (different from elapsed-by-threads colors)."""
    return {
        "before": {"color": ACTIVE_COLORS[2 % len(ACTIVE_COLORS)], "hatch": "//"},
        "after": {"color": ACTIVE_COLORS[4 % len(ACTIVE_COLORS)], "hatch": "\\\\"},
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Plot downloader benchmark results from JSON.")
    parser.add_argument(
        "--input",
        default="build/benchmark-results.json",
        help="Path to benchmark JSON file (default: build/benchmark-results.json)",
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
    parser.add_argument(
        "--palette",
        choices=["jetbrains", "high-contrast"],
        default="jetbrains",
        help="Color palette for charts (default: jetbrains)",
    )
    return parser.parse_args()


def configure_theme() -> None:
    plt.rcParams.update(
        {
            "figure.facecolor": ACTIVE_BACKGROUND,
            "axes.facecolor": ACTIVE_PANEL,
            "savefig.facecolor": ACTIVE_BACKGROUND,
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
            "legend.facecolor": ACTIVE_PANEL,
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


def summarize_metric(values: list[float]) -> float:
    if not values:
        return math.nan
    return mean(values)


def build_series(grouped: dict[str, dict[int, list[dict]]], modes: list[str], threads: list[int], key: str) -> dict[str, dict[str, list[float]]]:
    series: dict[str, dict[str, list[float]]] = {}

    for mode in modes:
        means: list[float] = []
        for thread in threads:
            samples = [float(run[key]) for run in grouped.get(mode, {}).get(thread, []) if run.get(key) is not None]
            avg = summarize_metric(samples)
            means.append(avg)
        series[mode] = {"mean": means}

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

    x_positions = [math.log2(t) for t in threads]
    group_width = 0.8
    bar_width = group_width / max(1, len(mode_order))

    for index, mode in enumerate(mode_order):
        style = mode_style(mode, index)
        means = metric_series.get(mode, {}).get("mean", [])

        if not means:
            continue

        filtered = [
            (x, y)
            for x, y in zip(x_positions, means)
            if not math.isnan(y)
        ]
        if not filtered:
            continue

        xs = [item[0] for item in filtered]
        ys = [item[1] for item in filtered]

        shifted = [x - (group_width / 2.0) + (index + 0.5) * bar_width for x in xs]
        ax.bar(
            shifted,
            ys,
            width=bar_width,
            color=style["color"],
            hatch=style["hatch"],
            alpha=0.9,
            edgecolor="#E6E6E6",
            linewidth=1.1,
            label=mode,
        )

    ax.set_title(title)
    ax.set_xlabel("Thread Count")
    ax.set_ylabel(y_label)
    ax.set_xticks(x_positions)
    ax.set_xticklabels([str(t) for t in threads])
    for spine in ax.spines.values():
        spine.set_visible(True)
        spine.set_color("#8A8A8A")
        spine.set_linewidth(1.2)

    mode_handles = [
        Patch(
            facecolor=mode_style(mode, idx)["color"],
            edgecolor="#E6E6E6",
            hatch=mode_style(mode, idx)["hatch"],
            linewidth=1.1,
            label=mode,
        )
        for idx, mode in enumerate(mode_order)
    ]
    ax.legend(handles=mode_handles, title="Mode")
    fig.tight_layout()
    fig.savefig(output_path, dpi=dpi)
    plt.close(fig)


def main() -> None:
    args = parse_args()
    apply_palette(args.palette)
    configure_theme()

    input_path = Path(args.input)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    report = load_report(input_path)
    grouped, threads, modes = aggregate_runs(report)

    if not threads:
        raise ValueError("No thread-count data found in benchmark JSON")

    elapsed_series = build_series(grouped, modes, threads, key="elapsedMillis")

    plot_metric(
        threads=threads,
        mode_order=modes,
        metric_series=elapsed_series,
        title=f"{args.title_prefix} - Elapsed Time by Thread Count",
        y_label="Elapsed Time (ms)",
        output_path=output_dir / "elapsed_by_threads.png",
        dpi=args.dpi,
    )

    warmups = report.get("warmups", [])
    plot_jit_warmup_comparison(
        warmups=warmups,
        runs=report.get("runs", []),
        threads=threads,
        title=f"{args.title_prefix} - Naive Before vs After JIT Warmup",
        output_path=output_dir / "jit_warmup_before_after.png",
        dpi=args.dpi,
    )

    print(f"Wrote charts to {output_dir.resolve()}")


def plot_jit_warmup_comparison(
    warmups: list[dict],
    runs: list[dict],
    threads: list[int],
    title: str,
    output_path: Path,
    dpi: int,
) -> None:
    before_values: list[float] = []
    after_values: list[float] = []
    labels: list[str] = []
    target_mode = "naive"

    for thread in threads:
        if warmups:
            warmup_samples = [
                float(item["elapsedMillis"])
                for item in warmups
                if (
                    item.get("mode") == target_mode
                    and int(item.get("threadCount", 0)) == thread
                    and item.get("success", False)
                    and item.get("elapsedMillis") is not None
                )
            ]
            measured_samples = [
                float(item["elapsedMillis"])
                for item in runs
                if (
                    item.get("mode") == target_mode
                    and int(item.get("threadCount", 0)) == thread
                    and item.get("success", False)
                    and item.get("elapsedMillis") is not None
                )
            ]
        else:
            warmup_samples = [
                float(item["elapsedMillis"])
                for item in runs
                if (
                    item.get("mode") == target_mode
                    and int(item.get("threadCount", 0)) == thread
                    and int(item.get("iteration", 0)) == 1
                    and item.get("success", False)
                    and item.get("elapsedMillis") is not None
                )
            ]
            measured_samples = [
                float(item["elapsedMillis"])
                for item in runs
                if (
                    item.get("mode") == target_mode
                    and int(item.get("threadCount", 0)) == thread
                    and int(item.get("iteration", 0)) > 1
                    and item.get("success", False)
                    and item.get("elapsedMillis") is not None
                )
            ]

        if not warmup_samples or not measured_samples:
            continue

        labels.append(str(thread))
        before_values.append(summarize_metric(warmup_samples))
        after_values.append(summarize_metric(measured_samples))

    if not labels:
        return

    fig, ax = plt.subplots(figsize=(10, 6))

    x_positions = list(range(len(labels)))
    bar_width = 0.38

    before_positions = [x - bar_width / 2 for x in x_positions]
    after_positions = [x + bar_width / 2 for x in x_positions]
    pair_style = warmup_pair_style()
    before_style = pair_style["before"]
    after_style = pair_style["after"]

    ax.bar(
        before_positions,
        before_values,
        width=bar_width,
        color=before_style["color"],
        hatch=before_style["hatch"],
        alpha=0.9,
        edgecolor="#E6E6E6",
        linewidth=1.1,
        label="Before warmup (warmup runs)",
    )
    ax.bar(
        after_positions,
        after_values,
        width=bar_width,
        color=after_style["color"],
        hatch=after_style["hatch"],
        alpha=0.9,
        edgecolor="#E6E6E6",
        linewidth=1.1,
        label="After warmup (measured runs)",
    )

    ax.set_title(title)
    ax.set_xlabel("Thread Count (naive mode)")
    ax.set_ylabel("Average Elapsed Time (ms)")
    ax.set_xticks(x_positions)
    ax.set_xticklabels(labels)
    for spine in ax.spines.values():
        spine.set_visible(True)
        spine.set_color("#8A8A8A")
        spine.set_linewidth(1.2)
    ax.legend()
    fig.tight_layout()
    fig.savefig(output_path, dpi=dpi)
    plt.close(fig)

if __name__ == "__main__":
    main()
