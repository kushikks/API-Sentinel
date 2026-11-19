# Developer A load testing

The k6 script uses authenticated requests and a generated 32-operation OpenAPI contract with 16 referenced resource schemas. The mixed reads cover API listing, version retrieval, findings, summaries and the authenticated profile. The worker scenario uploads each changed specification, submits a comparison against a shared baseline, and waits for a terminal result.

| Scenario | Default workload | Threshold |
| --- | --- | --- |
| `volume` | 10,000 mixed business reads with 100 VUs | At least 99% successful checks and less than 1% HTTP errors |
| `sustained` | 50 reads per second for 120 seconds, up to 100 VUs | No dropped iterations, p95 below one second and less than 1% HTTP errors |
| `jobs` | 100 comparisons with up to 12 submission VUs | All jobs complete with expected breaking findings and less than 1% HTTP errors |

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests/load/analyzer/run-local.ps1 -Scenario volume
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests/load/analyzer/run-local.ps1 -Scenario sustained
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests/load/analyzer/run-local.ps1 -Scenario jobs
docker @compose up -d --no-build --scale analyzer=2 --wait
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests/load/analyzer/run-local.ps1 -Scenario jobs
```

The runner writes JSON in `.local/evidence/` with configured VUs, request count and rate, p50/p95/p99, errors, threshold status, hostname-independent environment details, Analyzer replicas, completed jobs, throughput, and before/after broker queue snapshots. It also samples per-replica completion counters when the local metrics key is present. The worker turnaround trend includes queue time and polling. Throughput is completed jobs divided by test wall-clock duration. Speedup depends on the Registry, broker, database, data shape and CPU resources.

The standalone runs exercise the three Developer A services directly. The full lab benchmark also requires Developer B's Gateway and downstream services on the deployment environment. See [implementation status](IMPLEMENTATION_STATUS.md) for executed results.

## Measured results

The 19 November 2026 runs used Windows NT 10.0.26200.0 with 20 host logical CPUs and a Docker Linux engine with 20 CPUs and 8,126,300,160 bytes of memory. The k6 fixture was 21,962 bytes before the changes. Each Analyzer replica used the default two job-processing threads.

| Scenario | Analyzer replicas | Iterations | Max VUs | HTTP requests | RPS | p50 | p95 | p99 | HTTP error rate |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Volume | 1 | 10,000 | 100 | 10,007 | 939.66 | 63.82 ms | 196.33 ms | 423.81 ms | 0% |
| Sustained | 1 | 6,000 | 20 | 6,007 | 49.05 | 4.35 ms | 7.11 ms | 9.13 ms | 0% |
| Analysis jobs | 1 | 100 | 12 | 541 | 29.32 | 15.42 ms | 185.03 ms | 362.56 ms | 0% |
| Analysis jobs | 2 | 100 | 12 | 445 | 38.59 | 18.68 ms | 85.15 ms | 468.60 ms | 0% |

All four runs met their configured thresholds. The sustained run issued 6,000 reads over the 120-second arrival schedule with zero dropped iterations. The aggregate k6 request count and RPS include fixture setup. The worker scenarios include job-status polling. A poll may return 404 before the message is consumed, which the job scenario explicitly treats as an expected transient response.

Both worker runs completed all 100 jobs. Wall-clock throughput increased from 5.42 to 8.67 completed jobs per second. The two-replica counter deltas were 47 and 53. Analyzer's RabbitMQ input and dead-letter queues had zero ready and unacknowledged messages after both runs. The Impact queue retained completions for the separate consumer.

The raw results are `k6-volume-1-replicas.json`, `k6-sustained-1-replicas.json`, `k6-jobs-1-replicas.json`, and `k6-jobs-2-replicas.json` in `.local/evidence/`. Corresponding `queue-*.json` files contain broker and replica snapshots.
