/** 관리자 대시보드 연결 상태. 실시간 수신이 끊겨도 화면의 HTTP 폴링은 별도로 유지된다. */
export type DashboardSocketStatus = "CONNECTING" | "LIVE" | "POLLING";

/** 관리자 HTTP 인가의 명시적 거부만 영구 중단하고, 확인 불가·일시 장애는 재시도한다. */
export function shouldRetryDashboardStompError(httpStatus: number | null): boolean {
  return httpStatus !== 401 && httpStatus !== 403;
}

/** 브라우저 WebSocket과 결정적 테스트의 가짜 소켓이 공유하는 최소 전송 경계. */
export interface DashboardSocketTransport {
  send(frame: string): void;
  close(): void;
}

/** 연결마다 등록하는 이벤트. 구독 성공과 연결 실패를 서로 다른 단계로 취급한다. */
export interface DashboardSocketEvents {
  open(): void;
  frame(value: string): void;
  fail(): void;
  close(): void;
}

/** 시간·소켓을 주입해 재시도와 정리를 실제 네트워크 없이 검증할 수 있게 한다. */
export interface DashboardSocketDependencies {
  createSocket?: (url: string, events: DashboardSocketEvents) => DashboardSocketTransport;
  schedule?: (callback: () => void, delayMs: number) => ReturnType<typeof setTimeout>;
  cancel?: (timer: ReturnType<typeof setTimeout>) => void;
  random?: () => number;
}

/** 한 관리자 화면의 연결만 소유한다. HTTP snapshot 조회 자체는 호출자가 담당한다. */
export interface DashboardSocketOptions {
  url: string;
  token: string;
  tokenIsCurrent: () => boolean;
  onStatus: (status: DashboardSocketStatus) => void;
  onSnapshot: (reason: "connected" | "message") => void;
  onStompError?: () => Promise<boolean>;
  dependencies?: DashboardSocketDependencies;
}

/** 일시적인 전송 장애에는 재연결하고, 서버의 STOMP ERROR에는 폴링으로 안전하게 남는다. */
export function connectDashboardSocket(options: DashboardSocketOptions): () => void {
  const createSocket = options.dependencies?.createSocket ?? browserSocket;
  const schedule = options.dependencies?.schedule ?? setTimeout;
  const cancel = options.dependencies?.cancel ?? clearTimeout;
  const random = options.dependencies?.random ?? Math.random;
  let disposed = false;
  let generation = 0;
  let attempt = 0;
  let socket: DashboardSocketTransport | null = null;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;

  function stop() {
    disposed = true;
    generation += 1;
    if (retryTimer !== null) cancel(retryTimer);
    retryTimer = null;
    socket?.close();
    socket = null;
  }

  function currentToken() {
    if (disposed || !options.tokenIsCurrent()) {
      stop();
      return false;
    }
    return true;
  }

  function retry() {
    if (!currentToken() || retryTimer !== null) return;
    // 1. 최대 30초의 지수 backoff에 ±20% jitter를 적용해 동시 재시작의 재접속 집중을 피한다.
    const base = Math.min(30_000, 1000 * 2 ** Math.min(attempt, 5));
    const delay = Math.min(30_000, Math.round(base * (0.8 + random() * 0.4)));
    attempt += 1;
    retryTimer = schedule(() => {
      retryTimer = null;
      connect();
    }, delay);
  }

  function connect() {
    if (!currentToken()) return;
    options.onStatus("CONNECTING");
    const cycle = ++generation;
    let connected = false;

    function disconnect(reconnect: boolean) {
      if (disposed || cycle !== generation) return;
      // 2. error와 close가 연이어 와도 이 세대의 연결은 한 번만 정리·재시도한다.
      generation += 1;
      const previous = socket;
      socket = null;
      previous?.close();
      options.onStatus("POLLING");
      if (reconnect) retry();
    }

    try {
      socket = createSocket(options.url, {
        open: () => {
          if (!currentToken() || cycle !== generation) return;
          socket?.send(`CONNECT\naccept-version:1.2\nAuthorization:Bearer ${options.token}\nheart-beat:10000,10000\n\n\0`);
        },
        frame: (value) => {
          if (!currentToken() || cycle !== generation) return;
          if (value.startsWith("ERROR")) {
            // 3. STOMP 인증 실패와 Redis 일시 장애를 프레임만으로 구분할 수 없어 HTTP 인가를 재확인한다.
            disconnect(false);
            void (async () => {
              try {
                if (await options.onStompError?.()) retry();
              } catch {
                // HTTP 경로도 일시 장애라면 소켓은 차단한 채 제한된 backoff만 계속한다.
                retry();
              }
            })();
          } else if (value.startsWith("CONNECTED") && !connected) {
            socket?.send("SUBSCRIBE\nid:ragops-dashboard\ndestination:/topic/dashboard\nack:auto\n\n\0");
            connected = true;
            attempt = 0;
            options.onStatus("LIVE");
            // 4. Pub/Sub가 저장하지 않은 변경은 구독 직후 DB 기반 HTTP snapshot으로 보정한다.
            options.onSnapshot("connected");
          } else if (value.startsWith("MESSAGE") && connected) {
            options.onSnapshot("message");
          }
        },
        fail: () => disconnect(true),
        close: () => disconnect(true),
      });
    } catch {
      disconnect(true);
    }
  }

  connect();
  return stop;
}

function browserSocket(url: string, events: DashboardSocketEvents): DashboardSocketTransport {
  const socket = new WebSocket(url);
  socket.onopen = events.open;
  socket.onmessage = (event) => events.frame(String(event.data));
  socket.onerror = events.fail;
  socket.onclose = events.close;
  return socket;
}
