import assert from "node:assert/strict";
import test from "node:test";

import { connectDashboardSocket, shouldRetryDashboardStompError,
  type DashboardSocketDependencies, type DashboardSocketEvents,
  type DashboardSocketStatus } from "../app/lib/dashboard-socket-connection.ts";

function harness(random = 0.5, onStompError?: () => Promise<boolean>) {
  const sockets: Array<{ events: DashboardSocketEvents; sent: string[]; closed: boolean }> = [];
  const timers = new Map<number, { callback: () => void; delay: number }>();
  const statuses: DashboardSocketStatus[] = [];
  let nextTimerId = 0;
  let token = "test-token";
  let snapshots = 0;
  const snapshotReasons: string[] = [];
  const dependencies: DashboardSocketDependencies = {
    createSocket: (_url, events) => {
      const socket = { events, sent: [] as string[], closed: false };
      sockets.push(socket);
      return {
        send: (frame) => socket.sent.push(frame),
        close: () => { socket.closed = true; },
      };
    },
    schedule: (callback, delay) => {
      const id = ++nextTimerId;
      timers.set(id, { callback, delay });
      return id as unknown as ReturnType<typeof setTimeout>;
    },
    cancel: (timer) => { timers.delete(timer as unknown as number); },
    random: () => random,
  };
  const stop = connectDashboardSocket({
    url: "ws://test.invalid/ws/websocket",
    token: "test-token",
    tokenIsCurrent: () => token === "test-token",
    onStatus: (status) => statuses.push(status),
    onSnapshot: (reason) => { snapshots += 1; snapshotReasons.push(reason); },
    onStompError,
    dependencies,
  });
  return {
    sockets, timers, statuses, stop,
    snapshots: () => snapshots, snapshotReasons,
    setToken: (value: string) => { token = value; },
    nextRetry: () => {
      const entry = timers.entries().next().value;
      assert.ok(entry, "재시도 타이머가 있어야 합니다.");
      timers.delete(entry[0]);
      entry[1].callback();
    },
  };
}

test("B 재시작으로 소켓이 닫히면 한 번만 재접속하고 새 구독 직후 snapshot을 읽는다", () => {
  const run = harness();
  const first = run.sockets[0];
  first.events.open();
  first.events.frame("CONNECTED\n\n\0");
  first.events.frame("CONNECTED\n\n\0");
  assert.equal(run.snapshots(), 1);
  assert.equal(first.sent.filter((frame) => frame.startsWith("SUBSCRIBE")).length, 1);
  assert.match(first.sent[1], /^SUBSCRIBE/);

  first.events.close();
  first.events.fail();
  assert.equal(run.timers.size, 1, "error와 close가 겹쳐도 재시도는 하나여야 합니다.");
  assert.deepEqual([...run.timers.values()].map((timer) => timer.delay), [1000]);
  run.nextRetry();
  const second = run.sockets[1];
  second.events.open();
  second.events.frame("CONNECTED\n\n\0");
  assert.equal(run.snapshots(), 2, "놓친 Redis 신호를 구독 직후 HTTP snapshot으로 보정합니다.");
  assert.equal(second.sent.filter((frame) => frame.startsWith("SUBSCRIBE")).length, 1);
  second.events.frame("MESSAGE\n\n{}\0");
  assert.equal(run.snapshots(), 3);
  assert.deepEqual(run.snapshotReasons, ["connected", "connected", "message"]);
  assert.deepEqual(run.statuses, ["CONNECTING", "LIVE", "POLLING", "CONNECTING", "LIVE"]);
  run.stop();
});

test("연속 장애에는 backoff를 늘리고 성공 후 첫 재시도 간격을 되돌린다", () => {
  const run = harness();
  run.sockets[0].events.close();
  assert.equal([...run.timers.values()][0].delay, 1000);
  run.nextRetry();
  run.sockets[1].events.close();
  assert.equal([...run.timers.values()][0].delay, 2000);
  run.nextRetry();
  run.sockets[2].events.frame("CONNECTED\n\n\0");
  run.sockets[2].events.close();
  assert.equal([...run.timers.values()][0].delay, 1000);
  run.stop();
});

test("언마운트는 소켓과 타이머를 정리해 늦은 callback이 재연결하지 못하게 한다", () => {
  const run = harness();
  run.sockets[0].events.close();
  run.stop();
  assert.equal(run.timers.size, 0);
  assert.equal(run.sockets[0].closed, true);
  run.sockets[0].events.close();
  assert.equal(run.sockets.length, 1);
});

test("로그아웃으로 토큰이 바뀌면 이전 토큰으로 재접속하지 않는다", () => {
  const run = harness();
  run.setToken("");
  run.sockets[0].events.close();
  assert.equal(run.timers.size, 0);
  assert.equal(run.sockets.length, 1);
});

test("STOMP ERROR에 재확인 경로가 없으면 HTTP 폴링에 남는다", () => {
  const run = harness();
  run.sockets[0].events.frame("ERROR\nmessage:Access denied\n\n\0");
  assert.equal(run.timers.size, 0);
  assert.equal(run.sockets[0].closed, true);
  assert.equal(run.statuses.at(-1), "POLLING");
  run.stop();
});

test("Redis 장애로 인한 STOMP ERROR 후 HTTP 관리자가 확인되면 제한된 간격으로 재접속한다", async () => {
  const run = harness(0.5, async () => true);
  run.sockets[0].events.frame("ERROR\nmessage:Access denied\n\n\0");
  await new Promise(setImmediate);
  assert.deepEqual([...run.timers.values()].map((timer) => timer.delay), [1000]);
  run.nextRetry();
  run.sockets[1].events.open();
  run.sockets[1].events.frame("CONNECTED\n\n\0");
  assert.equal(run.snapshots(), 1);
  run.stop();
});

test("HTTP에서 관리자 권한을 거부하면 STOMP ERROR 뒤에는 재접속하지 않는다", async () => {
  const run = harness(0.5, async () => false);
  run.sockets[0].events.frame("ERROR\n\n\0");
  await new Promise(setImmediate);
  assert.equal(run.timers.size, 0);
  assert.equal(run.statuses.at(-1), "POLLING");
  run.stop();
});

test("관리자 HTTP 401·403만 재접속을 멈추고 일시 장애는 안전하게 재시도한다", () => {
  assert.equal(shouldRetryDashboardStompError(401), false);
  assert.equal(shouldRetryDashboardStompError(403), false);
  assert.equal(shouldRetryDashboardStompError(503), true);
  assert.equal(shouldRetryDashboardStompError(null), true);
  assert.equal(shouldRetryDashboardStompError(200), true);
});

test("jitter는 재접속 시각을 분산하고 상한을 넘기지 않는다", () => {
  const early = harness(0);
  early.sockets[0].events.close();
  assert.equal([...early.timers.values()][0].delay, 800);
  early.stop();

  const late = harness(1);
  late.sockets[0].events.close();
  assert.equal([...late.timers.values()][0].delay, 1200);
  for (let i = 0; i < 6; i += 1) {
    late.nextRetry();
    late.sockets.at(-1)?.events.close();
  }
  assert.equal([...late.timers.values()][0].delay, 30_000);
  late.stop();
});
