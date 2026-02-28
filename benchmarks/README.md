
# Benchmark Images

The plots generated from the benchmark JSON files. Each benchmark has two images: an "elapsed" plot and a "JIT/warmup" comparison.

> [!CAUTION]
Be careful when reading these plots — the x-axis ordering is switched in some graphs and the y-axis scale changes between plots. This happened because benchmarks were run from smallest to largest so smaller runs finished earlier; compare axes and scales carefully before drawing conclusions.

## Navigation

- **Small samples:** 
· [64KB](#benchmark-sample-64kb)
· [128KB](#benchmark-sample-128kb) 
· [256KB](#benchmark-sample-256kb) 
· [512KB](#benchmark-sample-512kb) 
· [1024KB](#benchmark-sample-1024kb)
- **Large samples:** 
· [64MB](#benchmark-sample-64mb) 
· [128MB](#benchmark-sample-128mb) 
· [256MB](#benchmark-sample-256mb) 
· [512MB](#benchmark-sample-512mb) 
· [1024MB](#benchmark-sample-1024mb)

## Methodology

- Benchmarks are executed by [`run_benchmarks.sh`](../run_benchmarks.sh), which runs Gradle `:lib:runBenchmark` once per sample file and writes JSON reports to `build/`.
- Files are benchmarked from smallest to largest (`64KB` → `1024KB`, then `64MB` → `1024MB`). For each file, thread counts are tested in descending order `64,32,16,8,4,2,1`; this changes plot axis ordering.
- For each benchmark invocation, the Kotlin CLI first fetches metadata and computes a reference SHA-256 by downloading the full target once. Every warmup/run result is validated against this checksum.
- Per mode/thread combination, execution order is:
	1. warmups (`--warmup-iterations 3` in this dataset),
	2. measured runs (`--iterations 5` in this dataset).
	Warmups are recorded under `warmups`; measured iterations are recorded under `runs`.
- This benchmark set uses modes `naive` and `optimized` (as passed by `run_benchmarks.sh`). The CLI can also benchmark `processes`, but it is not included in these plots.
- For each attempt, the runner downloads to a unique temp file, measures `elapsedMillis`, computes `sha256`, records `bytesDownloaded`, and marks `success=true` only when checksum matches expected content. Temp artifacts are deleted after each attempt.
- Summary computation (`summary.perMode`) groups measured `runs` by mode/thread:
	- `successRate = successfulRuns / totalRuns`,
	- `averageElapsedMillis` is the mean of successful run times only,
	- `bestThreadCount` is the thread count with the lowest average among thread counts with success rate > 0.
- `summary.optimizedVsNaive.speedupAtModeOptimum` is reported as `naiveBestAverage / optimizedBestAverage` (using each mode’s own best thread count).
- Plots are generated from these JSON reports by [`scripts/plot_benchmark.py`](../scripts/plot_benchmark.py) (use `--palette high-contrast` to match the images in this document).

## Small samples

### benchmark-sample-64KB
![Elapsed - 64KB](images/elapsed_benchmark-sample-64KB.png)
![JIT - 64KB](images/JIT_benchmark-sample-64KB.png)

### benchmark-sample-128KB
![Elapsed - 128KB](images/elapsed_benchmark-sample-128KB.png)
![JIT - 128KB](images/JIT_benchmark-sample-128KB.png)

### benchmark-sample-256KB
![Elapsed - 256KB](images/elapsed_benchmark-sample-256KB.png)
![JIT - 256KB](images/JIT_benchmark-sample-256KB.png)

### benchmark-sample-512KB
![Elapsed - 512KB](images/elapsed_benchmark-sample-512KB.png)
![JIT - 512KB](images/JIT_benchmark-sample-512KB.png)

### benchmark-sample-1024KB
![Elapsed - 1024KB](images/elapsed_benchmark-sample-1024KB.png)
![JIT - 1024KB](images/JIT_benchmark-sample-1024KB.png)

## Large samples

### benchmark-sample-64MB
![Elapsed - 64MB](images/elapsed_benchmark-sample-64MB.png)
![JIT - 64MB](images/JIT_benchmark-sample-64MB.png)

### benchmark-sample-128MB
![Elapsed - 128MB](images/elapsed_benchmark-sample-128MB.png)
![JIT - 128MB](images/JIT_benchmark-sample-128MB.png)

### benchmark-sample-256MB
![Elapsed - 256MB](images/elapsed_benchmark-sample-256MB.png)
![JIT - 256MB](images/JIT_benchmark-sample-256MB.png)

### benchmark-sample-512MB
![Elapsed - 512MB](images/elapsed_benchmark-sample-512MB.png)
![JIT - 512MB](images/JIT_benchmark-sample-512MB.png)

### benchmark-sample-1024MB
![Elapsed - 1024MB](images/elapsed_benchmark-sample-1024MB.png)
![JIT - 1024MB](images/JIT_benchmark-sample-1024MB.png)
