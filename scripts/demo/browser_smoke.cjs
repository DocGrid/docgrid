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
  const page = await browser.newPage({ acceptDownloads: true });
  page.setDefaultTimeout(45000);
  let stage = 'login';
  try {
    await page.goto('http://127.0.0.1:3000/login?returnTo=%2Fsearch');
    await page.getByLabel('이메일', { exact: true }).fill('demo@docgrid.local');
    await page.getByLabel('비밀번호', { exact: true }).fill(process.env.DOCGRID_DEMO_PASSWORD);
    await page.getByRole('button', { name: '로그인', exact: true }).click();
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
    // Playwright 오류 본문에는 입력값이 포함될 수 있으므로 유형과 단계만 보존한다.
    await fs.writeFile(path.join(directory, 'summary.json'), JSON.stringify({ verdict: 'FAIL', stage, errorType: error.name }));
    console.log(JSON.stringify({ verdict: 'FAIL', stage, errorType: error.name }));
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
