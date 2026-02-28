#!/usr/bin/env python3
"""Run the benchmark plotter for each JSON and collect images.

Creates `benchmarks/images/elapsed_<name>.png` and
`benchmarks/images/JIT_<name>.png` for each JSON file.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JSON_DIR = ROOT / "benchmarks" / "json"
OUT_BASE = ROOT / "build" / "benchmark-plots"
IMAGES_DIR = ROOT / "benchmarks" / "images"

IMAGES_DIR.mkdir(parents=True, exist_ok=True)


def main() -> None:
    plotter = ROOT / "scripts" / "plot_benchmark.py"
    if not plotter.exists():
        print("plot_benchmark.py not found in scripts/", file=sys.stderr)
        raise SystemExit(2)

    json_files = sorted(JSON_DIR.glob("*.json"))
    if not json_files:
        print("No JSON files found in", JSON_DIR)
        return

    for jf in json_files:
        name = jf.stem
        out_dir = OUT_BASE / name
        if out_dir.exists():
            shutil.rmtree(out_dir)
        out_dir.mkdir(parents=True, exist_ok=True)

        cmd = [
            sys.executable,
            str(plotter),
            "--input",
            str(jf),
            "--output-dir",
            str(out_dir),
            "--palette",
            "high-contrast",
        ]
        print("Running:", " ".join(cmd))
        res = subprocess.run(cmd)
        if res.returncode != 0:
            print(f"plot_benchmark.py failed for {jf} (rc={res.returncode})", file=sys.stderr)
            continue

        pngs = list(out_dir.glob("*.png"))
        elapsed = None
        jit = None

        for p in pngs:
            n = p.name.lower()
            if "warm" in n or "jit" in n:
                jit = p
            elif "elapsed" in n or "thread" in n:
                elapsed = p

        # Fallback selection if heuristics didn't find both
        if not elapsed and pngs:
            elapsed = pngs[0]
        if not jit and len(pngs) > 1:
            candidate = pngs[1] if pngs[0] == elapsed else pngs[0]
            jit = candidate

        if elapsed:
            dest = IMAGES_DIR / f"elapsed_{name}.png"
            print(f"Moving {elapsed.name} -> {dest.name}")
            shutil.move(str(elapsed), str(dest))
        else:
            print(f"No elapsed image found for {name}", file=sys.stderr)

        if jit:
            dest = IMAGES_DIR / f"JIT_{name}.png"
            print(f"Moving {jit.name} -> {dest.name}")
            shutil.move(str(jit), str(dest))
        else:
            print(f"No JIT image found for {name}", file=sys.stderr)

        # cleanup temporary output dir
        try:
            for leftover in out_dir.iterdir():
                if leftover.is_file():
                    leftover.unlink()
            out_dir.rmdir()
        except Exception:
            pass

    print("Done. Images collected in:", IMAGES_DIR)


if __name__ == "__main__":
    main()
