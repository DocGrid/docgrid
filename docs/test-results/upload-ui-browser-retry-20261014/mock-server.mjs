import http from 'node:http';
import { createHash } from 'node:crypto';

const port = 18761;
const attempts = new Map();
const events = [];
let firstResponseLost = false;
let nextId = 1001;
const digest = (value) => createHash('sha256').update(value).digest('hex');
const envelope = (data, status = 200) => ({ success: true, status, data, timestamp: new Date().toISOString() });
function send(res, status, data) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(data));
}
function documentList() {
  const content = [...attempts.values()].map((item) => ({ documentId: item.response.documentId, title: item.title,
    description: item.description, documentType: 'TXT', status: 'UPLOADED', visibility: item.visibility,
    ownerUserId: 1, ownerName: 'Browser Test', currentVersionNo: 1, currentVersionStatus: 'UPLOADED',
    createdAt: '2026-10-14T00:00:00Z', updatedAt: '2026-10-14T00:00:00Z' }));
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0,
    first: true, last: true };
}
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${port}`);
  try {
    if (url.pathname === '/__test/state') {
      const values = [...attempts.values()];
      return send(res, 200, { runId: 'UI-BROWSER-20261014-01', requestCount: events.length,
        documentCount: values.length, versionCount: values.length, fileCount: values.length, jobCount: values.length,
        events });
    }
    if (url.pathname === '/auth/login' && req.method === 'POST') {
      for await (const _chunk of req) { /* Discard synthetic credentials without storing them. */ }
      return send(res, 200, envelope({ accessToken: 'mock-browser-token', tokenType: 'Bearer', expiresIn: 3600,
        userId: 1, email: 'browser-test@example.invalid', roles: ['USER'] }));
    }
    if (url.pathname === '/auth/me' && req.method === 'GET') {
      return send(res, 200, envelope({ userId: 1, email: 'browser-test@example.invalid', name: 'Browser Test',
        nickname: null, profileImageUrl: null, departmentId: 1, departmentName: 'Test', roles: ['USER'],
        status: 'ACTIVE', createdAt: '2026-10-14T00:00:00Z', lastLoginAt: null }));
    }
    if (url.pathname === '/api/documents' && req.method === 'GET') return send(res, 200, envelope(documentList()));
    if (url.pathname === '/api/documents' && req.method === 'POST') {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const body = Buffer.concat(chunks);
      const form = await new Request('http://mock.invalid/api/documents', { method: 'POST',
        headers: { 'content-type': req.headers['content-type'] }, body, duplex: 'half' }).formData();
      const file = form.get('file');
      const title = String(form.get('title') ?? '');
      const description = String(form.get('description') ?? '');
      const visibility = String(form.get('visibility') ?? '');
      const key = String(req.headers['idempotency-key'] ?? '');
      if (!key || !file || typeof file === 'string') return send(res, 400, { success: false, message: '키 또는 파일 누락' });
      const fileBytes = Buffer.from(await file.arrayBuffer());
      const payloadHash = digest(Buffer.concat([Buffer.from(JSON.stringify({ title, description, visibility,
        fileName: file.name, fileType: file.type })), fileBytes]));
      const existing = attempts.get(key);
      const keyDigest = digest(key).slice(0, 12);
      if (existing && existing.payloadHash !== payloadHash) {
        events.push({ ordinal: events.length + 1, keyDigest, payloadDigest: payloadHash.slice(0, 12),
          replay: true, samePayload: false, status: 409 });
        return send(res, 409, { success: false, status: 409, code: 'DOCUMENT-UPLOAD-004', message: '다른 내용' });
      }
      let entry = existing;
      if (!entry) {
        const id = nextId++;
        entry = { title, description, visibility, payloadHash,
          response: { documentId: id, documentVersionId: id + 1000, fileObjectId: id + 2000,
            embeddingJobId: id + 3000, documentStatus: 'UPLOADED', jobStatus: 'PENDING' } };
        attempts.set(key, entry);
      }
      const loseResponse = !firstResponseLost;
      if (loseResponse) firstResponseLost = true;
      events.push({ ordinal: events.length + 1, keyDigest, payloadDigest: payloadHash.slice(0, 12),
        replay: Boolean(existing), samePayload: !existing || existing.payloadHash === payloadHash,
        status: loseResponse ? 502 : 201, documentId: entry.response.documentId, fileBytes: fileBytes.length });
      if (loseResponse) return send(res, 502, { success: false, status: 502, code: 'MOCK_RESPONSE_UNKNOWN',
        message: '시험용 응답 확인 불가' });
      return send(res, 201, envelope(entry.response, 201));
    }
    return send(res, 404, { success: false, status: 404, message: '시험 모의 서버에 없는 경로' });
  } catch (error) {
    send(res, 500, { success: false, status: 500, message: '시험 서버 처리 오류' });
  }
});
server.listen(port, '127.0.0.1', () => process.stdout.write('mock-ready port=18761 run=UI-BROWSER-20261014-01\n'));
