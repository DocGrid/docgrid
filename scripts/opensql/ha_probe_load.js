import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

// Only run IDs, request IDs, numeric outcomes and times are emitted to the live ledger.
const runId = __ENV.HA_RUN_ID;
const target = __ENV.HA_TARGET_URL;
const token = __ENV.HA_JWT;
const rate = Number(__ENV.HA_RATE || 10);
const duration = __ENV.HA_DURATION || '60s';

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
  event(requestId, 'sent', { operation: 'ha_probe_write' });
  let response;
  try {
    response = http.post(target, JSON.stringify({ runId, requestId }), {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      timeout: __ENV.HA_TIMEOUT || '10s',
    });
  } catch (_) {
    event(requestId, 'unknown', { reason: 'other' });
    return;
  }
  if (response.status === 201) {
    event(requestId, 'acknowledged', { http_status: 201 });
  } else if (response.status >= 300 && response.status < 600) {
    event(requestId, 'failed', { http_status: response.status });
  } else {
    // A status of 0 alone cannot distinguish a timeout from a connection failure.
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
