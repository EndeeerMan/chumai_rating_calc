"use strict";

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const webRoot = path.join(__dirname, "web");
const read = (name) => fs.readFileSync(path.join(webRoot, name), "utf8");

for (const [page, script] of [
  ["index.html", "app.js"],
  ["chunithm.html", "chunithm.js"],
  ["sync.html", "sync.js"]
]) {
  test(`${page} 未登录时只跳转独立登录页`, () => {
    const html = read(page);
    const js = read(script);
    assert.match(html, /class="(?:maimai-page|chunithm-page|sync-page) auth-checking"/);
    assert.match(html, /id="auth-startup-mask"[^>]*role="status"[^>]*aria-live="polite"/);
    assert.match(js, /function requireAuthentication\(/);
    assert.match(js, /window\.location\.replace\("\/login\.html"\)/);
    assert.match(js, /(?:void )?checkAuthStatus\(\);/);
    assert.doesNotMatch(html, /id="auth-dialog"|id="auth-form"|data-auth-mode/);
    assert.doesNotMatch(js, /handleAuthSubmit|createRegistrationController|authMode/);
  });
}

test("游戏页不提供游客写入或 AI 识图入口", () => {
  for (const [page, script] of [
    ["index.html", "app.js"],
    ["chunithm.html", "chunithm.js"]
  ]) {
    const html = read(page);
    const js = read(script);
    assert.match(js, /charts:\s*\[\]/);
    assert.match(js, /!state\.authReady \|\| !state\.authenticated/);
    assert.doesNotMatch(js, /function writeGuestCharts\(/);
    assert.doesNotMatch(html, /id="ai-button"|id="ai-dialog"|ai-batch\.js/i);
    assert.doesNotMatch(js, /\/api\/ai|openAiDialog|recognizeAiImages/i);
  }
});

test("同步页拒绝未登录写入并开放双游戏正式同步", () => {
  const html = read("sync.html");
  const js = read("sync.js");
  assert.match(js, /const canWrite = state\.authReady && state\.authenticated/);
  assert.match(html, /name="proxy-game"\s+value="chunithm"(?![^>]*disabled)/);
  assert.match(html, /id="login-link" href="\/login\.html"/);
  assert.match(js, /本站不提供游客模式/);
  assert.doesNotMatch(html, /auth-email\.js/);
});

test("独立登录页提供小型注册和忘记密码入口", () => {
  const html = read("login.html");
  const css = read("login.css");
  const js = read("login.js");
  assert.match(html, /<form class="auth-form" id="login-form"/);
  assert.match(html, /id="login-identity"[^>]*maxlength="254"/s);
  assert.match(html, /还没有账号？\s*<a class="text-action" href="\/register\.html">注册<\/a>/s);
  assert.match(html, /href="\/forgot-password\.html">忘记密码？<\/a>/);
  assert.match(html, /<form class="auth-form" id="register-form"[^>]*hidden>/);
  assert.match(html, /已经有账号？\s*<a class="text-action" href="\/login\.html">返回登录<\/a>/s);
  assert.match(js, /window\.location\.pathname === "\/register\.html"/);
  assert.match(js, /window\.location\.pathname === "\/forgot-password\.html"/);
  assert.match(js, /window\.location\.replace\(emailRequired \? "\/profile\.html\?bindEmail=1" : "\/"\)/);
  assert.doesNotMatch(html, /theme\.css|user-theme\.js/);
  assert.doesNotMatch(css, /assets\/backgrounds|api\/user\/profile\/background/);
});

test("注册页严格限制新用户名与新密码", () => {
  const html = read("login.html");
  const js = read("login.js");
  assert.match(html, /id="register-username"[^>]*minlength="3"[^>]*maxlength="18"[^>]*pattern="\[A-Za-z0-9_\]\{3,18\}"/s);
  assert.equal((html.match(/pattern="\[!-~\]\{6,32\}"/g) || []).length, 4);
  assert.match(js, /const USERNAME_PATTERN = \/\^\[A-Za-z0-9_\]\{3,18\}\$\/u;/);
  assert.match(js, /const NEW_PASSWORD_PATTERN = \/\^\[!-~\]\{6,32\}\$\/u;/);
  assert.match(js, /const PASSWORD_RESET_ENDPOINT = "\/api\/auth\/password\/reset";/);
  assert.match(js, /createRegistrationController/);
  assert.match(js, /createPasswordResetController/);
  assert.match(read("auth-email.js"), /window\.addEventListener\("pageshow", resumeCountdown\)/);
});

test("认证前端脚本语法有效", () => {
  for (const name of ["login.js", "auth-email.js", "app.js", "chunithm.js", "sync.js"]) {
    assert.doesNotThrow(() => new Function(read(name)), name);
  }
});
