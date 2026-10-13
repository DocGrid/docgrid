// Local browser test proxy: forward real backend requests and replace only the first committed version response with 502.
// It records request-key hashes and synthetic result IDs, never credentials, tokens, or file contents.
import http from "node:http";
import { createHash } from "node:crypto";

const listenPort = 18761;
const backend = "http://127.0.0.1:18762";
const events = [];
let responseLost = false;
const digest = (value) => createHash("sha256").update(value).digest("hex").slice(0, 12);

function send(res, status, payload) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(payload));
}

http.createServer(async (req, res) => {
  if (req.url === "/__test/state") {
    return send(res, 200, { runId: "VERSION-UI-BROWSER-20261014-01", events });
  }

  try {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = Buffer.concat(chunks);
    const headers = new Headers();
    for (const [name, value] of Object.entries(req.headers)) {
      if (value && !["host", "connection", "content-length", "transfer-encoding"].includes(name)) {
        headers.set(name, Array.isArray(value) ? value.join(", ") : value);
      }
    }
    const upstream = await fetch(new URL(req.url, backend), {
      method: req.method, headers, body: body.length ? body : undefined, redirect: "manual",
    });
    const payload = await upstream.text();

    if (req.method === "POST" && /^\/api\/documents\/\d+\/versions$/.test(req.url ?? "")) {
      const form = await new Request("http://local.test/upload", {
        method: "POST", headers: { "content-type": req.headers["content-type"] }, body,
      }).formData();
      const file = form.get("file");
      const fileBytes = file && typeof file !== "string" ? Buffer.from(await file.arrayBuffer()) : Buffer.alloc(0);
      const result = upstream.status === 201 ? JSON.parse(payload).data : null;
      const browserStatus = upstream.status === 201 && !responseLost ? 502 : upstream.status;
      events.push({
        ordinal: events.length + 1,
        keyDigest: digest(String(req.headers["idempotency-key"] ?? "")),
        fileDigest: digest(fileBytes), fileBytes: fileBytes.length,
        backendStatus: upstream.status, browserStatus,
        documentId: result?.documentId ?? null,
        versionId: result?.documentVersionId ?? null,
        versionNo: result?.versionNo ?? null,
        jobId: result?.embeddingJobId ?? null,
      });
      if (browserStatus === 502) {
        responseLost = true;
        return send(res, 502, {
          success: false, status: 502, code: "TEST_RESPONSE_UNKNOWN",
          message: "시험용 응답 확인 불가",
        });
      }
    }

    res.writeHead(upstream.status, {
      "content-type": upstream.headers.get("content-type") ?? "application/json; charset=utf-8",
      "cache-control": "no-store",
    });
    res.end(payload);
  } catch {
    send(res, 502, { success: false, status: 502, message: "시험 프록시 연결 실패" });
  }
}).listen(listenPort, "127.0.0.1", () => process.stdout.write("fault-proxy-ready\n"));
