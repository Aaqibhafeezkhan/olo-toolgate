# Gateway performance verification

Measure the complete request path before making latency or capacity claims.
Protected execution includes current Control authorization and database-backed
state; an ingress-only benchmark does not measure those costs.

```sh
python tools/gateway/benchmark.py
```

This runs the `ingress-validation` Rust microbenchmark and writes its execution
context and per-request parsing measurement to `build/gateway/benchmark.json`.
Network, TLS, Control, executor work and collector I/O are excluded. Earlier
static-policy evaluation numbers do not describe the current online runtime.

For an end-to-end evaluation, run the [enterprise verification workflow](../testing/README.md)
and then your deployment's workload. Record source revision, hardware, topology,
concurrency, tool/catalog size, warmup, sample count, p50/p95/p99, error rate and
memory. Include outage, queue pressure and denied requests. Keep performance
observations separate from deterministic correctness gates.

[Recorded enterprise evidence](../enterprise-access-control/implementation-status.md)
contains small-fixture results with explicit limits; enterprise-scale qualification
remains in [the backlog](../planning/README.md).
