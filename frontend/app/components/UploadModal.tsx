"use client";

import { FormEvent, MouseEvent, useRef, useState } from "react";
import { ApiError, apiRequest, errorMessage } from "../lib/api";
import type { DocumentUploadResponse, DocumentVersionUploadResponse } from "../lib/api-types";
import { formatBytes } from "./ui";

type UploadAttempt = { key: string; fields: FormData };

function mayHaveSavedUpload(reason: unknown): boolean {
  if (!(reason instanceof ApiError)) return true;
  return reason.status === 408 || reason.status === 429 || reason.status >= 500
    || reason.code === "DOCUMENT-UPLOAD-004" || reason.code === "DOCUMENT-UPLOAD-005";
}

export function UploadModal({ documentId, onClose, onSuccess }: {
  documentId?: number;
  onClose: () => void;
  onSuccess: (message: string) => void;
}) {
  const versionMode = documentId !== undefined;
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [retryAvailable, setRetryAvailable] = useState(false);
  const attemptRef = useRef<UploadAttempt | null>(null);
  const inFlight = useRef(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file || inFlight.current) return;
    inFlight.current = true;
    setSubmitting(true);
    setError("");

    try {
      // 1. 최초 업로드와 새 버전 모두 한 번의 시도에 키·파일·입력 본문을 고정한다.
      if (!attemptRef.current) {
        const fields = new FormData(event.currentTarget);
        fields.set("file", file);
        attemptRef.current = { key: crypto.randomUUID(), fields };
      }
      const { key, fields } = attemptRef.current;

      // 2. 응답 확인 실패 뒤에는 두 업로드 경로 모두 동일한 키와 본문을 재전송한다.
      if (versionMode) {
        const result = await apiRequest<DocumentVersionUploadResponse>(`/api/documents/${documentId}/versions`, {
          method: "POST", body: fields, headers: { "Idempotency-Key": key },
        });
        onSuccess(`v${result.versionNo} 업로드가 접수됐습니다. Job #${result.embeddingJobId}`);
      } else {
        const result = await apiRequest<DocumentUploadResponse>("/api/documents", {
          method: "POST", body: fields, headers: { "Idempotency-Key": key },
        });
        onSuccess(`문서 #${result.documentId} 업로드가 접수됐습니다. Job #${result.embeddingJobId}`);
      }
      attemptRef.current = null;
      setRetryAvailable(false);
      onClose();
    } catch (reason) {
      // 3. 저장 여부를 모를 때만 입력을 잠그고 명시적인 동일 시도 재전송을 제공한다.
      if (attemptRef.current && mayHaveSavedUpload(reason)) {
        setRetryAvailable(true);
        setError(`${errorMessage(reason)} 저장 여부를 확인하지 못했습니다. 같은 업로드를 다시 시도할 수 있습니다. 이 창을 닫으면 요청 키가 사라집니다.`);
      } else {
        attemptRef.current = null;
        setRetryAvailable(false);
        setError(errorMessage(reason));
      }
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  }

  function startNewUpload(event: MouseEvent<HTMLButtonElement>) {
    if (inFlight.current) return;
    // 4. 파일 내용과 무관하게 사용자가 새 업로드를 선택했을 때만 이전 시도의 키를 버린다.
    attemptRef.current = null;
    event.currentTarget.form?.reset();
    setFile(null);
    setTitle("");
    setRetryAvailable(false);
    setError(versionMode
      ? "이전 버전이 이미 저장됐을 수 있습니다. 새 버전을 올리기 전 버전 이력을 확인하세요."
      : "이전 업로드가 이미 저장됐을 수 있습니다. 같은 문서를 다시 올리기 전 문서 목록을 확인하세요.");
  }

  return (
    <div className="modal-layer">
      <form className="modal" onSubmit={submit}>
        <div className="modal-header"><div><span className="modal-symbol">⇧</span><div><h2>{versionMode ? "새 버전 업로드" : "문서 업로드"}</h2><p>{versionMode ? `문서 #${documentId}에 새 파일 버전을 추가합니다.` : "파일 저장 후 비동기 인덱싱 Job을 생성합니다."}</p></div></div><button type="button" onClick={onClose} disabled={submitting}>×</button></div>
        {error ? <div className="form-error" role="alert">{error}</div> : null}
        <label className={`dropzone ${file ? "selected" : ""}`}><input type="file" accept=".pdf,.docx,.hwp,.hwpx,.txt,.md" disabled={submitting || retryAvailable} onChange={(event) => { const selected = event.target.files?.[0] ?? null; setFile(selected); if (selected && !title) setTitle(selected.name.replace(/\.[^.]+$/, "")); }} /><span>▤</span>{file ? <><strong>{file.name}</strong><small>{formatBytes(file.size)} · {retryAvailable ? "같은 업로드를 다시 시도할 수 있습니다." : "업로드할 준비가 됐습니다."}</small></> : <><strong>클릭해 파일을 선택하세요</strong><small>PDF, DOCX, HWP, HWPX, TXT, MD</small></>}</label>
        {!versionMode ? <>
          <label className="form-field">문서 제목<input name="title" value={title} onChange={(event) => setTitle(event.target.value)} disabled={submitting || retryAvailable} placeholder="문서 제목" maxLength={500} required /></label>
          <label className="form-field">설명<textarea name="description" rows={3} disabled={submitting || retryAvailable} placeholder="선택 입력" /></label>
          <label className="form-field">공개 범위<select name="visibility" defaultValue="PRIVATE" disabled={submitting || retryAvailable}><option value="PRIVATE">PRIVATE · 나만 보기</option><option value="DEPARTMENT">DEPARTMENT · 같은 부서</option><option value="PUBLIC">PUBLIC · 전체 공개</option></select></label>
        </> : null}
        <div className="modal-footer"><button type="button" className="secondary-button" onClick={onClose} disabled={submitting}>취소</button>{retryAvailable ? <button type="button" className="secondary-button" onClick={startNewUpload} disabled={submitting}>새 업로드 시작</button> : null}<button className="primary-button" disabled={!file || submitting}>{submitting ? "업로드 중…" : retryAvailable ? "같은 업로드 다시 시도" : "업로드 시작"}</button></div>
      </form>
    </div>
  );
}
