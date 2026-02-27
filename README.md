# Parallel Downloader Playground

## Task 1: Local HTTP/2 Apache file server

This repo includes a Dockerized Apache HTTP server configured to:
- serve static files from `/usr/local/apache2/htdocs`
- support byte-range downloads (`Accept-Ranges: bytes`)
- expose content length (`Content-Length`)
- advertise HTTP/2 support (`h2`/`h2c`)

### Build image

```bash
docker build -t pdownload-apache-h2 docker/apache
```

### Run server

```bash
docker run --rm --name pdownload-httpd -p 8080:80 pdownload-apache-h2
```

For interactive shells, prefer detached mode so terminal signals do not stop Apache:

```bash
docker run -d --rm --name pdownload-httpd -p 8080:80 pdownload-apache-h2
```

Check the container is running:

```bash
docker ps --filter name=pdownload-httpd
docker logs pdownload-httpd --tail 50
```

### Verify server is reachable from host

```bash
curl -i http://localhost:8080/sample.txt
```

If `localhost` resolution is inconsistent in your environment, use:

```bash
curl -i http://127.0.0.1:8080/sample.txt
```

Expected:
- status `200 OK`
- body includes the sample file text

### Verify required HEAD headers

```bash
curl -I http://localhost:8080/sample.txt
```

Expected headers include:
- `Accept-Ranges: bytes`
- `Content-Length: <number>`

### Verify ranged request works

```bash
curl -i -H "Range: bytes=0-31" http://localhost:8080/sample.txt
```

Expected:
- status `206 Partial Content`
- `Content-Range` header present
- returned body contains only the requested slice

### Verify HTTP/2 support

For cleartext HTTP/2 (h2c), use:

```bash
curl -i --http2-prior-knowledge http://localhost:8080/sample.txt
```

If your local `curl` does not support prior knowledge mode, verify protocol negotiation by checking container config and module list:

```bash
docker exec -it pdownload-httpd httpd -M | grep http2
docker exec -it pdownload-httpd httpd -t -D DUMP_RUN_CFG | grep -i protocol
```

### Stop server

```bash
docker stop pdownload-httpd
```

### Quick troubleshooting

If you see `Connection refused`, verify the container still exists and inspect modules:

```bash
docker ps --filter name=pdownload-httpd
docker logs pdownload-httpd --tail 100
docker run --rm --name pdownload-httpd -p 8080:80 pdownload-apache-h2 httpd -M | grep -E "http2|headers|deflate"
```

Other resources: https://blog.jetbrains.com/kotlin/2025/05/kotlin-and-azul-collaboration-for-enhanced-runtime-performance/