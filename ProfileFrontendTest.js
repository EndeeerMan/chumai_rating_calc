"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const root = __dirname;
const webRoot = path.join(root, "web");
const readWeb = (name) => fs.readFileSync(path.join(webRoot, name), "utf8");
const profileHtml = readWeb("profile.html");
const profileCss = readWeb("profile.css");
const profileJs = readWeb("profile.js");
const themeCss = readWeb("theme.css");
const userThemeJs = readWeb("user-theme.js");
const authEmailJs = readWeb("auth-email.js");
const loginHtml = readWeb("login.html");
const loginCss = readWeb("login.css");
const loginJs = readWeb("login.js");
const mainCss = readWeb("styles.css");
const syncCss = readWeb("sync.css");
const pages = new Map([
  ["index.html", readWeb("index.html")],
  ["chunithm.html", readWeb("chunithm.html")],
  ["sync.html", readWeb("sync.html")],
  ["profile.html", profileHtml]
]);
const authScripts = new Map([
  ["app.js", readWeb("app.js")],
  ["chunithm.js", readWeb("chunithm.js")],
  ["sync.js", readWeb("sync.js")]
]);

function sourceBetween(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start);
  assert.ok(start >= 0 && end > start, `无法提取 ${startMarker}`);
  return source.slice(start, end);
}

function countMatches(source, expression) {
  return Array.from(source.matchAll(expression)).length;
}

test("四张默认背景均为项目内有效图片资产", () => {
  const assets = [
    ["assets/backgrounds/maimai-desktop.png", "png"],
    ["assets/backgrounds/maimai-mobile.jpg", "jpeg"],
    ["assets/backgrounds/chunithm-desktop.png", "png"],
    ["assets/backgrounds/chunithm-mobile.jpg", "jpeg"]
  ];
  for (const [relativePath, type] of assets) {
    const contents = fs.readFileSync(path.join(webRoot, ...relativePath.split("/")));
    assert.ok(contents.length > 1024, `${relativePath} 不能是空壳图片`);
    assert.equal(
      contents.subarray(0, type === "png" ? 8 : 2).toString("hex"),
      type === "png" ? "89504e470d0a1a0a" : "ffd8"
    );
    assert.ok(themeCss.includes(`url("/${relativePath}")`));
  }
  const appearance = `${themeCss}\n${userThemeJs}\n${profileHtml}\n${profileJs}`;
  assert.doesNotMatch(appearance, /(?:file:\/\/|[A-Za-z]:\\)/i);
  assert.doesNotMatch(appearance, /url\(["']?https?:\/\//i);
});

test("四页加载共用主题并保留响应式默认背景", () => {
  const bodyClasses = {
    "index.html": "maimai-page",
    "chunithm.html": "chunithm-page",
    "sync.html": "sync-page",
    "profile.html": "profile-page"
  };
  for (const [name, html] of pages) {
    const extraClass = name === "profile.html" ? "" : " auth-checking";
    assert.match(html, new RegExp(`<body class="${bodyClasses[name]}${extraClass}">`));
    assert.match(html, /<link rel="stylesheet" href="theme\.css">/);
    assert.match(html, /<script src="user-theme\.js" defer><\/script>/);
  }
  assert.match(themeCss, /body\.maimai-page,[\s\S]*body\.profile-page\s*\{[^}]*maimai-desktop\.png[^}]*maimai-mobile\.jpg/s);
  assert.match(themeCss, /body\.chunithm-page\s*\{[^}]*chunithm-desktop\.png[^}]*chunithm-mobile\.jpg/s);
  assert.match(themeCss, /@media \(max-width:\s*760px\)[\s\S]*background-image:\s*var\(--page-background-mobile\);/s);
  assert.match(themeCss, /background-size:\s*cover;/);
});

test("舞萌和中二背景独立存储、独立选择且兼容旧舞萌字段", () => {
  assert.match(themeCss, /has-maimai-background[\s\S]*background\?game=maimai/s);
  assert.match(themeCss, /has-chunithm-background[\s\S]*background\?game=chunithm/s);
  assert.match(themeCss, /body\.profile-page\.has-maimai-background::before/);
  assert.match(themeCss, /body\.sync-page\.has-maimai-background:not\(\.sync-chunithm-background\)::before/);
  assert.match(themeCss, /body\.sync-page\.sync-chunithm-background\.has-chunithm-background::before/);
  assert.match(userThemeJs, /backgroundUrls:\s*\{[\s\S]*maimai:[\s\S]*chunithm:/s);
  assert.match(userThemeJs, /value\?\.backgroundUrl/);
  assert.match(userThemeJs, /has-maimai-background/);
  assert.match(userThemeJs, /has-chunithm-background/);
  assert.match(userThemeJs, /classList\.remove\("has-maimai-background", "has-chunithm-background"\)/);
  assert.match(authScripts.get("sync.js"), /sync-chunithm-background/);
  assert.doesNotMatch(userThemeJs, /style\.background(?:Image)?\s*=/);
});

test("普通容器提高透明度而带封面成绩卡保持完全不透明", () => {
  assert.match(themeCss, /--glass-surface:\s*rgba\(255,\s*255,\s*255,\s*0\.58\);/);
  assert.match(themeCss, /\.topbar,[\s\S]*\.profile-hero\s*\{[^}]*background-color:\s*var\(--glass-surface\);[^}]*backdrop-filter:/s);
  assert.match(themeCss, /button\.lxns-score-card,[\s\S]*background-color:\s*#1e2423;[^}]*-webkit-backdrop-filter:\s*none;[^}]*backdrop-filter:\s*none;/s);
});

test("手机顶栏为两行布局并避免窄屏横向溢出", () => {
  for (const [name, html] of pages) {
    const header = html.match(/<header class="topbar">[\s\S]*?<\/header>/)?.[0] || "";
    assert.match(header, /<nav class="game-switcher" aria-label="游戏切换">/);
    assert.equal(countMatches(header, /<a(?:\s[^>]*)?href="\/(?:"|chunithm\.html"|sync\.html")/g), 3, name);
  }
  const mobile = sourceBetween(themeCss, "@media (max-width: 760px)", "@media (max-width: 360px)");
  assert.match(mobile, /grid-template-areas:\s*"brand actions"\s*"navigation navigation";/s);
  assert.match(mobile, /grid-template-columns:\s*repeat\(3,\s*minmax\(0,\s*1fr\)\);/);
  assert.match(mobile, /\.verification-input-row\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);/s);
  for (const css of [mainCss, syncCss]) assert.match(css, /\.game-switcher a\s*\{[^}]*white-space:\s*nowrap;/s);
});

