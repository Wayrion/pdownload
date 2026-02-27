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