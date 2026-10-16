"use client";

import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { ApiError, apiRequest, errorMessage } from "../lib/api";
import type { Collection, Department, DocumentSummary, PageResponse, PermissionGrant, PermissionSummary, PermissionTargetUser, Role } from "../lib/api-types";
import { permissionTargetUserLabel } from "../lib/permission-target-user";
import { ErrorState, LoadingState, Notice, PageHeading, StatusPill, formatDate } from "../components/ui";

export function PermissionsPage({ notify }: { notify: (message: string) => void }) {
  // 응답 불명 재요청도 같은 문서·권한 키를 재사용한다. 성공 시에만 폐기한다.
  const revocationKeys = useRef(new Map<string, string>());
  const [documents, setDocuments] = useState<DocumentSummary[]>([]);
  const [collections, setCollections] = useState<Collection[]>([]);
  const [roles, setRoles] = useState<Role[]>([]);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [resourceType, setResourceType] = useState<"documents" | "collections">("documents");
  const [resourceId, setResourceId] = useState("");
  const [targetType, setTargetType] = useState<"USER" | "ROLE" | "DEPARTMENT">("USER");
  const [userKeyword, setUserKeyword] = useState("");
  const [userOptions, setUserOptions] = useState<PermissionTargetUser[]>([]);
  const [selectedUser, setSelectedUser] = useState<PermissionTargetUser | null>(null);
  const [usersLoading, setUsersLoading] = useState(false);
  const [usersError, setUsersError] = useState("");
  const [summary, setSummary] = useState<PermissionSummary | null>(null);
  const [directPermissions, setDirectPermissions] = useState<PermissionGrant[]>([]);
  const [permissionsLoading, setPermissionsLoading] = useState(false);
  const [permissionsError, setPermissionsError] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const loadResources = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [documentPage, collectionPage, roleList, departmentList] = await Promise.all([apiRequest<PageResponse<DocumentSummary>>("/api/documents?page=0&size=100"), apiRequest<PageResponse<Collection>>("/collections?page=0&size=100"), apiRequest<Role[]>("/roles"), apiRequest<Department[]>("/departments")]);
      setDocuments(documentPage.content);
      setCollections(collectionPage.content);
      setRoles(roleList.filter((role) => role.code !== "USER"));
      setDepartments(departmentList);
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => void loadResources(), 0);
    return () => window.clearTimeout(timer);
  }, [loadResources]);

  useEffect(() => {
    if (resourceType !== "documents" || !resourceId) return;
    apiRequest<PermissionSummary>(`/permissions/documents/${resourceId}/me`).then(setSummary).catch(() => setSummary(null));
  }, [resourceId, resourceType]);

  const loadDirectPermissions = useCallback(async () => {
    if (!resourceId) {
      setDirectPermissions([]);
      return;
    }
    setPermissionsLoading(true);
    setPermissionsError("");
    try {
      // Direct grants require ADMIN permission on the selected resource and intentionally exclude inherited grants.
      setDirectPermissions(await apiRequest<PermissionGrant[]>(`/permissions/${resourceType}/${resourceId}`));
    } catch (reason) {
      setDirectPermissions([]);
      setPermissionsError(reason instanceof ApiError && reason.status === 405
        ? "배포된 백엔드가 직접 권한 목록 API를 아직 지원하지 않습니다. 최신 백엔드를 배포한 뒤 다시 조회해 주세요."
        : errorMessage(reason));
    } finally {
      setPermissionsLoading(false);
    }
  }, [resourceId, resourceType]);

  useEffect(() => {
    const timer = window.setTimeout(() => void loadDirectPermissions(), 0);
    return () => window.clearTimeout(timer);
  }, [loadDirectPermissions]);

  useEffect(() => {
    const keyword = userKeyword.trim();
    if (targetType !== "USER" || !resourceId || !keyword) return;

    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setUsersLoading(true);
      setUsersError("");
      apiRequest<PermissionTargetUser[]>(`/permissions/${resourceType}/${resourceId}/users?keyword=${encodeURIComponent(keyword)}`, { signal: controller.signal })
        .then(setUserOptions)
        .catch((reason) => {
          if (reason instanceof DOMException && reason.name === "AbortError") return;
          setUserOptions([]);
          setUsersError(errorMessage(reason));
        })
        .finally(() => {
          if (!controller.signal.aborted) setUsersLoading(false);
        });
    }, 300);

    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [resourceId, resourceType, targetType, userKeyword]);

  async function grant(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!resourceId) return;
    const form = new FormData(event.currentTarget);
    const requestTargetType = String(form.get("targetType"));
    const targetId = requestTargetType === "USER" ? selectedUser?.userId : Number(form.get("targetId"));
    if (!targetId) {
      setError(requestTargetType === "USER"
        ? "권한을 부여할 사용자를 검색 결과에서 선택해 주세요."
        : "권한을 부여할 대상을 선택해 주세요.");
      return;
    }
    setBusy(true);
    setError("");
    const request = {
      targetType: requestTargetType,
      userId: requestTargetType === "USER" ? targetId : null,
      roleId: requestTargetType === "ROLE" ? targetId : null,
      departmentId: requestTargetType === "DEPARTMENT" ? targetId : null,
      permissionType: String(form.get("permissionType")),
      expiresAt: form.get("expiresAt") || null,
    };
    try {
      const created = await apiRequest<PermissionGrant>(`/permissions/${resourceType}/${resourceId}`, { method: "POST", body: request });
      notify(`${created.permissionType} 권한을 부여했습니다.`);
      if (requestTargetType === "USER") resetUserPicker();
      await loadDirectPermissions();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  async function revoke(permission: PermissionGrant) {
    if (!resourceId) return;
    const target = targetLabel(permission);
    const resourceName = resourceType === "documents"
      ? documents.find((item) => item.documentId === Number(resourceId))?.title
      : collections.find((item) => item.collectionId === Number(resourceId))?.name;
    const resourceLabel = resourceName ?? `${resourceType === "documents" ? "문서" : "컬렉션"} #${resourceId}`;
    if (!window.confirm(`${resourceLabel}에서 ${target}의 ${permission.permissionType} 권한을 회수할까요?`)) return;
    setBusy(true);
    setError("");
    try {
      const binding = `${resourceType}:${resourceId}:${permission.permissionId}`;
      let requestKey = revocationKeys.current.get(binding);
      if (!requestKey) {
        requestKey = crypto.randomUUID();
        revocationKeys.current.set(binding, requestKey);
      }
      await apiRequest(`/permissions/${resourceType}/${resourceId}/${permission.permissionId}`, {
        method: "DELETE", headers: { "Idempotency-Key": requestKey },
      });
      revocationKeys.current.delete(binding);
      notify("권한을 회수했습니다.");
      await loadDirectPermissions();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  const resources = resourceType === "documents"
    ? documents.map((item) => ({ id: item.documentId, label: `#${item.documentId} · ${item.title}` }))
    : collections.map((item) => ({ id: item.collectionId, label: `#${item.collectionId} · ${item.name}` }));
  const selectedResourceLabel = resources.find((item) => String(item.id) === resourceId)?.label ?? null;

  function changeResourceType(next: "documents" | "collections") {
    setResourceType(next);
    setSummary(null);
    setDirectPermissions([]);
    setPermissionsError("");
    setResourceId("");
    resetUserPicker();
  }

  function resetUserPicker() {
    setUserKeyword("");
    setUserOptions([]);
    setSelectedUser(null);
    setUsersLoading(false);
    setUsersError("");
  }

  return <section className="content page-view">
    <PageHeading kicker="ACCESS CONTROL" title="권한 관리" description="문서 또는 컬렉션에 사용자·역할·부서 단위 권한을 부여하세요." />
    {error ? <ErrorState message={error} /> : null}
    {loading ? <LoadingState label="권한 리소스를 불러오는 중입니다." /> : null}
    {!loading ? <>
      <div className="resource-selector"><div><span className="modal-symbol">⌘</span><div><strong>대상 리소스</strong><span>관리할 문서 또는 컬렉션을 선택하세요.</span></div></div><div className="resource-controls"><select aria-label="리소스 종류" value={resourceType} onChange={(event) => changeResourceType(event.target.value as "documents" | "collections")}><option value="documents">문서</option><option value="collections">컬렉션</option></select><select aria-label="권한 관리 대상" value={resourceId} onChange={(event) => { setSummary(null); setResourceId(event.target.value); resetUserPicker(); }}><option value="">대상을 선택하세요</option>{resources.map((item) => <option key={item.id} value={item.id}>{item.label}</option>)}</select></div></div>
      <div className="permission-layout">
        <div className="panel-card permission-summary"><div className="panel-heading"><div><h2>{resourceType === "documents" ? "내 문서 권한" : "컬렉션 권한"}</h2><p>{resourceId ? `resourceId ${resourceId}` : "대상 없음"}</p></div></div>{resourceType === "documents" && summary ? <><div className="permission-checks"><StatusPill value={`READ ${summary.canRead ? "✓" : "✕"}`} /><StatusPill value={`WRITE ${summary.canWrite ? "✓" : "✕"}`} /><StatusPill value={`ADMIN ${summary.canAdmin ? "✓" : "✕"}`} /></div><span className="field-label">권한 경로</span><div className="source-chips">{summary.sources.map((source) => <b key={source}>{source}</b>)}</div></> : <Notice>컬렉션의 현재 사용자 권한 요약은 제공되지 않으며 직접 부여 목록만 확인합니다.</Notice>}</div>
        <form className="panel-card permission-list" onSubmit={grant}>
          <div className="panel-heading"><div><h2>권한 부여</h2><p>{selectedResourceLabel ? `대상: ${selectedResourceLabel}` : "대상 타입을 고르고 리소스를 먼저 선택하세요."}</p></div></div>
          <label className="form-field">대상 타입
            <select name="targetType" value={targetType} onChange={(event) => {
              setTargetType(event.target.value as "USER" | "ROLE" | "DEPARTMENT");
              resetUserPicker();
            }}><option value="USER">USER</option><option value="ROLE">ROLE</option><option value="DEPARTMENT">DEPARTMENT</option></select>
          </label>
          {targetType === "USER" ? <div className="form-field permission-user-picker">
            <label htmlFor="permission-user-search">대상 사용자</label>
            <input
              id="permission-user-search"
              type="search"
              role="combobox"
              aria-controls="permission-user-options"
              aria-expanded={userOptions.length > 0}
              autoComplete="off"
              value={userKeyword}
              disabled={!resourceId}
              placeholder={resourceId ? "이름 또는 이메일로 검색" : "리소스를 먼저 선택하세요"}
              onChange={(event) => {
                setUserKeyword(event.target.value);
                setSelectedUser(null);
                setUserOptions([]);
                setUsersLoading(Boolean(event.target.value.trim()));
                setUsersError("");
              }}
            />
            <input name="targetId" type="hidden" value={selectedUser?.userId ?? ""} />
            {selectedUser ? <div className="permission-user-selected"><strong>{selectedUser.name}</strong><small>{permissionTargetUserLabel(selectedUser)}</small></div> : null}
            {!selectedUser && usersLoading ? <span className="permission-user-status">사용자를 검색하는 중입니다.</span> : null}
            {!selectedUser && usersError ? <span className="permission-user-status error">{usersError}</span> : null}
            {!selectedUser && resourceId && userKeyword.trim() && !usersLoading && !usersError && !userOptions.length ? <span className="permission-user-status">검색 결과가 없습니다.</span> : null}
            {!selectedUser && resourceId && !userKeyword.trim() ? <span className="permission-user-status">이름 또는 이메일을 입력한 뒤 결과에서 사용자를 선택하세요.</span> : null}
            {userOptions.length ? <div id="permission-user-options" className="permission-user-options" role="listbox">
              {userOptions.map((user) => <button
                key={user.userId}
                type="button"
                role="option"
                aria-selected={selectedUser?.userId === user.userId}
                onClick={() => {
                  setSelectedUser(user);
                  setUserKeyword("");
                  setUserOptions([]);
                  setUsersError("");
                }}
              ><strong>{user.name}</strong><small>{permissionTargetUserLabel(user)}</small></button>)}
            </div> : null}
          </div> : <label className="form-field">대상 {targetType === "ROLE" ? "역할" : "부서"}
            {targetType === "ROLE" ? <select name="targetId" required defaultValue=""><option value="" disabled>역할을 선택하세요</option>{roles.map((role) => <option key={role.id} value={role.id}>{role.name}</option>)}</select> : <select name="targetId" required defaultValue=""><option value="" disabled>부서를 선택하세요</option>{departments.map((department) => <option key={department.id} value={department.id}>{department.name}</option>)}</select>}
          </label>}
          <label className="form-field">권한 종류<select name="permissionType" defaultValue="READ"><option value="READ">READ</option><option value="WRITE">WRITE</option><option value="ADMIN">ADMIN</option></select></label>
          <label className="form-field">만료 시각<input name="expiresAt" type="datetime-local" /></label>
          <button className="primary-button full-button action-submit" disabled={!resourceId || busy || (targetType === "USER" && !selectedUser)}>{busy ? "처리 중…" : "권한 부여"}</button>
        </form>
      </div>
      <div className="panel-card latest-result"><div className="panel-heading"><div><h2>직접 부여된 권한</h2><p>상속·계산 권한을 제외한 USER·ROLE·DEPARTMENT 권한입니다.</p></div><span>{directPermissions.length}건</span></div>
        {permissionsLoading ? <LoadingState label="직접 권한을 불러오는 중입니다." /> : null}
        {!permissionsLoading && permissionsError ? <Notice>직접 권한 목록을 조회할 수 없습니다. {permissionsError}</Notice> : null}
        {!permissionsLoading && !permissionsError && !directPermissions.length ? <Notice>이 리소스에 직접 부여된 권한이 없습니다.</Notice> : null}
        {!permissionsLoading && directPermissions.length ? <div className="mini-table permission-table"><div className="table-labels"><span>대상</span><span>권한</span><span>만료</span><span /></div>{directPermissions.map((permission) => <div key={permission.permissionId}><span><strong>{targetLabel(permission)}</strong><small>#{permission.permissionId} · {permission.grantedByName ?? `#${permission.grantedBy}`} · {formatDate(permission.grantedAt)}</small></span><StatusPill value={permission.permissionType} /><span>{permission.expiresAt ? formatDate(permission.expiresAt) : "제한 없음"}</span><button className="danger-text" disabled={busy} title={`${targetLabel(permission)} ${permission.permissionType} 권한 회수`} aria-label={`${targetLabel(permission)} ${permission.permissionType} 권한 회수`} onClick={() => void revoke(permission)}>회수</button></div>)}</div> : null}
      </div>
    </> : null}
  </section>;
}

function targetLabel(permission: PermissionGrant) {
  if (permission.targetType === "ROLE") {
    return permission.roleName ? `ROLE ${permission.roleName}` : `ROLE #${permission.roleId ?? "—"}`;
  }
  if (permission.targetType === "DEPARTMENT") {
    return permission.departmentName ? `DEPARTMENT ${permission.departmentName}` : `DEPARTMENT #${permission.departmentId ?? "—"}`;
  }
  return permission.userName
    ? `USER ${permission.userName} · #${permission.userId ?? "—"}`
    : `USER #${permission.userId ?? "—"}`;
}
