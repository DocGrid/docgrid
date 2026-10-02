#!/usr/bin/env bash
# Print only numeric probe and Hikari metrics; never expose Prometheus labels.
set -euo pipefail

curl --fail --silent --max-time 5 http://127.0.0.1:8081/actuator/prometheus |
  awk '
    /^http_server_requests_seconds_count\{/ && /uri="\/api\/ha-probe\/writes"/ && /status="201"/ {probe += $NF}
    /^hikaricp_connections_active\{/ {active += $NF}
    /^hikaricp_connections_pending\{/ {pending += $NF}
    /^process_cpu_usage / {cpu = $NF}
    END {
      printf "probe_201_total=%.0f\nhikari_active=%.0f\nhikari_pending=%.0f\nprocess_cpu_ratio=%.4f\n", probe, active, pending, cpu
    }
  '
