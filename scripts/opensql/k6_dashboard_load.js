import ws from 'k6/ws';
import { Counter, Trend } from 'k6/metrics';

// This script records only numeric STOMP measurements; the JWT stays in a 0600 file.
const token = open(__ENV.DASHBOARD_TOKEN_FILE).trim();
const clients = Number(__ENV.DASHBOARD_CLIENTS || '1');
const warmupSeconds = Number(__ENV.DASHBOARD_WARMUP_SECONDS || '10');
const measureSeconds = Number(__ENV.DASHBOARD_MEASURE_SECONDS || '30');
const dashboardUrl = __ENV.DASHBOARD_WS_URL;

const connected = new Counter('dashboard_connected');
const received = new Counter('dashboard_received');
const errors = new Counter('dashboard_errors');
const negativeClock = new Counter('dashboard_negative_clock');
const gaps = new Counter('dashboard_sequence_gaps');
const duplicates = new Counter('dashboard_duplicates');
const latency = new Trend('dashboard_latency_ms', true);

export const options = {
  scenarios: {
    dashboard: {
      executor: 'per-vu-iterations',
      vus: clients,
      iterations: 1,
      maxDuration: `${warmupSeconds + measureSeconds + 30}s`,
    },
  },
  thresholds: {
    dashboard_connected: [`count==${clients}`],
    dashboard_errors: ['count==0'],
    dashboard_negative_clock: ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)', 'count'],
};

function processFrame(frame, socket, state) {
  const value = frame.replace(/^\n+/, '');
  if (value.startsWith('CONNECTED')) {
    if (!state.subscribedAt) {
      socket.send(`SUBSCRIBE\nid:dashboard-${__VU}\ndestination:/topic/dashboard\nack:auto\n\n\0`);
      state.subscribedAt = Date.now();
      connected.add(1);
    }
    return;
  }
  if (value.startsWith('ERROR')) {
    errors.add(1);
    socket.close();
    return;
  }
  if (!value.startsWith('MESSAGE\n')) {
    return;
  }
  const now = Date.now();
  if (!state.subscribedAt || now < state.subscribedAt + warmupSeconds * 1000
      || now >= state.subscribedAt + (warmupSeconds + measureSeconds) * 1000) {
    return;
  }
  const separator = value.indexOf('\n\n');
  if (separator < 0) {
    errors.add(1);
    return;
  }
  try {
    const body = JSON.parse(value.slice(separator + 2));
    const sequence = Number(body.documents.total);
    const sentEpochMillis = Number(body.documents.searchable);
    if (!Number.isSafeInteger(sequence) || !Number.isSafeInteger(sentEpochMillis)) {
      errors.add(1);
      return;
    }
    const elapsed = now - sentEpochMillis;
    if (elapsed < 0) {
      negativeClock.add(1);
    } else {
      if (state.lastSequence && sequence > state.lastSequence + 1) {
        gaps.add(sequence - state.lastSequence - 1);
      } else if (state.lastSequence && sequence <= state.lastSequence) {
        duplicates.add(1);
      }
      state.lastSequence = sequence;
      latency.add(elapsed);
      received.add(1);
    }
  } catch (_) {
    // A malformed frame is counted without logging its potentially private payload.
    errors.add(1);
  }
}

export default function () {
  const state = { subscribedAt: 0, lastSequence: 0 };
  const response = ws.connect(dashboardUrl, {}, (socket) => {
    socket.on('open', () => {
      socket.send(`CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer ${token}\n\n\0`);
    });
    socket.on('message', (data) => {
      String(data).split('\0').filter((frame) => frame.trim()).forEach((frame) => {
        processFrame(frame, socket, state);
      });
    });
    socket.on('error', (error) => {
      if (!error || !error.error || error.error() !== 'websocket: close sent') {
        errors.add(1);
      }
    });
    socket.setTimeout(() => socket.close(), (warmupSeconds + measureSeconds + 2) * 1000);
  });
  if (!response || response.status !== 101) {
    errors.add(1);
  }
}

export function handleSummary(data) {
  const names = [
    'dashboard_connected', 'dashboard_received', 'dashboard_errors',
    'dashboard_negative_clock', 'dashboard_sequence_gaps',
    'dashboard_duplicates', 'dashboard_latency_ms',
  ];
  const metrics = {};
  for (const name of names) {
    metrics[name] = data.metrics[name] ? data.metrics[name].values : null;
  }
  // Allowlist the summary fields before k6 writes any artifact. Never include endpoint tags.
  const summary = {
    run_id: __ENV.DASHBOARD_RUN_ID,
    clients_requested: clients,
    warmup_seconds: warmupSeconds,
    measure_seconds: measureSeconds,
    metrics,
  };
  return { [__ENV.DASHBOARD_SUMMARY_FILE]: `${JSON.stringify(summary, null, 2)}\n` };
}
