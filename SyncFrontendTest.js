"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = __dirname;
const html = fs.readFileSync(path.join(root, "web", "sync.html"), "utf8");
const maimaiHtml = fs.readFileSync(path.join(root, "web", "index.html"), "utf8");
const chunithmHtml = fs.readFileSync(path.join(root, "web", "chunithm.html"), "utf8");
const css = fs.readFileSync(path.join(root, "web", "sync.css"), "utf8");
const js = fs.readFileSync(path.join(root, "web", "sync.js"), "utf8");
const frontend = `${html}\n${css}\n${js}`;
const tests = [];

function test(name, callback) {
  tests.push({ name, callback });
}

function countMatches(source, expression) {
  return Array.from(source.matchAll(expression)).length;
}

test("独立页面只加载本地样式与外部脚本", () => {
  assert.match(html, /<link\s+rel="stylesheet"\s+href="sync\.css">/);
  assert.match(html, /<script\s+src="sync\.js"\s+defer><\/script>/);
  assert.doesNotMatch(html, /<script(?![^>]*\bsrc=)[^>]*>/i, "不应存在内联脚本");
  assert.doesNotMatch(html, /(?:src|href)="https?:\/\//i, "不应加载外部页面资源");
});

test("三页共用三项顶栏且同步页只高亮同步入口", () => {
  const maimaiHeader = maimaiHtml.match(/<header class="topbar">[\s\S]*?<\/header>/)?.[0] || "";
  const chunithmHeader = chunithmHtml.match(/<header class="topbar">[\s\S]*?<\/header>/)?.[0] || "";
  const syncHeader = html.match(/<header class="topbar">[\s\S]*?<\/header>/)?.[0] || "";
  for (const header of [maimaiHeader, chunithmHeader, syncHeader]) {
    assert.match(header, /<nav class="game-switcher" aria-label="游戏切换">/);
    assert.equal(countMatches(header, /<a(?:\s[^>]*)?href="\/(?:"|chunithm\.html"|sync\.html")/g), 3);
    assert.match(header, />舞萌 DX<\/a>/);
    assert.match(header, />中二节奏<\/a>/);
    assert.match(header, />同步游戏数据<\/a>/);
    assert.doesNotMatch(header, /<div class="header-actions">\s*<a[^>]*href="\/sync\.html"/s);
  }
  assert.match(maimaiHeader, /<a class="is-active" href="\/" aria-current="page">舞萌 DX<\/a>/);
  assert.match(chunithmHeader, /<a class="is-active" href="\/chunithm\.html" aria-current="page">中二节奏<\/a>/);
  assert.match(syncHeader, /<a class="is-active" href="\/sync\.html" aria-current="page">同步游戏数据<\/a>/);
  assert.equal(countMatches(syncHeader, /class="is-active"/g), 1);
});

test("同步页品牌、账户区和顶栏尺寸与主站一致", () => {
  assert.match(
    html,
    /<img class="brand-mark" src="\/assets\/maimai-mark\.png" width="40" height="40"\s+alt="" aria-hidden="true">/
  );
  assert.match(html, /class="header-actions"[\s\S]*class="account-control"[\s\S]*class="button button-account"/);
  assert.match(html, /class="account-avatar"[^>]*>游<\/span>[\s\S]*id="account-label"/);
  assert.match(css, /\.topbar\s*\{[^}]*min-height:\s*72px;[^}]*display:\s*grid;[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)\s+auto\s+minmax\(0,\s*1fr\);[^}]*padding:\s*12px max\(24px, calc\(\(100vw - 1280px\) \/ 2\)\);/s);
  assert.match(css, /\.brand-mark\s*\{[^}]*width:\s*40px;[^}]*height:\s*40px;[^}]*object-fit:\s*contain;/s);
  assert.match(css, /@media \(max-width: 760px\)[\s\S]*?\.topbar\s*\{[^}]*display:\s*flex;[^}]*flex-wrap:\s*wrap;[^}]*padding:\s*12px 16px;/s);
  assert.doesNotMatch(frontend, /\.topnav|class="topnav"/);
});

