// Habit push (Firebase Cloud Messaging): wakes a phone for a Habit call or a new chat message while Habit is closed there,
// so the phone rings like a normal call instead of showing a missed call later.
//  - The push carries no words: chats and calls stay locked end to end. It only says "a call" or "a message" (and which
//    chat); the phone wakes up, fetches the locked notes from this server and opens them itself.
//  - Set up once by the owner on the Teach page with two files from the Firebase console (free):
//      firebase-app.json   = google-services.json: the app's Firebase ids. Not secret (every Firebase app carries them);
//                            the phones get them from here, so they are not in the public code.
//      firebase-admin.json = the service account key: lets this server send pushes. Secret: it stays on this server
//                            (data folder, only the server can read it) and is never sent anywhere.
//  - Sending uses Google's FCM HTTP v1 API with a short-lived access token signed with that key (no extra packages).
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

module.exports = function makePush({ DATA_DIR }) {
  const APP_FILE = path.join(DATA_DIR, "firebase-app.json");
  const ADMIN_FILE = path.join(DATA_DIR, "firebase-admin.json");
  const FCM_BASE = (process.env.FCM_BASE || "https://fcm.googleapis.com").replace(/\/+$/, "");   // tests point these at a fake
  const read = f => { try { return JSON.parse(fs.readFileSync(f, "utf8")); } catch (e) { return null; } };
  let app = null, admin = null, access = { token: "", until: 0 };
  const stats = { sent: 0, failed: 0, gone: 0, last: "", lastError: "" };

  /** The app's Firebase ids from google-services.json: { projectId, senderId, clients: { package: { appId, apiKey } } }. */
  function parseApp(j) {
    if (!j || !j.project_info || !Array.isArray(j.client)) return null;
    const projectId = String(j.project_info.project_id || ""), senderId = String(j.project_info.project_number || "");
    const clients = {};
    for (const c of j.client) {
      const pkg = String((((c || {}).client_info || {}).android_client_info || {}).package_name || "");
      const appId = String(((c || {}).client_info || {}).mobilesdk_app_id || "");
      const apiKey = String((((c || {}).api_key || [])[0] || {}).current_key || "");
      if (/^[\w.]+$/.test(pkg) && appId && apiKey) clients[pkg] = { appId, apiKey };
    }
    return projectId && senderId && Object.keys(clients).length ? { projectId, senderId, clients } : null;
  }
  function parseAdmin(j) {
    if (!j || j.type !== "service_account" || !j.client_email || !/BEGIN (RSA )?PRIVATE KEY/.test(String(j.private_key || "")) || !j.project_id) return null;
    return { email: String(j.client_email), key: String(j.private_key), projectId: String(j.project_id), tokenUri: String(j.token_uri || "https://oauth2.googleapis.com/token") };
  }
  function load() { app = parseApp(read(APP_FILE)); admin = parseAdmin(read(ADMIN_FILE)); access = { token: "", until: 0 }; }
  load();

  const ready = () => !!(app && admin && app.projectId === admin.projectId);

  /** What a phone needs to start Firebase for its app ([pkg]: its package name), or null. */
  function clientConfig(pkg) {
    if (!ready()) return null;
    const c = app.clients[pkg];
    return c ? { projectId: app.projectId, senderId: app.senderId, appId: c.appId, apiKey: c.apiKey } : null;
  }

  const b64url = b => Buffer.from(b).toString("base64").replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
  /** A short-lived access token for FCM (an hour), signed with the service account key. */
  async function token() {
    if (access.token && Date.now() < access.until) return access.token;
    const now = Math.floor(Date.now() / 1000);
    const head = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
    const claims = b64url(JSON.stringify({ iss: admin.email, scope: "https://www.googleapis.com/auth/firebase.messaging", aud: admin.tokenUri, iat: now, exp: now + 3600 }));
    const sig = b64url(crypto.createSign("RSA-SHA256").update(head + "." + claims).sign(admin.key));
    const r = await fetch(process.env.FCM_TOKEN_URI || admin.tokenUri, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
      body: "grant_type=" + encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + head + "." + claims + "." + sig, signal: AbortSignal.timeout(15000) });
    const j = await r.json().catch(() => ({}));
    if (!r.ok || !j.access_token) throw new Error("Google didn't give a token: " + (j.error_description || j.error || r.status));
    access = { token: j.access_token, until: Date.now() + Math.max(60, (Number(j.expires_in) || 3600) - 120) * 1000 };
    return access.token;
  }

  /**
   * Sends one push to one phone. [data]: small words only (no message text). [high]: a call (wakes the phone at once).
   * Returns "ok", "gone" (that phone's token is no longer valid: forget it) or "fail".
   */
  async function send(tok, data, high, ttlSec) {
    if (!ready() || !tok) return "fail";
    try {
      const body = { message: { token: tok, data: Object.fromEntries(Object.entries(data || {}).map(([k, v]) => [k, String(v)])),
        android: { priority: high ? "HIGH" : "NORMAL", ttl: (ttlSec || (high ? 45 : 86400)) + "s" } } };
      const r = await fetch(FCM_BASE + "/v1/projects/" + encodeURIComponent(admin.projectId) + "/messages:send", {
        method: "POST", headers: { authorization: "Bearer " + await token(), "content-type": "application/json" }, body: JSON.stringify(body), signal: AbortSignal.timeout(15000) });
      if (r.ok) { stats.sent++; stats.last = new Date().toISOString(); return "ok"; }
      const j = await r.json().catch(() => ({}));
      const why = JSON.stringify(j.error || {}).slice(0, 300);
      if (r.status === 401) access = { token: "", until: 0 };
      if (r.status === 404 || /UNREGISTERED|registration-token-not-registered/i.test(why) || (r.status === 400 && /token/i.test(why))) { stats.gone++; return "gone"; }
      stats.failed++; stats.lastError = r.status + " " + why;
      return "fail";
    } catch (e) { stats.failed++; stats.lastError = e.message; return "fail"; }
  }

  /** The owner's two files from the Firebase console (as text). Checks them and that Google accepts the key. */
  async function setup(appText, adminText) {
    let a = null, d = null;
    try { a = parseApp(JSON.parse(String(appText || ""))); } catch (e) { }
    try { d = parseAdmin(JSON.parse(String(adminText || ""))); } catch (e) { }
    if (!a) return { ok: false, error: "The first file isn't google-services.json (Firebase › Project settings › Your apps › google-services.json)." };
    if (!d) return { ok: false, error: "The second file isn't a service account key (Firebase › Project settings › Service accounts › Generate new private key)." };
    if (a.projectId !== d.projectId) return { ok: false, error: `The two files are from different Firebase projects (${a.projectId} and ${d.projectId}).` };
    const oldApp = app, oldAdmin = admin;
    app = a; admin = d; access = { token: "", until: 0 };
    try { await token(); }
    catch (e) { app = oldApp; admin = oldAdmin; access = { token: "", until: 0 }; return { ok: false, error: "Google didn't accept the key: " + e.message }; }
    fs.writeFileSync(APP_FILE, JSON.stringify(JSON.parse(String(appText))), { mode: 0o600 });
    fs.writeFileSync(ADMIN_FILE, JSON.stringify(JSON.parse(String(adminText))), { mode: 0o600 });
    try { fs.chmodSync(ADMIN_FILE, 0o600); fs.chmodSync(APP_FILE, 0o600); } catch (e) { }
    return Object.assign({ ok: true }, status());
  }
  function remove() {
    for (const f of [APP_FILE, ADMIN_FILE]) { try { fs.unlinkSync(f); } catch (e) { } }
    load();
    return status();
  }
  function status() {
    return { ready: ready(), project: app ? app.projectId : (admin ? admin.projectId : ""), apps: app ? Object.keys(app.clients) : [],
      keyFile: !!admin, appFile: !!app, sent: stats.sent, failed: stats.failed, gone: stats.gone, last: stats.last, lastError: stats.lastError };
  }

  return { ready, clientConfig, send, setup, remove, status };
};
