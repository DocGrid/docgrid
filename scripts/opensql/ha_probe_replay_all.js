import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

// Replay only IDs frozen from one original synthetic run; never generate new business IDs.
const sourceRunId = __ENV.HA_SOURCE_RUN_ID;
const target = __ENV.HA_TARGET_URL;
const token = __ENV.HA_JWT;
const rate = Number(__ENV.HA_REPLAY_RATE);
const vus = Number(__ENV.HA_REPLAY_VUS);
const maxVus = Number(__ENV.HA_REPLAY_MAX_VUS);
const manifestPath = __ENV.HA_REPLAY_MANIFEST;
if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$/.test(sourceRunId || '') ||
    !manifestPath || !target || !token || !__ENV.HA_SUMMARY_FILE ||
    !Number.isInteger(rate) || rate < 1 || rate > 100 ||
    !Number.isInteger(vus) || vus < 1 || vus > 800 ||
    !Number.isInteger(maxVus) || maxVus < vus || maxVus > 800) {
  throw new Error('Invalid replay configuration');
}

const requests = new SharedArray('frozen-ha-replay-ids', function () {
  const manifest = JSON.parse(open(manifestPath));
  if (manifest.schema_version !== 1 || manifest.run_id !== sourceRunId ||
      !Array.isArray(manifest.request_ids) || manifest.request_ids.length === 0) {
    throw new Error('Invalid replay manifest');
  }
  const seen = new Set();
  return manifest.request_ids.map((requestId) => {
    if (typeof requestId !== 'string' ||
        !requestId.startsWith(`${sourceRunId}-`) ||
        !/^v[0-9]+-i[0-9]+$/.test(requestId.slice(sourceRunId.length + 1)) ||
        seen.has(requestId)) {
      throw new Error('Invalid replay request ID');
    }
    seen.add(requestId);
    return { request_id: requestId };
  });
});

const responses200 = new Counter('ha_replay_200');
const responses201 = new Counter('ha_replay_201');
const responsesOther = new Counter('ha_replay_other');
const responsesUnknown = new Counter('ha_replay_unknown');

export const options = {
  discardResponseBodies: true,
  systemTags: [],
  summaryTrendStats: ['med', 'p(95)', 'p(99)'],
  scenarios: {
    replay: {
      executor: 'constant-arrival-rate', rate, timeUnit: '1s',
      // One extra second lets every frozen ID start without inventing another HTTP request.
      duration: `${Math.ceil(requests.length / rate) + 1}s`,
      preAllocatedVUs: vus, maxVUs: maxVus,
    },
  },
  thresholds: { http_req_failed: ['rate==0'], dropped_iterations: ['count==0'] },
};

function event(requestId, kind, status) {
  const value = { run_id: sourceRunId, request_id: requestId, attempt: 2,
                  kind, at: new Date().toISOString() };
  if (kind === 'result') value.http_status = status;
  console.log(JSON.stringify(value));
}

export default function () {
  // 1. A global scenario iteration selects one original ID, independent of VU scheduling.
  const index = exec.scenario.iterationInTest;
  if (index >= requests.length) return;
  const requestId = requests[index].request_id;
  const body = JSON.stringify({ runId: sourceRunId, requestId, payload: requestId });
  event(requestId, 'sent');

  // 2. Reuse the original body and headers; a renewed JWT may authenticate the same write.
  let status = null;
  try {
    const response = http.post(target, body, {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'X-Ha-Run-Id': sourceRunId,
        'X-Ha-Request-Id': requestId,
      },
      redirects: 0,
      timeout: '10s',
    });
    if (response.status >= 200 && response.status < 600) status = response.status;
  } catch (_) {
    status = null;
  }
  event(requestId, 'result', status);

  // 3. Keep replay outcomes separate from the original k6 counters and HTTP result.
  if (status === 200) responses200.add(1);
  else if (status === 201) responses201.add(1);
  else if (status === null) responsesUnknown.add(1);
  else responsesOther.add(1);
  check(status, { 'same-ID replay returned 200 or 201': (value) => value === 200 || value === 201 });
}

export function handleSummary(data) {
  const count = (name) => data.metrics[name]?.values?.count || 0;
  const summary = {
    source_run_id: sourceRunId,
    manifest_requests: requests.length,
    iterations: count('iterations'),
    http_requests: count('http_reqs'),
    dropped_iterations: count('dropped_iterations'),
    status_counts: {
      '200': count('ha_replay_200'),
      '201': count('ha_replay_201'),
      other: count('ha_replay_other'),
      unknown: count('ha_replay_unknown'),
    },
  };
  return { [__ENV.HA_SUMMARY_FILE]: `${JSON.stringify(summary, null, 2)}\n` };
}
