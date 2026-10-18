"use client";

import { FormEvent, useEffect, useState } from "react";
import { apiRequest, errorMessage } from "../lib/api";
import type { Department } from "../lib/api-types";
import { useAuth } from "./AuthProvider";
import { BrandMark } from "./ui";

export function AuthPage({ mode }: { mode: "login" | "signup" }) {
  const signup = mode === "signup";
  const { login, user } = useAuth();
  const [departments, setDepartments] = useState<Department[]>([]);
  const [departmentId, setDepartmentId] = useState("");
  const [departmentsLoading, setDepartmentsLoading] = useState(signup);
  const [departmentError, setDepartmentError] = useState("");
  const [departmentRequest, setDepartmentRequest] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    // 1. SSR 폼이 기본 GET으로 먼저 제출되지 않도록 클라이언트 이벤트 연결 후 활성화한다.
    const timer = window.setTimeout(() => setReady(true), 0);
    return () => window.clearTimeout(timer);
  }, []);

  useEffect(() => {
    if (user) window.location.replace(returnPath());
  }, [user]);

  useEffect(() => {
    if (!signup) return;
    let active = true;

    // 1. Initial state and retry events keep signup unavailable until the required choices are ready.
    apiRequest<Department[]>("/departments", { auth: false })
      .then((result) => {
        if (!active) return;
        setDepartments(result);
        if (!result.length) setDepartmentError("현재 가입 가능한 부서가 없습니다.");
      })
      .catch((reason) => {
        if (active) setDepartmentError(errorMessage(reason));
      })
      .finally(() => {
        if (active) setDepartmentsLoading(false);
      });

    return () => {
      active = false;
    };
  }, [departmentRequest, signup]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const email = String(form.get("email") ?? "");
    const password = String(form.get("password") ?? "");
    if (signup && !departmentId) {
      setError("부서를 선택해 주세요.");
      return;
    }
    setSubmitting(true);
    setError("");

    try {
      // 1. Signup creates the DocGrid account but does not issue an access token.
      if (signup) {
        await apiRequest("/auth/signup", {
          method: "POST",
          auth: false,
          body: {
            email,
            password,
            name: String(form.get("name") ?? ""),
            departmentId: Number(departmentId),
          },
        });
      }
      // 2. Login immediately establishes the JWT session for both flows.
      await login(email, password);
      window.location.replace(returnPath());
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setSubmitting(false);
    }
  }

  const signupUnavailable = signup && (departmentsLoading || Boolean(departmentError) || !departmentId);

  return (
    <main className="auth-shell">
      <section className="auth-brand-panel">
        <div className="auth-brand"><BrandMark /><strong>DocGrid</strong></div>
        <div>
          <span className="auth-eyebrow">KNOWLEDGE, CONNECTED</span>
          <h1>{signup ? <>팀의 지식을<br />한곳에서 시작하세요.</> : <>흩어진 문서에서<br /><em>정확한 답</em>을 찾으세요.</>}</h1>
          <p>{signup ? "가입 후 소속 부서와 공유된 문서를 바로 탐색할 수 있습니다." : "권한이 허용된 사내 문서를 검색하고 근거가 포함된 AI 답변을 확인하세요."}</p>
        </div>
        <ul><li>문서 업로드부터 임베딩·색인까지 자동화</li><li>권한 사전 필터와 실시간 접근 검증</li><li>MCP 토큰으로 AI 클라이언트 연동</li></ul>
      </section>
      <section className="auth-form-panel">
        <form className="auth-form" method="post" onSubmit={submit}>
          <div className="auth-mobile-brand"><BrandMark /><strong>DocGrid</strong></div>
          <span className="page-kicker">{signup ? "CREATE ACCOUNT" : "WELCOME BACK"}</span>
          <h2>{signup ? "회원가입" : "로그인"}</h2>
          <p>{signup ? "DocGrid 계정을 만들고 워크스페이스에 참여하세요." : "DocGrid 이메일과 비밀번호를 입력하세요."}</p>
          {error ? <div className="form-error" role="alert">{error}</div> : null}
          {departmentError ? <div className="form-error auth-load-error" role="alert"><span>{departmentError}</span><button type="button" onClick={() => { setDepartmentsLoading(true); setDepartmentError(""); setDepartments([]); setDepartmentId(""); setDepartmentRequest((request) => request + 1); }}>다시 시도</button></div> : null}
          <label>이메일<input name="email" type="email" placeholder="name@company.com" autoComplete="email" required /></label>
          <label>비밀번호<input name="password" type="password" placeholder={signup ? "비밀번호 (12자 이상)" : "비밀번호"} autoComplete={signup ? "new-password" : "current-password"} minLength={signup ? 12 : undefined} required /></label>
          {signup ? <>
            <label>이름<input name="name" placeholder="홍길동" autoComplete="name" required /></label>
            <label>부서<select name="departmentId" required value={departmentId} disabled={departmentsLoading || Boolean(departmentError)} aria-describedby="department-help" onChange={(event) => { setDepartmentId(event.target.value); setError(""); }}><option value="" disabled>{departmentsLoading ? "부서 목록 불러오는 중…" : departmentError ? "부서를 불러오지 못했습니다" : "부서를 선택하세요"}</option>{departments.map((department) => <option value={department.id} key={department.id}>{department.name} · {department.code}</option>)}</select></label>
            <span className="auth-field-help" id="department-help" role="status" aria-live="polite">{departmentsLoading ? "가입 가능한 부서 목록을 불러오는 중입니다." : departmentError ? "목록을 다시 불러온 후 가입할 수 있습니다." : departmentId ? "선택한 부서로 계정이 생성됩니다." : "부서를 선택하면 가입 버튼이 활성화됩니다."}</span>
          </> : null}
          <button className="primary-button auth-submit" disabled={!ready || submitting || signupUnavailable}>{submitting ? "처리 중…" : signup && departmentsLoading ? "부서 목록 불러오는 중…" : signup ? "가입하고 시작하기" : "로그인"}</button>
          <span className="auth-switch">{signup ? "이미 계정이 있나요?" : "계정이 없으신가요?"} <a href={signup ? "/login" : "/signup"} target="_top">{signup ? "로그인" : "회원가입"}</a></span>
        </form>
      </section>
    </main>
  );
}

function returnPath() {
  const requested = new URLSearchParams(window.location.search).get("returnTo");
  return requested?.startsWith("/") && !requested.startsWith("//") ? requested : "/search";
}
