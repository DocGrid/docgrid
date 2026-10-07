"use client";

// vinext production navigation uses full requests because its client router does not complete these catch-all route transitions.
/* eslint-disable @next/next/no-html-link-for-pages */

import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { apiRequest, errorMessage, toQuery } from "../lib/api";
import type { Collection, CollectionDocument, DocumentSummary, PageResponse } from "../lib/api-types";
import { EmptyState, ErrorState, LoadingState, PageHeading, StatusPill, formatDate } from "../components/ui";
import { useAuth } from "../components/AuthProvider";

export function CollectionsPage({ notify }: { notify: (message: string) => void }) {
  const [collections, setCollections] = useState<PageResponse<Collection> | null>(null);
  const [parentCandidates, setParentCandidates] = useState<Collection[]>([]);
  const [keywordInput, setKeywordInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  // 검색어/페이지를 빠르게 바꾸면 먼저 보낸 요청이 나중에 도착해 최신 화면을 덮어쓸 수 있어, 가장 최근 호출의 결과만 반영한다.
  const loadSeqRef = useRef(0);

  const load = useCallback(async () => {
    const seq = ++loadSeqRef.current;
    setLoading(true);
    setError("");
    try {
      const result = await apiRequest<PageResponse<Collection>>(`/collections${toQuery({ keyword, page, size: 20 })}`);
      if (seq !== loadSeqRef.current) return;
      setCollections(result);
    } catch (reason) {
      if (seq !== loadSeqRef.current) return;
      setError(errorMessage(reason));
    } finally {
      if (seq === loadSeqRef.current) setLoading(false);
    }
  }, [keyword, page]);

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const nextKeyword = keywordInput.trim();
    setPage(0);
    if (nextKeyword === keyword && page === 0) void load();
    else setKeyword(nextKeyword);
  }

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  async function openCreateModal() {
    try {
      // 상위 폴더 후보는 페이지네이션과 무관하게 넉넉히 한 번에 가져온다.
      setParentCandidates((await apiRequest<PageResponse<Collection>>("/collections?page=0&size=100")).content);
    } catch (reason) { setError(errorMessage(reason)); }
    setModalOpen(true);
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setCreating(true);
    const form = new FormData(event.currentTarget);
    const parentCollectionId = form.get("parentCollectionId");
    try {
      await apiRequest<Collection>("/collections", { method: "POST", body: { name: String(form.get("name")), description: String(form.get("description") || ""), parentCollectionId: parentCollectionId ? Number(parentCollectionId) : null, visibility: String(form.get("visibility")) } });
      setModalOpen(false);
      notify("새 컬렉션을 만들었습니다.");
      await load();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setCreating(false); }
  }

  return <section className="content page-view">
    <PageHeading kicker="COLLECTIONS" title="컬렉션" description="내가 읽을 수 있는 활성 컬렉션과 검색 범위를 관리하세요." actions={<button className="primary-button" onClick={() => void openCreateModal()}>＋ 새 컬렉션</button>} />
    <form className="toolbar" onSubmit={search}><label className="toolbar-search"><span>⌕</span><input value={keywordInput} onChange={(event) => setKeywordInput(event.target.value)} placeholder="이름 또는 설명 검색" /></label><button className="filter-submit">검색</button></form>
    {error ? <ErrorState message={error} onRetry={() => void load()} /> : null}
    {loading ? <LoadingState label="컬렉션을 불러오는 중입니다." /> : null}
    {!loading && !error && !collections?.content.length ? <EmptyState symbol="▱" title={keyword ? "검색 결과가 없습니다" : "아직 컬렉션이 없습니다"} description={keyword ? "다른 검색어로 다시 찾아보세요." : "관련 문서를 묶을 첫 컬렉션을 만들어 보세요."} /> : null}
    {!loading && collections?.content.length ? <>
      <div className="collection-grid">{collections.content.map((collection, index) => <a className="collection-card" href={`/collections/${collection.collectionId}`} target="_top" key={collection.collectionId}><div className="collection-top"><span className="folder-shape" style={{ "--folder-color": collectionColor(index) } as React.CSSProperties}>▱</span><StatusPill value={collection.status} /></div><div className="collection-badges"><StatusPill value={collection.visibility} /></div><h2>{collection.name}</h2><p>{collection.description || "설명이 없습니다."}</p><div className="collection-footer"><span>owner {collection.ownerName ?? `#${collection.ownerUserId}`}</span><strong>{formatDate(collection.createdAt, false)} · 상세 보기 →</strong></div></a>)}</div>
      <div className="pagination"><span>{collections.totalElements}건 · {collections.size}건씩</span><div><button disabled={collections.first} onClick={() => setPage((current) => Math.max(0, current - 1))}>이전</button><button className="active">{collections.page + 1}</button><button disabled={collections.last} onClick={() => setPage((current) => current + 1)}>다음</button></div></div>
    </> : null}
    {modalOpen ? <div className="modal-layer"><form className="modal compact-modal" onSubmit={create}><div className="modal-header"><div><span className="modal-symbol mint">▱</span><div><h2>새 컬렉션</h2><p>관련 문서를 하나의 검색 범위로 묶습니다.</p></div></div><button type="button" onClick={() => setModalOpen(false)}>×</button></div><label className="form-field">컬렉션 이름<input name="name" placeholder="예: 운영 문서" required /></label><label className="form-field">설명<textarea name="description" rows={3} placeholder="컬렉션을 설명해 주세요." /></label><label className="form-field">상위 폴더<select name="parentCollectionId" defaultValue=""><option value="">없음 (최상위 컬렉션)</option>{parentCandidates.map((parent) => <option key={parent.collectionId} value={parent.collectionId}>{parent.name}</option>)}</select></label><label className="form-field">공개 범위<select name="visibility" defaultValue="PRIVATE"><option value="PRIVATE">PRIVATE</option><option value="DEPARTMENT">DEPARTMENT</option><option value="PUBLIC">PUBLIC</option></select></label><div className="modal-footer"><button type="button" className="secondary-button" onClick={() => setModalOpen(false)}>취소</button><button className="primary-button" disabled={creating}>{creating ? "생성 중…" : "컬렉션 만들기"}</button></div></form></div> : null}
  </section>;
}

