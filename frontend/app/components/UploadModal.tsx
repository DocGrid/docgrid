"use client";

import { FormEvent, useState } from "react";
import { apiRequest, errorMessage } from "../lib/api";
import type { DocumentUploadResponse, DocumentVersionUploadResponse } from "../lib/api-types";
import { formatBytes } from "./ui";

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

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file) return;
    setSubmitting(true);
    setError("");
    const fields = new FormData(event.currentTarget);
    fields.set("file", file);

    try {
      if (versionMode) {
        const result = await apiRequest<DocumentVersionUploadResponse>(`/api/documents/${documentId}/versions`, { method: "POST", body: fields });
        onSuccess(`v${result.versionNo} 업로드가 접수됐습니다. Job #${result.embeddingJobId}`);
      } else {
        const result = await apiRequest<DocumentUploadResponse>("/api/documents", { method: "POST", body: fields });
        onSuccess(`문서 #${result.documentId} 업로드가 접수됐습니다. Job #${result.embeddingJobId}`);
      }
      onClose();
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="modal-layer">
      <form className="modal" onSubmit={submit}>
        <div className="modal-header"><div><span className="modal-symbol">⇧</span><div><h2>{versionMode ? "새 버전 업로드" : "문서 업로드"}</h2><p>{versionMode ? `문서 #${documentId}에 새 파일 버전을 추가합니다.` : "파일 저장 후 비동기 인덱싱 Job을 생성합니다."}</p></div></div><button type="button" onClick={onClose}>×</button></div>
        {error ? <div className="form-error" role="alert">{error}</div> : null}
        <label className={`dropzone ${file ? "selected" : ""}`}><input type="file" accept=".pdf,.docx,.txt,.md" onChange={(event) => { const selected = event.target.files?.[0] ?? null; setFile(selected); if (selected && !title) setTitle(selected.name.replace(/\.[^.]+$/, "")); }} /><span>▤</span>{file ? <><strong>{file.name}</strong><small>{formatBytes(file.size)} · 업로드할 준비가 됐습니다.</small></> : <><strong>클릭해 파일을 선택하세요</strong><small>PDF, DOCX, TXT, MD</small></>}</label>
        {!versionMode ? <>
          <label className="form-field">문서 제목<input name="title" value={title} onChange={(event) => setTitle(event.target.value)} placeholder="문서 제목" maxLength={500} required /></label>
          <label className="form-field">설명<textarea name="description" rows={3} placeholder="선택 입력" /></label>
          <label className="form-field">공개 범위<select name="visibility" defaultValue="PRIVATE"><option value="PRIVATE">PRIVATE · 나만 보기</option><option value="DEPARTMENT">DEPARTMENT · 같은 부서</option><option value="PUBLIC">PUBLIC · 전체 공개</option></select></label>
        </> : null}
        <div className="modal-footer"><button type="button" className="secondary-button" onClick={onClose}>취소</button><button className="primary-button" disabled={!file || submitting}>{submitting ? "업로드 중…" : "업로드 시작"}</button></div>
      </form>
    </div>
  );
}
