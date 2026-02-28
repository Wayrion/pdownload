# Parallel Range Downloader (Kotlin)

This project implements a parallel chunk-based downloader using HTTP `Range` requests.
It includes:
- a local Apache Docker setup configured for HTTP/2 (`h2`/`h2c`),
- a downloader CLI (default `--threads 8`),
- benchmark CLI for powers-of-two thread counts,
- KoTest coverage for correctness and retries,
- a Python plotting script with a JetBrains-inspired theme.

## Code layout

Core Kotlin sources are under `lib/src/main/kotlin/com/wayrion/pdownload`:

- `Library.kt`: downloader core (`DownloadConfig`, metadata fetch, range splitting, thread/process download paths).
- `NaiveChunkWriter.kt` / `OptimizedChunkWriter.kt`: two in-process chunk write strategies.
- `ProcessChunkWorker.kt`: child JVM worker used by `processes` mode.
- `ChunkHttp.kt`: shared chunk-range GET + retry helper logic.
- `DownloaderCli.kt`: user-facing downloader CLI entrypoint.
- `BenchmarkCli.kt`: benchmark CLI entrypoint.
- `BenchmarkRunner.kt`: benchmark orchestration + option parsing.
- `BenchmarkModels.kt`: benchmark report and row data models.
- `BenchmarkSummary.kt`: benchmark summary/statistics calculations.
- `BenchmarkJson.kt`: benchmark JSON serialization.
- `CliArgs.kt`: shared CLI argument parsing and standardized CLI error helpers.

Tests are under `lib/src/test/kotlin/com/wayrion/pdownload/LibraryTest.kt`.
Plotting lives in `scripts/plot_benchmark.py`.

## 1) Local Apache HTTP server (Task 1)

Build image:

```bash
docker build -t pdownload-apache-h2 docker
```

Run with bundled sample file:

```bash
docker run --rm -d --name pdownload-httpd -p 8080:80 pdownload-apache-h2
```

Run against your own host directory:

```bash
docker run --rm -d --name pdownload-httpd -p 8080:80 \
	-v /path/to/your/local/directory:/usr/local/apache2/htdocs:ro \
	pdownload-apache-h2
```

Verify host accessibility + required headers:

```bash
curl -I http://127.0.0.1:8080/sample.txt
curl -i -H "Range: bytes=0-31" http://127.0.0.1:8080/sample.txt
```

Stop server:

```bash
docker stop pdownload-httpd
```

## 2) Downloader CLI (Task 2 + Task 4)

Run help:

```bash
./gradlew :lib:run --args="--help"
```

Download file with defaults (`8` threads, `naive` mode):

```bash
./gradlew :lib:run --args="--url http://127.0.0.1:8080/sample.txt --output build/download.bin"
```

Run optimized mode:

```bash
./gradlew :lib:run --args="--url http://127.0.0.1:8080/sample.txt --output build/download-opt.bin --mode optimized --threads 16"
```

`--mode` values:
- `naive`: buffered read/write chunk loop,
- `optimized`: `FileChannel.transferFrom` path,
- `processes`: per-chunk child JVM worker and merge.

## 3) Tests (Task 3)

Run unit tests:

```bash
./gradlew :lib:test
```

Latest observed run summary (`com.wayrion.pdownload.LibraryTest`):
- tests: `10`
- failures: `0`
- skipped: `0`
- success rate: `100%`
- duration: `4.485s`

HTML report path:
- `build/lib/reports/tests/test/classes/com.wayrion.pdownload.LibraryTest.html`

Covered scenarios include:
- parallel chunks with dynamic payload sizing (content + chunk count validation),
- non-even boundaries where file size is not divisible by chunk size,
- optimized mode correctness (content + chunk count validation),
- processes mode correctness (content + chunk count validation),
- retry success for thread mode when one chunk fails once,
- retry success for process mode when one chunk fails once,
- retry exhausted failure when a chunk keeps returning errors,
- checksum mismatch failure when expected SHA-256 is incorrect,
- metadata validation failure when `Accept-Ranges: bytes` is missing,
- config validation failure when invalid settings are provided (e.g. `threadCount=0`).

P.S. If you're SSH'd into a remote machine and want to view generated test reports in your browser, you can serve them quickly:

```bash
cd build/lib/reports/tests/test
python3 -m http.server 8765
```

Then tunnel locally and open in your browser:

```bash
ssh -L 8765:127.0.0.1:8765 <user>@<remote-host>
```

Open `http://127.0.0.1:8765` locally.

## 4) Benchmark matrix + JSON output

Run benchmark help:

```bash
./gradlew :lib:runBenchmark --args="--help"
```

Run required thread powers (`1,2,4,8,16,32,64`) across all modes:

```bash
./gradlew :lib:runBenchmark --args="--url http://127.0.0.1:8080/sample.txt --mode both --threads 1,2,4,8,16,32,64 --output-json build/benchmark-compare.json"
```

By default, each mode/thread pair runs `5` warm-up iterations (stored under `warmups`) before measured iterations (stored under `runs`).
Override warm-up count when needed:

```bash
./gradlew :lib:runBenchmark --args="--url http://127.0.0.1:8080/sample.txt --mode both --threads 1,2,4,8,16,32,64 --warmup-iterations 8 --output-json build/benchmark-compare.json"
```

This writes run-level and summary metrics to JSON (`schemaVersion`, target metadata, host info, per-run elapsed time, and per-mode best thread count).

## 5) Plot benchmark results (Task 5)

Render plots with `uv` (recommended):

```bash
uv run scripts/plot_benchmark.py --input build/benchmark-compare.json --output-dir build/benchmark-plots
```

Use high-contrast colors when needed:

```bash
uv run scripts/plot_benchmark.py --input build/benchmark-compare.json --output-dir build/benchmark-plots --palette high-contrast
```

Alternative (venv + pip) setup:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -U pip
python -m pip install matplotlib
python scripts/plot_benchmark.py --input build/benchmark-compare.json --output-dir build/benchmark-plots
```

Generated charts include:
- `elapsed_by_threads.png`
- `jit_warmup_before_after.png`

### Benchmark screenshots

- [Elapsed by threads](screenshots/elapsed_by_threads.png)
- [Naive warmup before vs after](screenshots/jit_warmup_before_after.png)

![Elapsed by threads](screenshots/elapsed_by_threads.png)

![Naive warmup before vs after](screenshots/jit_warmup_before_after.png)

## CLI flags that can improve performance

Useful tuning flags for experiments:
- `--threads`: increase parallelism until network/disk saturates.
- `--chunk-size-bytes`: reduce request overhead with larger chunks; too large can underutilize threads.
- `--io-buffer-bytes`: increase per-thread buffering to reduce syscall pressure.
- `--mode optimized`: usually lower copy overhead versus naive mode.
- `--max-retries` + `--retry-delay-ms`: improve stability on flaky links without over-retrying.
- `--connect-timeout-ms` and `--request-timeout-ms`: avoid hangs and improve benchmark consistency.