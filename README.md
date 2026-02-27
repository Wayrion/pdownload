# Parallel Range Downloader (Kotlin)

This project implements a parallel chunk-based downloader using HTTP `Range` requests.
It includes:
- a local Apache Docker setup with HTTP/2 enabled,
- a downloader CLI (default `--threads 8`),
- benchmark CLI for powers-of-two thread counts,
- KoTest coverage for correctness and retries,
- a Python plotting script with a JetBrains-inspired theme.

## 1) Local Apache HTTP server (Task 1)

Build image:

```bash
docker build -t pdownload-apache-h2 docker/apache
```

Run with bundled sample file:

```bash
docker run --rm -d --name pdownload-httpd -p 8080:80 pdownload-apache-h2
```

Run against your own host directory (as requested in task text):

```bash
docker run --rm -d --name pdownload-httpd -p 8080:80 \
	-v /path/to/your/local/directory:/usr/local/apache2/htdocs:ro \
	pdownload-apache-h2
```

Verify host accessibility + required headers:

```bash
curl -I http://127.0.0.1:8080/sample.txt
curl -i -H "Range: bytes=0-31" http://127.0.0.1:8080/sample.txt
curl -I --http2-prior-knowledge http://127.0.0.1:8080/sample.txt
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

Covered scenarios include:
- 200 KB parallel chunk download,
- non-even chunk boundaries,
- retry once then succeed,
- retry exhausted failure.

## 4) Benchmark matrix + JSON output

Run benchmark help:

```bash
./gradlew :lib:runBenchmark --args="--help"
```

Run required thread powers (`1,2,4,8,16,32,64`) across all modes:

```bash
./gradlew :lib:runBenchmark --args="--url http://127.0.0.1:8080/sample.txt --mode both --threads 1,2,4,8,16,32,64 --output-json build/benchmark-compare.json"
```

By default, each mode/thread pair runs `5` warm-up iterations (not recorded in `runs`) before measured iterations.
Override warm-up count when needed:

```bash
./gradlew :lib:runBenchmark --args="--url http://127.0.0.1:8080/sample.txt --mode both --threads 1,2,4,8,16,32,64 --warmup-iterations 8 --output-json build/benchmark-compare.json"
```

This writes run-level and summary metrics to JSON (`schemaVersion`, target metadata, host info, per-run elapsed time, and per-mode best thread count).

## 5) Plot benchmark results (Task 5)

Install Python deps and render plots:

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
- `jit_warmup_delta_by_threads.png`

## CLI flags that can improve performance

Useful tuning flags for experiments:
- `--threads`: increase parallelism until network/disk saturates.
- `--chunk-size-bytes`: reduce request overhead with larger chunks; too large can underutilize threads.
- `--io-buffer-bytes`: increase per-thread buffering to reduce syscall pressure.
- `--mode optimized`: usually lower copy overhead versus naive mode.
- `--max-retries` + `--retry-delay-ms`: improve stability on flaky links without over-retrying.
- `--connect-timeout-ms` and `--request-timeout-ms`: avoid hangs and improve benchmark consistency.