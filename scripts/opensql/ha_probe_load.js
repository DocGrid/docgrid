import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// Only run IDs, request IDs, numeric outcomes and times are emitted to the live ledger.
const runId = __ENV.HA_RUN_ID;
const target = __ENV.HA_TARGET_URL;
const token = __ENV.HA_JWT;
const rate = Number(__ENV.HA_RATE || 10);
const duration = __ENV.HA_DURATION || '60s';
const idempotent = __ENV.HA_IDEMPOTENT === '1';

// Keep outcome metrics tag-free so the existing JSONL evidence gate stays strict.
const outcome201 = new Counter('ha_outcome_201');
const outcome500 = new Counter('ha_outcome_500');
const outcome503 = new Counter('ha_outcome_503');
const outcomeOtherFailed = new Counter('ha_outcome_other_failed');
const outcomeUnknown = new Counter('ha_outcome_unknown');
// Count received HTTP responses separately from unknown transport outcomes.
const http2xx = new Counter('ha_http_2xx');
const http3xx = new Counter('ha_http_3xx');
const http4xx = new Counter('ha_http_4xx');
const http5xx = new Counter('ha_http_5xx');

if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$/.test(runId || '') || !target || !token || !__ENV.HA_SUMMARY_FILE) {
  throw new Error('A short HA_RUN_ID, HA_TARGET_URL, HA_JWT and HA_SUMMARY_FILE are required');
}

export const options = {
  discardResponseBodies: true,
  // 1. Do not emit URL, error text or other infrastructure identifiers as metric tags.
  systemTags: [],
  summaryTrendStats: ['med', 'p(95)', 'p(99)'],
  scenarios: {
    writes: {
      executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration,
      preAllocatedVUs: Number(__ENV.HA_VUS || 20), maxVUs: Number(__ENV.HA_MAX_VUS || 100),
    },
  },
  thresholds: { http_req_failed: ['rate<0.01'], dropped_iterations: ['count==0'] },
};

function event(requestId, kind, fields = {}) {
  console.log(JSON.stringify({
    event_id: `${requestId}-${kind}`, run_id: runId,
    at: new Date().toISOString(), kind, request_id: requestId, ...fields,
  }));
}

export default function () {
  const requestId = `${runId}-v${exec.vu.idInTest}-i${exec.vu.iterationInScenario}`;
  event(requestId, 'sent', { operation: idempotent ? 'ha_probe_idempotent_write' : 'ha_probe_write' });
  let response;
  try {
    // The optional synthetic query ID lets short-lived LB logs join this external ledger.
    const requestTarget = __ENV.HA_TRACE_QUERY === '1'
      ? `${target}${target.includes('?') ? '&' : '?'}ha_request_id=${encodeURIComponent(requestId)}`
      : target;
    // The synthetic payload is derived only from the stable ID, so a replay sends identical bytes.
    const body = idempotent ? { runId, requestId, payload: requestId } : { runId, requestId };
    response = http.post(requestTarget, JSON.stringify(body), {
      // The profile-gated app diagnostic joins sanitized failure logs to this external ledger.
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'X-Ha-Run-Id': runId,
        'X-Ha-Request-Id': requestId,
      },
      timeout: __ENV.HA_TIMEOUT || '10s',
    });
  } catch (_) {
    outcomeUnknown.add(1);
    event(requestId, 'unknown', { reason: 'other' });
    return;
  }
  // 2. Class counters stay tag-free; run_id is added only by Remote Write.
  if (response.status >= 200 && response.status < 300) http2xx.add(1);
  else if (response.status >= 300 && response.status < 400) http3xx.add(1);
  else if (response.status >= 400 && response.status < 500) http4xx.add(1);
  else if (response.status >= 500 && response.status < 600) http5xx.add(1);
  if (response.status === 201) {
    outcome201.add(1);
    event(requestId, 'acknowledged', { http_status: 201 });
  } else if (response.status >= 200 && response.status < 600) {
    // 3. Keep every unexpected HTTP response visible alongside 500/503.
    if (response.status === 500) outcome500.add(1);
    else if (response.status === 503) outcome503.add(1);
    else outcomeOtherFailed.add(1);
    event(requestId, 'failed', { http_status: response.status });
  } else {
    // A status of 0 alone cannot distinguish a timeout from a connection failure.
    outcomeUnknown.add(1);
    event(requestId, 'unknown', { reason: 'other' });
  }
  check(response, { '201 commit response': (r) => r.status === 201 });
}

export function handleSummary(data) {
  const values = (name) => data.metrics[name]?.values || {};
  const summary = {
    run_id: runId,
    iterations: values('iterations').count || 0,
    http_requests: values('http_reqs').count || 0,
    dropped_iterations: values('dropped_iterations').count || 0,
    failed_rate: values('http_req_failed').rate || 0,
    duration_ms: {
      p50: values('http_req_duration')['med'] || 0,
      p95: values('http_req_duration')['p(95)'] || 0,
      p99: values('http_req_duration')['p(99)'] || 0,
    },
  };
  return { [__ENV.HA_SUMMARY_FILE]: `${JSON.stringify(summary, null, 2)}\n` };
}