test("正式代理同步是默认入口且只保留两个页签", () => {
  assert.match(html, /class="tab is-active" id="proxy-tab"[^>]*aria-selected="true"[^>]*aria-controls="proxy-panel"/s);
  assert.match(html, /id="upload-tab"[^>]*role="tab"[^>]*aria-controls="upload-panel"/s);
  assert.doesNotMatch(html, /id="proxy-panel"[^>]*\bhidden\b/);
  assert.match(html, /id="upload-panel"[^>]*\bhidden\b/);
  assert.equal(countMatches(html, /class="tab(?:\s|\")/g), 2);
  assert.ok(js.includes("const tabs = [elements.proxyTab, elements.uploadTab]"));
  assert.ok(js.includes('requestedTab === "upload" ? elements.uploadTab : elements.proxyTab'));
  assert.ok(js.includes('activeTab === elements.uploadTab ? "upload" : "proxy"'));
});

test("诊断抓取、临时证书与旧配置已从前端彻底移除", () => {
  assert.doesNotMatch(frontend, /capture|诊断|mitm(?:proxy|\.it)|\bCA\b|PROCESS-NAME|DOMAIN-SUFFIX/i);
  assert.ok(html.includes(".\\run-wechat-helper.ps1"));
  assert.doesNotMatch(html, /run-wechat-helper\.ps1\s+-/i);
  assert.ok(html.includes("不解密 HTTPS"));
});

test("HTML id 唯一且异步状态具备可访问语义", () => {
  const ids = Array.from(html.matchAll(/\sid="([^"]+)"/g), (match) => match[1]);
  assert.equal(new Set(ids).size, ids.length, "HTML 中不能出现重复 id");
  assert.match(html, /id="proxy-panel"[^>]*role="tabpanel"[^>]*aria-labelledby="proxy-tab"/);
  assert.match(html, /id="upload-panel"[^>]*role="tabpanel"[^>]*aria-labelledby="upload-tab"/);
  assert.ok(countMatches(html, /role="status"/g) >= 3);
});

test("Helper 地址由用户手动填写并只接受 127.0.0.1 或私网 IPv4", () => {
  assert.doesNotMatch(frontend, /name="proxy-device"|proxyMode|mobileProxyHost|device-options|device-option/);
  assert.doesNotMatch(html, /电脑版微信|手机微信|电脑版微信与 Helper/);
  assert.match(html, /id="proxy-host"(?![^>]*\bvalue=)[^>]*placeholder="例如 192\.168\.1\.12"/s);
  assert.match(html, /请手动填写 127\.0\.0\.1/);
  assert.ok(js.includes('return hostname === "127.0.0.1" || isPrivateIpv4(hostname) ? hostname : ""'));
  assert.ok(js.includes('elements.proxyHost.value = ""'));
  assert.ok(js.includes("只接受 127.0.0.1、10.x、172.16–31.x 或 192.168.x 地址"));
  assert.doesNotMatch(js, /HELPER_HOST_ENDPOINT|PROXY_HOST_STORAGE_KEY|selectAutomaticProxyHost|detectPreferredHelperHost|payload\?\.helperHost|readStoredProxyHost|persistProxyHost|localStorage/);
});

test("Clash 可一键唤起并保留 YAML 备用配置", () => {
  assert.match(js, /const PROXY_PORT = 8081;/);
  assert.match(js, /const CLASH_CONFIG_ENDPOINT = "\/api\/sync\/clash-config";/);
  assert.match(html, /id="open-clash-button"[^>]*>导入 Clash 配置<\/a>/s);
  assert.match(html, /仅“导入”配置不会自动激活/);
  assert.match(html, /不要同时运行 Fiddler、Charles/);
  assert.ok(js.includes("new URL(window.location.origin)"));
  assert.ok(js.includes('configUrl.searchParams.set("helperHost", hostname)'));
  assert.ok(js.includes("clash://install-config?url="));
  assert.ok(js.includes("导入后请点选启用"));
  assert.ok(js.includes("encodeURIComponent(configUrl)"));
  assert.match(html, /id="copy-clash-button"[^>]*>复制 YAML<\/button>/);
  assert.ok(js.includes("wahlap-wechat-local"));
  assert.ok(js.includes("AND,((DOMAIN,tgk-wcaime.wahlap.com),(DST-PORT,80)),wahlap-wechat-local"));
  assert.doesNotMatch(js, /DOMAIN-SUFFIX|wahlap\.com\)\),|DST-PORT,443/);
  assert.doesNotMatch(js, /username:|password:/i);
  assert.ok(js.includes('"  - MATCH,DIRECT"'));
});

