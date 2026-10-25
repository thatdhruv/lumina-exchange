# Operations

## Sizing and scaling

| Role | Replicas | Notes |
|------|----------|-------|
| `api` | HPA on CPU (2 to 10 by default) | Stateless. Each pod also consumes the full event stream, so CPU grows with event rate. |
| `engine` | 2 or more, **at most `kafka.partitions`** | The chart refuses to render more. Each replica owns a share of the partitions; a spare replica is a hot standby for failover. |
| `projector` | 1 or more, at most `kafka.partitions` | Stateless; Postgres write rate is the limit. |

`kafka.partitions` is permanent (see Known limits). Production Kafka: replication factor 3,
`min.insync.replicas` 2, which is what the chart defaults to.

## Health and readiness

- Probes and Prometheus use the management port **8081**. Keep it off the public network
  (`networkPolicy.enabled=true` restricts it).
- `api` readiness includes `eventStream`: a pod that has not joined the event stream would miss
  the answer to its first command, so it is not ready until partitions are assigned.

## What to watch

The Grafana dashboard (`deploy/observability/grafana/dashboards/lumina.json`) and alert rules
(`charts/lumina-exchange/files/alerts.yml`, also installed as a PrometheusRule) cover:

| Signal | Metric | Meaning |
|--------|--------|---------|
| Order latency | `lumina_gateway_order_seconds` | SLO: p99 under 250 ms |
| Error rate | same, `outcome="error"` | Timeouts or an unreachable command log |
| Partitions owned | `lumina_engine_partitions` | Zero everywhere means orders are timing out |
| Recoveries | `lumina_engine_recoveries_total` | A batch failed to publish and state was rebuilt |
| Poison commands | `lumina_engine_poison_total` | An undecodable command was skipped (deterministically) |
| Lag | `kafka_consumer_fetch_manager_records_lag_max` | Engine on `exchange.commands`, projector on `exchange.events` |
| Gaps | `lumina_projector_gaps_total` | A missing event sequence. The read model may be inconsistent |

Traces: set `tracing.otlpEndpoint` (or `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`) and a sampling
probability. Logs are ECS-structured JSON when deployed via the compose file or the chart (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`).

## Runbooks

**An engine pod died.** Nothing to do. Another replica takes its partitions within the session
timeout and replays from the last snapshot. Clients may see 504 during the gap; they should look the
order up (or retry with the same `Idempotency-Key`) rather than resubmit blindly.

**Planned restart / deploy.** The StatefulSet gives the pod 60 s to flush and write a final
snapshot, so the replacement replays almost nothing. Static membership keeps partitions with the
same pod name across restarts. The PDB allows one engine pod down at a time.

**Engine snapshot is corrupt.** The partition fails to start with a clear error. Delete that row
from `engine_snapshots` and restart: the engine replays the full command log. Correct, but takes
time proportional to log length.

**The read model is wrong or empty.** Stop the projector, truncate `orders`, `trades`,
`book_levels`, `projection_cursor`, reset the `lumina-projector` consumer group to earliest, start
it again. It rebuilds from the event stream (retained 7 days; extend `exchange.events` retention
or replay the engine to regenerate older history).

**Rebuilding events from scratch.** Delete the `engine_snapshots` rows and the events topic, restart
the engines: they replay the command log and re-publish every event with identical sequences.

**Poison command.** The engine logs it, counts it, and skips it. Because the skip is a pure function
of the bytes, replay skips it too. Find it with the offset in the log line.

## Security notes

- API keys come from a Secret and are compared as SHA-256 digests. Rotate by updating the Secret and
  restarting `api`. Replace with OAuth2/JWT at the gateway for real users.
- The UI's route handler injects one fixed key for the demo. A real deployment authenticates users
  there and maps each to their own key or account.
- Rate limits are per gateway pod. Put a global limit at the ingress.
- Containers run as non-root with a read-only root filesystem and no capabilities.
