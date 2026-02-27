# Parallel Downloader Playground

## Task 1: Local HTTP/2 Apache file server

### Build image

```bash
docker build -t pdownload-apache-h2 docker/apache
```

### Run server

```bash
docker run -d --rm --name pdownload-httpd -p 8080:80 pdownload-apache-h2
```

### Test from host

```bash
curl -i http://127.0.0.1:8080/sample.txt
curl -i -H "Range: bytes=0-31" http://127.0.0.1:8080/sample.txt
curl -i --http2-prior-knowledge http://127.0.0.1:8080/sample.txt
```

### Stop server

```bash
docker stop pdownload-httpd
```

## Task 5: Plot benchmark results

Generate benchmark JSON:

```bash
./gradlew :lib:runBenchmark --args="--url http://127.0.0.1:8080/sample.txt --mode naive --output-json build/benchmark-naive.json --threads 1,2,4,8,16,32,64"
./gradlew :lib:runBenchmark --args="--url http://127.0.0.1:8080/sample.txt --mode optimized --output-json build/benchmark-optimized.json --threads 1,2,4,8,16,32,64"
./gradlew :lib:runBenchmark --args="--url http://127.0.0.1:8080/sample.txt --mode processes --output-json build/benchmark-processes.json --threads 1,2,4,8,16,32,64"
./gradlew :lib:runBenchmark --args="--url http://127.0.0.1:8080/sample.txt --mode both --output-json build/benchmark-compare.json --threads 1,2,4,8,16,32,64"
```

Install plotting dependency and render charts:

```bash
python3 -m pip install uv
uv init
# Acitivate the environment with source .venv/bin/activate
uv run scripts/plot_benchmark.py --input build/benchmark-compare.json --output-dir build/benchmark-plots
```

## Java 25 benchmark run

Use Java 25 in your shell, then run:

```bash
./gradlew :lib:runBenchmark --args="--url http://127.0.0.1:8080/sample.txt --mode both --output-json build/benchmark-zulu25.json --threads 1,2,4,8,16,32,64"
```