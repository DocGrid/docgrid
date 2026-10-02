#!/usr/bin/env node
/** Observe the actual frontend reconnection controller through an authenticated WebSocket. */

import { closeSync, existsSync, openSync, readFileSync, writeFileSync } from "node:fs";
import { performance } from "node:perf_hooks";

import { connectDashboardSocket, shouldRetryDashboardStompError } from
  "../../frontend/app/lib/dashboard-socket-connection.ts";

const args = Object.fromEntries(process.argv.slice(2).map((part, index, all) => {
  if (!part.startsWith("--")) return [];
  return [part.slice(2), all[index + 1]];
}).filter((entry) => entry.length === 2));
const runId = args["run-id"];
if (!/^[a-zA-Z0-9_-]{4,64}$/.test(runId ?? "") ||
    !args["ws-url"] || !args["http-url"] || !args["token-file"] ||
    !args["ready-file"] || !args.output) {
  throw new Error("필수 인자 또는 run ID 형식이 올바르지 않습니다.");
}

// 1. 비밀은 메모리에서만 읽고 원장에는 허용된 상태·숫자만 기록한다.
const token = readFileSync(args["token-file"], "utf8").trim();
const output = openSync(args.output, "wx", 0o600);
let outputClosed = false;
const started = performance.now();
let generation = 0;
let ready = false;
let lostAt = null;
let connectedSnapshot = false;
let messages = 0;
let finished = false;
let resolveFinish;
let rejectFinish;
const completion = new Promise((resolve, reject) => {
  resolveFinish = resolve;
  rejectFinish = reject;
});

function record(event, fields = {}) {
  if (outputClosed) return;
  const kst = new Date().toLocaleString("sv-SE", { timeZone: "Asia/Seoul" });
  writeFileSync(output, `${JSON.stringify({ run_id: runId, 시각_KST: `${kst}+09:00`,
    경과_ms: Math.round(performance.now() - started), 이벤트: event, ...fields })}\n`);
}

function progress() {
  if (!ready && generation === 1 && connectedSnapshot && messages >= 1) {
    ready = true;
    // 2. 준비 표시 뒤에만 B를 중단하므로 최초 연결·수신 성공을 전제로 장애를 건다.
    closeSync(openSync(args["ready-file"], "wx", 0o600));
    record("초기_구독_스냅샷_메시지_확인", { 메시지_건수: messages });
  }
  if (!finished && ready && lostAt !== null && generation >= 2 && connectedSnapshot && messages >= 1) {
    finished = true;
    record("재연결_복구", { 복구시간_ms: Math.round(performance.now() - lostAt),
      재연결_세대: generation, 새_메시지_건수: messages });
    resolveFinish();
  }
}

const stop = connectDashboardSocket({
  url: args["ws-url"],
  token,
  tokenIsCurrent: () => true,
  onStatus: (status) => {
    record("연결_상태", { 상태: status });
    if (status === "POLLING" && ready && lostAt === null) lostAt = performance.now();
    if (status === "LIVE") {
      generation += 1;
      connectedSnapshot = false;
      messages = 0;
    }
  },
  onStompError: async () => {
    try {
      const response = await fetch(args["http-url"], {
        headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(10_000),
      });
      record("STOMP_ERROR_HTTP_재확인", { 상태코드: response.status });
      return shouldRetryDashboardStompError(response.status);
    } catch (error) {
      record("STOMP_ERROR_HTTP_재확인_실패", { 오류종류: error?.constructor?.name ?? "Unknown" });
      return shouldRetryDashboardStompError(null);
    }
  },
  onSnapshot: (reason) => {
    if (reason === "message") {
      messages += 1;
      record("STOMP_메시지", { 연결_세대: generation, 누적_건수: messages });
      progress();
    }
    // 3. 재구독 직후의 HTTP 조회와 그 후의 STOMP push를 별도 관측한다.
    const requestGeneration = generation;
    void fetch(args["http-url"], { headers: { Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(10_000) }).then(async (response) => {
      let documentCount = null;
      if (response.ok) {
        const body = await response.json();
        const count = body?.data?.documents?.total;
        if (typeof count === "number") documentCount = count;
      }
      record("HTTP_스냅샷", { 이유: reason, 연결_세대: requestGeneration,
        상태코드: response.status, 문서수: documentCount });
      if (reason === "connected" && response.status === 200 && requestGeneration === generation) {
        connectedSnapshot = true;
        progress();
      }
    }).catch((error) => record("HTTP_스냅샷_실패", {
      이유: reason, 연결_세대: requestGeneration, 오류종류: error?.constructor?.name ?? "Unknown",
    }));
  },
});

const timeoutSeconds = Math.min(240, Math.max(10, Number(args["timeout-seconds"] ?? 180)));
const deadline = setTimeout(() => rejectFinish(new Error("관측 시간초과")), timeoutSeconds * 1000);
try {
  await completion;
  record("판정", { 결과: "PASS" });
} catch (error) {
  record("판정", { 결과: "FAIL", 오류종류: error?.constructor?.name ?? "Unknown" });
  process.exitCode = 1;
} finally {
  clearTimeout(deadline);
  stop();
  record("정리", { 소켓_종료: true, 준비표시_존재: existsSync(args["ready-file"]) });
  outputClosed = true;
  closeSync(output);
}
