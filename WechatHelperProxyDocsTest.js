"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const read = (relativePath) => fs.readFileSync(path.join(__dirname, relativePath), "utf8");
const readme = read(path.join("wechat-helper", "README.md"));
const manual = read("说明文件.txt");
const clashExample = read(path.join("wechat-helper", "clash-example.yaml"));
const firewallScript = read("setup-lan-access.ps1");

for (const [name, document] of [
  ["Helper README", readme],
  ["项目说明", manual],
]) {
  assert.match(
    document,
    /目标端口\s*不是 80 的 CONNECT[\s\S]*?原样透传/,
    `${name} 必须说明非 80 CONNECT 只作透明隧道`
  );
  assert.match(
    document,
    /Wahlap\s*`?:80`?[\s\S]*?CONNECT[\s\S]*?受审计 addon/i,
    `${name} 必须说明 Clash 的 Wahlap CONNECT:80 会进入受审计 addon`
  );
  assert.match(
    document,
    /(?:HTTPS\s*)?443[\s\S]{0,100}非 80[\s\S]{0,120}不由[\s\S]{0,40}Clash[\s\S]{0,40}规则接管[\s\S]{0,80}不送入 Helper[\s\S]{0,100}自代理循环/i,
    `${name} 必须说明 Clash 不接管 443/非 80 流量以防 Helper 自代理循环`
  );
  assert.match(document, /(?:二选一|只能选一种)/, `${name} 必须要求 Clash 与手动 HTTP 代理二选一`);
  assert.match(document, /不需要[\s\S]{0,80}(?:SOCKS5\s*`?8082`?|第二个监听端口)/i, `${name} 必须明确无需 8082`);
}

assert.match(clashExample, /^\s*type:\s*http\s*$/m, "Clash 出站必须保持 HTTP 类型");
assert.match(clashExample, /^\s*port:\s*8081\s*$/m, "Clash 出站必须使用 Helper 8081");
assert.match(
  clashExample,
  /DOMAIN,tgk-wcaime\.wahlap\.com\)\s*,\s*\(DST-PORT,80\)/,
  "Clash 只能把 Wahlap 的 80 端口回调送到 Helper"
);
assert.match(clashExample, /^\s*-\s*AND,/m, "Clash 必须用 DOMAIN 与 DST-PORT 的 AND 规则");
assert.doesNotMatch(clashExample, /(?:socks5|8082)/i, "Clash 示例不得引入未监听的 SOCKS5 8082");

assert.match(firewallScript, /\$effectivePort\s*=\s*8081\b/, "Helper 防火墙规则必须继续使用 8081");
assert.match(firewallScript, /Profile\s*=\s*"Private"/, "Helper 防火墙规则必须限于 Private");
assert.match(firewallScript, /RemoteAddress\s*=\s*"LocalSubnet"/, "Helper 防火墙规则必须限于 LocalSubnet");
assert.doesNotMatch(firewallScript, /\b8082\b/, "单端口方案不得扩大防火墙到 8082");

process.stdout.write("PASS Helper 单端口代理文档契约\n");
