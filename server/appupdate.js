// One-tap updates for Habit AI Lite. Every Lite build on GitHub becomes a public release (see the build workflow);
// this finds the newest one, so people who got the app on WhatsApp get new versions without a GitHub account.
//   /app/latest  -> { versionCode, versionName, url, size, notes }  (the app asks this once in a while)
//   /download    -> the newest Habit AI Lite APK (a link anyone can share)
const { execFileSync } = require("child_process");

module.exports = function makeAppUpdate({ REPO_DIR }) {
  // owner/repo: APP_REPO in .env, or worked out from this folder's GitHub address
  function repo() {
    if (process.env.APP_REPO) return process.env.APP_REPO;
    try {
      const u = execFileSync("git", ["-C", REPO_DIR, "remote", "get-url", "origin"], { encoding: "utf8", timeout: 3000 }).trim();
      const m = u.match(/github\.com[:/]+([^/]+)\/([^/]+?)(?:\.git)?\/?$/);
      if (m) return m[1] + "/" + m[2];
    } catch (e) { }
    return "";
  }

  let cache = { at: 0, data: null };
  async function latest() {
    if (cache.data && Date.now() - cache.at < 15 * 60000) return cache.data;
    const r = repo();
    if (!r) return null;
    try {
      const res = await fetch(`${process.env.GITHUB_API || "https://api.github.com"}/repos/${r}/releases?per_page=10`, {
        headers: { accept: "application/vnd.github+json", "user-agent": "Habit-AI-server" }, signal: AbortSignal.timeout(8000) });
      if (!res.ok) throw new Error("GitHub said " + res.status);
      const list = await res.json();
      const rel = (Array.isArray(list) ? list : []).find(x => !x.draft && !x.prerelease && /^lite-v/.test(x.tag_name || ""));
      const apk = rel && (rel.assets || []).find(a => /lite.*\.apk$/i.test(a.name || ""));
      // the tiny brain app (Habit Brain) from the newest release that has it
      const withBrain = (Array.isArray(list) ? list : []).find(x => !x.draft && /^lite-v/.test(x.tag_name || "") && (x.assets || []).some(a => /brain.*\.apk$/i.test(a.name || "")));
      const brain = withBrain && withBrain.assets.find(a => /brain.*\.apk$/i.test(a.name || ""));
      const data = apk ? {
        versionCode: Number((String(rel.tag_name).match(/(\d+)$/) || [])[1] || 0),
        versionName: String(rel.tag_name).replace(/^lite-v/, "") + "-lite",
        url: apk.browser_download_url, size: apk.size, notes: String(rel.body || "").slice(0, 500), at: rel.published_at,
        ...(brain ? { brainUrl: brain.browser_download_url, brainSize: brain.size } : {})
      } : null;
      cache = { at: Date.now(), data };
      return data;
    } catch (e) {
      cache.at = Date.now() - 10 * 60000;     // try GitHub again in 5 minutes; meanwhile use what we had
      return cache.data;
    }
  }

  return { latest, repo };
};
