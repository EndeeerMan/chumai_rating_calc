"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = __dirname;
const html = fs.readFileSync(path.join(root, "web", "index.html"), "utf8");
const css = fs.readFileSync(path.join(root, "web", "styles.css"), "utf8");
const scoreCss = fs.readFileSync(path.join(root, "web", "lxns-score.css"), "utf8");
const js = fs.readFileSync(path.join(root, "web", "app.js"), "utf8");
const chunithmHtml = fs.readFileSync(path.join(root, "web", "chunithm.html"), "utf8");
const syncHtml = fs.readFileSync(path.join(root, "web", "sync.html"), "utf8");
const syncCss = fs.readFileSync(path.join(root, "web", "sync.css"), "utf8");
const chunithmJs = fs.readFileSync(path.join(root, "web", "chunithm.js"), "utf8");
const judgmentHelper = fs.readFileSync(
  path.join(root, "web", "judgment-details.js"),
  "utf8"
);

const tests = [];
function test(name, callback) {
  tests.push({ name, callback });
}

function fakeDocument() {
  class FakeElement {
    constructor(tagName) {
      this.tagName = tagName;
      this.className = "";
      this.textContent = "";
      this.children = [];
      this.dataset = {};
      this.attributes = {};
      this.classList = { add: (...names) => {
        this.className = [this.className, ...names].filter(Boolean).join(" ");
      } };
    }

    append(...children) {
      this.children.push(...children);
    }

    setAttribute(name, value) {
      this.attributes[name] = String(value);
    }

    addEventListener() {}

    remove() {
      this.removed = true;
    }
  }
  return { createElement: (tagName) => new FakeElement(tagName) };
}

function elementTree(root) {
  return [root, ...root.children.flatMap(elementTree)];
}

function sourceBetween(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assert.notEqual(start, -1, `找不到源码起点：${startMarker}`);
  assert.notEqual(end, -1, `找不到源码终点：${endMarker}`);
  return source.slice(start, end);
}

test("舞萌页提供分数构成、成绩列表与游玩记录页签", () => {
  assert.match(html, /id="composition-view-tab"[^>]*role="tab"/);
  assert.match(html, /id="score-list-view-tab"[^>]*role="tab"/);
  assert.match(html, /id="composition-view-panel"/);
  assert.match(html, /id="score-list-view-panel"[^>]*hidden/);
  assert.match(html, /id="play-history-view-tab"[^>]*role="tab"/);
  assert.match(html, /id="play-history-view-panel"[^>]*hidden/);
  assert.match(html, /id="play-history-overview-list"/);
  assert.match(html, /id="score-list-search-input"/);
  assert.match(html, /id="score-list-version-filter"/);
  assert.match(html, /id="score-list-difficulty-filter"/);
  assert.match(html, /id="score-list-chart-type-filter"/);
  assert.match(html, /id="score-list-sort"/);
  assert.match(html, /id="score-list-pagination"/);
});

test("舞萌三项与中二两项成绩视图切换器保持横向排列", () => {
  const maimaiControl = html.match(
    /<div class="segmented-control score-view-control"[\s\S]*?<\/div>/
  )?.[0] || "";
  const chunithmControl = chunithmHtml.match(
    /<div class="segmented-control score-view-control"[\s\S]*?<\/div>/
  )?.[0] || "";

  assert.equal(Array.from(maimaiControl.matchAll(/<button\b/g)).length, 3);
  assert.equal(Array.from(chunithmControl.matchAll(/<button\b/g)).length, 2);
  assert.match(
    css,
    /\.score-view-control\s*\{[^}]*display:\s*inline-flex;[^}]*flex-wrap:\s*nowrap;/s
  );
  assert.match(
    css,
    /\.score-view-control button\s*\{[^}]*flex:\s*1\s+1\s+104px;[^}]*min-width:\s*104px;/s
  );
  assert.match(
    css,
    /@media \(max-width:\s*760px\)[\s\S]*?\.score-view-control button\s*\{[^}]*min-width:\s*0;/
  );
});

