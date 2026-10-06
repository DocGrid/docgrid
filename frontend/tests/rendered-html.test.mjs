import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function render(path = "/") {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}-${path}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request(`http://localhost${path}`, {
      headers: { accept: "text/html", host: "localhost" },
    }),
    {
      ASSETS: {
        fetch: async () => new Response("Not found", { status: 404 }),
      },
    },
    {
      waitUntil() {},
      passThroughOnException() {},
    },
  );
}

test("server-renders every application route", async () => {
  const routes = [
    "/",
    "/login",
    "/signup",
    "/search",
    "/documents",
    "/documents/1024",
    "/collections",
    "/collections/12",
    "/permissions",
    "/mcp-tokens",
    "/account",
    "/admin/dashboard",
    "/admin/indexing-jobs",
    "/admin/indexing-jobs/4402",
    "/admin/workers",
    "/admin/users",
  ];

  for (const route of routes) {
    const response = await render(route);
    assert.equal(response.status, 200, `${route} should render`);
    assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);
  }
});

test("renders DocGrid auth and protected loading boundaries", async () => {
  const [home, login, signup] = await Promise.all([
    render("/"),
    render("/login"),
    render("/signup"),
  ]);

  const homeHtml = await home.text();
  const loginHtml = await login.text();
  const signupHtml = await signup.text();
  assert.match(homeHtml, /<html lang="ko">/i);
  assert.match(homeHtml, /<title>DocGrid — 팀의 지식에서 정확한 답을<\/title>/i);
  assert.match(homeHtml, /세션을 확인하는 중입니다/);
  assert.match(loginHtml, /DocGrid 이메일과 비밀번호/);
  assert.match(loginHtml, /<a href="\/signup" target="_top">회원가입<\/a>/);
  assert.match(signupHtml, /가입 가능한 부서 목록을 불러오는 중입니다/);
  assert.match(signupHtml, /<button class="primary-button auth-submit" disabled="">부서 목록 불러오는 중…<\/button>/);
  assert.doesNotMatch(`${homeHtml}${loginHtml}${signupHtml}`, /로그인 없이 둘러보기|signin-with-chatgpt|codex-preview|Your site is taking shape/i);
});

