"use client";

import { useEffect, useState } from "react";
import { ACCESS_TOKEN_KEY, AUTH_EXPIRED_EVENT, ApiError, apiRequest } from "./api";
import { connectDashboardSocket, shouldRetryDashboardStompError, type DashboardSocketStatus } from "./dashboard-socket-connection";

const WS_BASE_URL = (process.env.NEXT_PUBLIC_BACKEND_WS_URL ?? "http://localhost:8080").replace(/\/$/, "");

export type { DashboardSocketStatus } from "./dashboard-socket-connection";

/** RAGOps Dashboard에 재연결하고, 구독 직후·push 때 DB 기반 HTTP snapshot 조회를 요청한다. */
export function useDashboardSocket(onMessage: () => void): DashboardSocketStatus {
  const [status, setStatus] = useState<DashboardSocketStatus>(() => {
    const token = typeof window === "undefined" ? null : window.sessionStorage.getItem(ACCESS_TOKEN_KEY);
    return token ? "CONNECTING" : "POLLING";
  });

  useEffect(() => {
    const token = typeof window === "undefined" ? null : window.sessionStorage.getItem(ACCESS_TOKEN_KEY);
    if (!token) return;

    // 1. 이 화면의 소켓·재시도 타이머를 한 연결 소유자에게 맡긴다.
    const socketUrl = `${WS_BASE_URL.replace(/^http/, "ws")}/ws/websocket`;
    const stop = connectDashboardSocket({
      url: socketUrl,
      token,
      tokenIsCurrent: () => window.sessionStorage.getItem(ACCESS_TOKEN_KEY) === token,
      onStatus: setStatus,
      onSnapshot: onMessage,
      onStompError: async () => {
        try {
          // 3. HTTP primary 인가가 거부한 토큰·역할은 재시도하지 않는다. 일시 장애만 재접속한다.
          await apiRequest<unknown>("/admin/dashboard/summary");
          return shouldRetryDashboardStompError(200);
        } catch (error) {
          return shouldRetryDashboardStompError(error instanceof ApiError ? error.status : null);
        }
      },
    });
    // 2. HTTP 401로 세션이 폐기되면 다음 재시도 전에 기다리지 않고 즉시 정리한다.
    window.addEventListener(AUTH_EXPIRED_EVENT, stop);
    return () => {
      window.removeEventListener(AUTH_EXPIRED_EVENT, stop);
      stop();
    };
  }, [onMessage]);

  return status;
}
