# Persistent device notifications

Status: unfinished device/Control work. The current
[check-in contract](../control-plane/configuration.md#client-check-in) remains the
operating guide; the settings below are proposals, not available configuration.

Provide persistent authenticated WebSocket notification for queued jobs, permission
changes and kill events, with bounded polling fallback during disconnects.
Define heartbeat, pickup timeout, socket grace and offline semantics independently.
Use matching current client and Control artifacts; do not add old-client fallback
formats or a mixed-version compatibility path.

Acceptance requires idle/reconnect/partition/crash cases, current identity and
revocation checks, bounded queue/backpressure and cross-tenant negatives. An
ambiguous leased effect must never be reassigned or replayed because its socket
disconnected. Qualify the declared connectivity bounds with actual measurements.

Proposed setting families are socket mode, heartbeat interval, fallback-poll
interval, job-pickup timeout and socket grace. Review their canonical contract,
validation and deployment wiring before exposing them as supported settings.
