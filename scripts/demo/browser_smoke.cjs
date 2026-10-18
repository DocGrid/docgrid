// 독립 실행기의 실제 Chromium으로 로그인·검색·다운로드를 검증한다. 인증정보·원문 답변은 기록하지 않는다.
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { chromium } = require(process.env.DEMO_BROWSER_MODULE);

(async () => {
  const directory = process.argv[2];
  await fs.mkdir(directory, { recursive: false });
  const query = JSON.parse(await fs.readFile('examples/demo-corpus/checks.json')).queries[0];
  const original = await fs.readFile(path.join('examples/demo-corpus', query.expectedFile));
  const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
  const previous = JSON.parse(await fs.readFile('.demo/launcher/last-report.json'));
  const report = JSON.parse(await fs.readFile(previous.report));
  const documentId = report.documents[sha(original)];
  const browser = await chromium.launch();
  // SSR 경계는 별도 무스크립트 화면에서 검증하며, 인증정보를 입력하지 않는다.
  const noScriptContext = await browser.newContext({ javaScriptEnabled: false });
  const noScriptPage = await noScriptContext.newPage();
  await noScriptPage.goto('http://127.0.0.1:3000/login');
  const postFallback = (await noScriptPage.locator('form').getAttribute('method')) === 'post';
  const initiallyDisabled = !(await noScriptPage.getByRole('button', { name: '로그인', exact: true }).isEnabled());
  await fs.writeFile(path.join(directory, 'ssr-auth.json'), JSON.stringify({ purpose: 'hydration 이전 인증정보 URL 제출 방지',
    timestamp: new Date().toISOString(), postFallback, initiallyDisabled,
    verdict: postFallback && initiallyDisabled ? 'PASS' : 'FAIL' }, null, 2));
  await noScriptContext.close();
  if (!postFallback || !initiallyDisabled) { await browser.close(); throw new Error('SSR auth boundary'); }
  const page = await browser.newPage({ acceptDownloads: true });
  page.setDefaultTimeout(45000);
  const clean = value => String(value).replaceAll(process.env.DOCGRID_DEMO_PASSWORD, '<비밀값 생략>')
    .replace(/([?&](?:password|token|email)=)[^&#\s"']+/gi, '$1<비밀값 생략>')
    .replaceAll(process.cwd(), '<작업공간>')
    .replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '<토큰 생략>')
    .replace(/[\w.+%-]+@[\w.-]+\.[\w.-]+/g, '<이메일 생략>');
  const pageErrors = [];
  const consoleErrors = [];
  const failedResources = [];
  page.on('pageerror', error => pageErrors.push(clean(error.message)));
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(clean(message.text())); });
  page.on('requestfailed', request => failedResources.push({ path: clean(new URL(request.url()).pathname),
    error: clean(request.failure()?.errorText ?? 'unknown') }));
  let stage = 'login-page';
  try {
    await page.goto('http://127.0.0.1:3000/login?returnTo=%2Fsearch');
    stage = 'login-form';
    await page.getByLabel('이메일', { exact: true }).fill('demo@docgrid.local');
    await page.getByLabel('비밀번호', { exact: true }).fill(process.env.DOCGRID_DEMO_PASSWORD);
    stage = 'login-submit';
    await page.getByRole('button', { name: '로그인', exact: true }).click();
    stage = 'login-navigation';
    await page.waitForURL('**/search');
    stage = 'search';
    await page.getByRole('textbox', { name: '문서 검색', exact: true }).fill(query.query);
    const response = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/search'));
    await page.getByRole('button', { name: '검색', exact: true }).click();
    const result = await response;
    const body = await result.json();
    if (result.status() !== 200 || !body.data.results.some(item => item.documentId === documentId)) throw new Error('search verdict');
    const link = page.locator(`a.source-card[href="/documents/${documentId}"]`).first();
    await link.waitFor();
    await page.screenshot({ path: path.join(directory, 'search.png'), fullPage: true,
      mask: [page.getByText('demo@docgrid.local', { exact: true })] });
    await link.click();
    await page.waitForURL(`**/documents/${documentId}`);
    stage = 'download';
    const downloaded = page.waitForEvent('download');
    await page.getByRole('button', { name: '↓ 다운로드', exact: true }).click();
    const download = await downloaded;
    const bytes = await fs.readFile(await download.path());
    if (sha(bytes) !== sha(original)) throw new Error('download hash');
    await fs.writeFile(path.join(directory, 'summary.json'), JSON.stringify({ verdict: 'PASS',
      timestamp: new Date().toISOString(), browser: browser.version(), login: 'PASS', search: 'PASS',
      download: 'PASS', documentId, bytes: bytes.length, sha256: sha(bytes) }, null, 2));
    console.log(JSON.stringify({ login: 'PASS', search: 'PASS', download: 'PASS', downloadedBytes: bytes.length }));
  } catch (error) {
    // 입력값·토큰을 제거한 뒤에만 실패 원인과 화면을 보존한다.
    await fs.writeFile(path.join(directory, 'summary.json'), JSON.stringify({ verdict: 'FAIL', stage,
      errorType: error.name, message: clean(error.message), pageErrors, consoleErrors, failedResources,
      pagePath: new URL(page.url()).pathname,
      alerts: (await page.getByRole('alert').allTextContents()).map(clean) }, null, 2));
    await page.screenshot({ path: path.join(directory, 'failure.png'), fullPage: true,
      mask: [page.locator('input'), page.getByText('demo@docgrid.local', { exact: true })] });
    console.log(JSON.stringify({ verdict: 'FAIL', stage, errorType: error.name }));
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
