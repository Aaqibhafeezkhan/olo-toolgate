# Scalability

Gateway and Control support stateless service replicas, with durable authorization,
invocations and audit owned by Control's external PostgreSQL database. Gateway
does not query the database directly, but protected requests require online Control
round trips. Size and measure the complete path, including database contention.

Use [production deployment](../deployment/production.md) for external custody,
networking and Helm. Bound queues, request sizes, deadlines and concurrency; fail
closed when current authority is unavailable. Client presence and delivery use
server-directed polling as described in [ADR 023](../adr/023-server-directed-device-presence.md).

The [recorded enterprise checks](../enterprise-access-control/implementation-status.md)
include small-fixture observations, not general throughput guarantees. Report
workload, hardware, topology, concurrency and p50/p95/p99 together. Multi-host
capacity, soak and multi-region qualification remain [planned work](../planning/README.md).
