#!/usr/bin/env bash
set -euo pipefail

THREADS="64,32,16,8,4,2,1"

# Smaller sizes first so early jobs finish quickly
KB_SIZES=(64 128 256 512 1024)
MB_SIZES=(64 128 256 512 1024)

echo "Starting benchmarks: $(date)"
echo "Threads order: ${THREADS}"

for size in "${KB_SIZES[@]}"; do
  echo "Running KB benchmark size=${size}KB at $(date)"
  ./gradlew :lib:runBenchmark --args="--url http://127.0.0.1:8080/sample-${size}KB.txt --modes naive,optimized --threads ${THREADS} --warmup-iterations 3 --iterations 5 --output-json build/benchmark-sample-${size}KB.json"
done

for size in "${MB_SIZES[@]}"; do
  echo "Running MB benchmark size=${size}MB at $(date)"
  ./gradlew :lib:runBenchmark --args="--url http://127.0.0.1:8080/sample-${size}MB.txt --modes naive,optimized --threads ${THREADS} --warmup-iterations 3 --iterations 5 --output-json build/benchmark-sample-${size}MB.json"
done

echo "Benchmarks completed: $(date)"