test("标准 HTTP 代理说明复用 Helper 地址并提供分别复制", () => {
  assert.match(html, /标准 HTTP 代理（推荐，与原抓取方式一致）/);
  assert.match(html, /Wi-Fi 或移动网络的代理设置中选择“手动”或“HTTP 代理”/);
  assert.match(html, /服务器填写上方 Helper 地址，端口填写 <code>8081<\/code>/);
  assert.match(html, /id="copy-http-proxy-host-button"[^>]*data-copy-target="proxy-host"/s);
  assert.match(html, /id="copy-http-proxy-port-button"[^>]*data-copy-target="proxy-port"/s);
  assert.ok(js.includes("elements.copyHttpProxyHostButton.disabled = !state.proxyHostValid"));
  assert.ok(js.includes("elements.copyHttpProxyPortButton.disabled = !state.proxyHostValid"));
});

test("舞萌与中二均可创建正式同步会话", () => {
  assert.match(html, /name="proxy-game"\s+value="maimai"\s+checked/);
  assert.match(html, /name="proxy-game"\s+value="chunithm"(?![^>]*disabled)/);
  assert.match(
    html,
    /name="proxy-game"\s+value="maimai"[^>]*>[\s\S]*?<img class="game-option-mark" src="\/assets\/maimai-mark\.png" width="34" height="34"\s+alt="" aria-hidden="true">/
  );
  assert.match(
    html,
    /name="proxy-game"\s+value="chunithm"[^>]*>[\s\S]*?<img class="game-option-mark" src="\/assets\/chunithm-mark\.png" width="34" height="34"\s+alt="" aria-hidden="true">/
  );
  assert.match(css, /\.game-option-mark\s*\{[^}]*width:\s*34px;[^}]*height:\s*34px;[^}]*object-fit:\s*contain;/s);
  assert.doesNotMatch(frontend, /game-option-mark-chuni|class="game-option-mark"[^>]*>\s*[MC]\s*</);
  assert.equal(countMatches(html, /support-badge is-supported/g), 2);
  assert.ok(js.includes('const game = selectedRadioValue("proxy-game") || "maimai"'));
  assert.ok(js.includes("body: JSON.stringify({ game })"));
  assert.ok(js.includes("state.sessionGame = responseGame || game"));
  assert.ok(js.includes("responseGame !== state.sessionGame"));
});

test("创建同步会话前必须验证用户手填的 Helper", () => {
  assert.match(html, /通过后才签发本次授权会话/);
  assert.match(js, /const HELPER_HEALTH_TIMEOUT_MS = 4000;/);
  assert.ok(js.includes('const healthUrl = `http://${urlHost}:${PROXY_PORT}/health`'));
  assert.ok(js.includes('mode: "cors"'));
  assert.ok(js.includes('credentials: "omit"'));
  assert.ok(js.includes('redirect: "error"'));
  assert.ok(js.includes('payload?.service !== "wahlap-wechat-helper"'));
  assert.ok(js.includes("elements.proxyHost.disabled = state.sessionBusy"));

  const createStart = js.indexOf("  async function createSession()");
  const createEnd = js.indexOf("  async function probeHelper(value)", createStart);
  const createSource = js.slice(createStart, createEnd);
  assert.ok(createSource.includes("await probeHelper(proxyHost)"));
  assert.ok(
    createSource.indexOf("await probeHelper(proxyHost)")
      < createSource.indexOf("requestJson(SYNC_SESSIONS_ENDPOINT")
  );
});

