"use strict";

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const root = __dirname;
const read = (name) => fs.readFileSync(path.join(root, "web", name), "utf8");

for (const [page, script] of [
  ["index.html", "app.js"],
  ["chunithm.html", "chunithm.js"]
]) {
  test(`${page} 首次进入使用不可跳过的登录门`, () => {
    const html = read(page);
    const js = read(script);
    assert.match(html, /id="close-auth-dialog-button"[^>]*hidden/);
    assert.match(html, /id="cancel-auth-button"[^>]*hidden/);
    assert.match(html, /本站不提供游客模式/);
    assert.match(js, /charts:\s*\[\]/);
    assert.match(js, /function requireAuthentication\(/);
    assert.match(js, /function releaseAuthenticationGate\(/);
    assert.match(html, /class="(?:maimai-page|chunithm-page) auth-checking"/);
    assert.match(html, /id="auth-startup-mask"[^>]*role="status"[^>]*aria-live="polite"/);
    assert.match(js, /function finishAuthStartup\(\)/);
    assert.match(js, /finishAuthStartup\(\);[\s\S]*requireAuthentication\(\);/s);
    const boot = js.slice(js.lastIndexOf("state.calculation = calculateLocally()"));
    assert.doesNotMatch(boot, /requireAuthentication\(\);/);
    assert.match(boot, /checkAuthStatus\(\);/);
    assert.match(js, /!state\.authReady \|\| !state\.authenticated/);
    assert.doesNotMatch(js, /function writeGuestCharts\(/);
    assert.doesNotMatch(html, /id="ai-button"|id="ai-dialog"|ai-batch\.js/i);
    assert.doesNotMatch(js, /\/api\/ai|openAiDialog|recognizeAiImages/i);
  });
}

test("同步页拒绝未登录写入，同时开放双游戏正式同步", () => {
  const html = read("sync.html");
  const js = read("sync.js");
  assert.match(js, /const canWrite = state\.authReady && state\.authenticated/);
  assert.match(html, /name="proxy-game"\s+value="chunithm"(?![^>]*disabled)/);
  assert.match(js, /本站不提供游客模式/);
  assert.match(html, /id="auth-dialog"/);
  assert.match(html, /id="auth-submit-button"/);
  assert.match(js, /function requireAuthentication\(/);
  assert.match(js, /function handleAuthSubmit\(/);
  assert.match(html, /<body class="sync-page auth-checking">/);
  assert.match(html, /id="auth-startup-mask"[^>]*role="status"/);
  const initialize = js.slice(js.indexOf("function initialize()"), js.indexOf("function cacheElements()"));
  assert.doesNotMatch(initialize, /requireAuthentication\(\);/);
  assert.match(initialize, /void checkAuthStatus\(\);/);
  assert.match(js, /finishAuthStartup\(\);[\s\S]*else requireAuthentication\(\);/s);
});