test("两游戏品牌使用本地图标且不再显示字母占位", () => {
  assert.match(
    html,
    /<img class="brand-mark" src="\/assets\/maimai-mark\.png" width="40" height="40"\s+alt="" aria-hidden="true">/
  );
  assert.match(
    chunithmHtml,
    /<img class="brand-mark" src="\/assets\/chunithm-mark\.png" width="40" height="40"\s+alt="" aria-hidden="true">/
  );
  assert.doesNotMatch(html, /class="brand-mark"[^>]*>\s*M\s*</);
  assert.doesNotMatch(chunithmHtml, /class="brand-mark[^>]*>\s*C\s*</);
  assert.match(css, /\.brand-mark\s*\{[^}]*object-fit:\s*contain;[^}]*border-radius:\s*50%;/s);
  assert.ok(fs.statSync(path.join(root, "web", "assets", "maimai-mark.png")).size > 0);
  assert.ok(fs.statSync(path.join(root, "web", "assets", "chunithm-mark.png")).size > 0);
  assert.match(html, /<link rel="icon" href="\/assets\/maimai-mark\.png" type="image\/png">/);
  assert.match(syncHtml, /<link rel="icon" href="\/assets\/maimai-mark\.png" type="image\/png">/);
  assert.match(chunithmHtml, /<link rel="icon" href="\/assets\/chunithm-mark\.png" type="image\/png">/);
  assert.equal(fs.existsSync(path.join(root, "web", "favicon.svg")), false);
});

test("三页顶栏使用相同三项切换器且各自只高亮当前页", () => {
  const cases = [
    [html, /<a class="is-active" href="\/" aria-current="page">舞萌 DX<\/a>/],
    [chunithmHtml, /<a class="is-active" href="\/chunithm\.html" aria-current="page">中二节奏<\/a>/],
    [syncHtml, /<a class="is-active" href="\/sync\.html" aria-current="page">同步游戏数据<\/a>/]
  ];
  for (const [page, activeLink] of cases) {
    const header = page.match(/<header class="topbar">[\s\S]*?<\/header>/)?.[0] || "";
    const switcher = header.match(/<nav class="game-switcher"[\s\S]*?<\/nav>/)?.[0] || "";
    assert.match(switcher, />舞萌 DX<\/a>/);
    assert.match(switcher, />中二节奏<\/a>/);
    assert.match(switcher, />同步游戏数据<\/a>/);
    assert.equal(Array.from(switcher.matchAll(/<a\b/g)).length, 3);
    assert.equal(Array.from(switcher.matchAll(/class="is-active"/g)).length, 1);
    assert.match(switcher, activeLink);
    assert.doesNotMatch(header, /<div class="header-actions">\s*<a[^>]*href="\/sync\.html"/s);
  }
});

test("桌面顶栏使用对称三列固定导航位置并在窄屏统一换行", () => {
  const desktopGrid = /\.topbar\s*\{[^}]*display:\s*grid;[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)\s+auto\s+minmax\(0,\s*1fr\);/s;
  assert.match(css, desktopGrid);
  assert.match(syncCss, desktopGrid);
  assert.match(
    css,
    /@media \(max-width:\s*760px\)[\s\S]*?\.topbar\s*\{[^}]*display:\s*flex;/
  );
  assert.match(
    syncCss,
    /@media \(max-width:\s*760px\)[\s\S]*?\.topbar\s*\{[^}]*display:\s*flex;/
  );
});