test("会话轮询与完成结果保持创建时的游戏归属", () => {
  assert.ok(js.includes('const SYNC_SESSIONS_ENDPOINT = "/api/sync/sessions"'));
  assert.match(js, /encodeURIComponent\(state\.sessionId\)/);
  assert.ok(js.includes("const completedGame = state.sessionGame || \"maimai\""));
  assert.ok(js.includes('loadHistoryCount(completedGame, elements.proxyHistoryCount, "proxy")'));
  assert.ok(js.includes('return game === "chunithm" ? "/chunithm.html" : "/"'));
  assert.match(js, /window\.setTimeout\(\(\) => void pollSession\(sequence\), POLL_INTERVAL_MS\)/);
  assert.ok(
    js.indexOf("state.sessionRequestSequence += 1") < js.indexOf("resetSessionPresentation()"),
    "创建新会话前必须先使旧轮询序号失效"
  );
  assert.ok(js.includes("[400, 404, 409].includes(error?.status)"));
  assert.ok(js.includes('window.clearInterval(state.sessionCountdownTimer)'));
  assert.ok(js.includes('"同步已结束"'));
});

test("舞萌与中二详情抓取显示真实进度并保留失败现场", () => {
  assert.match(html, /id="sync-progress"[^>]*role="progressbar"[^>]*aria-valuemin="0"[^>]*aria-valuemax="100"/s);
  assert.match(html, /id="sync-progress-count">已处理 0 \/ 0 · 详情成功 0 · 跳过 0/);
  assert.match(html, /id="sync-progress-reasons" hidden/);
  assert.ok(js.includes('normalizeStatus(value.stage) !== "play_details"'));
  assert.ok(js.includes("value.completed"));
  assert.ok(js.includes("value.total"));
  assert.ok(js.includes("value.succeeded"));
  assert.ok(js.includes("value.skipped"));
  assert.ok(js.includes("value.failureReasons"));
  assert.ok(js.includes("DETAIL_FAILURE_LABELS"));
  assert.ok(js.includes('"invalid-detail-template": "官网返回的不是详情页"'));
  assert.ok(js.includes("normalizeGameName(value.game) || state.sessionGame"));
  assert.ok(js.includes("detailProgress?.game || state.sessionGame"));
  assert.ok(js.includes("state.lastDetailProgress = reportedProgress"));
  assert.ok(js.includes("isTerminalStatus(normalized) ? state.lastDetailProgress : null"));
  assert.match(
    js,
    /暂时无法获取状态：[\s\S]*?state\.lastRenderedProgress,[\s\S]*?state\.lastDetailProgress/
  );
  assert.ok(js.includes('elements.syncProgress.removeAttribute("aria-valuenow")'));
  assert.ok(js.includes('elements.syncProgress.setAttribute("aria-valuenow", String(percent))'));
  assert.ok(js.includes('elements.syncProgressCount.textContent = `${countText}${successText}${skippedText}`'));
  assert.ok(js.includes('elements.syncProgressReasons.textContent = reasonText ? `跳过原因：${reasonText}` : ""'));
  assert.ok(js.includes('elements.syncProgressPercent.textContent = percentText'));
  assert.match(css, /\.sync-progress\.is-indeterminate span\s*\{[^}]*animation:/s);
  assert.match(css, /@keyframes sync-progress-indeterminate/);
});

