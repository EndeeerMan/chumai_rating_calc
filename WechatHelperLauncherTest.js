"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const script = fs.readFileSync(path.join(__dirname, "run-wechat-helper.ps1"), "utf8");

assert.match(
  script,
  /function Get-ConflictingInterceptProxyProcesses\s*\{[\s\S]*?Get-Process -Id \$PID[\s\S]*?\.SessionId[\s\S]*?Get-Process -ErrorAction SilentlyContinue[\s\S]*?\$_\.SessionId -eq \$currentSessionId/s,
  "应只检查当前用户会话中的进程"
);

for (const processName of [
  "Fiddler",
  "Fiddler Everywhere",
  "FiddlerEverywhere",
  "Fiddler.WebUi",
  "Charles",
  "Charles4",
  "Charles64",
  "CharlesProxy",
]) {
  assert.ok(script.includes(`"${processName}"`), `缺少冲突代理进程：${processName}`);
}

const knownNamesBlock = script.match(/\$knownInterceptProxyProcessNames\s*=\s*@\([\s\S]*?\n\s*\)/)?.[0] || "";
assert.doesNotMatch(knownNamesBlock, /Clash/i, "Clash 是受支持的代理，不应被拦截");

assert.match(
  script,
  /if \(\$DryRun\)\s*\{\s*Write-Warning[\s\S]*?\}\s*else\s*\{\s*throw \$proxyConflictMessage\s*\}/s,
  "DryRun 应只警告，正式运行应中止"
);
assert.match(script, /抢走舞萌 DX \/ 中二节奏的微信公众号 OAuth 回调/);
assert.match(script, /504 Gateway Timeout/);
assert.match(script, /彻底退出这些程序（包括任务栏托盘中的后台进程）/);
assert.match(script, /Clash 是本项目支持的代理，不需要退出/);

const conflictCheckIndex = script.indexOf("$conflictingProxyProcesses = @(");
const helperLaunchIndex = script.indexOf("Push-Location $helperRoot");
assert.ok(conflictCheckIndex >= 0 && helperLaunchIndex > conflictCheckIndex, "冲突检测必须发生在 Helper 启动前");

process.stdout.write("PASS Helper 启动器代理冲突保护\n");