test("资料页提供两套背景上传与恢复操作", () => {
  for (const game of ["maimai", "chunithm"]) {
    assert.match(profileHtml, new RegExp(`id="${game}-background-preview"`));
    assert.match(profileHtml, new RegExp(`id="${game}-background-input" type="file" accept="image/png,image/jpeg" hidden`));
    assert.match(profileHtml, new RegExp(`id="remove-${game}-background-button"`));
  }
  assert.equal(countMatches(profileHtml, /<article class="background-editor"/g), 2);
  assert.equal(countMatches(profileHtml, /<\/article>/g), 2);
  assert.match(profileJs, /backgroundEndpoint\(game\)/);
  assert.match(profileJs, /uploadImage\(backgroundEndpoint\(game\), file/);
  assert.match(profileJs, /removeImage\(backgroundEndpoint\(game\)/);
});

test("邮箱必须绑定且可验证换绑，未绑定时仍保留退出与注销逃生口", () => {
  assert.match(profileHtml, /<form id="email-form"/);
  assert.match(profileHtml, /id="email-address"[^>]*type="email"[^>]*required/s);
  assert.match(profileHtml, /id="email-verification-code"[^>]*pattern="\[0-9\]\{6\}"[^>]*maxlength="6"/s);
  assert.match(profileHtml, /id="email-current-password"[^>]*autocomplete="current-password"[^>]*required/s);
  assert.match(profileHtml, /id="email-required-notice"[^>]*role="alert"[^>]*hidden/s);
  assert.ok(countMatches(profileHtml, /data-email-protected/g) >= 4);
  const logoutSection = profileHtml.match(/<section class="profile-card profile-card-wide danger-zone" aria-labelledby="session-title">[\s\S]*?<\/section>/)?.[0] || "";
  assert.doesNotMatch(logoutSection, /data-email-protected/);
  const deleteSection = profileHtml.match(/<section class="profile-card profile-card-wide danger-zone" aria-labelledby="delete-account-title">[\s\S]*?<\/section>/)?.[0] || "";
  assert.doesNotMatch(deleteSection, /data-email-protected/);
  assert.match(profileJs, /const EMAIL_ENDPOINT = "\/api\/user\/email";/);
  assert.match(profileJs, /requestJson\(EMAIL_ENDPOINT, \{ method: "GET" \}\)/);
  assert.match(profileJs, /body:\s*JSON\.stringify\(\{ email, verificationCode, currentPassword \}\)/);
  assert.match(profileJs, /querySelectorAll\("\[data-email-protected\]"\)/);
  assert.match(profileJs, /section\.hidden = emailState\.required/);
});

test("邮箱验证码为六位、十分钟有效且重发冷却 120 秒", () => {
  assert.match(profileJs, /const EMAIL_CODE_RESEND_SECONDS = 120;/);
  assert.match(profileJs, /const EMAIL_CODE_ENDPOINT = "\/api\/auth\/email\/code";/);
  assert.match(profileJs, /body:\s*JSON\.stringify\(\{ email, purpose: "bind" \}\)/);
  assert.match(profileJs, /\/\^\\d\{6\}\$\/u\.test\(verificationCode\)/);
  assert.match(profileJs, /10 分钟内有效/);
  assert.match(profileJs, /2 分钟/);
  assert.match(authEmailJs, /const RESEND_SECONDS = 120;/);
  assert.match(authEmailJs, /const CODE_PATTERN = \/\^\\d\{6\}\$\/u;/);
  assert.match(authEmailJs, /const FLOW_ID_PATTERN = \/\^\[0-9a-f\]/);
  assert.match(authEmailJs, /body:\s*JSON\.stringify\(\{ email, purpose \}\)/);
  assert.match(authEmailJs, /purpose:\s*"register"/);
  assert.match(authEmailJs, /purpose:\s*"reset-password"/);
  assert.match(authEmailJs, /payload\?\.verificationFlowId/);
  assert.match(authEmailJs, /return \{ email, verificationCode, verificationFlowId \};/);
  assert.match(authEmailJs, /normalizedEmail\(\) !== flowEmail/);
  assert.match(authEmailJs, /emailInput\.disabled = !active/);
  assert.match(authEmailJs, /codeInput\.disabled = !active/);
  assert.match(authEmailJs, /验证码已发送，10 分钟内有效/);
  assert.match(authEmailJs, /countdownTimer = null;[\s\S]*window\.addEventListener\("pageshow", resumeCountdown\)/s);
  assert.match(profileJs, /emailCodeTimer = null;[\s\S]*window\.addEventListener\("pageshow", resumeEmailCodeCooldown\)/s);
});

test("资料页支持带双重确认的账号注销", () => {
  assert.match(profileHtml, /<form id="delete-account-form"/);
  assert.match(profileHtml, /id="delete-current-password"[^>]*autocomplete="current-password"[^>]*required/s);
  assert.match(profileHtml, /id="delete-username-confirmation"[^>]*autocomplete="off"[^>]*required/s);
  assert.match(profileJs, /const ACCOUNT_ENDPOINT = "\/api\/user\/account";/);
  assert.match(profileJs, /confirmation !== profile\.username/);
  assert.doesNotMatch(
    sourceBetween(profileJs, 'elements.deleteAccountForm.addEventListener("submit"', 'window.addEventListener("pagehide"'),
    /emailState\?\.required/
  );
  assert.match(profileJs, /requestJson\(ACCOUNT_ENDPOINT, \{[\s\S]*method:\s*"DELETE"[\s\S]*body:\s*JSON\.stringify\(\{ currentPassword \}\)/s);
  assert.match(profileJs, /window\.B50ProfileTheme\?\.clear\(\);[\s\S]*window\.location\.replace\("\/login\.html"\);/s);
});

test("登录、注册和找回密码使用独立认证页面", () => {
  for (const [name, html] of pages) {
    assert.doesNotMatch(html, /id="auth-dialog"|id="auth-form"|id="auth-email"/, name);
  }
  assert.match(loginHtml, /id="login-identity"[^>]*autocomplete="username"/s);
  assert.match(loginHtml, /href="\/register\.html">注册<\/a>/);
  assert.match(loginHtml, /href="\/forgot-password\.html">忘记密码？<\/a>/);
  assert.match(loginHtml, /id="register-email"[^>]*type="email"[^>]*required/s);
  assert.match(loginHtml, /id="register-code"[^>]*pattern="\[0-9\]\{6\}"/s);
  assert.match(loginHtml, /id="forgot-email"[^>]*type="email"[^>]*required/s);
  assert.match(loginJs, /createRegistrationController/);
  assert.match(loginJs, /createPasswordResetController/);
  assert.doesNotMatch(loginHtml, /theme\.css|user-theme\.js/);
  assert.doesNotMatch(loginCss, /assets\/backgrounds|profile\/background/);
});

test("登录状态遇到 emailRequired 强制绑定，认证成功默认进入舞萌", () => {
  for (const [name, js] of authScripts) {
    assert.match(js, /payload\?\.emailRequired === true \|\| payload\?\.user\?\.emailRequired === true/, name);
    assert.match(js, /window\.location\.replace\("\/profile\.html\?bindEmail=1"\)/, name);
    assert.ok(countMatches(js, /redirectIfEmailRequired\(payload\)/g) >= 1, name);
  }
  assert.match(loginJs, /window\.location\.replace\(emailRequired \? "\/profile\.html\?bindEmail=1" : "\/"\)/);
  assert.match(profileJs, /auth\?\.emailRequired === true \|\| auth\?\.user\?\.emailRequired === true/);
});

test("资料页先验登录，头像与双背景使用原始 File 上传", () => {
  const loadProfile = sourceBetween(profileJs, "async function loadProfile()", "elements.nicknameForm.addEventListener");
  assert.ok(loadProfile.indexOf("requestJson(AUTH_STATUS_ENDPOINT") < loadProfile.indexOf("requestJson(EMAIL_ENDPOINT"));
  assert.match(loadProfile, /window\.location\.replace\("\/login\.html"\)/);
  const upload = sourceBetween(profileJs, "async function uploadImage", "async function removeImage");
  assert.match(upload, /method:\s*"PUT"/);
  assert.match(upload, /headers:\s*\{ "Content-Type": file\.type \}/);
  assert.match(upload, /body:\s*file/);
  assert.doesNotMatch(upload, /JSON\.stringify|FormData|FileReader|base64/i);
  assert.match(profileJs, /const AVATAR_MAX_BYTES = 5 \* 1024 \* 1024;/);
  assert.match(profileJs, /const BACKGROUND_MAX_BYTES = 12 \* 1024 \* 1024;/);
});

test("预览资源会回收且前端不持久保存敏感资料", () => {
  const preview = sourceBetween(profileJs, "function previewFile", "async function profileFromMutation");
  assert.match(preview, /URL\.createObjectURL\(file\)/);
  assert.match(preview, /URL\.revokeObjectURL\(url\)/);
  assert.match(profileJs, /window\.addEventListener\("pagehide"[\s\S]*previewUrls\.clear\(\)/s);
  const scripts = `${profileJs}\n${userThemeJs}\n${authEmailJs}`;
  assert.doesNotMatch(scripts, /localStorage|sessionStorage|indexedDB|document\.cookie/i);
  assert.doesNotMatch(scripts, /innerHTML\s*=/);
});

test("资料表单与双背景在手机上收为单列", () => {
  assert.match(profileCss, /@media \(max-width:\s*700px\)[\s\S]*\.profile-grid\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);/s);
  assert.match(profileCss, /@media \(max-width:\s*700px\)[\s\S]*\.email-form,[\s\S]*\.delete-account-form\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);/s);
  assert.match(profileCss, /@media \(max-width:\s*700px\)[\s\S]*\.background-editors-grid\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);/s);
  assert.match(profileCss, /\.email-code-row\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\) auto;/s);
  const ids = Array.from(profileHtml.matchAll(/\sid="([^"]+)"/g), (match) => match[1]);
  assert.equal(new Set(ids).size, ids.length, "资料页不能出现重复 id");
});

test("新增前端脚本语法有效", () => {
  for (const [name, source] of [
    ["profile.js", profileJs],
    ["user-theme.js", userThemeJs],
    ["auth-email.js", authEmailJs],
    ["login.js", loginJs],
    ...authScripts
  ]) {
    assert.doesNotThrow(() => new Function(source), name);
  }
});
