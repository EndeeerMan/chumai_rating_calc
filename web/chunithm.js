(() => {
  "use strict";

  const STORAGE_KEY = "chunithm-b30-n20-workbench:v1";
  const CALCULATE_ENDPOINT = "/api/chunithm/calculate";
  const CATALOG_ENDPOINT = "/api/chunithm/songs/catalog";
  const SEARCH_ENDPOINT = "/api/chunithm/songs/search";
  const USER_CHARTS_ENDPOINT = "/api/chunithm/user/charts";
  const PLAY_HISTORY_ENDPOINT = "/api/history";
  const AUTH_STATUS_ENDPOINT = "/api/auth/status";
  const AUTH_LOGIN_ENDPOINT = "/api/auth/login";
  const AUTH_REGISTER_ENDPOINT = "/api/auth/register";
  const AUTH_LOGOUT_ENDPOINT = "/api/auth/logout";
  const COVER_PLACEHOLDER_URL = "/song-catalog/cover-placeholder.svg";
  const CHUNITHM_RANK_ASSET_ROOT = "/assets/lxns/chunithm/music_rank";
  const RANK_ASSET_BY_GRADE = Object.freeze({
    D: "d",
    C: "c",
    B: "b",
    BB: "bb",
    BBB: "bbb",
    A: "a",
    AA: "aa",
    AAA: "aaa",
    S: "s",
    "S+": "sp",
    SS: "ss",
    "SS+": "ssp",
    SSS: "sss",
    "SSS+": "sssp"
  });
  const FALLBACK_LATEST_VERSIONS = ["CHUNITHM LUMINOUS PLUS", "CHUNITHM VERSE"];
  const DIFFICULTIES = [
    "BASIC",
    "ADVANCED",
    "EXPERT",
    "MASTER",
    "ULTIMA",
    "WORLD'S END"
  ];
  const MAX_CHARTS = 3000;
  const MAX_SONG_ID_LENGTH = 100;
  const MAX_TITLE_LENGTH = 300;
  const MAX_DIFFICULTY_LENGTH = 50;
  const MAX_VERSION_LENGTH = 100;
  const MAX_CSV_BYTES = 5 * 1024 * 1024;
  const MAX_CSV_ROWS = MAX_CHARTS;
  const JUDGMENT_DETAILS = globalThis.RhythmJudgmentDetails;
  if (!JUDGMENT_DETAILS) throw new Error("Judgment detail helper failed to load");

  class RevisionConflictError extends Error {
    constructor(message = "用户数据版本冲突") {
      super(message);
      this.name = "RevisionConflictError";
    }
  }

  const elements = {
    authStartupMask: document.querySelector("#auth-startup-mask"),
    syncStatus: document.querySelector("#sync-status"),
    syncStatusText: document.querySelector("#sync-status-text"),
    totalRating: document.querySelector("#total-rating"),
    b30Rating: document.querySelector("#b30-rating"),
    n20Rating: document.querySelector("#n20-rating"),
    selectedCount: document.querySelector("#selected-count"),
    b30Count: document.querySelector("#b30-count"),
    n20Count: document.querySelector("#n20-count"),
    libraryCount: document.querySelector("#library-count"),
    worldsEndCount: document.querySelector("#worlds-end-count"),
    addButton: document.querySelector("#add-chart-button"),
    scoreCompositionPanel: document.querySelector("#score-composition-panel"),
    compositionViewTab: document.querySelector("#composition-view-tab"),
    playHistoryViewTab: document.querySelector("#play-history-view-tab"),
    playHistoryViewPanel: document.querySelector("#play-history-view-panel"),
    refreshPlayHistoryButton: document.querySelector("#refresh-play-history-button"),
    playHistoryViewStatus: document.querySelector("#play-history-view-status"),
    playHistoryOverviewList: document.querySelector("#play-history-overview-list"),
    playHistoryOverviewEmpty: document.querySelector("#play-history-overview-empty"),
    compositionRatingAverage: document.querySelector("#composition-rating-average"),
    compositionRatingMeta: document.querySelector("#composition-rating-meta"),
    compositionB30Average: document.querySelector("#composition-b30-average"),
    compositionB30Meta: document.querySelector("#composition-b30-meta"),
    compositionS10Average: document.querySelector("#composition-s10-average"),
    compositionS10Meta: document.querySelector("#composition-s10-meta"),
    compositionN20Average: document.querySelector("#composition-n20-average"),
    compositionN20Meta: document.querySelector("#composition-n20-meta"),
    compositionB30Count: document.querySelector("#composition-b30-count"),
    compositionS10Count: document.querySelector("#composition-s10-count"),
    compositionN20Count: document.querySelector("#composition-n20-count"),
    compositionB30List: document.querySelector("#composition-b30-list"),
    compositionS10List: document.querySelector("#composition-s10-list"),
    compositionN20List: document.querySelector("#composition-n20-list"),
    compositionB30Empty: document.querySelector("#composition-b30-empty"),
    compositionS10Empty: document.querySelector("#composition-s10-empty"),
    compositionN20Empty: document.querySelector("#composition-n20-empty"),
    importButton: document.querySelector("#import-button"),
    exportButton: document.querySelector("#export-button"),
    csvInput: document.querySelector("#csv-input"),
    accountButton: document.querySelector("#account-button"),
    accountLabel: document.querySelector("#account-label"),
    logoutButton: document.querySelector("#logout-button"),
    chartDialog: document.querySelector("#chart-dialog"),
    chartForm: document.querySelector("#chart-form"),
    dialogMode: document.querySelector("#dialog-mode"),
    titleInput: document.querySelector("#chart-title"),
    songIdInput: document.querySelector("#chart-song-id"),
    difficultyInput: document.querySelector("#chart-difficulty"),
    cidInput: document.querySelector("#chart-cid"),
    constantInput: document.querySelector("#chart-constant"),
    scoreInput: document.querySelector("#chart-score"),
    versionInput: document.querySelector("#chart-version"),
    formError: document.querySelector("#form-error"),
    closeDialogButton: document.querySelector("#close-dialog-button"),
    cancelDialogButton: document.querySelector("#cancel-dialog-button"),
    songSearchResults: document.querySelector("#song-search-results"),
    selectedSong: document.querySelector("#selected-song"),
    selectedSongCover: document.querySelector("#selected-song-cover"),
    selectedSongTitle: document.querySelector("#selected-song-title"),
    selectedSongMeta: document.querySelector("#selected-song-meta"),
    selectedChartMeta: document.querySelector("#selected-chart-meta"),
    authDialog: document.querySelector("#auth-dialog"),
    authForm: document.querySelector("#auth-form"),
    authDialogTitle: document.querySelector("#auth-dialog-title"),
    authIdentityLabel: document.querySelector("#auth-identity-label"),
    authUsername: document.querySelector("#auth-username"),
    authPassword: document.querySelector("#auth-password"),
    authRegisterFields: document.querySelector("#auth-register-fields"),
    authEmail: document.querySelector("#auth-email"),
    authVerificationCode: document.querySelector("#auth-verification-code"),
    sendAuthCodeButton: document.querySelector("#send-auth-code-button"),
    authCodeStatus: document.querySelector("#auth-code-status"),
    authNote: document.querySelector("#auth-note"),
    authError: document.querySelector("#auth-error"),
    authSubmitButton: document.querySelector("#auth-submit-button"),
    closeAuthDialogButton: document.querySelector("#close-auth-dialog-button"),
    cancelAuthButton: document.querySelector("#cancel-auth-button"),
    scoreDetailDialog: document.querySelector("#score-detail-dialog"),
    scoreDetailPool: document.querySelector("#score-detail-pool"),
    scoreDetailTitle: document.querySelector("#score-detail-title"),
    scoreDetailCover: document.querySelector("#score-detail-cover"),
    scoreDetailSongId: document.querySelector("#score-detail-song-id"),
    scoreDetailSongTitle: document.querySelector("#score-detail-song-title"),
    scoreDetailSongMeta: document.querySelector("#score-detail-song-meta"),
    scoreDetailConstant: document.querySelector("#score-detail-constant"),
    scoreDetailRankImage: document.querySelector("#score-detail-rank-image"),
    scoreDetailScore: document.querySelector("#score-detail-score"),
    scoreDetailRating: document.querySelector("#score-detail-rating"),
    scoreDetailMetrics: document.querySelector("#score-detail-metrics"),
    scoreDetailHistory: document.querySelector("#score-detail-history"),
    editScoreDetailButton: document.querySelector("#edit-score-detail-button"),
    deleteScoreDetailButton: document.querySelector("#delete-score-detail-button"),
    closeScoreDetailButton: document.querySelector("#close-score-detail-button"),
    deleteDialog: document.querySelector("#delete-dialog"),
    deleteChartName: document.querySelector("#delete-chart-name"),
    cancelDeleteButton: document.querySelector("#cancel-delete-button"),
    confirmDeleteButton: document.querySelector("#confirm-delete-button"),
    toast: document.querySelector("#toast")
  };

  const state = {
    charts: [],
    calculation: null,
    scoreDetailKey: null,
    scoreDetailReturnFocus: null,
    scoreHistoryController: null,
    scoreHistorySequence: 0,
    playHistoryController: null,
    playHistorySequence: 0,
    playHistoryRecords: [],
    playHistoryLoaded: false,
    selectedPlayRecord: null,
    playHistoryTrigger: null,
    scoreView: "composition",
    editingKey: null,
    deletingKey: null,
    requestSequence: 0,
    apiTimer: null,
    toastTimer: null,
    catalog: [],
    catalogById: new Map(),
    catalogByTitle: new Map(),
    catalogReady: false,
    latestVersions: [...FALLBACK_LATEST_VERSIONS],
    selectedCatalogSong: null,
    songSearchTimer: null,
    songSearchController: null,
    authenticated: false,
    user: null,
    userRevision: null,
    authReady: false,
    authBusy: false,
    authMode: "login",
    sessionSequence: 0,
    chartSaveBusy: false,
    catalogRefreshController: null
  };

  if (!window.B50EmailVerification) {
    throw new Error("邮箱验证模块加载失败");
  }
  const authEmailController = window.B50EmailVerification.createRegistrationController({
    container: elements.authRegisterFields,
    emailInput: elements.authEmail,
    codeInput: elements.authVerificationCode,
    sendButton: elements.sendAuthCodeButton,
    status: elements.authCodeStatus,
    showError: setAuthError
  });

  function createKey() {
    if (globalThis.crypto && typeof globalThis.crypto.randomUUID === "function") {
      return globalThis.crypto.randomUUID();
    }
    return `chuni-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  function finiteNumber(value, fallback = 0) {
    if (value === null || value === undefined || String(value).trim() === "") return fallback;
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  }

  function normalizedText(value) {
    return String(value ?? "")
      .normalize("NFKC")
      .trim()
      .replace(/\s+/g, " ")
      .toLocaleLowerCase("zh-CN");
  }

  function normalizeSongKey(value) {
    return String(value ?? "")
      .normalize("NFKC")
      .toLocaleLowerCase("und")
      .replace(/[\p{M}\p{P}\p{S}\s]+/gu, "");
  }

  function songKeyDistance(left, right) {
    const a = Array.from(normalizeSongKey(left));
    const b = Array.from(normalizeSongKey(right));
    if (a.length === 0) return b.length;
    if (b.length === 0) return a.length;
    let previous = Array.from({ length: b.length + 1 }, (_, index) => index);
    for (let aIndex = 0; aIndex < a.length; aIndex += 1) {
      const current = [aIndex + 1];
      for (let bIndex = 0; bIndex < b.length; bIndex += 1) {
        const substitution = previous[bIndex] + (a[aIndex] === b[bIndex] ? 0 : 1);
        current.push(Math.min(
          current[bIndex] + 1,
          previous[bIndex + 1] + 1,
          substitution
        ));
      }
      previous = current;
    }
    return previous[b.length];
  }

  function songKeySimilarity(left, right) {
    const leftKey = normalizeSongKey(left);
    const rightKey = normalizeSongKey(right);
    const length = Math.max(Array.from(leftKey).length, Array.from(rightKey).length);
    return length === 0 ? 1 : Math.max(0, 1 - songKeyDistance(leftKey, rightKey) / length);
  }

  function normalizeDifficulty(value) {
    const source = String(value ?? "").normalize("NFKC").trim().toUpperCase();
    if (!source) return null;
    const key = source.replace(/[\s._:\-–—/\\|+·・'’"()[\]{}【】]+/gu, "");
    const aliases = {
      BAS: "BASIC",
      BSC: "BASIC",
      BASIC: "BASIC",
      ADV: "ADVANCED",
      ADVANCED: "ADVANCED",
      EXP: "EXPERT",
      EX: "EXPERT",
      EXPERT: "EXPERT",
      MAS: "MASTER",
      MST: "MASTER",
      MASTER: "MASTER",
      ULT: "ULTIMA",
      ULTIMA: "ULTIMA",
      WE: "WORLD'S END",
      WEND: "WORLD'S END",
      WORLDEND: "WORLD'S END",
      WORLDSEND: "WORLD'S END"
    };
    return aliases[key] || (DIFFICULTIES.includes(source) ? source : null);
  }

  function normalizeCoverUrl(value) {
    const text = String(value ?? "").trim();
    if (!text || text.length > 2048) return "";
    try {
      const url = new URL(text, window.location.origin);
      if (!/^https?:$/.test(url.protocol) || url.username || url.password) return "";
      if (url.origin !== window.location.origin && url.protocol !== "https:") return "";
      return url.origin === window.location.origin
        ? `${url.pathname}${url.search}`
        : url.href;
    } catch (error) {
      return "";
    }
  }

  function normalizeChart(source) {
    const chart = source && typeof source === "object" ? source : {};
    return {
      key: typeof chart.key === "string" && chart.key ? chart.key : createKey(),
      songId: String(chart.songId ?? chart.id ?? "").trim(),
      cid: String(chart.cid ?? "").trim(),
      title: String(chart.title ?? "").trim(),
      difficulty: normalizeDifficulty(chart.difficulty),
      constant: finiteNumber(chart.constant ?? chart.ds, Number.NaN),
      score: finiteNumber(chart.score, Number.NaN),
      version: String(chart.version ?? "").trim(),
      coverUrl: normalizeCoverUrl(chart.coverUrl ?? chart.cover ?? ""),
      artist: String(chart.artist ?? "").trim(),
      genre: String(chart.genre ?? "").trim(),
      bpm: finiteNumber(chart.bpm, Number.NaN),
      displayLevel: String(chart.displayLevel ?? chart.level ?? "").trim(),
      combo: Math.max(0, Math.trunc(finiteNumber(chart.combo, 0))),
      charter: String(chart.charter ?? "").trim(),
      disabled: chart.disabled === true
    };
  }

  function hasAtMostOneDecimal(value) {
    return Math.abs(Math.round(value * 10) - value * 10) < 1e-7;
  }

  function isValidChart(chart) {
    return Boolean(
      chart.songId &&
      chart.songId.length <= MAX_SONG_ID_LENGTH &&
      chart.title &&
      chart.title.length <= MAX_TITLE_LENGTH &&
      DIFFICULTIES.includes(chart.difficulty) &&
      chart.difficulty.length <= MAX_DIFFICULTY_LENGTH &&
      Number.isFinite(chart.constant) &&
      chart.constant >= 0 &&
      chart.constant <= 20 &&
      hasAtMostOneDecimal(chart.constant) &&
      Number.isSafeInteger(chart.score) &&
      chart.score >= 0 &&
      chart.score <= 1010000 &&
      chart.version &&
      chart.version.length <= MAX_VERSION_LENGTH
    );
  }

  function chartPersistenceValue(chart) {
    return {
      songId: chart.songId,
      title: chart.title,
      difficulty: chart.difficulty,
      constant: chart.constant,
      score: chart.score,
      version: chart.version
    };
  }

  function normalizeChartCollection(value) {
    if (!Array.isArray(value)) return [];
    const deduplicated = new Map();
    for (const source of value.slice(0, MAX_CHARTS)) {
      const chart = normalizeChart(source);
      if (isValidChart(chart)) deduplicated.set(chartIdentity(chart), chart);
    }
    return Array.from(deduplicated.values());
  }

  function loadLegacyLocalCharts() {
    try {
      return normalizeChartCollection(JSON.parse(localStorage.getItem(STORAGE_KEY) || "[]"));
    } catch (error) {
      console.warn("无法读取中二节奏旧版本地成绩", error);
      return [];
    }
  }

  function chartIdentity(chart) {
    return `${String(chart.songId)}\u0000${normalizeDifficulty(chart.difficulty) || ""}`;
  }

  function isWorldsEnd(difficulty) {
    return normalizeDifficulty(difficulty) === "WORLD'S END";
  }

  function latestVersionSet() {
    return new Set(state.latestVersions.map(normalizedText));
  }

  function isNewChart(chart) {
    return latestVersionSet().has(normalizedText(chart.version));
  }

  function floorHundredths(value) {
    return Math.floor(value * 100 + 1e-8);
  }

  function lerpHundredths(x1, x2, y1, y2, score) {
    const value = (score - x1) / (x2 - x1) * (y2 - y1) + y1;
    return floorHundredths(value);
  }

  function singleRatingHundredths(chart) {
    if (isWorldsEnd(chart.difficulty)) return 0;
    const score = chart.score;
    const ds = chart.constant;
    let result;
    if (score < 500000) return 0;
    if (score < 800000) {
      result = lerpHundredths(500000, 800000, 0, (ds - 5) / 2, score);
      return Math.max(0, result);
    }
    if (score < 900000) {
      result = lerpHundredths(800000, 900000, (ds - 5) / 2, ds - 5, score);
      return Math.max(0, result);
    }
    if (score < 925000) {
      result = lerpHundredths(900000, 925000, ds - 5, ds - 3, score);
      return Math.max(0, result);
    }
    if (score < 975000) {
      result = lerpHundredths(925000, 975000, ds - 3, ds, score);
      return Math.max(0, result);
    }
    if (score < 1000000) return lerpHundredths(975000, 1000000, ds, ds + 1, score);
    if (score < 1005000) return lerpHundredths(1000000, 1005000, ds + 1, ds + 1.5, score);
    if (score < 1007500) return lerpHundredths(1005000, 1007500, ds + 1.5, ds + 2, score);
    if (score < 1009000) return lerpHundredths(1007500, 1009000, ds + 2, ds + 2.15, score);
    return floorHundredths(ds + 2.15);
  }

  function rankingComparator(left, right) {
    // Array#sort is stable in current browsers. Equal-RA charts deliberately keep
    // their input order so the B30/N20 boundary matches the JDK calculator.
    return right.ratingHundredths - left.ratingHundredths;
  }

  function calculateLocally() {
    const charts = state.charts.map((chart) => {
      const ratingEligible = !isWorldsEnd(chart.difficulty) && !chart.disabled;
      const ratingHundredths = ratingEligible ? singleRatingHundredths(chart) : 0;
      return {
        ...chart,
        newChart: isNewChart(chart),
        ratingEligible,
        ratingHundredths,
        rating: ratingHundredths / 100,
        selected: false,
        rank: 0,
        poolRank: 0
      };
    });

    const eligible = charts.filter((chart) => chart.ratingEligible);
    eligible.slice().sort(rankingComparator).forEach((chart, index) => {
      chart.rank = index + 1;
    });

    const assignPool = (newChart, limit) => {
      charts
        .filter((chart) => chart.ratingEligible && chart.newChart === newChart)
        .sort(rankingComparator)
        .forEach((chart, index) => {
          chart.poolRank = index + 1;
          chart.selected = index < limit;
        });
    };
    assignPool(false, 30);
    assignPool(true, 20);

    const b30 = charts.filter((chart) => chart.selected && !chart.newChart);
    const n20 = charts.filter((chart) => chart.selected && chart.newChart);
    const b30Hundredths = b30.reduce((total, chart) => total + chart.ratingHundredths, 0);
    const n20Hundredths = n20.reduce((total, chart) => total + chart.ratingHundredths, 0);
    const b30Sum = b30Hundredths / 100;
    const n20Sum = n20Hundredths / 100;
    return {
      rating: (b30Hundredths + n20Hundredths) / 5000,
      b30Rating: b30Hundredths / 5000,
      n20Rating: n20Hundredths / 5000,
      b30Sum,
      n20Sum,
      selectedCount: b30.length + n20.length,
      b30Count: b30.length,
      n20Count: n20.length,
      charts
    };
  }

  function mergeApiCalculation(payload, localCalculation) {
    if (!payload || !Array.isArray(payload.charts)) {
      throw new Error("后端响应缺少 charts");
    }
    const localByIdentity = new Map(localCalculation.charts.map((chart) => [chartIdentity(chart), chart]));
    const merged = [];
    for (const remote of payload.charts) {
      const local = localByIdentity.get(chartIdentity(remote));
      if (!local) continue;
      const remoteRating = finiteNumber(remote.rating, local.rating);
      merged.push({
        ...local,
        newChart: typeof remote.newChart === "boolean" ? remote.newChart : local.newChart,
        ratingEligible: typeof remote.ratingEligible === "boolean"
          ? remote.ratingEligible
          : local.ratingEligible,
        rating: remoteRating,
        ratingHundredths: Math.round(remoteRating * 100),
        selected: typeof remote.selected === "boolean" ? remote.selected : local.selected,
        rank: Math.max(0, Math.trunc(finiteNumber(remote.rank, local.rank))),
        poolRank: Math.max(0, Math.trunc(finiteNumber(remote.poolRank, local.poolRank)))
      });
    }
    if (merged.length !== localCalculation.charts.length) {
      throw new Error("后端响应谱面数量不一致");
    }
    return {
      rating: finiteNumber(payload.rating, localCalculation.rating),
      b30Rating: finiteNumber(payload.b30Rating, localCalculation.b30Rating),
      n20Rating: finiteNumber(payload.n20Rating, localCalculation.n20Rating),
      b30Sum: finiteNumber(payload.b30Sum, localCalculation.b30Sum),
      n20Sum: finiteNumber(payload.n20Sum, localCalculation.n20Sum),
      selectedCount: Math.max(0, Math.trunc(finiteNumber(payload.selectedCount, localCalculation.selectedCount))),
      b30Count: Math.max(0, Math.trunc(finiteNumber(payload.b30Count, localCalculation.b30Count))),
      n20Count: Math.max(0, Math.trunc(finiteNumber(payload.n20Count, localCalculation.n20Count))),
      charts: merged
    };
  }

  function setSyncStatus(status, text) {
    elements.syncStatus.dataset.state = status;
    elements.syncStatusText.textContent = text;
    elements.syncStatus.title = status === "server"
      ? "B30 + N20 结果已由 JDK 后端校验"
      : status === "syncing"
        ? "正在与 JDK 后端校验计算结果"
        : "当前使用浏览器内置的官方分段公式";
  }

  function scheduleCalculation() {
    window.clearTimeout(state.apiTimer);
    const sequence = ++state.requestSequence;
    const localCalculation = calculateLocally();
    state.calculation = localCalculation;
    render();
    if (state.charts.length === 0) {
      setSyncStatus("local", "本地计算");
      return;
    }
    setSyncStatus("syncing", "正在校验");
    state.apiTimer = window.setTimeout(() => requestApiCalculation(localCalculation, sequence), 180);
  }

  async function requestApiCalculation(localCalculation, sequence) {
    try {
      const response = await fetch(CALCULATE_ENDPOINT, {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json;charset=UTF-8"
        },
        body: JSON.stringify({ charts: state.charts.map(chartPersistenceValue) })
      });
      const payload = await readJsonResponse(response);
      if (sequence !== state.requestSequence) return;
      if (!response.ok) throw new Error(apiErrorMessage(payload, `HTTP ${response.status}`));
      state.calculation = mergeApiCalculation(payload, localCalculation);
      setSyncStatus("server", "后端已校验");
      render();
    } catch (error) {
      if (sequence !== state.requestSequence) return;
      state.calculation = calculateLocally();
      setSyncStatus("local", "本地计算");
      render();
      console.info("JDK 后端暂不可用，已使用浏览器内置 CHUNITHM 公式", error);
    }
  }

  function formatRating(value, digits = 2) {
    return finiteNumber(value).toFixed(digits);
  }

  function formatScore(value) {
    return Math.round(finiteNumber(value)).toLocaleString("zh-CN");
  }

  function scoreGrade(score) {
    if (score < 500000) return "D";
    if (score < 600000) return "C";
    if (score < 700000) return "B";
    if (score < 800000) return "BB";
    if (score < 900000) return "BBB";
    if (score < 925000) return "A";
    if (score < 950000) return "AA";
    if (score < 975000) return "AAA";
    if (score < 990000) return "S";
    if (score < 1000000) return "S+";
    if (score < 1005000) return "SS";
    if (score < 1007500) return "SS+";
    if (score < 1009000) return "SSS";
    return "SSS+";
  }

  function configureRankImageSize(image) {
    const height = image.classList.contains("lxns-rank-image--compact")
      ? 18
      : image.classList.contains("lxns-rank-image--card") ? 20 : 24;
    image.removeAttribute("width");
    image.height = height;
    image.style.width = "auto";
    image.style.height = `${height}px`;
    image.style.objectFit = "contain";
    image.style.flex = "0 0 auto";
  }

  function setLxnsBadgeSource(image, source, alt) {
    image.dataset.lxnsAsset = source;
    image.dataset.loadState = "loading";
    image.alt = alt;
    image.hidden = false;
    image.onload = () => {
      if (image.dataset.lxnsAsset !== source || image.getAttribute("src") !== source) return;
      image.dataset.loadState = "loaded";
      image.hidden = false;
    };
    image.onerror = () => {
      if (image.dataset.lxnsAsset !== source || image.getAttribute("src") !== source) return;
      image.dataset.loadState = "unavailable";
      image.hidden = true;
      image.removeAttribute("src");
    };
    image.src = source;
  }

  function setRankImage(image, score) {
    const grade = scoreGrade(finiteNumber(score));
    const asset = RANK_ASSET_BY_GRADE[grade];
    const source = `${CHUNITHM_RANK_ASSET_ROOT}/${asset}.webp`;
    configureRankImageSize(image);
    setLxnsBadgeSource(image, source, `${grade} 评级图标`);
  }

  function createRankImage(score, variant) {
    const image = document.createElement("img");
    image.className = `lxns-rank-image lxns-rank-image--${variant}`;
    image.loading = "lazy";
    image.decoding = "async";
    setRankImage(image, score);
    return image;
  }

  function difficultyClass(difficulty) {
    const map = {
      BASIC: "basic",
      ADVANCED: "advanced",
      EXPERT: "expert",
      MASTER: "master",
      ULTIMA: "ultima",
      "WORLD'S END": "worldsend"
    };
    return map[normalizeDifficulty(difficulty)] || "master";
  }

  function createCoverImage(chart, sizeClass = "chart-cover") {
    const image = document.createElement("img");
    image.className = sizeClass;
    image.src = chart.coverUrl || COVER_PLACEHOLDER_URL;
    image.alt = `${chart.title} 封面`;
    image.loading = "lazy";
    image.decoding = "async";
    image.addEventListener("error", () => {
      if (!image.src.endsWith(COVER_PLACEHOLDER_URL)) image.src = COVER_PLACEHOLDER_URL;
    }, { once: true });
    return image;
  }

  function renderSummary(calculation) {
    elements.totalRating.textContent = formatRating(calculation.rating, 4);
    elements.b30Rating.textContent = formatRating(calculation.b30Rating, 4);
    elements.n20Rating.textContent = formatRating(calculation.n20Rating, 4);
    elements.selectedCount.textContent = `已入选 ${calculation.selectedCount} / 50`;
    elements.b30Count.textContent = `${calculation.b30Count} / 30 · 贡献 ${formatRating(calculation.b30Sum)}`;
    elements.n20Count.textContent = `${calculation.n20Count} / 20 · 贡献 ${formatRating(calculation.n20Sum)}`;
    elements.libraryCount.textContent = String(state.charts.length);
    const worldsEndCount = calculation.charts.filter(
      (chart) => isWorldsEnd(chart.difficulty)
    ).length;
    elements.worldsEndCount.textContent = `WORLD'S END ${worldsEndCount} 条`;
  }

  function compositionPools(calculation) {
    const byPoolRank = (left, right) => left.poolRank - right.poolRank || rankingComparator(left, right);
    const b30 = calculation.charts
      .filter((chart) => chart.ratingEligible && !chart.newChart && chart.selected &&
        chart.poolRank >= 1 && chart.poolRank <= 30)
      .sort(byPoolRank);
    const s10 = calculation.charts
      .filter((chart) => chart.ratingEligible && !chart.newChart &&
        chart.poolRank >= 31 && chart.poolRank <= 40)
      .sort(byPoolRank);
    const n20 = calculation.charts
      .filter((chart) => chart.ratingEligible && chart.newChart && chart.selected &&
        chart.poolRank >= 1 && chart.poolRank <= 20)
      .sort(byPoolRank);
    return { b30, s10, n20 };
  }

  function formatPoolAverage(charts) {
    if (charts.length === 0) return "0.00";
    const totalHundredths = charts.reduce(
      (total, chart) => total + Math.round(finiteNumber(chart.ratingHundredths)), 0);
    return (Math.floor(totalHundredths / charts.length) / 100).toFixed(2);
  }

  function compositionPlacement(chart) {
    if (chart.newChart) {
      return {
        shortLabel: `New 20 · #${chart.poolRank}`,
        detailLabel: `New 20 第 ${chart.poolRank} 名${chart.rank ? ` · 全体第 ${chart.rank} 名` : ""}`
      };
    }
    if (chart.poolRank >= 31 && chart.poolRank <= 40) {
      return {
        shortLabel: `Selection 10 · #${chart.poolRank - 30}`,
        detailLabel: `Selection 10 第 ${chart.poolRank - 30} 名 · 旧曲第 ${chart.poolRank} 名` +
          `${chart.rank ? ` · 全体第 ${chart.rank} 名` : ""} · 不计总 Rating`
      };
    }
    return {
      shortLabel: `Best 30 · #${chart.poolRank}`,
      detailLabel: `Best 30 第 ${chart.poolRank} 名${chart.rank ? ` · 全体第 ${chart.rank} 名` : ""}`
    };
  }

  function createCompositionCard(chart) {
    const difficultyToken = difficultyClass(chart.difficulty);
    const placement = compositionPlacement(chart);
    const item = document.createElement("article");
    item.className = `score-card-item lxns-score-card-item difficulty-${difficultyToken}-card`;
    item.setAttribute("role", "listitem");

    const button = document.createElement("button");
    button.type = "button";
    button.className = `score-card-button lxns-score-card lxns-score-card--chunithm difficulty-${difficultyToken}-card`;
    button.dataset.compositionKey = chart.key;
    button.dataset.difficulty = difficultyToken;
    button.setAttribute("aria-haspopup", "dialog");
    button.setAttribute(
      "aria-label",
      `查看 ${placement.shortLabel}，${chart.title}，${chart.difficulty}，分数 ${formatScore(chart.score)}，单曲 Rating ${formatRating(chart.rating)}`
    );

    const cover = createCoverImage(chart, "score-card-cover lxns-score-card__cover");
    const shade = document.createElement("span");
    shade.className = "lxns-score-card__shade";
    shade.setAttribute("aria-hidden", "true");

    const header = document.createElement("span");
    header.className = `lxns-score-card__header difficulty-${difficultyToken}`;
    const title = document.createElement("strong");
    title.className = "lxns-score-card__title";
    title.textContent = chart.title;
    title.title = chart.title;
    const difficulty = document.createElement("span");
    difficulty.className = "lxns-score-card__difficulty";
    difficulty.textContent = chart.difficulty;
    header.append(title, difficulty);

    const content = document.createElement("span");
    content.className = "lxns-score-card__content";
    const pool = document.createElement("span");
    pool.className = "lxns-score-card__pool";
    pool.textContent = placement.shortLabel;
    const result = document.createElement("span");
    result.className = "lxns-score-card__result";
    const score = document.createElement("strong");
    score.className = "lxns-score-card__score";
    score.textContent = formatScore(chart.score);
    result.append(createRankImage(chart.score, "card"), score);

    const footer = document.createElement("span");
    footer.className = "lxns-score-card__footer";
    const rating = document.createElement("span");
    rating.className = "lxns-score-card__rating";
    rating.textContent = `Rating ${formatRating(chart.rating)}`;
    const constant = document.createElement("span");
    constant.className = "lxns-score-card__constant";
    constant.setAttribute("aria-label", `谱面定数 ${chart.constant.toFixed(1)}`);
    constant.textContent = chart.constant.toFixed(1);
    footer.append(rating, constant);
    content.append(pool, result, footer);

    button.append(cover, shade, header, content);
    item.append(button);
    return item;
  }

  function renderCompositionList(list, emptyState, charts) {
    const fragment = document.createDocumentFragment();
    charts.forEach((chart) => fragment.append(createCompositionCard(chart)));
    list.replaceChildren(fragment);
    emptyState.hidden = charts.length > 0;
    list.hidden = charts.length === 0;
  }

  function detailValue(value) {
    const text = String(value ?? "").trim();
    return text || "—";
  }

  function createScoreDetailMetric(label, value, wide = false) {
    const metric = document.createElement("div");
    metric.className = wide
      ? "lxns-detail-metric lxns-detail-metric--wide"
      : "lxns-detail-metric";
    const term = document.createElement("dt");
    term.textContent = label;
    const description = document.createElement("dd");
    description.textContent = detailValue(value);
    metric.append(term, description);
    return metric;
  }

  function createCatalogTable(headers, className) {
    const viewport = document.createElement("div");
    viewport.className = "catalog-chart-table-viewport";
    const table = document.createElement("table");
    table.className = className;
    const head = document.createElement("thead");
    const row = document.createElement("tr");
    headers.forEach((header) => {
      const cell = document.createElement("th");
      cell.scope = "col";
      cell.textContent = header;
      row.append(cell);
    });
    head.append(row);
    const body = document.createElement("tbody");
    table.append(head, body);
    viewport.append(table);
    return { viewport, body };
  }

  function catalogCount(value) {
    return Number.isSafeInteger(value) && value >= 0
      ? value.toLocaleString("zh-CN")
      : "—";
  }

  function createCatalogChartDetails(chart) {
    const section = document.createElement("section");
    section.className = "catalog-chart-details";
    const heading = document.createElement("div");
    heading.className = "catalog-chart-details-heading";
    const kicker = document.createElement("span");
    kicker.className = "section-kicker";
    kicker.textContent = "曲库资料";
    const title = document.createElement("h3");
    title.textContent = "谱面详情";
    heading.append(kicker, title);
    section.append(heading);
    const song = catalogSongById(chart.songId) || catalogMatchForChart(chart);
    const charts = Array.isArray(song?.charts)
      ? song.charts.slice().sort((left, right) =>
          DIFFICULTIES.indexOf(normalizeDifficulty(left.difficulty)) -
          DIFFICULTIES.indexOf(normalizeDifficulty(right.difficulty)))
      : [];
    if (charts.length === 0) {
      const empty = document.createElement("p");
      empty.className = "catalog-chart-details-empty";
      empty.textContent = "当前曲库尚未提供这首歌的谱面资料。";
      section.append(empty);
      return section;
    }

    const notes = createCatalogTable(
      ["难度 / 定数", "TOTAL", "TAP", "HOLD", "SLIDE", "AIR", "FLICK", "谱师"],
      "catalog-chart-table catalog-chart-note-table"
    );
    charts.forEach((catalogChart) => {
      const row = document.createElement("tr");
      row.className = difficultyClass(catalogChart.difficulty);
      const difficulty = document.createElement("th");
      difficulty.scope = "row";
      const name = document.createElement("strong");
      name.textContent = catalogChart.difficulty;
      const level = document.createElement("span");
      level.className = "catalog-chart-level";
      level.textContent = [
        catalogChart.displayLevel ? `Lv ${catalogChart.displayLevel}` : "",
        Number.isFinite(catalogChart.constant) ? `定数 ${catalogChart.constant.toFixed(1)}` : "定数 —"
      ].filter(Boolean).join(" · ");
      difficulty.append(name, level);
      row.append(difficulty);
      ["total", "tap", "hold", "slide", "air", "flick"].forEach((key) => {
        const cell = document.createElement("td");
        cell.textContent = catalogCount(catalogChart[key]);
        row.append(cell);
      });
      const charter = document.createElement("td");
      charter.className = "catalog-chart-charter";
      charter.textContent = catalogChart.charter || "—";
      row.append(charter);
      notes.body.append(row);
    });
    section.append(notes.viewport);

    const ratingScores = [
      ["SSS+", 1_009_000], ["SSS", 1_007_500], ["SS+", 1_005_000],
      ["SS", 1_000_000], ["S+", 990_000], ["S", 975_000]
    ];
    const ratingCharts = charts.filter((catalogChart) =>
      ["EXPERT", "MASTER", "ULTIMA"].includes(catalogChart.difficulty)
    );
    if (ratingCharts.length > 0) {
      const ratings = createCatalogTable(
        ["难度", "谱师", ...ratingScores.map(([label]) => label)],
        "catalog-chart-table catalog-chart-rating-table"
      );
      ratingCharts.forEach((catalogChart) => {
        const row = document.createElement("tr");
        row.className = difficultyClass(catalogChart.difficulty);
        const difficulty = document.createElement("th");
        difficulty.scope = "row";
        difficulty.textContent = catalogChart.difficulty;
        const charter = document.createElement("td");
        charter.className = "catalog-chart-charter";
        charter.textContent = catalogChart.charter || "—";
        row.append(difficulty, charter);
        ratingScores.forEach(([, score]) => {
          const cell = document.createElement("td");
          cell.textContent = Number.isFinite(catalogChart.constant)
            ? formatRating(singleRatingHundredths({
                difficulty: catalogChart.difficulty,
                constant: catalogChart.constant,
                score
              }) / 100)
            : "—";
          row.append(cell);
        });
        ratings.body.append(row);
      });
      section.append(ratings.viewport);
    }
    return section;
  }

  function normalizePlayDetails(value) {
    if (!value || typeof value !== "object" || Array.isArray(value)) return null;
    const allowedRootFields = new Set(["fast", "late", "maxCombo", "maxSync", "dxScore", "rating"]);
    if (Object.keys(value).some((key) => !allowedRootFields.has(key))) return null;
    const safeCount = (raw, signed = false) => {
      return Number.isSafeInteger(raw) && (signed || raw >= 0) ? raw : null;
    };
    const metric = (raw) => {
      if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
      if (Object.keys(raw).some((key) => !["current", "maximum"].includes(key))) {
        return null;
      }
      const normalized = {
        current: safeCount(raw.current),
        maximum: safeCount(raw.maximum)
      };
      return Object.values(normalized).some((entry) => entry !== null) ? normalized : null;
    };
    const rating = (() => {
      const raw = value.rating;
      if (!raw || typeof raw !== "object" || Array.isArray(raw) ||
          Object.keys(raw).some((key) => !["value", "playerTotal", "delta"].includes(key))) {
        return null;
      }
      const normalizedRating = {
        value: safeCount(raw.value),
        playerTotal: safeCount(raw.playerTotal),
        delta: safeCount(raw.delta, true)
      };
      return Object.values(normalizedRating).some((entry) => entry !== null)
        ? normalizedRating
        : null;
    })();
    const normalized = {
      fast: safeCount(value.fast),
      late: safeCount(value.late),
      maxCombo: metric(value.maxCombo),
      maxSync: metric(value.maxSync),
      dxScore: metric(value.dxScore),
      rating
    };
    return Object.values(normalized).some((entry) => entry !== null) ? normalized : null;
  }

  function createPlayDetailsSection(playDetails) {
    const section = document.createElement("section");
    section.className = "play-result-details";
    const heading = document.createElement("h3");
    heading.textContent = "本局详细信息";
    section.append(heading);
    if (!playDetails) {
      const empty = document.createElement("p");
      empty.className = "play-result-details-empty";
      empty.textContent = "本局尚未抓取到额外结果信息。";
      section.append(empty);
      return section;
    }
    const grid = document.createElement("dl");
    grid.className = "play-result-details-grid lxns-detail-metrics";
    if (playDetails.fast !== null) grid.append(createScoreDetailMetric("FAST", playDetails.fast));
    if (playDetails.late !== null) grid.append(createScoreDetailMetric("LATE", playDetails.late));
    const appendMetric = (label, metric) => {
      if (!metric) return;
      const current = metric.current === null ? "—" : formatScore(metric.current);
      const maximum = metric.maximum === null ? "" : ` / ${formatScore(metric.maximum)}`;
      grid.append(createScoreDetailMetric(label, `${current}${maximum}`));
    };
    appendMetric("MAX COMBO", playDetails.maxCombo);
    appendMetric("MAX SYNC", playDetails.maxSync);
    appendMetric("DX SCORE", playDetails.dxScore);
    if (playDetails.rating) {
      if (playDetails.rating.value !== null) {
        grid.append(createScoreDetailMetric("单曲 RATING", playDetails.rating.value));
      }
      if (playDetails.rating.playerTotal !== null || playDetails.rating.delta !== null) {
        const total = playDetails.rating.playerTotal === null ? "—" : playDetails.rating.playerTotal;
        const delta = playDetails.rating.delta === null
          ? ""
          : ` (${playDetails.rating.delta >= 0 ? "+" : ""}${playDetails.rating.delta})`;
        grid.append(createScoreDetailMetric("玩家总 RATING", `${total}${delta}`));
      }
    }
    section.append(grid);
    return section;
  }

  function currentCompositionChart(key) {
    return state.calculation?.charts.find((chart) => chart.key === key) || null;
  }

  function createScoreHistoryShell(chart) {
    const section = document.createElement("section");
    section.className = "score-play-history";
    section.dataset.songId = chart.songId;

    const heading = document.createElement("div");
    heading.className = "score-play-history-heading";
    const copy = document.createElement("div");
    const kicker = document.createElement("span");
    kicker.className = "section-kicker";
    kicker.textContent = "最近游玩";
    const title = document.createElement("h3");
    title.textContent = "游玩历史记录";
    copy.append(kicker, title);
    const source = document.createElement("span");
    source.className = "score-play-history-source";
    source.textContent = "中二 NET · 最近窗口";
    heading.append(copy, source);

    const body = document.createElement("div");
    body.className = "score-play-history-body";
    body.setAttribute("role", "status");
    body.setAttribute("aria-live", "polite");
    const empty = document.createElement("p");
    empty.className = "score-play-history-empty";
    empty.textContent = state.authenticated
      ? "正在读取这张谱面的最近游玩记录…"
      : "登录并完成一次中二节奏微信同步后，这里会显示逐局记录。";
    body.append(empty);
    if (!state.authenticated) {
      const syncLink = document.createElement("a");
      syncLink.className = "score-play-history-link";
      syncLink.href = "/sync.html?game=chunithm";
      syncLink.textContent = "前往同步游戏数据";
      body.append(syncLink);
    }
    section.append(heading, body);
    return section;
  }

  function normalizedHistoryStatus(value, aliases) {
    const token = String(value ?? "")
      .normalize("NFKC")
      .trim()
      .toLowerCase()
      .replace(/[\s._:\-–—/\\|+·・'’"()[\]{}【】]+/gu, "");
    return aliases[token] || "";
  }

  function normalizeHistoryRecord(value) {
    if (!value || typeof value !== "object") return null;
    const score = finiteNumber(value.score, Number.NaN);
    const playedAt = String(value.playedAt ?? "").trim();
    const timestamp = Date.parse(playedAt);
    if (!Number.isSafeInteger(score) || score < 0 || score > 1_010_000 ||
        !Number.isFinite(timestamp)) {
      return null;
    }
    const track = finiteNumber(value.track, Number.NaN);
    return {
      sourceRecordId: String(value.sourceRecordId ?? "").trim(),
      songId: String(value.songId ?? "").trim(),
      title: String(value.title ?? "").trim(),
      difficulty: normalizeDifficulty(value.difficulty) || "MASTER",
      score,
      playedAt,
      timestamp,
      track: Number.isSafeInteger(track) && track > 0 && track <= 99 ? track : null,
      clearStatus: normalizedHistoryStatus(value.clearStatus, {
        clear: "CLEAR",
        cleared: "CLEAR",
        failed: "FAILED",
        fail: "FAILED"
      }),
      comboStatus: normalizedHistoryStatus(value.comboStatus, {
        fc: "FULL COMBO",
        fullcombo: "FULL COMBO",
        aj: "ALL JUSTICE",
        alljustice: "ALL JUSTICE",
        ajc: "ALL JUSTICE CRITICAL",
        alljusticecritical: "ALL JUSTICE CRITICAL"
      }),
      rank: String(value.rank ?? "").trim(),
      playDetails: normalizePlayDetails(value.playDetails),
      judgmentDetails: JUDGMENT_DETAILS.normalizeRecord(value, "chunithm")
    };
  }

  function formatHistoryPlayedAt(record) {
    try {
      return new Intl.DateTimeFormat("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false
      }).format(new Date(record.timestamp));
    } catch (error) {
      return record.playedAt;
    }
  }

  function createHistoryRecordRow(record) {
    const item = document.createElement("li");
    item.className = "score-play-history-item";
    const time = document.createElement("time");
    time.className = "score-play-history-time";
    time.dateTime = record.playedAt;
    time.textContent = formatHistoryPlayedAt(record);
    const result = document.createElement("div");
    result.className = "score-play-history-result";
    const score = document.createElement("strong");
    score.textContent = formatScore(record.score);
    const meta = document.createElement("span");
    meta.textContent = [
      record.track ? `TRACK ${record.track}` : "",
      scoreGrade(record.score),
      record.clearStatus,
      record.comboStatus
    ].filter(Boolean).join(" · ");
    result.append(score, meta);
    item.append(
      time,
      result,
      JUDGMENT_DETAILS.createDetails(document, record.judgmentDetails)
    );
    return item;
  }

  function renderHistoryRecords(container, records) {
    if (records.length === 0) {
      const empty = document.createElement("p");
      empty.className = "score-play-history-empty";
      empty.textContent = "这张谱面还没有同步到逐局记录，玩家判定详情也尚未抓取。";
      container.replaceChildren(empty);
      return;
    }
    const sorted = records.slice().sort((left, right) => right.timestamp - left.timestamp);
    const note = document.createElement("p");
    note.className = "score-play-history-note";
    note.textContent = `已保存 ${sorted.length} 局；每局谱面详情只显示官网实际抓取到的游玩数据。`;
    const list = document.createElement("ol");
    list.className = "score-play-history-list";
    sorted.forEach((record) => list.append(createHistoryRecordRow(record)));
    container.replaceChildren(note, list);
    container.removeAttribute("role");
  }

  async function loadScoreHistory(chart, section) {
    if (!state.authenticated) return;
    state.scoreHistoryController?.abort();
    const controller = new AbortController();
    state.scoreHistoryController = controller;
    const sequence = ++state.scoreHistorySequence;
    const body = section.querySelector(".score-play-history-body");
    const query = new URLSearchParams({
      game: "chunithm",
      songId: chart.songId,
      difficulty: chart.difficulty
    });
    try {
      const response = await fetch(`${PLAY_HISTORY_ENDPOINT}?${query}`, {
        method: "GET",
        headers: { Accept: "application/json" },
        signal: controller.signal
      });
      const payload = await readJsonResponse(response);
      if (sequence !== state.scoreHistorySequence || state.scoreDetailKey !== chart.key) return;
      if (!response.ok) throw new Error(apiErrorMessage(payload, "无法读取游玩历史"));
      const records = Array.isArray(payload.records)
        ? payload.records.map(normalizeHistoryRecord).filter(Boolean)
        : [];
      renderHistoryRecords(body, records);
    } catch (error) {
      if (error?.name === "AbortError" || sequence !== state.scoreHistorySequence) return;
      const empty = document.createElement("p");
      empty.className = "score-play-history-empty is-error";
      empty.textContent = error instanceof Error ? error.message : "无法读取游玩历史";
      body.replaceChildren(empty);
    }
  }

  function historyRecordChart(record) {
    return state.calculation?.charts.find((chart) =>
      chart.songId === record.songId && normalizeDifficulty(chart.difficulty) === record.difficulty
    ) || null;
  }

  function createPlayHistoryOverviewItem(record, index) {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.className = "play-history-overview-item";
    button.dataset.playHistoryIndex = String(index);
    button.setAttribute("aria-label", `${record.title || `Song ID ${record.songId}`}，${formatHistoryPlayedAt(record)}，查看本局详情`);
    const song = catalogSongById(record.songId);
    const cover = createCoverImage(song || historyRecordChart(record) || {}, "play-history-overview-cover");
    cover.width = 58;
    cover.height = 58;
    cover.alt = "";

    const copy = document.createElement("span");
    copy.className = "play-history-overview-copy";
    const meta = document.createElement("span");
    meta.className = "play-history-overview-meta";
    const difficulty = document.createElement("span");
    difficulty.className = `difficulty-tag difficulty-${difficultyClass(record.difficulty)}`;
    difficulty.textContent = record.difficulty;
    const id = document.createElement("span");
    id.textContent = `Song ID ${record.songId || "—"}`;
    if (record.track) {
      const track = document.createElement("span");
      track.textContent = `TRACK ${record.track}`;
      meta.append(difficulty, track, id);
    } else {
      meta.append(difficulty, id);
    }
    const title = document.createElement("strong");
    title.className = "play-history-overview-title";
    title.textContent = record.title || song?.title || "未知曲目";
    const time = document.createElement("time");
    time.className = "play-history-overview-time";
    time.dateTime = record.playedAt;
    time.textContent = formatHistoryPlayedAt(record);
    copy.append(meta, title, time);

    const result = document.createElement("span");
    result.className = "play-history-overview-result";
    const score = document.createElement("strong");
    score.textContent = formatScore(record.score);
    const statuses = document.createElement("span");
    statuses.textContent = [scoreGrade(record.score), record.clearStatus, record.comboStatus]
      .filter(Boolean).join(" · ");
    result.append(score, statuses, createRankImage(record.score, "compact"));
    button.append(cover, copy, result);
    item.append(button);
    return item;
  }

  function renderPlayHistoryOverview() {
    const fragment = document.createDocumentFragment();
    state.playHistoryRecords.forEach((record, index) => {
      fragment.append(createPlayHistoryOverviewItem(record, index));
    });
    elements.playHistoryOverviewList.replaceChildren(fragment);
    elements.playHistoryOverviewList.hidden = state.playHistoryRecords.length === 0;
    elements.playHistoryOverviewEmpty.hidden = state.playHistoryRecords.length !== 0;
    elements.playHistoryViewStatus.textContent = state.playHistoryRecords.length > 0
      ? `共显示最近 ${state.playHistoryRecords.length} 局，按游玩时间从新到旧排列。`
      : "当前账号还没有同步到游玩记录。";
  }

  function resetPlayHistoryOverview() {
    state.playHistoryController?.abort();
    state.playHistoryController = null;
    state.playHistorySequence += 1;
    state.playHistoryRecords = [];
    state.playHistoryLoaded = false;
    elements.playHistoryOverviewList.replaceChildren();
    elements.playHistoryOverviewList.hidden = true;
    elements.playHistoryOverviewEmpty.hidden = true;
    elements.playHistoryViewStatus.textContent = "切换到此页后读取游玩记录。";
  }

  async function loadPlayHistoryOverview(force = false) {
    if (!state.authenticated || (state.playHistoryLoaded && !force)) return;
    state.playHistoryController?.abort();
    const controller = new AbortController();
    state.playHistoryController = controller;
    const sequence = ++state.playHistorySequence;
    elements.refreshPlayHistoryButton.disabled = true;
    elements.playHistoryViewStatus.textContent = "正在读取中二节奏游玩记录…";
    try {
      const query = new URLSearchParams({ game: "chunithm", limit: "500" });
      const response = await fetch(`${PLAY_HISTORY_ENDPOINT}?${query}`, {
        method: "GET",
        headers: { Accept: "application/json" },
        signal: controller.signal
      });
      const payload = await readJsonResponse(response);
      if (sequence !== state.playHistorySequence) return;
      if (!response.ok) throw new Error(apiErrorMessage(payload, "无法读取游玩记录"));
      state.playHistoryRecords = Array.isArray(payload.records)
        ? payload.records.map(normalizeHistoryRecord).filter(Boolean)
        : [];
      state.playHistoryLoaded = true;
      renderPlayHistoryOverview();
    } catch (error) {
      if (error?.name === "AbortError" || sequence !== state.playHistorySequence) return;
      elements.playHistoryViewStatus.textContent = error instanceof Error
        ? error.message
        : "无法读取游玩记录";
    } finally {
      if (sequence === state.playHistorySequence) elements.refreshPlayHistoryButton.disabled = false;
    }
  }

  function setScoreView(view) {
    state.scoreView = view === "history" ? "history" : "composition";
    const composition = state.scoreView === "composition";
    elements.scoreCompositionPanel.hidden = !composition;
    elements.playHistoryViewPanel.hidden = composition;
    elements.compositionViewTab.classList.toggle("is-active", composition);
    elements.playHistoryViewTab.classList.toggle("is-active", !composition);
    elements.compositionViewTab.setAttribute("aria-selected", String(composition));
    elements.playHistoryViewTab.setAttribute("aria-selected", String(!composition));
    elements.compositionViewTab.tabIndex = composition ? 0 : -1;
    elements.playHistoryViewTab.tabIndex = composition ? -1 : 0;
    if (!composition) void loadPlayHistoryOverview();
  }

  function renderPlayRecordDetail(record) {
    const song = catalogSongById(record.songId);
    const savedChart = historyRecordChart(record);
    const catalogEntry = catalogChart(song, record.difficulty);
    const constant = catalogEntry?.constant ?? savedChart?.constant ?? null;
    const title = record.title || song?.title || savedChart?.title || "未知曲目";
    elements.scoreDetailDialog.dataset.difficulty = difficultyClass(record.difficulty);
    elements.scoreDetailPool.textContent = record.track ? `游玩记录 · TRACK ${record.track}` : "游玩记录";
    elements.scoreDetailTitle.textContent = "游玩详情";
    elements.editScoreDetailButton.hidden = true;
    elements.deleteScoreDetailButton.hidden = true;
    elements.scoreDetailCover.src = song?.coverUrl || savedChart?.coverUrl || COVER_PLACEHOLDER_URL;
    elements.scoreDetailCover.alt = `${title} 封面`;
    elements.scoreDetailSongId.textContent = catalogEntry?.cid
      ? `Song ID ${record.songId} · CID ${catalogEntry.cid}`
      : `Song ID ${record.songId}`;
    elements.scoreDetailSongTitle.textContent = title;
    elements.scoreDetailSongMeta.textContent = song?.artist || savedChart?.artist || "艺术家信息未提供";
    elements.scoreDetailConstant.textContent = Number.isFinite(constant) ? Number(constant).toFixed(1) : "—";
    elements.scoreDetailScore.textContent = formatScore(record.score);
    elements.scoreDetailRating.textContent = Number.isFinite(constant)
      ? formatRating(singleRatingHundredths({
          difficulty: record.difficulty,
          constant: Number(constant),
          score: record.score
        }) / 100)
      : "—";
    setRankImage(elements.scoreDetailRankImage, record.score);
    elements.scoreDetailMetrics.replaceChildren(
      createScoreDetailMetric("游玩时间", formatHistoryPlayedAt(record)),
      createScoreDetailMetric("难度", record.difficulty),
      createScoreDetailMetric("成绩等级", record.rank || scoreGrade(record.score)),
      createScoreDetailMetric(
        "状态",
        [record.clearStatus, record.comboStatus].filter(Boolean).join(" · ") || "—",
        true
      )
    );
    const judgments = JUDGMENT_DETAILS.createDetails(document, record.judgmentDetails);
    judgments.open = true;
    const sections = [];
    if (record.playDetails) sections.push(createPlayDetailsSection(record.playDetails));
    sections.push(judgments);
    elements.scoreDetailHistory.replaceChildren(...sections);
  }

  function openPlayRecordDetail(record, trigger) {
    state.selectedPlayRecord = record;
    state.playHistoryTrigger = trigger instanceof HTMLElement ? trigger : null;
    state.scoreDetailKey = null;
    state.scoreDetailReturnFocus = null;
    renderPlayRecordDetail(record);
    showModal(elements.scoreDetailDialog);
    window.requestAnimationFrame(() => elements.closeScoreDetailButton.focus());
  }

  function renderScoreDetail(chart) {
    const song = catalogMatchForChart(chart);
    const catalogEntry = catalogChart(song, chart.difficulty);
    const title = song?.title || chart.title;
    const artist = song?.artist || chart.artist;
    const cid = catalogEntry?.cid || chart.cid;
    const version = catalogEntry?.version || chart.version || song?.version;
    const displayLevel = catalogEntry?.displayLevel || chart.displayLevel;
    const catalogConstant = finiteNumber(catalogEntry?.constant, Number.NaN);
    const detailConstant = Number.isFinite(catalogConstant)
      ? catalogConstant
      : chart.constant;
    const placement = compositionPlacement(chart);
    elements.scoreDetailDialog.dataset.difficulty = difficultyClass(chart.difficulty);

    elements.scoreDetailPool.textContent = placement.shortLabel;
    elements.scoreDetailTitle.textContent = "成绩详情";
    const coverSource = song?.coverUrl || chart.coverUrl || COVER_PLACEHOLDER_URL;
    elements.scoreDetailCover.src = coverSource;
    elements.scoreDetailCover.alt = `${title} 封面`;
    elements.scoreDetailSongId.textContent = cid
      ? `Song ID ${chart.songId} · CID ${cid}`
      : `Song ID ${chart.songId}`;
    elements.scoreDetailSongTitle.textContent = title;
    elements.scoreDetailSongMeta.textContent = artist || "艺术家信息未提供";
    elements.scoreDetailConstant.textContent = detailConstant.toFixed(1);
    elements.scoreDetailScore.textContent = formatScore(chart.score);
    elements.scoreDetailRating.textContent = chart.ratingEligible
      ? formatRating(chart.rating)
      : "—";
    setRankImage(elements.scoreDetailRankImage, chart.score);

    const difficultyMetric = displayLevel
      ? `${chart.difficulty} · Lv ${displayLevel}`
      : chart.difficulty;
    elements.scoreDetailMetrics.replaceChildren(
      createScoreDetailMetric("难度", difficultyMetric),
      createScoreDetailMetric("版本", version),
      createScoreDetailMetric("分池", placement.detailLabel, true)
    );
    const history = createScoreHistoryShell(chart);
    elements.scoreDetailHistory.replaceChildren(createCatalogChartDetails(chart), history);
    void loadScoreHistory(chart, history);
  }

  function openScoreDetail(key, returnFocus) {
    const chart = currentCompositionChart(key);
    if (!chart) return;
    state.scoreDetailKey = key;
    state.selectedPlayRecord = null;
    state.playHistoryTrigger = null;
    elements.editScoreDetailButton.hidden = false;
    elements.deleteScoreDetailButton.hidden = false;
    state.scoreDetailReturnFocus = returnFocus instanceof HTMLElement ? returnFocus : null;
    renderScoreDetail(chart);
    showModal(elements.scoreDetailDialog);
    window.requestAnimationFrame(() => elements.closeScoreDetailButton.focus());
  }

  function closeScoreDetail() {
    const playReturnFocus = state.playHistoryTrigger?.isConnected
      ? state.playHistoryTrigger
      : null;
    const detailKey = state.scoreDetailKey;
    const returnFocus = state.scoreDetailReturnFocus?.isConnected
      ? state.scoreDetailReturnFocus
      : Array.from(document.querySelectorAll("button[data-composition-key]"))
          .find((button) => button.dataset.compositionKey === detailKey) || null;
    state.scoreDetailKey = null;
    state.scoreDetailReturnFocus = null;
    state.selectedPlayRecord = null;
    state.playHistoryTrigger = null;
    state.scoreHistoryController?.abort();
    state.scoreHistoryController = null;
    state.scoreHistorySequence++;
    closeModal(elements.scoreDetailDialog);
    const focusTarget = playReturnFocus || returnFocus;
    if (focusTarget?.isConnected) window.requestAnimationFrame(() => focusTarget.focus());
  }

  function renderScoreComposition(calculation) {
    const pools = compositionPools(calculation);
    elements.compositionRatingAverage.textContent = formatRating(calculation.rating, 4);
    elements.compositionRatingMeta.textContent = `B30 + N20 · ${pools.b30.length + pools.n20.length} / 50`;
    elements.compositionB30Average.textContent = formatPoolAverage(pools.b30);
    elements.compositionB30Meta.textContent = `${pools.b30.length} / 30 · 按实际入池成绩平均`;
    elements.compositionS10Average.textContent = formatPoolAverage(pools.s10);
    elements.compositionS10Meta.textContent = `${pools.s10.length} / 10 · 不计总 Rating`;
    elements.compositionN20Average.textContent = formatPoolAverage(pools.n20);
    elements.compositionN20Meta.textContent = `${pools.n20.length} / 20 · 按实际入池成绩平均`;
    elements.compositionB30Count.textContent = `${pools.b30.length} / 30`;
    elements.compositionS10Count.textContent = `${pools.s10.length} / 10 · 不计总 Rating`;
    elements.compositionN20Count.textContent = `${pools.n20.length} / 20`;
    renderCompositionList(elements.compositionB30List, elements.compositionB30Empty, pools.b30);
    renderCompositionList(elements.compositionS10List, elements.compositionS10Empty, pools.s10);
    renderCompositionList(elements.compositionN20List, elements.compositionN20Empty, pools.n20);

    if (state.scoreDetailKey && elements.scoreDetailDialog.open) {
      const chart = currentCompositionChart(state.scoreDetailKey);
      if (chart) renderScoreDetail(chart);
      else closeScoreDetail();
    }
  }

  function render() {
    if (!state.calculation) state.calculation = calculateLocally();
    renderSummary(state.calculation);
    renderScoreComposition(state.calculation);
    updateMutationControls();
  }

  function showModal(dialog) {
    if (typeof dialog.showModal === "function") {
      if (!dialog.open) dialog.showModal();
    } else {
      dialog.setAttribute("open", "");
    }
  }

  function closeModal(dialog) {
    if (typeof dialog.close === "function") {
      if (dialog.open) dialog.close();
    } else {
      dialog.removeAttribute("open");
    }
  }

  function setFormError(message = "") {
    elements.formError.textContent = message;
    elements.formError.hidden = !message;
  }

  function catalogSongById(songId) {
    return state.catalogById.get(String(songId).trim()) || null;
  }

  function exactCatalogSongs(title) {
    return state.catalogByTitle.get(normalizedText(title)) || [];
  }

  function catalogMatchForChart(chart) {
    const byId = catalogSongById(chart.songId);
    if (byId) return byId;
    const matches = exactCatalogSongs(chart.title);
    return matches.length === 1 ? matches[0] : null;
  }

  function catalogChart(song, difficulty) {
    const normalized = normalizeDifficulty(difficulty);
    return song?.charts?.find((chart) => chart.difficulty === normalized) || null;
  }

  function applyCatalogMetadata(chart) {
    const song = catalogMatchForChart(chart);
    if (!song) return chart;
    const songChart = catalogChart(song, chart.difficulty);
    return {
      ...chart,
      cid: songChart?.cid || chart.cid,
      coverUrl: song.coverUrl || chart.coverUrl,
      artist: song.artist || chart.artist,
      genre: song.genre || chart.genre,
      bpm: Number.isFinite(song.bpm) ? song.bpm : chart.bpm,
      displayLevel: songChart?.displayLevel || chart.displayLevel,
      combo: songChart?.combo ?? chart.combo,
      charter: songChart?.charter || chart.charter,
      version: songChart?.version || chart.version || song.version,
      disabled: song.disabled === true
    };
  }

  function hydrateChartsFromCatalog() {
    if (!state.catalogReady) return;
    state.charts = state.charts.map(applyCatalogMetadata);
    state.calculation = calculateLocally();
    render();
  }

  function optionalCatalogCount(value) {
    if (value === null || value === undefined || String(value).trim() === "") return null;
    const number = Number(value);
    return Number.isSafeInteger(number) && number >= 0 ? number : null;
  }

  function normalizeCatalogChart(source, fallbackVersion = "") {
    const chart = source && typeof source === "object" ? source : {};
    const difficulty = normalizeDifficulty(chart.difficulty);
    const constant = finiteNumber(chart.constant ?? chart.ds, Number.NaN);
    if (!difficulty || !Number.isFinite(constant) || constant < 0 || constant > 20) return null;
    return {
      cid: String(chart.cid ?? "").trim(),
      difficulty,
      displayLevel: String(chart.displayLevel ?? chart.level ?? "").trim(),
      constant,
      combo: Math.max(0, Math.trunc(finiteNumber(chart.combo, 0))),
      total: optionalCatalogCount(chart.total ?? chart.combo),
      tap: optionalCatalogCount(chart.tap),
      hold: optionalCatalogCount(chart.hold),
      slide: optionalCatalogCount(chart.slide),
      air: optionalCatalogCount(chart.air),
      flick: optionalCatalogCount(chart.flick),
      charter: String(chart.charter ?? "").trim(),
      version: String(chart.version ?? fallbackVersion).trim()
    };
  }

  function extractCatalog(payload) {
    const latest = Array.isArray(payload?.latestVersions)
      ? payload.latestVersions
      : Array.isArray(payload?.version) ? payload.version : null;
    if (latest) {
      const normalized = latest.map((value) => String(value).trim()).filter(Boolean);
      if (normalized.length > 0) state.latestVersions = Array.from(new Set(normalized));
    }
    const values = Array.isArray(payload)
      ? payload
      : payload && [payload.songs, payload.results, payload.items].find(Array.isArray);
    if (!Array.isArray(values)) return [];
    return values.map((source) => {
      const song = source && typeof source === "object" ? source : {};
      const version = String(song.version ?? "").trim();
      const charts = Array.isArray(song.charts)
        ? song.charts.map((chart) => normalizeCatalogChart(chart, version)).filter(Boolean)
        : [];
      const aliases = Array.isArray(song.aliases)
        ? song.aliases.map((alias) => String(alias).trim()).filter(Boolean).slice(0, 100)
        : [];
      return {
        songId: String(song.songId ?? song.id ?? "").trim(),
        title: String(song.title ?? song.name ?? "").trim(),
        artist: String(song.artist ?? "").trim(),
        genre: String(song.genre ?? "").trim(),
        bpm: finiteNumber(song.bpm, Number.NaN),
        version,
        isNew: song.isNew === true,
        disabled: song.disabled === true,
        aliases,
        coverUrl: normalizeCoverUrl(song.coverUrl ?? song.cover ?? ""),
        charts
      };
    }).filter((song) => song.songId && song.title && song.version && song.charts.length > 0);
  }

  function rebuildCatalogIndexes() {
    state.catalogById = new Map();
    state.catalogByTitle = new Map();
    for (const song of state.catalog) {
      state.catalogById.set(song.songId, song);
      const key = normalizedText(song.title);
      const matches = state.catalogByTitle.get(key) || [];
      matches.push(song);
      state.catalogByTitle.set(key, matches);
    }
  }

  function mergeCatalogSongs(songs) {
    const previousSelection = state.selectedCatalogSong;
    const previousChart = previousSelection
      ? catalogChart(previousSelection, elements.difficultyInput.value)
      : null;
    const previousVersion = elements.versionInput.value;
    const merged = new Map(state.catalog.map((song) => [song.songId, song]));
    songs.forEach((song) => merged.set(song.songId, song));
    state.catalog = Array.from(merged.values());
    rebuildCatalogIndexes();
    if (previousSelection) {
      const refreshed = catalogSongById(previousSelection.songId);
      if (refreshed) {
        state.selectedCatalogSong = refreshed;
        const refreshedChart = catalogChart(
          refreshed, elements.difficultyInput.value);
        const untouchedVersion = previousVersion === previousSelection.version ||
          previousVersion === (previousChart?.version || "");
        if (untouchedVersion) {
          elements.versionInput.value = refreshedChart?.version || refreshed.version;
        }
        updateSelectedSongCard();
      }
    }
  }

  async function loadSongCatalog() {
    try {
      const response = await fetch(CATALOG_ENDPOINT, {
        method: "GET",
        headers: { Accept: "application/json" }
      });
      const payload = await readJsonResponse(response);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      mergeCatalogSongs(extractCatalog(payload));
      state.catalogReady = state.catalog.length > 0;
      hydrateChartsFromCatalog();
      scheduleCalculation();
    } catch (error) {
      state.catalogReady = false;
      console.info("中二节奏曲库暂不可用，仍可手动录入成绩", error);
    } finally {
      void refreshSongCatalogOnline();
    }
  }

  async function refreshSongCatalogOnline() {
    try {
      const response = await fetch(`${CATALOG_ENDPOINT}?online=true`, {
        method: "GET",
        headers: { Accept: "application/json" }
      });
      const payload = await readJsonResponse(response);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      mergeCatalogSongs(extractCatalog(payload));
      state.catalogReady = state.catalog.length > 0;
      hydrateChartsFromCatalog();
      scheduleCalculation();
    } catch (error) {
      console.info("LXNS 中二元数据暂不可用，继续使用离线曲库", error);
    }
  }

  function localSongSearch(query) {
    const needle = normalizedText(query);
    const compactNeedle = normalizeSongKey(query);
    if (!needle || (!compactNeedle && !/[\p{L}\p{N}]/u.test(needle))) return [];
    const compactLength = Array.from(compactNeedle).length;
    const ranked = state.catalog.map((song, index) => {
      const normalizedId = normalizedText(song.songId);
      const titleTerms = [song.title, ...(song.aliases || [])];
      let score = normalizedId === needle
        ? 1000
        : normalizedId.startsWith(needle) ? 640
          : normalizedId.includes(needle) ? 560 : 0;
      for (const term of titleTerms) {
        const normalizedTerm = normalizedText(term);
        const compactTerm = normalizeSongKey(term);
        if (normalizedTerm === needle) score = Math.max(score, 960);
        if (compactNeedle && compactTerm === compactNeedle) score = Math.max(score, 930);
        if (normalizedTerm.startsWith(needle)) score = Math.max(score, 850);
        if (compactLength >= 2 && compactTerm.startsWith(compactNeedle)) {
          score = Math.max(score, 820);
        }
        if (normalizedTerm.includes(needle)) score = Math.max(score, 760);
        if (compactLength >= 2 && compactTerm.includes(compactNeedle)) {
          score = Math.max(score, 730);
        }
        if (compactLength >= 4) {
          const similarity = songKeySimilarity(compactNeedle, compactTerm);
          if (similarity >= 0.58) score = Math.max(score, 500 + similarity * 100);
        }
      }
      const metadataMatches = [song.artist, song.genre, song.version]
        .some((value) => normalizedText(value).includes(needle));
      if (metadataMatches) score = Math.max(score, 400);
      return { song, index, score };
    }).filter((entry) => entry.score > 0)
      .sort((left, right) => right.score - left.score || left.index - right.index);
    return ranked.slice(0, 20).map((entry) => entry.song);
  }

  async function fetchSongSearch(query, signal, shouldHydrate = true) {
    const params = new URLSearchParams({ q: query, limit: "20", online: "true" });
    try {
      const response = await fetch(`${SEARCH_ENDPOINT}?${params}`, {
        method: "GET",
        headers: { Accept: "application/json" },
        signal
      });
      const payload = await readJsonResponse(response);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const songs = extractCatalog(payload);
      mergeCatalogSongs(songs);
      if (songs.length > 0) state.catalogReady = true;
      if (state.catalogReady && shouldHydrate) hydrateChartsFromCatalog();
      return localSongSearch(query);
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") throw error;
      return localSongSearch(query);
    }
  }

  function hideSongSearchResults() {
    elements.songSearchResults.hidden = true;
    elements.songSearchResults.replaceChildren();
    elements.titleInput.setAttribute("aria-expanded", "false");
  }

  function createSongOption(song, onSelect = selectCatalogSong) {
    const option = document.createElement("button");
    option.type = "button";
    option.className = "song-search-option";
    option.setAttribute("role", "option");
    option.dataset.songId = song.songId;
    option.append(createCoverImage(song, "song-option-cover"));
    const copy = document.createElement("span");
    copy.className = "song-option-copy";
    const title = document.createElement("strong");
    title.textContent = song.title;
    const meta = document.createElement("span");
    const difficulties = song.charts.map((chart) => chart.difficulty).join(" / ");
    meta.textContent = [
      `Song ID ${song.songId}`,
      song.artist || "未知艺术家",
      song.version,
      song.disabled ? "已禁用 · 不计 Rating" : "",
      difficulties
    ].filter(Boolean).join(" · ");
    copy.append(title, meta);
    option.append(copy);
    option.addEventListener("click", () => onSelect(song));
    return option;
  }

  function renderSongSearchResults(songs, query) {
    const fragment = document.createDocumentFragment();
    if (songs.length === 0) {
      const empty = document.createElement("p");
      empty.className = "song-search-empty";
      empty.textContent = `没有找到“${query}”，仍可手动填写。`;
      fragment.append(empty);
    } else {
      songs.forEach((song) => fragment.append(createSongOption(song)));
    }
    elements.songSearchResults.replaceChildren(fragment);
    elements.songSearchResults.hidden = false;
    elements.titleInput.setAttribute("aria-expanded", "true");
  }

  function scheduleSongSearch() {
    window.clearTimeout(state.songSearchTimer);
    state.songSearchController?.abort();
    const query = elements.titleInput.value.trim();
    if (!query) {
      hideSongSearchResults();
      return;
    }
    state.songSearchTimer = window.setTimeout(async () => {
      const controller = new AbortController();
      state.songSearchController = controller;
      try {
        const songs = await fetchSongSearch(query, controller.signal);
        if (elements.titleInput.value.trim() === query) renderSongSearchResults(songs, query);
      } catch (error) {
        if (!(error instanceof DOMException && error.name === "AbortError")) {
          renderSongSearchResults(localSongSearch(query), query);
        }
      } finally {
        if (state.songSearchController === controller) state.songSearchController = null;
      }
    }, 220);
  }

  function updateSelectedSongCard() {
    const song = state.selectedCatalogSong;
    if (!song) {
      elements.selectedSong.hidden = true;
      return;
    }
    const chart = catalogChart(song, elements.difficultyInput.value);
    elements.selectedSongCover.src = song.coverUrl || COVER_PLACEHOLDER_URL;
    elements.selectedSongCover.alt = `${song.title} 封面`;
    elements.selectedSongTitle.textContent = song.title;
    const selectedVersion = chart?.version || song.version;
    const selectedIsNew = latestVersionSet().has(normalizedText(selectedVersion));
    elements.selectedSongMeta.textContent = [
      song.artist,
      song.genre,
      Number.isFinite(song.bpm) ? `${song.bpm} BPM` : "",
      selectedVersion,
      selectedIsNew ? "当前版本" : "旧版本",
      song.disabled ? "已禁用 · 不计 Rating" : ""
    ].filter(Boolean).join(" · ");
    elements.selectedChartMeta.textContent = chart
      ? [
          chart.difficulty,
          chart.displayLevel ? `Lv ${chart.displayLevel}` : "",
          `定数 ${chart.constant.toFixed(1)}`,
          chart.cid ? `CID ${chart.cid}` : "",
          chart.combo ? `${chart.combo} COMBO` : "",
          chart.charter ? `谱师 ${chart.charter}` : ""
        ].filter(Boolean).join(" · ")
      : "该歌曲没有当前所选难度。";
    elements.selectedSong.hidden = false;
  }

  function applyCatalogDifficulty(song, difficulty) {
    const chart = catalogChart(song, difficulty);
    if (!chart) return false;
    elements.difficultyInput.value = chart.difficulty;
    elements.cidInput.value = chart.cid;
    elements.constantInput.value = chart.constant.toFixed(1);
    elements.versionInput.value = chart.version || song.version;
    updateSelectedSongCard();
    return true;
  }

  function selectCatalogSong(song, preferredDifficulty = elements.difficultyInput.value) {
    state.selectedCatalogSong = song;
    elements.titleInput.value = song.title;
    elements.songIdInput.value = song.songId;
    elements.versionInput.value = song.version;
    hideSongSearchResults();
    const difficulty = normalizeDifficulty(preferredDifficulty);
    const fallback = catalogChart(song, "MASTER") || song.charts[0];
    if (!applyCatalogDifficulty(song, difficulty) && fallback) {
      applyCatalogDifficulty(song, fallback.difficulty);
    }
    updateSelectedSongCard();
  }

  function resetChartForm() {
    elements.chartForm.reset();
    elements.difficultyInput.value = "MASTER";
    elements.constantInput.value = "13.0";
    elements.scoreInput.value = "1000000";
    elements.versionInput.value = state.latestVersions.at(-1) || "";
    elements.songIdInput.value = "";
    elements.cidInput.value = "";
    state.selectedCatalogSong = null;
    hideSongSearchResults();
    updateSelectedSongCard();
    setFormError();
  }

  function openAddDialog() {
    if (state.chartSaveBusy || !state.authReady) return;
    state.editingKey = null;
    resetChartForm();
    elements.dialogMode.textContent = "新增成绩";
    showModal(elements.chartDialog);
    window.requestAnimationFrame(() => elements.titleInput.focus());
  }

  function openEditDialog(key) {
    if (state.chartSaveBusy || !state.authReady) return;
    const chart = state.charts.find((candidate) => candidate.key === key);
    if (!chart) return;
    state.editingKey = key;
    resetChartForm();
    elements.dialogMode.textContent = "编辑成绩";
    elements.titleInput.value = chart.title;
    elements.songIdInput.value = chart.songId;
    elements.difficultyInput.value = chart.difficulty;
    elements.cidInput.value = chart.cid;
    elements.constantInput.value = chart.constant.toFixed(1);
    elements.scoreInput.value = String(chart.score);
    elements.versionInput.value = chart.version;
    const song = catalogMatchForChart(chart);
    if (song) {
      state.selectedCatalogSong = song;
      updateSelectedSongCard();
    }
    showModal(elements.chartDialog);
    window.requestAnimationFrame(() => elements.titleInput.focus());
  }

  function closeChartDialog() {
    if (!state.chartSaveBusy) {
      state.songSearchController?.abort();
      hideSongSearchResults();
      closeModal(elements.chartDialog);
    }
  }

  function chartFromForm() {
    const song = state.selectedCatalogSong || catalogSongById(elements.songIdInput.value);
    const chartData = song ? catalogChart(song, elements.difficultyInput.value) : null;
    return normalizeChart({
      key: state.editingKey || createKey(),
      songId: elements.songIdInput.value,
      cid: elements.cidInput.value,
      title: elements.titleInput.value,
      difficulty: elements.difficultyInput.value,
      constant: elements.constantInput.value,
      score: elements.scoreInput.value,
      version: elements.versionInput.value,
      coverUrl: song?.coverUrl || "",
      artist: song?.artist || "",
      genre: song?.genre || "",
      bpm: song?.bpm,
      displayLevel: chartData?.displayLevel || "",
      combo: chartData?.combo || 0,
      charter: chartData?.charter || "",
      disabled: song?.disabled === true
    });
  }

  async function handleChartSubmit(event) {
    event.preventDefault();
    setFormError();
    if (state.chartSaveBusy || !state.authReady || !elements.chartForm.reportValidity()) return;
    const chart = chartFromForm();
    if (!isValidChart(chart)) {
      setFormError("请检查 Song ID、曲名、难度、定数、整数分数与稼动版本。WORLD'S END 的定数可以为 0。 ");
      return;
    }
    const duplicate = state.charts.find((candidate) =>
      candidate.key !== state.editingKey && chartIdentity(candidate) === chartIdentity(chart));
    let nextCharts;
    let updatedExisting = false;
    if (state.editingKey) {
      nextCharts = state.charts.map((candidate) => candidate.key === state.editingKey ? chart : candidate);
    } else if (duplicate) {
      updatedExisting = true;
      chart.key = duplicate.key;
      nextCharts = state.charts.map((candidate) => candidate.key === duplicate.key ? chart : candidate);
    } else {
      nextCharts = [...state.charts, chart];
    }
    const saved = await commitCharts(nextCharts);
    if (!saved) return;
    closeModal(elements.chartDialog);
    showToast(state.editingKey || updatedExisting ? "成绩已更新并保存" : "成绩已添加并保存");
    state.editingKey = null;
  }

  function requestDelete(key) {
    if (state.chartSaveBusy || !state.authReady) return;
    const chart = state.charts.find((candidate) => candidate.key === key);
    if (!chart) return;
    state.deletingKey = key;
    elements.deleteChartName.textContent = `${chart.title} · ${chart.difficulty}`;
    showModal(elements.deleteDialog);
  }

  async function confirmDelete() {
    if (!state.deletingKey || state.chartSaveBusy || !state.authReady) return;
    const nextCharts = state.charts.filter((chart) => chart.key !== state.deletingKey);
    const saved = await commitCharts(nextCharts);
    if (!saved) return;
    state.deletingKey = null;
    closeModal(elements.deleteDialog);
    showToast("成绩已删除并保存");
  }

  function cancelDelete() {
    if (!state.chartSaveBusy) {
      state.deletingKey = null;
      closeModal(elements.deleteDialog);
    }
  }

  function revisionFromPayload(payload) {
    const revision = payload?.revision;
    if (typeof revision === "string" && revision.trim()) return revision;
    if (typeof revision === "number" && Number.isSafeInteger(revision) && revision >= 0) return revision;
    throw new Error("用户数据响应缺少有效 revision");
  }

  function handleSessionExpired(message = "登录会话已失效，请重新登录") {
    state.sessionSequence += 1;
    state.authenticated = false;
    state.user = null;
    state.userRevision = null;
    state.authReady = true;
    state.authBusy = false;
    state.chartSaveBusy = false;
    state.charts = [];
    resetPlayHistoryOverview();
    renderAccount();
    scheduleCalculation();
    requireAuthentication(message);
    showToast(message);
  }

  async function saveRemoteCharts(charts, sessionToken) {
    const expectedUserId = String(state.user?.id ?? "");
    const response = await fetch(USER_CHARTS_ENDPOINT, {
      method: "PUT",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json;charset=UTF-8"
      },
      body: JSON.stringify({
        expectedUserId,
        revision: state.userRevision,
        charts: charts.map(chartPersistenceValue)
      })
    });
    const payload = await readJsonResponse(response);
    if (sessionToken !== state.sessionSequence || !state.authenticated) {
      throw new DOMException("用户已切换", "AbortError");
    }
    if (response.status === 401) {
      handleSessionExpired();
      throw new DOMException("登录会话已失效", "AbortError");
    }
    if (response.status === 409) {
      throw new RevisionConflictError(apiErrorMessage(payload, "用户数据已在其他页面更新"));
    }
    if (!response.ok || payload.saved === false) {
      throw new Error(apiErrorMessage(payload, `云端保存失败（HTTP ${response.status}）`));
    }
    if (String(payload.userId ?? "") !== expectedUserId) {
      handleSessionExpired("服务器返回的用户身份不一致，请重新登录");
      throw new DOMException("用户身份不一致", "AbortError");
    }
    state.userRevision = revisionFromPayload(payload);
  }

  async function commitCharts(nextCharts) {
    if (!Array.isArray(nextCharts) || nextCharts.length > MAX_CHARTS) {
      showToast(`中二节奏成绩最多保存 ${MAX_CHARTS} 条`);
      return false;
    }
    if (state.chartSaveBusy) {
      showToast("上一项修改仍在保存，请稍候");
      return false;
    }
    if (!state.authenticated) {
      requireAuthentication("请先登录后再修改成绩");
      return false;
    }
    if (!state.authReady) {
      showToast("正在确认用户与成绩库，请稍候");
      return false;
    }
    const sessionToken = state.sessionSequence;
    state.chartSaveBusy = true;
    updateMutationControls();
    try {
      await saveRemoteCharts(nextCharts, sessionToken);
      if (sessionToken !== state.sessionSequence || !state.authenticated) return false;
      state.charts = nextCharts.map(applyCatalogMetadata);
      scheduleCalculation();
      return true;
    } catch (error) {
      if (error instanceof RevisionConflictError &&
          sessionToken === state.sessionSequence && state.authenticated) {
        state.chartSaveBusy = false;
        const synchronized = await resynchronizeSessionAfterConflict();
        showToast(synchronized
          ? "服务器会话或数据已变化，本次保存未覆盖；已同步当前用户的最新中二成绩"
          : "检测到会话或版本冲突，请重新登录");
      } else if (!(error instanceof DOMException && error.name === "AbortError")) {
        showToast(error instanceof Error ? error.message : "保存失败，成绩库未发生变更");
      }
      return false;
    } finally {
      if (sessionToken === state.sessionSequence) {
        state.chartSaveBusy = false;
        updateMutationControls();
      }
    }
  }

  function apiErrorMessage(payload, fallback) {
    if (payload && typeof payload === "object") {
      for (const key of ["error", "message", "detail"]) {
        if (typeof payload[key] === "string" && payload[key].trim()) return payload[key].trim();
      }
    }
    return fallback;
  }

  async function readJsonResponse(response) {
    const text = await response.text();
    if (!text) return {};
    try {
      return JSON.parse(text);
    } catch (error) {
      throw new Error("后端返回了无法解析的响应");
    }
  }

  function userFromPayload(payload) {
    const nested = payload && typeof payload.user === "object" ? payload.user : null;
    const username = String(nested?.username ?? payload?.username ?? "").trim();
    if (!username) return null;
    return {
      id: String(nested?.id ?? payload?.userId ?? username),
      username,
      displayName: String(nested?.displayName ?? payload?.displayName ?? username).trim() || username
    };
  }

  function updateMutationControls() {
    const unavailable = state.chartSaveBusy || !state.authReady || !state.authenticated;
    elements.addButton.disabled = unavailable;
    elements.importButton.disabled = unavailable;
    elements.exportButton.disabled = unavailable;
    elements.confirmDeleteButton.disabled = unavailable;
    elements.editScoreDetailButton.disabled = unavailable;
    elements.deleteScoreDetailButton.disabled = unavailable;
    elements.accountButton.disabled = state.authBusy || state.chartSaveBusy || !state.authReady;
    elements.logoutButton.disabled = state.authBusy || state.chartSaveBusy;
    const submit = elements.chartForm.querySelector("button[type='submit']");
    if (submit) submit.disabled = unavailable;
  }

  function renderAccount() {
    const loggedIn = state.authenticated && state.user;
    const profileReady = document.documentElement.dataset.profileReady === "true";
    elements.accountLabel.textContent = loggedIn
      ? state.user.displayName
      : state.authReady ? "登录 / 注册" : "检查账户…";
    const avatar = elements.accountButton.querySelector(".account-avatar");
    if (avatar && (!loggedIn || !profileReady)) {
      avatar.textContent = loggedIn
        ? Array.from(state.user.displayName)[0]?.toLocaleUpperCase("zh-CN") || "用"
        : "用";
    }
    elements.accountButton.title = loggedIn
      ? `当前用户：${state.user.username}`
      : "登录或注册用户";
    elements.accountButton.setAttribute("aria-label", loggedIn
      ? `当前用户 ${state.user.displayName}，打开个人资料`
      : "登录或注册");
    if (loggedIn) {
      elements.accountButton.removeAttribute("aria-haspopup");
    } else {
      elements.accountButton.setAttribute("aria-haspopup", "dialog");
    }
    elements.logoutButton.hidden = !loggedIn;
    if (loggedIn && !profileReady) {
      void window.B50ProfileTheme?.refresh({ force: true });
    }
    updateMutationControls();
  }

  function setAuthError(message = "") {
    elements.authError.textContent = message;
    elements.authError.hidden = !message;
  }

  function setAuthMode(mode) {
    state.authMode = mode === "register" ? "register" : "login";
    const registering = state.authMode === "register";
    elements.authDialogTitle.textContent = registering ? "注册新用户" : "登录";
    elements.authSubmitButton.textContent = registering ? "注册并登录" : "登录";
    elements.authIdentityLabel.textContent = registering ? "用户名" : "用户名或邮箱";
    elements.authPassword.autocomplete = registering ? "new-password" : "current-password";
    authEmailController.setActive(registering);
    elements.authNote.textContent = registering
      ? "密码至少 8 位，并需填写邮箱收到的 6 位验证码。注册成功后可迁移旧版本地成绩。"
      : "本站不提供游客模式。登录后才能查看和修改个人成绩。";
    elements.authDialog.querySelectorAll("[data-auth-mode]").forEach((button) => {
      const active = button.dataset.authMode === state.authMode;
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-selected", String(active));
    });
    setAuthError();
  }

  function redirectIfEmailRequired(payload) {
    const required = payload?.emailRequired === true || payload?.user?.emailRequired === true;
    if (!required) return false;
    window.location.replace("/profile.html?bindEmail=1");
    return true;
  }

  function finishAuthStartup() {
    document.body.classList.remove("auth-checking");
    elements.authStartupMask.hidden = true;
  }

  function openAuthDialog() {
    if (state.authenticated && state.user) {
      window.location.assign("/profile.html");
      return;
    }
    elements.authForm.reset();
    setAuthMode(state.authMode);
    showModal(elements.authDialog);
    window.requestAnimationFrame(() => elements.authUsername.focus());
  }

  function requireAuthentication(message = "") {
    document.body.classList.add("auth-required");
    elements.authDialog.dataset.required = "true";
    if (!elements.authDialog.open) {
      elements.authForm.reset();
      setAuthMode(state.authMode);
      showModal(elements.authDialog);
    }
    if (message) setAuthError(message);
    window.requestAnimationFrame(() => elements.authUsername.focus());
  }

  function releaseAuthenticationGate() {
    document.body.classList.remove("auth-required");
    delete elements.authDialog.dataset.required;
    closeModal(elements.authDialog);
  }

  function closeAuthDialog() {
    if (!state.authBusy && state.authenticated) closeModal(elements.authDialog);
  }

  async function loadUserCharts(sessionToken, offerMigration = true) {
    state.authReady = false;
    renderAccount();
    try {
      const response = await fetch(USER_CHARTS_ENDPOINT, {
        method: "GET",
        headers: { Accept: "application/json" }
      });
      const payload = await readJsonResponse(response);
      if (sessionToken !== state.sessionSequence || !state.authenticated) return false;
      if (response.status === 401) {
        handleSessionExpired();
        throw new DOMException("登录会话已失效", "AbortError");
      }
      if (!response.ok || !Array.isArray(payload.charts)) {
        throw new Error(apiErrorMessage(payload, `读取中二节奏用户成绩失败（HTTP ${response.status}）`));
      }
      const expectedUserId = String(state.user?.id ?? "");
      if (String(payload.userId ?? "") !== expectedUserId) {
        handleSessionExpired("服务器返回的用户身份不一致，请重新登录");
        throw new DOMException("用户身份不一致", "AbortError");
      }
      state.userRevision = revisionFromPayload(payload);
      let charts = normalizeChartCollection(payload.charts).map(applyCatalogMetadata);
      const guestCharts = loadLegacyLocalCharts().map(applyCatalogMetadata);
      if (offerMigration && charts.length === 0 && guestCharts.length > 0) {
        const migrate = window.confirm(
          `用户「${state.user.displayName}」的中二节奏成绩库为空。\n\n` +
          `检测到旧版浏览器本地保存的 ${guestCharts.length} 条中二成绩，是否复制到该用户？\n\n` +
          "选择“取消”会保留旧数据，但本站不会在未登录状态下使用它。"
        );
        if (sessionToken !== state.sessionSequence || !state.authenticated) return false;
        if (migrate) {
          await saveRemoteCharts(guestCharts, sessionToken);
          charts = guestCharts;
          showToast(`已将 ${guestCharts.length} 条中二旧版本地成绩复制到用户空间`);
        }
      }
      if (sessionToken !== state.sessionSequence || !state.authenticated) return false;
      state.charts = charts;
      state.authReady = true;
      renderAccount();
      scheduleCalculation();
      return true;
    } catch (error) {
      if (sessionToken !== state.sessionSequence) return false;
      if (error instanceof DOMException && error.name === "AbortError") return false;
      if (error instanceof RevisionConflictError) return await resynchronizeSessionAfterConflict();
      state.charts = [];
      state.authReady = false;
      renderAccount();
      scheduleCalculation();
      showToast(error instanceof Error ? error.message : "无法读取中二节奏用户成绩库");
      return false;
    }
  }

  async function resynchronizeSessionAfterConflict() {
    const sessionToken = ++state.sessionSequence;
    state.authReady = false;
    state.chartSaveBusy = false;
    state.userRevision = null;
    state.charts = [];
    state.calculation = calculateLocally();
    renderAccount();
    render();
    try {
      const response = await fetch(AUTH_STATUS_ENDPOINT, {
        method: "GET",
        headers: { Accept: "application/json" }
      });
      const payload = await readJsonResponse(response);
      if (sessionToken !== state.sessionSequence) return false;
      const user = userFromPayload(payload);
      if (response.ok && payload.authenticated === true && user && redirectIfEmailRequired(payload)) {
        return false;
      }
      if (!response.ok || payload.authenticated !== true || !user) {
        handleSessionExpired("服务器会话已变化，请重新登录");
        return false;
      }
      state.authenticated = true;
      state.user = user;
      renderAccount();
      return await loadUserCharts(sessionToken, false);
    } catch (error) {
      if (sessionToken === state.sessionSequence) {
        handleSessionExpired("无法重新确认服务器会话，请重新登录");
      }
      return false;
    }
  }

  async function checkAuthStatus() {
    const sessionToken = ++state.sessionSequence;
    try {
      const response = await fetch(AUTH_STATUS_ENDPOINT, {
        method: "GET",
        headers: { Accept: "application/json" }
      });
      const payload = await readJsonResponse(response);
      if (sessionToken !== state.sessionSequence) return;
      const user = userFromPayload(payload);
      if (response.ok && payload.authenticated === true && user && redirectIfEmailRequired(payload)) {
        return;
      }
      finishAuthStartup();
      if (response.ok && payload.authenticated === true && user) {
        state.authenticated = true;
        state.user = user;
        state.userRevision = null;
        releaseAuthenticationGate();
        await loadUserCharts(sessionToken, true);
      } else {
        state.authenticated = false;
        state.user = null;
        state.userRevision = null;
        state.authReady = true;
        state.charts = [];
        renderAccount();
        scheduleCalculation();
        requireAuthentication();
      }
    } catch (error) {
      if (sessionToken !== state.sessionSequence) return;
      finishAuthStartup();
      state.authenticated = false;
      state.user = null;
      state.userRevision = null;
      state.authReady = true;
      state.charts = [];
      renderAccount();
      scheduleCalculation();
      requireAuthentication("账户服务暂不可用，请确认 JDK 后端已经启动");
      console.info("账户服务暂不可用，等待登录", error);
    }
  }

  async function handleAuthSubmit(event) {
    event.preventDefault();
    setAuthError();
    if (state.authBusy || !elements.authForm.reportValidity()) return;
    const username = elements.authUsername.value.trim();
    const password = elements.authPassword.value;
    const registering = state.authMode === "register";
    if (!username || Array.from(username).length > 128) {
      setAuthError("请输入用户名或邮箱。");
      return;
    }
    if (registering && !/^[\p{L}\p{N}][\p{L}\p{N}_.-]{2,31}$/u.test(username)) {
      setAuthError("用户名须为 3–32 位字母或数字，可包含下划线、连字符和句点。");
      return;
    }
    let registrationFields = {};
    if (registering) {
      try {
        registrationFields = authEmailController.registrationFields();
      } catch (error) {
        setAuthError(error instanceof Error ? error.message : "请填写邮箱验证码。");
        return;
      }
    }
    const passwordLength = Array.from(password).length;
    if (passwordLength < 8 || passwordLength > 128) {
      setAuthError("密码须为 8–128 个字符。");
      return;
    }
    state.authBusy = true;
    elements.authSubmitButton.disabled = true;
    elements.authSubmitButton.textContent = state.authMode === "register" ? "正在注册…" : "正在登录…";
    renderAccount();
    try {
      const endpoint = state.authMode === "register" ? AUTH_REGISTER_ENDPOINT : AUTH_LOGIN_ENDPOINT;
      const response = await fetch(endpoint, {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json;charset=UTF-8"
        },
        body: JSON.stringify({ username, password, ...registrationFields })
      });
      const payload = await readJsonResponse(response);
      const user = userFromPayload(payload);
      if (payload.authenticated === true && user && redirectIfEmailRequired(payload)) return;
      if (!response.ok || payload.authenticated !== true || !user) {
        throw new Error(apiErrorMessage(payload, `${state.authMode === "register" ? "注册" : "登录"}失败（HTTP ${response.status}）`));
      }
      const sessionToken = ++state.sessionSequence;
      state.authenticated = true;
      state.user = user;
      state.userRevision = null;
      state.authReady = false;
      releaseAuthenticationGate();
      renderAccount();
      const loaded = await loadUserCharts(sessionToken, true);
      if (loaded && state.user) showToast(`已登录：${state.user.displayName}`);
    } catch (error) {
      setAuthError(error instanceof Error ? error.message : "账户操作失败，请稍后重试");
    } finally {
      state.authBusy = false;
      elements.authSubmitButton.disabled = false;
      const registering = state.authMode === "register";
      elements.authSubmitButton.textContent = registering ? "注册并登录" : "登录";
      elements.authPassword.autocomplete = registering ? "new-password" : "current-password";
      renderAccount();
    }
  }

  async function logout() {
    if (state.authBusy || state.chartSaveBusy || !state.authenticated) {
      if (state.chartSaveBusy) showToast("成绩仍在保存，请稍候再退出");
      return;
    }
    state.authBusy = true;
    const sessionToken = state.sessionSequence;
    renderAccount();
    try {
      const response = await fetch(AUTH_LOGOUT_ENDPOINT, {
        method: "POST",
        headers: { Accept: "application/json" }
      });
      const payload = await readJsonResponse(response);
      if (sessionToken !== state.sessionSequence) return;
      if (response.status === 401) {
        handleSessionExpired();
        return;
      }
      if (!response.ok || payload.authenticated === true) {
        throw new Error(apiErrorMessage(payload, `退出失败（HTTP ${response.status}）`));
      }
      state.sessionSequence += 1;
      state.authenticated = false;
      state.user = null;
      state.userRevision = null;
      state.authReady = true;
      state.chartSaveBusy = false;
      state.charts = [];
      window.B50ProfileTheme?.clear();
      resetPlayHistoryOverview();
      renderAccount();
      scheduleCalculation();
      requireAuthentication();
      showToast("已退出，请重新登录后使用");
    } catch (error) {
      showToast(error instanceof Error ? error.message : "退出失败");
    } finally {
      state.authBusy = false;
      renderAccount();
    }
  }

  function parseCsv(text) {
    const rows = [];
    let row = [];
    let field = "";
    let quoted = false;
    for (let index = 0; index < text.length; index += 1) {
      const char = text[index];
      if (quoted) {
        if (char === '"' && text[index + 1] === '"') {
          field += '"';
          index += 1;
        } else if (char === '"') {
          quoted = false;
        } else {
          field += char;
        }
      } else if (char === '"') {
        quoted = true;
      } else if (char === ",") {
        row.push(field);
        field = "";
      } else if (char === "\n") {
        row.push(field.replace(/\r$/u, ""));
        if (row.some((cell) => cell.trim())) rows.push(row);
        row = [];
        field = "";
        if (rows.length > MAX_CSV_ROWS + 1) throw new Error(`CSV 最多支持 ${MAX_CSV_ROWS} 条成绩`);
      } else {
        field += char;
      }
    }
    if (quoted) throw new Error("CSV 引号没有正确闭合");
    row.push(field.replace(/\r$/u, ""));
    if (row.some((cell) => cell.trim())) rows.push(row);
    if (rows.length > MAX_CSV_ROWS + 1) {
      throw new Error(`CSV 最多支持 ${MAX_CSV_ROWS} 条成绩`);
    }
    return rows;
  }

  function csvHeaderIndex(headers, names) {
    const normalized = headers.map((header) => normalizedText(header).replace(/[\s_-]+/gu, ""));
    return normalized.findIndex((header) => names.includes(header));
  }

  function restoredCsvText(value) {
    const text = String(value ?? "").trim();
    return /^'[=+\-@]/u.test(text) ? text.slice(1) : text;
  }

  async function importCsv(file) {
    if (!(file instanceof File)) return;
    if (file.size <= 0 || file.size > MAX_CSV_BYTES) {
      showToast(`CSV 文件须小于 ${Math.round(MAX_CSV_BYTES / 1024 / 1024)} MB`);
      return;
    }
    try {
      const rows = parseCsv(await file.text());
      if (rows.length < 2) throw new Error("CSV 没有可导入的成绩行");
      const headers = rows[0];
      const indexes = {
        songId: csvHeaderIndex(headers, ["songid", "id", "歌曲id", "曲目id"]),
        cid: csvHeaderIndex(headers, ["cid", "谱面cid", "chartid"]),
        title: csvHeaderIndex(headers, ["title", "曲名", "歌曲名"]),
        difficulty: csvHeaderIndex(headers, ["difficulty", "难度", "難度"]),
        constant: csvHeaderIndex(headers, ["constant", "ds", "定数", "定數"]),
        score: csvHeaderIndex(headers, ["score", "分数", "分數"]),
        version: csvHeaderIndex(headers, ["version", "版本", "稼动版本", "稼動版本"]),
        coverUrl: csvHeaderIndex(headers, ["coverurl", "cover", "封面"])
      };
      for (const required of ["songId", "title", "difficulty", "constant", "score", "version"]) {
        if (indexes[required] < 0) throw new Error(`CSV 缺少必需列：${required}`);
      }
      const merged = new Map(state.charts.map((chart) => [chartIdentity(chart), chart]));
      let added = 0;
      let updated = 0;
      rows.slice(1).forEach((row, rowIndex) => {
        const value = (name) => indexes[name] >= 0 ? row[indexes[name]] ?? "" : "";
        const chart = normalizeChart({
          songId: restoredCsvText(value("songId")),
          cid: restoredCsvText(value("cid")),
          title: restoredCsvText(value("title")),
          difficulty: restoredCsvText(value("difficulty")),
          constant: value("constant"),
          score: value("score"),
          version: restoredCsvText(value("version")),
          coverUrl: value("coverUrl")
        });
        if (!isValidChart(chart)) throw new Error(`CSV 第 ${rowIndex + 2} 行数据无效`);
        const identity = chartIdentity(chart);
        const existing = merged.get(identity);
        if (existing) {
          chart.key = existing.key;
          updated += 1;
        } else {
          added += 1;
        }
        merged.set(identity, applyCatalogMetadata(chart));
      });
      if (merged.size > MAX_CHARTS) {
        throw new Error(`中二节奏成绩最多保存 ${MAX_CHARTS} 条`);
      }
      const saved = await commitCharts(Array.from(merged.values()));
      if (saved) showToast(`CSV 导入完成：新增 ${added} 条，更新 ${updated} 条`);
    } catch (error) {
      showToast(error instanceof Error ? error.message : "CSV 导入失败");
    } finally {
      elements.csvInput.value = "";
    }
  }

  function csvCell(value, protectFormula = true) {
    let text = String(value ?? "");
    if (protectFormula && /^[=+\-@]/u.test(text)) text = `'${text}`;
    return `"${text.replaceAll('"', '""')}"`;
  }

  function exportCsv() {
    const headers = ["songId", "cid", "title", "difficulty", "constant", "score", "version", "coverUrl"];
    const rows = state.charts.map((chart) => [
      csvCell(chart.songId),
      csvCell(chart.cid),
      csvCell(chart.title),
      csvCell(chart.difficulty),
      csvCell(chart.constant.toFixed(1), false),
      csvCell(chart.score, false),
      csvCell(chart.version),
      csvCell(chart.coverUrl)
    ].join(","));
    const content = `\uFEFF${headers.join(",")}\r\n${rows.join("\r\n")}`;
    const url = URL.createObjectURL(new Blob([content], { type: "text/csv;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = `chunithm-b30-n20-${new Date().toISOString().slice(0, 10)}.csv`;
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
    showToast("中二节奏 CSV 已导出");
  }

  function showToast(message) {
    window.clearTimeout(state.toastTimer);
    elements.toast.textContent = String(message);
    elements.toast.hidden = false;
    state.toastTimer = window.setTimeout(() => {
      elements.toast.hidden = true;
    }, 3600);
  }

  [elements.compositionViewTab, elements.playHistoryViewTab].forEach((tab, index, tabs) => {
    tab.addEventListener("click", () => setScoreView(tab.dataset.scoreView));
    tab.addEventListener("keydown", (event) => {
      if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
      event.preventDefault();
      const next = tabs[index === 0 ? 1 : 0];
      setScoreView(next.dataset.scoreView);
      next.focus();
    });
  });
  elements.refreshPlayHistoryButton.addEventListener("click", () => {
    void loadPlayHistoryOverview(true);
  });
  elements.playHistoryOverviewList.addEventListener("click", (event) => {
    const button = event.target.closest("button[data-play-history-index]");
    if (!button) return;
    const record = state.playHistoryRecords[Number(button.dataset.playHistoryIndex)];
    if (record) openPlayRecordDetail(record, button);
  });

  elements.scoreCompositionPanel.addEventListener("click", (event) => {
    const button = event.target.closest("button[data-composition-key]");
    if (button) openScoreDetail(button.dataset.compositionKey, button);
  });
  elements.editScoreDetailButton.addEventListener("click", () => {
    const key = state.scoreDetailKey;
    if (!key) return;
    closeScoreDetail();
    openEditDialog(key);
  });
  elements.deleteScoreDetailButton.addEventListener("click", () => {
    const key = state.scoreDetailKey;
    if (!key) return;
    closeScoreDetail();
    requestDelete(key);
  });
  elements.closeScoreDetailButton.addEventListener("click", closeScoreDetail);
  elements.scoreDetailDialog.addEventListener("click", (event) => {
    if (event.target === elements.scoreDetailDialog) closeScoreDetail();
  });
  elements.scoreDetailDialog.addEventListener("cancel", (event) => {
    event.preventDefault();
    closeScoreDetail();
  });
  elements.scoreDetailCover.addEventListener("error", () => {
    if (!elements.scoreDetailCover.src.endsWith(COVER_PLACEHOLDER_URL)) {
      elements.scoreDetailCover.src = COVER_PLACEHOLDER_URL;
    }
  });
  elements.addButton.addEventListener("click", openAddDialog);
  elements.closeDialogButton.addEventListener("click", closeChartDialog);
  elements.cancelDialogButton.addEventListener("click", closeChartDialog);
  elements.chartForm.addEventListener("submit", handleChartSubmit);
  elements.titleInput.addEventListener("input", () => {
    if (state.selectedCatalogSong && elements.titleInput.value !== state.selectedCatalogSong.title) {
      state.selectedCatalogSong = null;
      updateSelectedSongCard();
    }
    scheduleSongSearch();
  });
  elements.songIdInput.addEventListener("change", () => {
    const song = catalogSongById(elements.songIdInput.value);
    if (song) selectCatalogSong(song, elements.difficultyInput.value);
  });
  elements.difficultyInput.addEventListener("change", () => {
    const song = state.selectedCatalogSong || catalogSongById(elements.songIdInput.value);
    if (song) {
      state.selectedCatalogSong = song;
      applyCatalogDifficulty(song, elements.difficultyInput.value);
    }
  });
  elements.selectedSongCover.addEventListener("error", () => {
    if (!elements.selectedSongCover.src.endsWith(COVER_PLACEHOLDER_URL)) {
      elements.selectedSongCover.src = COVER_PLACEHOLDER_URL;
    }
  });
  document.addEventListener("pointerdown", (event) => {
    if (!elements.titleInput.contains(event.target) && !elements.songSearchResults.contains(event.target)) {
      hideSongSearchResults();
    }
  });
  elements.cancelDeleteButton.addEventListener("click", cancelDelete);
  elements.confirmDeleteButton.addEventListener("click", confirmDelete);
  elements.importButton.addEventListener("click", () => elements.csvInput.click());
  elements.csvInput.addEventListener("change", () => importCsv(elements.csvInput.files?.[0]));
  elements.exportButton.addEventListener("click", exportCsv);
  window.addEventListener("b50:profile-applied", (event) => {
    const displayName = String(event.detail?.displayName || "").trim();
    if (!displayName || !state.authenticated || !state.user) return;
    state.user.displayName = displayName;
    elements.accountLabel.textContent = displayName;
    elements.accountButton.title = `当前用户：${state.user.username}`;
    elements.accountButton.setAttribute(
      "aria-label", `当前用户 ${displayName}，打开个人资料`
    );
  });
  elements.accountButton.addEventListener("click", openAuthDialog);
  elements.logoutButton.addEventListener("click", logout);
  elements.closeAuthDialogButton.addEventListener("click", closeAuthDialog);
  elements.cancelAuthButton.addEventListener("click", closeAuthDialog);
  elements.authForm.addEventListener("submit", handleAuthSubmit);
  elements.authDialog.addEventListener("cancel", (event) => {
    if (!state.authenticated || state.authBusy) event.preventDefault();
  });
  elements.authDialog.querySelectorAll("[data-auth-mode]").forEach((button) => {
    button.addEventListener("click", () => setAuthMode(button.dataset.authMode));
  });

  state.calculation = calculateLocally();
  renderAccount();
  render();
  loadSongCatalog();
  checkAuthStatus();
})();
