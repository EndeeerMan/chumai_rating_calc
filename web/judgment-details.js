(() => {
  "use strict";

  const MAX_COUNT = 10_000_000;
  const MAIMAI_NOTE_TYPES = [
    ["tap", "TAP"],
    ["hold", "HOLD"],
    ["slide", "SLIDE"],
    ["touch", "TOUCH"],
    ["break", "BREAK"]
  ];
  const MAIMAI_JUDGMENTS = [
    ["criticalPerfect", "CRITICAL PERFECT"],
    ["perfect", "PERFECT"],
    ["great", "GREAT"],
    ["good", "GOOD"],
    ["miss", "MISS"]
  ];
  const CHUNITHM_JUDGMENTS = [
    ["justiceCritical", "JUSTICE CRITICAL"],
    ["justice", "JUSTICE"],
    ["attack", "ATTACK"],
    ["miss", "MISS"]
  ];
  const CHUNITHM_NOTE_TYPES = [
    ["tap", "TAP"],
    ["hold", "HOLD"],
    ["slide", "SLIDE"],
    ["air", "AIR"],
    ["flick", "FLICK"]
  ];

  function isObject(value) {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
  }

  function countValue(value) {
    return Number.isSafeInteger(value) && value >= 0 && value <= MAX_COUNT
      ? value
      : null;
  }

  function achievementValue(value) {
    if (!Number.isFinite(value) || value < 0 || value > 101) return null;
    const scaled = value * 100;
    return Math.abs(scaled - Math.round(scaled)) <= 1e-7 ? value : null;
  }

  function hasExactKeys(value, definitions) {
    if (!isObject(value)) return false;
    const expected = new Set(definitions.map(([key]) => key));
    const actual = Object.keys(value);
    return actual.length === expected.size && actual.every((key) => expected.has(key));
  }

  function strictCounts(value, definitions) {
    if (!hasExactKeys(value, definitions)) return null;
    const result = [];
    for (const [key, label] of definitions) {
      const count = countValue(value[key]);
      if (count === null) return null;
      result.push({ key, token: key, label, count });
    }
    return result;
  }

  function strictAchievements(value, definitions) {
    if (!hasExactKeys(value, definitions)) return null;
    const result = [];
    for (const [key, label] of definitions) {
      const achievement = achievementValue(value[key]);
      if (achievement === null) return null;
      result.push({ key, token: key, label, achievement });
    }
    return result;
  }

  function emptyDetails() {
    return {
      kind: "empty",
      byNoteType: [],
      judgments: [],
      noteCounts: [],
      noteAchievements: [],
      maxCombo: null
    };
  }

  function normalizeMaimaiDetails(source) {
    if (!hasExactKeys(source, [["byNoteType", ""]])) return emptyDetails();
    const rawTable = source.byNoteType;
    if (!isObject(rawTable)) return emptyDetails();
    const allowedTypes = new Set(MAIMAI_NOTE_TYPES.map(([key]) => key));
    const suppliedTypes = Object.keys(rawTable);
    if (suppliedTypes.length === 0 || suppliedTypes.some((key) => !allowedTypes.has(key))) {
      return emptyDetails();
    }

    const byNoteType = [];
    for (const [key, label] of MAIMAI_NOTE_TYPES) {
      if (!Object.hasOwn(rawTable, key)) continue;
      const judgments = strictCounts(rawTable[key], MAIMAI_JUDGMENTS);
      if (!judgments) return emptyDetails();
      byNoteType.push({ key, token: key, label, judgments });
    }
    return {
      ...emptyDetails(),
      kind: "by-note-type",
      byNoteType
    };
  }

  function normalizeChunithmDetails(source) {
    if (!isObject(source)) return emptyDetails();
    const keys = Object.keys(source);
    const allowed = new Set(["judgments", "noteCounts", "noteAchievements", "maxCombo"]);
    if (keys.some((key) => !allowed.has(key)) ||
        !Object.hasOwn(source, "judgments") || !Object.hasOwn(source, "maxCombo") ||
        (!Object.hasOwn(source, "noteCounts") && !Object.hasOwn(source, "noteAchievements"))) {
      return emptyDetails();
    }
    const judgments = strictCounts(source.judgments, CHUNITHM_JUDGMENTS);
    const noteCounts = Object.hasOwn(source, "noteCounts")
      ? strictCounts(source.noteCounts, CHUNITHM_NOTE_TYPES)
      : [];
    const noteAchievements = Object.hasOwn(source, "noteAchievements")
      ? strictAchievements(source.noteAchievements, CHUNITHM_NOTE_TYPES)
      : [];
    const maxCombo = countValue(source.maxCombo);
    if (!judgments || noteCounts === null || noteAchievements === null ||
        maxCombo === null) return emptyDetails();
    return {
      ...emptyDetails(),
      kind: "chunithm-summary",
      judgments,
      noteCounts,
      noteAchievements,
      maxCombo
    };
  }

  function normalizeRecord(record, game) {
    if (!isObject(record) || !isObject(record.judgmentDetails)) {
      return emptyDetails();
    }
    const normalizedGame = String(game ?? "").trim().toLowerCase();
    if (normalizedGame === "maimai") {
      return normalizeMaimaiDetails(record.judgmentDetails);
    }
    if (normalizedGame === "chunithm") {
      return normalizeChunithmDetails(record.judgmentDetails);
    }
    return emptyDetails();
  }

  function hasDetails(value) {
    if (value?.kind === "by-note-type") {
      return Array.isArray(value.byNoteType) && value.byNoteType.length > 0;
    }
    return value?.kind === "chunithm-summary" &&
      Array.isArray(value.judgments) && value.judgments.length === CHUNITHM_JUDGMENTS.length &&
      ((Array.isArray(value.noteAchievements) &&
        value.noteAchievements.length === CHUNITHM_NOTE_TYPES.length) ||
       (Array.isArray(value.noteCounts) &&
        value.noteCounts.length === CHUNITHM_NOTE_TYPES.length)) &&
      countValue(value.maxCombo) !== null;
  }

  function appendCountValues(document, container, values) {
    values.forEach((value) => {
      const item = document.createElement("div");
      const term = document.createElement("dt");
      term.textContent = value.label;
      const description = document.createElement("dd");
      description.textContent = Object.hasOwn(value, "achievement")
        ? `${value.achievement.toFixed(2)}%`
        : value.count.toLocaleString("zh-CN");
      item.append(term, description);
      container.append(item);
    });
  }

  function appendMaimaiDetails(document, content, groups) {
    const viewport = document.createElement("div");
    viewport.className = "score-judgment-table-viewport";
    const table = document.createElement("table");
    table.className = "score-judgment-table score-judgment-table--maimai";
    const caption = document.createElement("caption");
    caption.textContent = "本局各音符类型判定";
    const head = document.createElement("thead");
    const headRow = document.createElement("tr");
    const noteHeading = document.createElement("th");
    noteHeading.scope = "col";
    noteHeading.textContent = "音符";
    headRow.append(noteHeading);
    MAIMAI_JUDGMENTS.forEach(([, label]) => {
      const heading = document.createElement("th");
      heading.scope = "col";
      heading.textContent = label;
      headRow.append(heading);
    });
    head.append(headRow);

    const body = document.createElement("tbody");
    groups.forEach((group) => {
      const row = document.createElement("tr");
      const label = document.createElement("th");
      label.scope = "row";
      label.textContent = group.label;
      row.append(label);
      group.judgments.forEach((judgment) => {
        const cell = document.createElement("td");
        cell.textContent = judgment.count.toLocaleString("zh-CN");
        row.append(cell);
      });
      body.append(row);
    });
    table.append(caption, head, body);
    viewport.append(table);
    content.append(viewport);
  }

  function appendChunithmSection(document, content, headingText, values) {
    const section = document.createElement("section");
    section.className = "score-judgment-summary-section";
    const heading = document.createElement("h4");
    heading.textContent = headingText;
    const list = document.createElement("dl");
    appendCountValues(document, list, values);
    section.append(heading, list);
    content.append(section);
  }

  function appendChunithmDetails(document, content, details) {
    appendChunithmSection(document, content, "本局判定", details.judgments);
    if (details.noteAchievements.length > 0) {
      appendChunithmSection(
        document,
        content,
        "各音符类型达成率",
        details.noteAchievements
      );
    } else {
      appendChunithmSection(
        document,
        content,
        "旧数据中的音符数量",
        details.noteCounts
      );
    }
    const section = document.createElement("section");
    section.className = "score-judgment-summary-section score-judgment-max-combo";
    const heading = document.createElement("h4");
    heading.textContent = "MAX COMBO";
    const value = document.createElement("strong");
    value.textContent = details.maxCombo.toLocaleString("zh-CN");
    section.append(heading, value);
    content.append(section);
  }

  function createDetails(document, value) {
    const normalized = isObject(value) ? value : emptyDetails();
    const available = hasDetails(normalized);
    const details = document.createElement("details");
    details.className = "score-judgment-details";
    const summary = document.createElement("summary");
    summary.className = "score-judgment-details-summary";
    const label = document.createElement("span");
    label.textContent = "本局玩家判定";
    const status = document.createElement("span");
    status.className = "score-judgment-details-status";
    status.textContent = available ? "本局数据" : "尚未抓取";
    summary.append(label, status);
    details.append(summary);

    if (!available) {
      details.classList.add("is-empty");
      const empty = document.createElement("p");
      empty.className = "score-judgment-details-empty";
      empty.textContent = "本局尚未抓取到官方游玩判定详情。";
      details.append(empty);
      return details;
    }

    const content = document.createElement("div");
    content.className = "score-judgment-details-content";
    if (normalized.kind === "chunithm-summary") {
      appendChunithmDetails(document, content, normalized);
    } else {
      appendMaimaiDetails(document, content, normalized.byNoteType);
    }
    details.append(content);
    return details;
  }

  globalThis.RhythmJudgmentDetails = Object.freeze({
    normalizeRecord,
    createDetails
  });
})();