export function CollectionDetailPage({ collectionId, notify }: { collectionId: number; notify: (message: string) => void }) {
  const { user } = useAuth();
  const [collection, setCollection] = useState<Collection | null>(null);
  const [children, setChildren] = useState<PageResponse<Collection> | null>(null);
  const [availableDocuments, setAvailableDocuments] = useState<DocumentSummary[]>([]);
  const [collectionDocuments, setCollectionDocuments] = useState<PageResponse<CollectionDocument> | null>(null);
  const [addDocumentId, setAddDocumentId] = useState("");
  const [removeDocumentId, setRemoveDocumentId] = useState("");
  const [page, setPage] = useState(0);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  // collectionId/page가 빠르게 바뀌면 먼저 보낸 요청이 나중에 도착해 최신 화면을 덮어쓸 수 있어, 가장 최근 호출의 결과만 반영한다.
  const loadSeqRef = useRef(0);

  const load = useCallback(async () => {
    const seq = ++loadSeqRef.current;
    setLoading(true);
    setError("");
    try {
      // 1. Load collection Metadata, direct child collections, addable documents, and current membership from their authoritative APIs.
      const [detail, childList, documentPage, memberPage] = await Promise.all([
        apiRequest<Collection>(`/collections/${collectionId}`),
        apiRequest<PageResponse<Collection>>(`/collections/${collectionId}/children?page=0&size=100`),
        apiRequest<PageResponse<DocumentSummary>>("/api/documents?page=0&size=100"),
        apiRequest<PageResponse<CollectionDocument>>(`/collections/${collectionId}/documents?page=${page}&size=20`),
      ]);
      if (seq !== loadSeqRef.current) return;
      setCollection(detail);
      setChildren(childList);
      setAvailableDocuments(documentPage.content);
      setCollectionDocuments(memberPage);
      // 현재 조회한 멤버십에 없는 선택값은 제거해 화면 밖 문서 ID로 DELETE하지 못하게 한다.
      setRemoveDocumentId((current) => memberPage.content.some(
        (item) => String(item.document.documentId) === current,
      ) ? current : "");
    } catch (reason) {
      if (seq !== loadSeqRef.current) return;
      setError(errorMessage(reason));
    } finally {
      if (seq === loadSeqRef.current) setLoading(false);
    }
  }, [collectionId, page]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  async function mutate(action: "add" | "remove", selectedDocumentId: number) {
    if (!selectedDocumentId) return;
    setBusy(true);
    setError("");
    try {
      if (action === "add") await apiRequest(`/collections/${collectionId}/documents`, { method: "POST", body: { documentId: selectedDocumentId } });
      else await apiRequest(`/collections/${collectionId}/documents/${selectedDocumentId}`, { method: "DELETE" });
      notify(action === "add" ? "문서를 컬렉션에 추가했습니다." : "문서를 컬렉션에서 제거했습니다.");
      if (action === "add") setAddDocumentId(""); else setRemoveDocumentId("");
      // 2. Refresh the permission-filtered list so totals and visible rows stay consistent with the backend.
      await load();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  async function updateVisibility(next: "PRIVATE" | "PUBLIC") {
    if (next === "PUBLIC" && !window.confirm("이 컬렉션을 조직 전체에 공개할까요?")) return;

    setBusy(true);
    setError("");
    try {
      await apiRequest(`/collections/${collectionId}/visibility`, { method: "PATCH", body: { visibility: next } });
      await load();
      notify(next === "PUBLIC" ? "컬렉션을 전체공개로 전환했습니다." : "컬렉션을 비공개로 전환했습니다.");
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  async function removeCollection() {
    // children은 현재 사용자가 읽을 수 있는 직계 자식만 담고 있어 실제 하위 컬렉션 존재 여부의 기준이 될 수 없다
    // (읽기 권한이 없는 후손도 삭제 시엔 함께 cascade 삭제되므로 항상 경고한다).
    const warning = "이 컬렉션을 삭제할까요? 하위 컬렉션도 함께 삭제되며, 들어 있던 문서는 컬렉션에서만 빠지고 문서 자체는 유지됩니다. 복구 API는 제공되지 않습니다.";
    if (!window.confirm(warning)) return;
    setBusy(true);
    try {
      await apiRequest(`/collections/${collectionId}`, { method: "DELETE" });
      window.location.href = "/collections";
    } catch (reason) { setError(errorMessage(reason)); setBusy(false); }
  }

  const isOwner = collection != null && user != null && user.userId === collection.ownerUserId;
  const removableDocuments = collectionDocuments?.content ?? [];
  const hasRemovableDocuments = removableDocuments.length > 0;

  return <section className="content page-view">
    <div className="detail-back"><a href="/collections" target="_top">← 컬렉션 목록</a><span>collectionId {collectionId}</span></div>
    <PageHeading kicker="COLLECTION DETAIL" title={collection?.name ?? `컬렉션 #${collectionId}`} description={collection?.description || "컬렉션 상세 정보를 확인하고 문서를 추가하거나 제거하세요."} actions={collection ? <><StatusPill value={collection.visibility} />{isOwner ? <button className="secondary-button" disabled={busy} onClick={() => void updateVisibility(collection.visibility === "PUBLIC" ? "PRIVATE" : "PUBLIC")}>{collection.visibility === "PUBLIC" ? "비공개로 전환" : "전체공개로 전환"}</button> : null}<button className="secondary-button" disabled={busy} onClick={() => void removeCollection()}>컬렉션 삭제</button></> : null} />
    {error ? <ErrorState message={error} onRetry={() => void load()} /> : null}
    {loading ? <LoadingState /> : null}
    {!loading && collection ? <>
      <div className="collection-summary"><div><span className="folder-shape" style={{ "--folder-color": "#6558e8" } as React.CSSProperties}>▱</span><div><strong>{collection.name}</strong><span>owner {collection.ownerName ?? `#${collection.ownerUserId}`} · {formatDate(collection.createdAt, false)}</span></div></div><StatusPill value={collection.status} /></div>
      <div className="panel-card"><div className="panel-heading"><div><h2>하위 컬렉션</h2><p>클릭하면 해당 컬렉션을 엽니다.</p></div></div>{children?.content.length ? <div className="collection-grid">{children.content.map((child, index) => <a className="collection-card" href={`/collections/${child.collectionId}`} target="_top" key={child.collectionId}><div className="collection-top"><span className="folder-shape" style={{ "--folder-color": collectionColor(index) } as React.CSSProperties}>▱</span><StatusPill value={child.status} /></div><div className="collection-badges"><StatusPill value={child.visibility} /></div><h2>{child.name}</h2><p>{child.description || "설명이 없습니다."}</p></a>)}</div> : <EmptyState symbol="▱" title="하위 컬렉션이 없습니다" description="이 컬렉션 안에 하위 컬렉션을 만들면 여기에 표시됩니다." />}</div>
      <div className="detail-grid collection-action-grid">
        <div className="panel-card"><div className="panel-heading"><div><h2>문서 추가</h2><p>내가 읽을 수 있는 문서 중 하나를 선택합니다.</p></div></div><label className="form-field">문서<select value={addDocumentId} onChange={(event) => setAddDocumentId(event.target.value)}><option value="">문서를 선택하세요</option>{availableDocuments.map((document) => <option key={document.documentId} value={document.documentId}>#{document.documentId} · {document.title}</option>)}</select></label><button className="primary-button full-button action-submit" disabled={!addDocumentId || busy} onClick={() => void mutate("add", Number(addDocumentId))}>컬렉션에 추가</button></div>
        <div className="panel-card"><div className="panel-heading"><div><h2>문서 제거</h2><p>이 컬렉션에 포함된 문서 중 하나를 선택합니다.</p></div></div><label className="form-field">문서<select value={removeDocumentId} disabled={!hasRemovableDocuments || busy} onChange={(event) => setRemoveDocumentId(event.target.value)}><option value="">{hasRemovableDocuments ? "문서를 선택하세요" : "제거할 문서가 없습니다"}</option>{removableDocuments.map((item) => <option key={item.document.documentId} value={item.document.documentId}>#{item.document.documentId} · {item.document.title}</option>)}</select></label><button className="secondary-button full-button action-submit" disabled={!removeDocumentId || busy || !hasRemovableDocuments} onClick={() => void mutate("remove", Number(removeDocumentId))}>컬렉션에서 제거</button></div>
      </div>
      {collectionDocuments && !collectionDocuments.content.length ? <EmptyState symbol="▱" title="컬렉션에 문서가 없습니다" description="위에서 문서를 선택해 컬렉션에 추가해 보세요." /> : null}
      {collectionDocuments?.content.length ? <div className="data-table collection-documents-table">
        <div className="data-row data-head collection-doc-head"><span>문서</span><span>상태</span><span>공개 범위</span><span>추가 정보</span><span /></div>
        {collectionDocuments.content.map((item) => <div className="data-row collection-doc-row" key={item.document.documentId}>
          <div className="doc-name"><span className="file-square violet">▤</span><div><a href={`/documents/${item.document.documentId}`} target="_top">{item.document.title}</a><small>#{item.document.documentId} · {item.document.documentType} · v{item.document.currentVersionNo ?? "—"}</small></div></div>
          <span><StatusPill value={item.document.currentVersionStatus ?? item.document.status} /></span>
          <span><StatusPill value={item.document.visibility} /></span>
          <span>{item.addedByName ?? (item.addedBy ? `#${item.addedBy}` : "—")}<br />{formatDate(item.addedAt)}</span>
          <button className="danger-text" disabled={busy} onClick={() => void mutate("remove", item.document.documentId)}>제거</button>
        </div>)}
        <div className="pagination"><span>{collectionDocuments.totalElements}건 · {collectionDocuments.size}건씩</span><div><button disabled={collectionDocuments.first} onClick={() => setPage((current) => Math.max(0, current - 1))}>이전</button><button className="active">{collectionDocuments.page + 1}</button><button disabled={collectionDocuments.last} onClick={() => setPage((current) => current + 1)}>다음</button></div></div>
      </div> : null}
    </> : null}
  </section>;
}

function collectionColor(index: number) {
  return ["#6558e8", "#1b9d82", "#e88a35", "#667085"][index % 4];
}