test("舞萌与中二都从独立游玩记录列表打开逐局详情", () => {
  assert.match(js, /function loadPlayHistoryOverview\(/);
  assert.match(js, /function createPlayHistoryOverviewItem\(/);
  assert.match(js, /function openPlayRecordDetail\(/);
  assert.match(js, /game: "maimai", limit: "500"/);
  assert.match(chunithmHtml, /id="play-history-view-tab"/);
  assert.match(chunithmHtml, /id="play-history-overview-list"/);
  assert.match(chunithmJs, /function loadPlayHistoryOverview\(/);
  assert.match(chunithmJs, /function openPlayRecordDetail\(/);
  assert.match(chunithmJs, /game: "chunithm", limit: "500"/);
  assert.match(js, /playDetails: normalizePlayDetails\(value\.playDetails\)/);
  assert.match(js, /"fast", "late", "maxCombo", "maxSync", "dxScore", "rating", "partners"/);
  assert.match(js, /referrerPolicy = "no-referrer"/);
});

test("两游戏前端彻底移除图片识别入口与调用", () => {
  const frontend = [html, chunithmHtml, js, chunithmJs, css, scoreCss].join("\n");
  assert.doesNotMatch(frontend, /id="ai-button"|id="ai-dialog"|ai-batch\.js|\/api\/ai|AI 识图|识图导入/i);
  assert.equal(fs.existsSync(path.join(root, "web", "ai-batch.js")), false);
});

test("成绩列表支持筛选、排序、分页并复用成绩卡详情入口", () => {
  assert.match(js, /function filteredScoreListCharts\(/);
  assert.match(js, /function renderScoreList\(/);
  assert.match(js, /function setScoreView\(/);
  assert.match(js, /scoreList\.pageSize/);
  assert.match(js, /scoreListMatches\(chart, query\)/);
  assert.match(js, /data-score-detail-key/);
  assert.doesNotMatch(js, /if \(!chart \|\| !chart\.selected\) return;/,
    "未入选成绩也必须能打开详情");
});

test("卡片与详情显式显示规范 SongID", () => {
  assert.match(js, /SongID \$\{chart\.songId\}/);
  assert.match(js, /poolName[\s\S]*poolRank[\s\S]*SongID \$\{chart\.songId\}/);
  assert.match(html, /SongID/);
});

test("曲库谱面详情与逐局玩家判定严格分区", () => {
  assert.match(js, /function createCatalogChartDetails\(/);
  assert.match(js, /STANDARD 谱面/);
  assert.match(js, /DX 谱面/);
  assert.match(js, /\["难度 \/ 定数", "TOTAL", "TAP", "HOLD", "SLIDE", "TOUCH", "BREAK", "谱师"\]/);
  assert.match(js, /\["SSS\+", 100\.5\]/);
  assert.match(chunithmJs, /function createCatalogChartDetails\(/);
  assert.match(chunithmJs, /\["难度 \/ 定数", "TOTAL", "TAP", "HOLD", "SLIDE", "AIR", "FLICK", "谱师"\]/);
  assert.match(js, /JUDGMENT_DETAILS\.normalizeRecord\(value, "maimai"\)/);
  assert.match(js, /JUDGMENT_DETAILS\.createDetails\(document, record\.judgmentDetails\)/);
  assert.match(chunithmJs, /JUDGMENT_DETAILS\.normalizeRecord\(value, "chunithm"\)/);
  assert.match(chunithmJs, /JUDGMENT_DETAILS\.createDetails\(document, record\.judgmentDetails\)/);
  assert.match(judgmentHelper, /本局尚未抓取到官方游玩判定详情/);
  assert.match(judgmentHelper, /本局各音符类型判定/);
  assert.match(judgmentHelper, /各音符类型达成率/);
  assert.match(judgmentHelper, /MAX COMBO/);
  assert.match(scoreCss, /\.score-judgment-details/);
  assert.match(scoreCss, /\.score-judgment-table/);
  assert.match(scoreCss, /\.catalog-chart-table/);
  assert.match(html, /id="score-detail-content"/);
  assert.match(html, /src="judgment-details\.js"/);
  assert.match(chunithmHtml, /src="judgment-details\.js"/);
  assert.match(chunithmHtml, /id="score-detail-history"/);

  const maimaiPlayDetail = sourceBetween(
    js,
    "function renderPlayRecordDetail(record)",
    "function openPlayRecordDetail(record, trigger)"
  );
  const chunithmPlayDetail = sourceBetween(
    chunithmJs,
    "function renderPlayRecordDetail(record)",
    "function openPlayRecordDetail(record, trigger)"
  );
  [maimaiPlayDetail, chunithmPlayDetail].forEach((source) => {
    assert.match(source, /JUDGMENT_DETAILS\.createDetails/);
    assert.doesNotMatch(
      source,
      /createCatalogChartDetails/,
      "逐局游玩详情不能混入曲库原谱面物量表"
    );
  });
});

test("判定适配器遵守舞萌矩阵与中二官网全局判定契约", () => {
  const context = {};
  vm.runInNewContext(judgmentHelper, context);
  const adapter = context.RhythmJudgmentDetails;
  const maimai = adapter.normalizeRecord({
    judgmentDetails: {
      byNoteType: {
        tap: { criticalPerfect: 300, perfect: 120, great: 3, good: 1, miss: 0 },
        break: { criticalPerfect: 6, perfect: 0, great: 0, good: 0, miss: 0 }
      }
    }
  }, "maimai");
  assert.equal(maimai.kind, "by-note-type");
  assert.deepEqual(
    JSON.parse(JSON.stringify(maimai.byNoteType.map((group) => [
      group.label,
      group.judgments.map((judgment) => [judgment.label, judgment.count])
    ]))),
    [
      ["TAP", [
        ["CRITICAL PERFECT", 300], ["PERFECT", 120], ["GREAT", 3],
        ["GOOD", 1], ["MISS", 0]
      ]],
      ["BREAK", [
        ["CRITICAL PERFECT", 6], ["PERFECT", 0], ["GREAT", 0],
        ["GOOD", 0], ["MISS", 0]
      ]]
    ]
  );

  const legacyMaimai = adapter.normalizeRecord({
    judgments: { hold: { perfect: 20, good: 1 } }
  }, "maimai");
  assert.equal(legacyMaimai.kind, "empty");

  const chunithm = adapter.normalizeRecord({
    judgmentDetails: {
      judgments: { justiceCritical: 88, justice: 12, attack: 2, miss: 0 },
      noteAchievements: {
        tap: 97.05,
        hold: 100.98,
        slide: 100.35,
        air: 99.52,
        flick: 96.93
      },
      maxCombo: 114
    }
  }, "chunithm");
  assert.equal(chunithm.kind, "chunithm-summary");
  assert.deepEqual(JSON.parse(JSON.stringify(chunithm.byNoteType)), []);
  assert.deepEqual(
    JSON.parse(JSON.stringify(chunithm.judgments.map((item) => [item.label, item.count]))),
    [
      ["JUSTICE CRITICAL", 88],
      ["JUSTICE", 12],
      ["ATTACK", 2],
      ["MISS", 0]
    ]
  );
  assert.deepEqual(
    JSON.parse(JSON.stringify(
      chunithm.noteAchievements.map((item) => [item.label, item.achievement])
    )),
    [
      ["TAP", 97.05], ["HOLD", 100.98], ["SLIDE", 100.35],
      ["AIR", 99.52], ["FLICK", 96.93]
    ]
  );
  assert.equal(chunithm.maxCombo, 114);

  const ambiguous = adapter.normalizeRecord({
      judgmentDetails: {
        judgments: { critical: 100 },
      noteAchievements: { tap: 100 },
        maxCombo: 100
      }
  }, "chunithm");
  assert.equal(ambiguous.kind, "empty");
  assert.deepEqual(
    JSON.parse(JSON.stringify(adapter.normalizeRecord({
      judgmentDetails: {
        byNoteType: { air: { justiceCritical: 100 } }
      }
    }, "chunithm"))),
    {
      kind: "empty",
      byNoteType: [],
      judgments: [],
      noteCounts: [],
      noteAchievements: [],
      maxCombo: null
    }
  );
  assert.equal(
    adapter.normalizeRecord({ noteCounts: { tap: 999 } }, "chunithm").kind,
    "empty",
    "记录顶层曲库 noteCounts 不能冒充官网本局详情"
  );
  assert.equal(
    adapter.normalizeRecord({
      judgmentDetails: {
        byNoteType: {
          tap: {
            criticalPerfect: 1,
            perfect: "2",
            great: 0,
            good: 0,
            miss: 0
          }
        }
      }
    }, "maimai").kind,
    "empty",
    "后端契约中的计数必须是整数而不是字符串"
  );
  assert.equal(
    adapter.normalizeRecord({
      judgmentDetails: {
        judgments: { justiceCritical: 1, justice: 0, attack: 0, miss: 0 },
        noteAchievements: { tap: 1, hold: 0, slide: 0, air: 0, flick: 0 },
        maxCombo: 1,
        byNoteType: {}
      }
    }, "chunithm").kind,
    "empty",
    "中二详情拒绝额外的伪矩阵字段"
  );
  assert.equal(
    adapter.normalizeRecord({
      judgmentDetails: {
        judgments: { justiceCritical: 1, justice: 0, attack: 0, miss: 0 },
        noteAchievements: { tap: 97.051, hold: 100, slide: 100, air: 100, flick: 100 },
        maxCombo: 1
      }
    }, "chunithm").kind,
    "empty",
    "中二音符类型达成率最多保留两位小数"
  );

  const document = fakeDocument();
  const renderedMaimai = elementTree(adapter.createDetails(document, maimai));
  assert.equal(
    renderedMaimai.filter((element) =>
      element.className === "score-judgment-table score-judgment-table--maimai"
    ).length,
    1,
    "舞萌使用一张正式二维判定表"
  );
  const maimaiText = renderedMaimai.map((element) => element.textContent).join(" ");
  assert.match(maimaiText, /TAP/);
  assert.match(maimaiText, /CRITICAL PERFECT/);
  assert.match(maimaiText, /BREAK/);

  const renderedChunithm = elementTree(adapter.createDetails(document, chunithm));
  assert.equal(
    renderedChunithm.filter((element) =>
      element.className.includes("score-judgment-summary-section")
    ).length,
    3,
    "中二分开渲染判定、各音符类型达成率与 MAX COMBO"
  );
  const chunithmText = renderedChunithm.map((element) => element.textContent).join(" ");
  assert.match(chunithmText, /JUSTICE CRITICAL/);
  assert.match(chunithmText, /各音符类型达成率/);
  assert.match(chunithmText, /97\.05%/);
  assert.match(chunithmText, /100\.98%/);
  assert.match(chunithmText, /FLICK/);
  assert.match(chunithmText, /MAX COMBO/);
});

test("舞萌逐局详情显示 DX Rating 变化、总值与官方颜色框", () => {
  const context = {
    URL,
    window: { location: { origin: "http://localhost:8080" } },
    document: fakeDocument()
  };
  const detailFunctions = [
    "const DX_RATING_FRAMES = new Set([\"white\", \"blue\", \"green\", \"yellow\", \"red\", \"purple\", \"bronze\", \"silver\", \"gold\", \"platinum\", \"rainbow\"]);",
    "const COVER_PLACEHOLDER_URL = \"/song-catalog/cover-placeholder.svg\";",
    sourceBetween(js, "function normalizeCoverUrl(value)", "function hasAtMostDecimals(value"),
    sourceBetween(js, "function createTextElement(tagName", "function achievementRankKey(achievement"),
    sourceBetween(js, "function createScoreDetailMetric(label", "function catalogEntriesForSongId(songId"),
    sourceBetween(js, "function normalizePlayDetails(value)", "function createScoreHistoryShell(chart"),
    "globalThis.detailHooks = { normalizePlayDetails, createDxRatingFrame, createPlayDetailsSection };"
  ].join("\n");
  vm.runInNewContext(detailFunctions, context);

  const normalized = context.detailHooks.normalizePlayDetails({
    fast: 3,
    late: 7,
    dxScore: { current: 2948, maximum: 3000 },
    rating: {
      value: 15234,
      playerTotal: 15220,
      delta: 14,
      frame: "yellow",
      frameImageUrl: "https://maimai.wahlap.com/maimai-mobile/img/rating_base_yellow.png"
    }
  });
  assert.deepEqual(JSON.parse(JSON.stringify(normalized.rating)), {
    value: 15234,
    playerTotal: 15220,
    delta: 14,
    frame: "yellow",
    frameImageUrl: "https://maimai.wahlap.com/maimai-mobile/img/rating_base_yellow.png"
  });

  const rendered = elementTree(context.detailHooks.createPlayDetailsSection(normalized));
  const frame = rendered.find((element) => element.className === "play-dx-rating-frame");
  const image = rendered.find((element) => element.className === "play-dx-rating-frame-image");
  assert.ok(frame);
  assert.equal(frame.dataset.frame, "yellow");
  assert.match(frame.attributes["aria-label"], /本局后 DX Rating 15234，变化 \+14/);
  assert.equal(
    image.src,
    "https://maimai.wahlap.com/maimai-mobile/img/rating_base_yellow.png"
  );
  assert.equal(image.referrerPolicy, "no-referrer");
  const text = rendered.map((element) => element.textContent).join(" ");
  assert.match(text, /本局后 DX Rating/);
  assert.match(text, /Rating 变化/);
  assert.match(text, /同步时总 Rating/);
  assert.match(text, /\+14/);
  assert.match(scoreCss, /\.play-dx-rating-frame-image\s*\{[^}]*z-index:\s*0/s);
  assert.match(scoreCss, /\.play-dx-rating-frame-copy\s*\{[^}]*z-index:\s*1/s);
});

test("统一基础 SongID 曲库会按 STD/DX 谱面展开供选择", () => {
  assert.match(js, /values\.flatMap\(\(source\) =>/);
  assert.match(js, /rawCharts[\s\S]*chartTypes[\s\S]*normalizeChartType\(chart\?\.chartType\)/);
  assert.match(js, /charts: rawCharts\.filter/);
  assert.match(js, /song\.aliases\.some/);
});

test("前端脚本语法有效", () => {
  assert.doesNotThrow(() => new Function(js));
  assert.doesNotThrow(() => new Function(chunithmJs));
  assert.doesNotThrow(() => new Function(judgmentHelper));
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
process.stdout.write(`\n${passed}/${tests.length} 项舞萌前端测试通过\n`);