test("uses full-page navigation for vinext catch-all routes", async () => {
  const navigationFiles = [
    "../app/components/AppShell.tsx",
    "../app/components/AuthPage.tsx",
    "../app/features/CollectionsPage.tsx",
    "../app/features/DocumentsPage.tsx",
    "../app/features/SearchPage.tsx",
    "../app/features/AdminPages.tsx",
  ];
  const sources = await Promise.all(navigationFiles.map((file) => readFile(new URL(file, import.meta.url), "utf8")));
  const source = sources.join("\n");
  assert.doesNotMatch(source, /from ["']next\/link["']|<Link\b/);

  const internalAnchors = source.match(/<a\b[^>]*\bhref=(?:["']\/|\{(?:`\/|[^}]*["']\/))[^>]*>/g) ?? [];
  assert.ok(internalAnchors.length > 0, "internal links should be present");
  for (const anchor of internalAnchors) {
    assert.match(anchor, /\btarget=["']_top["']/, `${anchor} should bypass vinext client navigation`);
  }
  assert.match(source, /AbortSignal\.timeout\(SEARCH_TIMEOUT_MS\)/, "search should finish before the Sites request limit");
  assert.match(source, /const SEARCH_TIMEOUT_MS = 29_000;/, "search should wait for the backend Ollama fallback");
});

test("groups search citations by document and constrains source cards to the viewport", async () => {
  const [searchPage, apiTypes, searchSources, styles] = await Promise.all([
    readFile(new URL("../app/features/SearchPage.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/lib/api-types.ts", import.meta.url), "utf8"),
    readFile(new URL("../app/lib/search-sources.ts", import.meta.url), "utf8"),
    readFile(new URL("../app/globals.css", import.meta.url), "utf8"),
  ]);

  assert.match(apiTypes, /export type SearchResult = \{[\s\S]*chunkId: number;/);
  assert.match(searchPage, /groupSearchSources\(turn\.response\)/);
  assert.match(searchSources, /new Map<number, GroupedSearchSource/);
  assert.match(styles, /\.source-list \{[^}]*grid-template-columns: minmax\(0, 1fr\)/);
  assert.match(styles, /\.source-card \{[^}]*max-width: 100%[^}]*overflow: hidden/);
});

test("restores saved search conversations and exposes a collapsible history panel", async () => {
  const source = await readFile(new URL("../app/features/SearchPage.tsx", import.meta.url), "utf8");

  assert.match(source, /\/search\/conversations\?page=0&size=20/);
  assert.match(source, /\/search\/conversations\/\$\{selectedConversationId\}/);
  assert.match(source, /conversationIdFromSearch\(window\.location\.search\)/);
  assert.match(source, /window\.addEventListener\("popstate", restoreFromBrowserHistory\)/);
  assert.match(source, /window\.history\.pushState\(\{\}, "", conversationPath/);
  assert.match(source, /aria-expanded=\{historyOpen\}/);
  assert.match(source, /aria-controls="conversation-history-panel"/);
  assert.match(source, /최근 20개까지 표시하며/);
  assert.match(source, /이 대화에 이어서 질문하세요/);
  assert.match(source, /aria-label="검색 범위"/);
  assert.match(source, /aria-label="결과 수"/);
  assert.doesNotMatch(source, /queryId \{turn\.queryId\}/);
  assert.match(source, /conversationRequestIdRef\.current !== requestId/);
  assert.match(source, /searchRequestIdRef\.current !== requestId/);
});

test("names filter controls for keyboard and screen reader users", async () => {
  const [documents, permissions, admin] = await Promise.all([
    readFile(new URL("../app/features/DocumentsPage.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/features/PermissionsPage.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/features/AdminPages.tsx", import.meta.url), "utf8"),
  ]);

  assert.match(documents, /aria-label="문서 상태"/);
  assert.match(permissions, /aria-label="리소스 종류"/);
  assert.match(permissions, /aria-label="권한 관리 대상"/);
  assert.match(admin, /aria-label="인덱싱 Job 상태"/);
  assert.match(admin, /aria-label="문서 ID"/);
  assert.match(admin, /aria-label="Worker ID"/);
  assert.match(admin, /aria-label="사용자 검색"/);
  assert.match(admin, /aria-label="사용자 부서"/);
  assert.match(admin, /aria-label="사용자 상태"/);
});

test("wires document metadata update and soft delete actions to their permissions", async () => {
  const source = await readFile(new URL("../app/features/DocumentsPage.tsx", import.meta.url), "utf8");

  assert.match(source, /permission\?\.canWrite[\s\S]*문서 정보 수정/);
  assert.match(source, /method: "PATCH"/);
  assert.match(source, /permission\?\.canAdmin[\s\S]*문서 삭제/);
  assert.match(source, /method: "DELETE"/);
  assert.match(source, /window\.location\.assign\("\/documents"\)/);
});

test("shows document version history and never renders the issued MCP token value", async () => {
  const [documents, tokens, styles] = await Promise.all([
    readFile(new URL("../app/features/DocumentsPage.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/features/McpTokensPage.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/globals.css", import.meta.url), "utf8"),
  ]);

  assert.match(documents, /\/api\/documents\/\$\{documentId\}\/versions/);
  assert.match(documents, /문서 버전 전체 이력/);
  assert.match(styles, /\.version-history-card/);
  assert.match(tokens, /maskToken\(issued\.token\)/);
  assert.match(tokens, /await navigator\.clipboard\.writeText\(issued\.token\);\s*setIssued\(null\);/);
  assert.doesNotMatch(tokens, /<span>\{issued\.token\}<\/span>/);
  assert.doesNotMatch(tokens, /보조 스크립트|Keychain용 복사/);
});

test("keeps collection document add selection independent from removal", async () => {
  const source = await readFile(new URL("../app/features/CollectionsPage.tsx", import.meta.url), "utf8");

  assert.match(source, /const \[addDocumentId, setAddDocumentId\] = useState\(""\);/);
  assert.match(source, /const \[removeDocumentId, setRemoveDocumentId\] = useState\(""\);/);
  assert.match(source, /<select value=\{addDocumentId\} onChange=\{\(event\) => setAddDocumentId\(event\.target\.value\)\}/);
  assert.match(source, /mutate\("add", Number\(addDocumentId\)\)/);
  assert.match(source, /if \(action === "add"\) setAddDocumentId\(""\); else setRemoveDocumentId\(""\);/);
});

test("restricts collection document removal to current members and disables empty removal", async () => {
  const source = await readFile(new URL("../app/features/CollectionsPage.tsx", import.meta.url), "utf8");

  assert.match(source, /const removableDocuments = collectionDocuments\?\.content \?\? \[\];/);
  assert.match(source, /<select value=\{removeDocumentId\} disabled=\{!hasRemovableDocuments \|\| busy\} onChange=\{\(event\) => setRemoveDocumentId\(event\.target\.value\)\}/);
  assert.match(source, /removableDocuments\.map\(\(item\) => <option/);
  assert.match(source, /disabled=\{!removeDocumentId \|\| busy \|\| !hasRemovableDocuments\}/);
  assert.match(source, /mutate\("remove", Number\(removeDocumentId\)\)/);
  assert.match(source, /memberPage\.content\.some\([\s\S]*String\(item\.document\.documentId\) === current/);
  assert.doesNotMatch(source, /<input[^>]*value=\{removeDocumentId\}/);
});

test("derives RAG socket status from token instead of setState inside the effect body", async () => {
  const source = await readFile(new URL("../app/lib/useRagAnswerSocket.ts", import.meta.url), "utf8");

  assert.match(source, /const \[liveStatus, setLiveStatus\] = useState<RagSocketStatus>\("CONNECTING"\);/);
  assert.match(source, /if \(!enabled\) return;\s*\n\s*const token = typeof window === "undefined" \? null : window\.sessionStorage\.getItem\(ACCESS_TOKEN_KEY\);\s*\n\s*if \(!token\) return;/);
  assert.match(source, /if \(!enabled\) return "CONNECTING";/);
  assert.match(source, /return hasToken \? liveStatus : "POLLING";/);
});

test("offers only upload visibility options the backend actually enforces", async () => {
  const source = await readFile(new URL("../app/components/UploadModal.tsx", import.meta.url), "utf8");

  assert.match(source, /<option value="PRIVATE">/);
  assert.match(source, /<option value="DEPARTMENT">/);
  assert.match(source, /<option value="PUBLIC">/);
  // COLLECTION은 부모 컬렉션 권한 상속 규칙 때문에 PRIVATE과 동작이 같아 구분할 의미가 없다.
  assert.doesNotMatch(source, /<option value="COLLECTION">/);
});
