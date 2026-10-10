"use client";

import { useEffect, useState } from "react";
import { ACCESS_TOKEN_KEY } from "./api";

const WS_BASE_URL = (process.env.NEXT_PUBLIC_BACKEND_WS_URL ?? "http://localhost:8080").replace(/\/$/, "");

export type RagSocketStatus = "CONNECTING" | "LIVE" | "POLLING";

/**
 * RAG 답변 완료 push(/user/queue/rag-answer)를 구독해 onMessage를 트리거한다.
 * enabled가 false면 연결하지 않는다(답변을 기다리는 동안에만 열어둔다) — useDashboardSocket과
 * 동일하게 원본 프레임은 신호로만 쓰고, 실제 최신 상태는 호출자가 REST로 다시 읽는다.
 */
export function useRagAnswerSocket(enabled: boolean, onMessage: () => void): RagSocketStatus {
  // liveStatus는 실제 WebSocket 생명주기(연결/성공/실패)에서만 바뀐다 — token 없음은
  // effect 밖에서 매 렌더마다 계산해, effect 안에서 즉시 setState하는 것을 피한다.
  const [liveStatus, setLiveStatus] = useState<RagSocketStatus>("CONNECTING");

  useEffect(() => {
    if (!enabled) return;
    const token = typeof window === "undefined" ? null : window.sessionStorage.getItem(ACCESS_TOKEN_KEY);
    if (!token) return;

    const socketUrl = `${WS_BASE_URL.replace(/^http/, "ws")}/ws/websocket`;
    const socket = new WebSocket(socketUrl);
    let heartbeatTimer: number | null = null;
    const stopHeartbeat = () => {
      if (heartbeatTimer !== null) window.clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    };
    socket.onopen = () => socket.send(
      `CONNECT\naccept-version:1.2\nAuthorization:Bearer ${token}\nheart-beat:10000,10000\n\n\0`,
    );
    socket.onmessage = (event) => {
      const frame = String(event.data);
      if (frame.startsWith("CONNECTED")) {
        // /user/** 목적지는 클라이언트가 이 형태로 그대로 구독하고, 서버가 세션별로 실제 큐를 연결한다.
        socket.send("SUBSCRIBE\nid:rag-answer\ndestination:/user/queue/rag-answer\nack:auto\n\n\0");
        // 협상한 heartbeat를 실제로 보내 유휴 프록시가 알림 대기 연결을 끊지 않게 한다.
        if (heartbeatTimer === null) {
          heartbeatTimer = window.setInterval(() => {
            if (socket.readyState === WebSocket.OPEN) socket.send("\n");
          }, 10_000);
        }
        setLiveStatus("LIVE");
        return;
      }
      if (frame.startsWith("MESSAGE")) onMessage();
    };
    socket.onerror = () => {
      stopHeartbeat();
      setLiveStatus("POLLING");
    };
    socket.onclose = () => {
      stopHeartbeat();
      setLiveStatus("POLLING");
    };
    return () => {
      stopHeartbeat();
      socket.close();
    };
  }, [enabled, onMessage]);

  if (!enabled) return "CONNECTING";
  const hasToken = typeof window !== "undefined" && Boolean(window.sessionStorage.getItem(ACCESS_TOKEN_KEY));
  return hasToken ? liveStatus : "POLLING";
}