test("Helper 启动链接只接受后端返回的相对 start 路径", () => {
  assert.ok(js.includes("payload?.session"));
  assert.ok(js.includes("payload?.helperPath"));
  assert.ok(js.includes('url.pathname !== "/start"'));
  assert.match(js, /http:\/\/\$\{urlHost}:\$\{PROXY_PORT}\$\{helperPath\}/);
  assert.doesNotMatch(js, /payload\?\.helperPort|session\?\.helperPort/);
  assert.match(js, /const SESSION_TTL_MS = 15 \* 60 \* 1000;/);
  assert.ok(js.includes("后端没有返回辅助程序令牌"));
  assert.ok(js.includes("后端没有返回有效的 Helper /start 路径"));
});

test("登录门禁仍保护同步和导入操作", () => {
  assert.ok(js.includes('const AUTH_STATUS_ENDPOINT = "/api/auth/status"'));
  assert.ok(js.includes('credentials: options?.credentials || "same-origin"'));
  assert.ok(js.includes("state.authReady && state.authenticated"));
  assert.match(html, /id="login-link" href="\/login\.html"/);
  assert.match(js, /window\.location\.replace\("\/login\.html"\)/);
  assert.doesNotMatch(html, /id="auth-dialog"|id="auth-form"/);
});

test("舞萌与中二都可上传 JSON，最多五份", () => {
  assert.match(html, /accept="\.json,application\/json"\s+multiple/);
  assert.match(html, /name="upload-game"\s+value="maimai"\s+checked/);
  assert.match(html, /name="upload-game"\s+value="chunithm"(?![^>]*disabled)/);
  assert.ok(html.includes("粘贴 JSON 文本"));
  assert.ok(html.includes("这里不会解析公众号 HTML"));
  assert.match(js, /const MAX_UPLOAD_ITEMS = 5;/);
  assert.ok(js.includes('addEventListener("drop"'));
  assert.ok(js.includes("JSON.parse(text)"));
});

test("JSON 导入请求包含游戏、记录和可选谱面", () => {
  assert.ok(js.includes('const SYNC_IMPORT_ENDPOINT = "/api/sync/import"'));
  assert.ok(js.includes("const requestBody = { game, records }"));
  assert.ok(js.includes("if (charts.length) requestBody.charts = charts"));
  assert.ok(js.includes('headers: { "Content-Type": "application/json" }'));
  assert.ok(js.includes("elements.uploadResultLink.href = gamePage(game)"));
  assert.ok(js.includes("payload?.recordsAdded"));
  assert.ok(js.includes("payload?.recordsEnriched"));
  assert.ok(js.includes("payload?.recordsIgnored"));
  assert.ok(js.includes("if (enriched !== null && enriched > 0)"));
  assert.ok(js.includes("补全判定详情 ${enriched} 条"));
  assert.ok(js.includes("const completionCounts = isSuccessStatus(normalized)"));
  assert.ok(js.includes("同步完成：${completionCounts}。"));
});

test("动态内容使用安全 DOM API且样式覆盖键盘与移动端", () => {
  assert.doesNotMatch(js, /\.innerHTML\s*=/);
  assert.ok(js.includes("textContent"));
  assert.ok(js.includes("replaceChildren"));
  assert.match(css, /:focus-visible/);
  assert.match(css, /@media \(max-width: 560px\)/);
  assert.match(css, /grid-template-columns: repeat\(2, 1fr\)/);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
});

test("前端脚本语法有效", () => {
  assert.doesNotThrow(() => new Function(js));
});

let passed = 0;
for (const { name, callback } of tests) {
  try {
    callback();
    passed += 1;
    process.stdout.write(`PASS ${name}\n`);
  } catch (error) {
    process.stderr.write(`FAIL ${name}\n${error.stack}\n`);
    process.exitCode = 1;
  }
}

process.stdout.write(`\n${passed}/${tests.length} 项 Sync 前端测试通过\n`);
