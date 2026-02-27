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
curl -I http://127.0.0.1:8080/sample.txt
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
./gradlew :lib:runBenchmark --args="--url http://127.0.0.1:8080/sample.txt --output-json build/benchmark-results.json"
```

Install plotting dependency and render charts:

```bash
python3 -m pip install matplotlib
python3 scripts/plot_benchmark.py --input build/benchmark-results.json --output-dir build/benchmark-plots
```