(() => {
  "use strict";

  const STORAGE_KEY = "maimai-b50-workbench:v1";
  const API_ENDPOINT = "/api/b50/calculate";
  const AUTH_STATUS_ENDPOINT = "/api/auth/status";
  const AUTH_LOGOUT_ENDPOINT = "/api/auth/logout";
  const USER_CHARTS_ENDPOINT = "/api/user/charts";
  const PLAY_HISTORY_ENDPOINT = "/api/history";
  const SONG_CATALOG_ENDPOINT = "/api/songs/catalog";
  const SONG_SEARCH_ENDPOINT = "/api/songs/search";
  const COVER_PLACEHOLDER_URL = "/song-catalog/cover-placeholder.svg";
  const JUDGMENT_DETAILS = globalThis.RhythmJudgmentDetails;
  if (!JUDGMENT_DETAILS) throw new Error("Judgment detail helper failed to load");
  const DIFFICULTIES = ["BASIC", "ADVANCED", "EXPERT", "MASTER", "RE:MASTER"];
  const CHART_TYPES = ["standard", "dx"];
  const COMBO_STATUSES = new Set(["", "fc", "fcp", "ap", "app"]);
  const SYNC_STATUSES = new Set(["", "sync", "fs", "fsp", "fsd", "fsdp"]);
  const DX_RATING_FRAMES = new Set([
    "white", "blue", "green", "yellow", "red", "purple",
    "bronze", "silver", "gold", "platinum", "rainbow"
  ]);
  const LXNS_MAIMAI_ASSET_ROOT = "/assets/lxns/maimai";
  const LXNS_BLANK_ICON_URL = `${LXNS_MAIMAI_ASSET_ROOT}/music_icon/blank.webp`;
  const COMBO_STATUS_LABELS = Object.freeze({
    "": "未记录 Full Combo 状态",
    fc: "FULL COMBO",
    fcp: "FULL COMBO +",
    ap: "ALL PERFECT",
    app: "ALL PERFECT +"
  });
  const SYNC_STATUS_LABELS = Object.freeze({
    "": "未记录同步状态",
    sync: "SYNC",
    fs: "FULL SYNC",
    fsp: "FULL SYNC +",
    fsd: "FULL SYNC DX",
    fsdp: "FULL SYNC DX +"
  });
  const ACHIEVEMENT_RANK_LABELS = Object.freeze({
    d: "D",
    c: "C",
    b: "B",
    bb: "BB",
    bbb: "BBB",
    a: "A",
    aa: "AA",
    aaa: "AAA",
    s: "S",
    sp: "S+",
    ss: "SS",
    ssp: "SS+",
    sss: "SSS",
    sssp: "SSS+"
  });
  let startupWarning = "";

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
    oldRating: document.querySelector("#old-rating"),
    newRating: document.querySelector("#new-rating"),
    selectedCount: document.querySelector("#selected-count"),
    oldCount: document.querySelector("#old-count"),
    newCount: document.querySelector("#new-count"),
    libraryCount: document.querySelector("#library-count"),
    compositionTotalRating: document.querySelector("#composition-total-rating"),
    compositionSelectedCount: document.querySelector("#composition-selected-count"),
    compositionOldTotal: document.querySelector("#composition-old-total"),
    compositionOldAverage: document.querySelector("#composition-old-average"),
    compositionNewTotal: document.querySelector("#composition-new-total"),
    compositionNewAverage: document.querySelector("#composition-new-average"),
    compositionPoolCount: document.querySelector("#composition-pool-count"),
    b35CompositionCount: document.querySelector("#b35-composition-count"),
    b15CompositionCount: document.querySelector("#b15-composition-count"),
    b35CompositionList: document.querySelector("#b35-composition-list"),
    b15CompositionList: document.querySelector("#b15-composition-list"),
    b35CompositionEmpty: document.querySelector("#b35-composition-empty"),
    b15CompositionEmpty: document.querySelector("#b15-composition-empty"),
    compositionViewTab: document.querySelector("#composition-view-tab"),
    scoreListViewTab: document.querySelector("#score-list-view-tab"),
    compositionViewPanel: document.querySelector("#composition-view-panel"),
    scoreListViewPanel: document.querySelector("#score-list-view-panel"),
    scoreListSearchInput: document.querySelector("#score-list-search-input"),
    scoreListVersionFilter: document.querySelector("#score-list-version-filter"),
    scoreListDifficultyFilter: document.querySelector("#score-list-difficulty-filter"),
    scoreListChartTypeFilter: document.querySelector("#score-list-chart-type-filter"),
    scoreListSort: document.querySelector("#score-list-sort"),
    scoreListSummary: document.querySelector("#score-list-summary"),
    scoreListGrid: document.querySelector("#score-list-grid"),
    scoreListEmpty: document.querySelector("#score-list-empty"),
    scoreListPagination: document.querySelector("#score-list-pagination"),
    scoreListPrev: document.querySelector("#score-list-prev"),
    scoreListNext: document.querySelector("#score-list-next"),
    scoreListPageLabel: document.querySelector("#score-list-page-label"),
    playHistoryViewTab: document.querySelector("#play-history-view-tab"),
    playHistoryViewPanel: document.querySelector("#play-history-view-panel"),
    refreshPlayHistoryButton: document.querySelector("#refresh-play-history-button"),
    playHistoryViewStatus: document.querySelector("#play-history-view-status"),
    playHistoryOverviewList: document.querySelector("#play-history-overview-list"),
    playHistoryOverviewEmpty: document.querySelector("#play-history-overview-empty"),
    addButton: document.querySelector("#add-chart-button"),
    importButton: document.querySelector("#import-button"),
    csvInput: document.querySelector("#csv-input"),
    exportButton: document.querySelector("#export-button"),
    accountButton: document.querySelector("#account-button"),
    accountLabel: document.querySelector("#account-label"),
    logoutButton: document.querySelector("#logout-button"),
    storageDescription: document.querySelector("#storage-description"),
    scoreDetailDialog: document.querySelector("#score-detail-dialog"),
    scoreDetailTitle: document.querySelector("#score-detail-title"),
    scoreDetailPool: document.querySelector("#score-detail-pool"),
    scoreDetailSummary: document.querySelector("#score-detail-summary"),
    scoreDetailContent: document.querySelector("#score-detail-content"),
    editScoreDetailButton: document.querySelector("#edit-score-detail-button"),
    deleteScoreDetailButton: document.querySelector("#delete-score-detail-button"),
    closeScoreDetailButton: document.querySelector("#close-score-detail-button"),
    chartDialog: document.querySelector("#chart-dialog"),
    chartForm: document.querySelector("#chart-form"),
    dialogMode: document.querySelector("#dialog-mode"),
    titleInput: document.querySelector("#chart-title"),
    idInput: document.querySelector("#chart-id"),
    songSearchResults: document.querySelector("#song-search-results"),
    selectedSong: document.querySelector("#selected-song"),
    selectedSongCover: document.querySelector("#selected-song-cover"),
    selectedSongTitle: document.querySelector("#selected-song-title"),
    selectedSongMeta: document.querySelector("#selected-song-meta"),
    difficultyInput: document.querySelector("#chart-difficulty"),
    levelInput: document.querySelector("#chart-level"),
    achievementInput: document.querySelector("#chart-achievement"),
    comboStatusInput: document.querySelector("#chart-combo-status"),
    syncStatusInput: document.querySelector("#chart-sync-status"),
    formError: document.querySelector("#form-error"),
    closeDialogButton: document.querySelector("#close-dialog-button"),
    cancelDialogButton: document.querySelector("#cancel-dialog-button"),
    deleteDialog: document.querySelector("#delete-dialog"),
    deleteChartName: document.querySelector("#delete-chart-name"),
    cancelDeleteButton: document.querySelector("#cancel-delete-button"),
    confirmDeleteButton: document.querySelector("#confirm-delete-button"),
    toast: document.querySelector("#toast")
  };

  const state = {
    charts: [],
    scoreDetailKey: null,
    scoreDetailTrigger: null,
    scoreHistoryController: null,
    scoreHistorySequence: 0,
    playHistoryController: null,
    playHistorySequence: 0,
    playHistoryRecords: [],
    playHistoryLoaded: false,
    selectedPlayRecord: null,
    playHistoryTrigger: null,
    editingKey: null,
    deletingKey: null,
    calculation: null,
    requestSequence: 0,
    apiTimer: null,
    toastTimer: null,
    authenticated: false,
    user: null,
    userRevision: null,
    authReady: false,
    authBusy: false,
    sessionSequence: 0,
    chartSaveBusy: false,
    catalog: [],
    catalogById: new Map(),
    catalogByTitle: new Map(),
    catalogReady: false,
    songSearchResults: [],
    songSearchTimer: null,
    songSearchController: null,
    editingCoverUrl: "",
    selectedCatalogSong: null,
    scoreView: "composition",
    scoreList: {
      query: "",
      version: "all",
      difficulty: "all",
      chartType: "all",
      sort: "rating",
      page: 1,
      pageSize: 24
    }
  };

  function createKey() {
    if (globalThis.crypto && typeof globalThis.crypto.randomUUID === "function") {
      return globalThis.crypto.randomUUID();
    }
    return `chart-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  function finiteNumber(value, fallback = 0) {
    if (value === null || value === undefined || String(value).trim() === "") {
      return fallback;
    }
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  }

  function normalizeDifficulty(value) {
    const upper = String(value ?? "").normalize("NFKC").trim().toUpperCase();
    if (!upper) return null;

    const withoutLevel = upper.replace(
      /(?:[\s,，/|·・:\-–—]*[([\{【]?\s*(?:(?:LV|LEVEL|定数|定數|等级|等級)\s*\.?\s*)?\d{1,2}(?:\.\d+)?\+?\s*[)\]}】]?)\s*$/u,
      ""
    ).trim();
    const aliasKey = withoutLevel
      .replace(/(?:DIFFICULTY|CHART)$/u, "")
      .replace(/(?:难度|難度|谱面|譜面|谱|譜)$/u, "")
      .replace(/色$/u, "")
      .replace(/[\s._:\-–—/\\|+·・'"()[\]{}【】]+/gu, "");
    const aliases = {
      "BAS": "BASIC",
      "BSC": "BASIC",
      "BEGINNER": "BASIC",
      "GREEN": "BASIC",
      "绿": "BASIC",
      "綠": "BASIC",
      "緑": "BASIC",
      "ADV": "ADVANCED",
      "YELLOW": "ADVANCED",
      "ORANGE": "ADVANCED",
      "YELLOWORANGE": "ADVANCED",
      "ORANGEYELLOW": "ADVANCED",
      "黄": "ADVANCED",
      "黃": "ADVANCED",
      "橙": "ADVANCED",
      "黄橙": "ADVANCED",
      "黃橙": "ADVANCED",
      "橙黄": "ADVANCED",
      "橙黃": "ADVANCED",
      "EXP": "EXPERT",
      "EX": "EXPERT",
      "RED": "EXPERT",
      "PINK": "EXPERT",
      "REDPINK": "EXPERT",
      "PINKRED": "EXPERT",
      "红": "EXPERT",
      "紅": "EXPERT",
      "赤": "EXPERT",
      "粉": "EXPERT",
      "粉红": "EXPERT",
      "粉紅": "EXPERT",
      "红粉": "EXPERT",
      "紅粉": "EXPERT",
      "MAS": "MASTER",
      "MST": "MASTER",
      "PURPLE": "MASTER",
      "VIOLET": "MASTER",
      "紫": "MASTER",
      "REM": "RE:MASTER",
      "REMAS": "RE:MASTER",
      "RMAS": "RE:MASTER",
      "REMASTER": "RE:MASTER",
      "RMST": "RE:MASTER",
      "REMST": "RE:MASTER",
      "WHITE": "RE:MASTER",
      "WHITEPURPLE": "RE:MASTER",
      "PURPLEWHITE": "RE:MASTER",
      "WHITEVIOLET": "RE:MASTER",
      "VIOLETWHITE": "RE:MASTER",
      "LIGHTPURPLE": "RE:MASTER",
      "PALEPURPLE": "RE:MASTER",
      "LAVENDER": "RE:MASTER",
      "白": "RE:MASTER",
      "白紫": "RE:MASTER",
      "紫白": "RE:MASTER"
    };
    const normalized = aliases[aliasKey] || aliasKey;
    return DIFFICULTIES.includes(normalized) ? normalized : null;
  }

  function normalizeVersion(value) {
    const normalized = String(value || "").trim().toLowerCase();
    if (["current", "new", "新曲", "b15"].includes(normalized)) {
      return "current";
    }
    if (["legacy", "old", "旧曲", "b35"].includes(normalized)) {
      return "legacy";
    }
    return null;
  }

  function normalizeChartType(value, allowMissing = false) {
    if (value === null || value === undefined || String(value).trim() === "") {
      return allowMissing ? "dx" : null;
    }
    const normalized = String(value).trim().toLowerCase().replace(/[\s_-]+/g, "");
    if (["standard", "std", "标准", "標準"].includes(normalized)) {
      return "standard";
    }
    if (["dx", "deluxe"].includes(normalized)) {
      return "dx";
    }
    return null;
  }

  function normalizeComboStatus(value) {
    const key = String(value ?? "")
      .normalize("NFKC")
      .trim()
      .toLowerCase()
      .replace(/[\s._:\-–—]/gu, "");
    const aliases = {
      "": "",
      none: "",
      blank: "",
      fc: "fc",
      fullcombo: "fc",
      "fc+": "fcp",
      fcp: "fcp",
      fcplus: "fcp",
      "fullcombo+": "fcp",
      fullcomboplus: "fcp",
      ap: "ap",
      allperfect: "ap",
      "ap+": "app",
      app: "app",
      applus: "app",
      "allperfect+": "app",
      allperfectplus: "app"
    };
    return aliases[key] ?? "";
  }

  function normalizeSyncStatus(value) {
    const key = String(value ?? "")
      .normalize("NFKC")
      .trim()
      .toLowerCase()
      .replace(/[\s._:\-–—]/gu, "");
    const aliases = {
      "": "",
      none: "",
      blank: "",
      sync: "sync",
      fs: "fs",
      fullsync: "fs",
      "fs+": "fsp",
      fsp: "fsp",
      fsplus: "fsp",
      "fullsync+": "fsp",
      fullsyncplus: "fsp",
      fsd: "fsd",
      fullsyncdx: "fsd",
      "fsd+": "fsdp",
      fsdp: "fsdp",
      fsdplus: "fsdp",
      "fullsyncdx+": "fsdp",
      fullsyncdxplus: "fsdp"
    };
    return aliases[key] ?? "";
  }

  function optionalText(value, maxLength = 160) {
    const text = String(value ?? "").trim();
    return text.length > 0 && text.length <= maxLength ? text : "";
  }

  function normalizeChart(chart, options = {}) {
    return {
      key: typeof chart.key === "string" && chart.key ? chart.key : createKey(),
      songId: String(chart.songId ?? chart.id ?? "").trim(),
      title: String(chart.title ?? "").trim(),
      artist: optionalText(chart.artist),
      chartType: normalizeChartType(chart.chartType, options.allowMissingChartType),
      difficulty: normalizeDifficulty(chart.difficulty),
      level: finiteNumber(chart.level, Number.NaN),
      achievement: finiteNumber(chart.achievement, Number.NaN),
      version: normalizeVersion(chart.version),
      coverUrl: normalizeCoverUrl(chart.coverUrl ?? chart.cover ?? chart.jacketUrl ?? ""),
      comboStatus: normalizeComboStatus(
        chart.comboStatus ?? chart.combo_status ?? chart.fcStatus ?? chart.fc ?? ""
      ),
      syncStatus: normalizeSyncStatus(
        chart.syncStatus ?? chart.sync_status ?? chart.sync ?? ""
      )
    };
  }

  function normalizeCoverUrl(value) {
    const text = String(value ?? "").trim();
    if (!text || text.length > 2048) return "";
    try {
      const url = new URL(text, window.location.origin);
      if (!/^https?:$/.test(url.protocol)) return "";
      if (url.username || url.password) return "";
      if (url.origin !== window.location.origin && url.protocol !== "https:") return "";
      return url.origin === window.location.origin
        ? `${url.pathname}${url.search}`
        : url.href;
    } catch (error) {
      return "";
    }
  }

  function hasAtMostDecimals(value, decimalPlaces) {
    return Number(value.toFixed(decimalPlaces)) === value;
  }

  function isValidChart(chart) {
    return Boolean(
      chart.songId &&
      chart.title &&
      CHART_TYPES.includes(chart.chartType) &&
      DIFFICULTIES.includes(chart.difficulty) &&
      ["legacy", "current"].includes(chart.version) &&
      chart.level >= 1 &&
      chart.level <= 15.9 &&
      hasAtMostDecimals(chart.level, 1) &&
      chart.achievement >= 0 &&
      chart.achievement <= 101 &&
      hasAtMostDecimals(chart.achievement, 4) &&
      COMBO_STATUSES.has(chart.comboStatus) &&
      SYNC_STATUSES.has(chart.syncStatus)
    );
  }

  function loadLegacyLocalCharts() {
    try {
      const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || "[]");
      if (!Array.isArray(parsed)) {
        return [];
      }
      const normalizedCharts = parsed
        .map((chart) => normalizeChart(chart, { allowMissingChartType: true }))
        .filter(isValidChart);
      return deduplicateCharts(normalizedCharts);
    } catch (error) {
      console.warn("无法读取本地谱面数据", error);
      return [];
    }
  }

  function apiErrorMessage(payload, fallback) {
    if (payload && typeof payload === "object") {
      for (const key of ["error", "message", "detail"]) {
        if (typeof payload[key] === "string" && payload[key].trim()) {
          return payload[key].trim();
        }
      }
    }
    return fallback;
  }

  function normalizeChartCollection(value) {
    if (!Array.isArray(value)) return [];
    const normalized = value
      .map((chart) => normalizeChart(chart, { allowMissingChartType: true }))
      .filter(isValidChart);
    return deduplicateCharts(normalized);
  }

  function deduplicateCharts(charts) {
    const bySignature = new Map();
    charts.forEach((chart) => {
      const key = signature(chart);
      const existing = bySignature.get(key);
      if (!existing) {
        bySignature.set(key, chart);
        return;
      }
      const preferred = chart.achievement > existing.achievement ? chart : existing;
      const fallback = preferred === chart ? existing : chart;
      bySignature.set(key, {
        ...preferred,
        key: existing.key,
        coverUrl: preferred.coverUrl || fallback.coverUrl,
        comboStatus: preferred.comboStatus || fallback.comboStatus,
        syncStatus: preferred.syncStatus || fallback.syncStatus
      });
    });
    return Array.from(bySignature.values());
  }

  function chartPersistenceValue(chart) {
    return {
      songId: chart.songId,
      title: chart.title,
      chartType: chart.chartType,
      difficulty: chart.difficulty,
      level: chart.level,
      achievement: chart.achievement,
      version: chart.version,
      comboStatus: chart.comboStatus,
      syncStatus: chart.syncStatus
    };
  }

  function revisionFromPayload(payload) {
    const revision = payload?.revision;
    if (typeof revision === "string" && revision.trim()) return revision;
    if (typeof revision === "number" && Number.isSafeInteger(revision) && revision >= 0) {
      return revision;
    }
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
    hydrateChartCovers();
    renderAccount();
    scheduleApiCalculation();
    requireAuthentication(message);
    showToast(message);
  }

  async function saveRemoteCharts(charts, sessionToken) {
    const expectedUserId = String(state.user?.id ?? "");
    const revision = state.userRevision;
    const response = await fetch(USER_CHARTS_ENDPOINT, {
      method: "PUT",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json;charset=UTF-8"
      },
      body: JSON.stringify({
        expectedUserId,
        revision,
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
    return payload;
  }

  async function commitCharts(nextCharts) {
    if (state.chartSaveBusy) {
      showToast("上一项修改仍在保存，请稍候");
      return false;
    }
    if (!state.authenticated) {
      requireAuthentication("请先登录后再修改成绩");
      return false;
    }
    if (!state.authReady) {
      showToast("正在确认用户与谱面库，请稍候");
      return false;
    }
    const sessionToken = state.sessionSequence;
    state.chartSaveBusy = true;
    updateMutationControls();
    try {
      await saveRemoteCharts(nextCharts, sessionToken);
      if (sessionToken !== state.sessionSequence || !state.authenticated) {
        return false;
      }
      state.charts = nextCharts;
      hydrateChartCovers();
      scheduleApiCalculation();
      return true;
    } catch (error) {
      if (error instanceof RevisionConflictError &&
          sessionToken === state.sessionSequence && state.authenticated) {
        state.chartSaveBusy = false;
        const resynchronized = await resynchronizeSessionAfterConflict();
        showToast(resynchronized
          ? "服务器会话或数据已变化，本次保存未覆盖；已同步当前登录用户的最新数据"
          : "检测到会话或版本冲突，请重新登录");
      } else if (!(error instanceof DOMException && error.name === "AbortError")) {
        showToast(error instanceof Error ? error.message : "保存失败，谱面库未发生变更");
      }
      return false;
    } finally {
      if (sessionToken === state.sessionSequence) {
        state.chartSaveBusy = false;
        updateMutationControls();
      }
    }
  }

  function updateMutationControls() {
    const unavailable = state.chartSaveBusy || !state.authReady || !state.authenticated;
    elements.addButton.disabled = unavailable;
    elements.editScoreDetailButton.disabled = unavailable;
    elements.deleteScoreDetailButton.disabled = unavailable;
    elements.importButton.disabled = unavailable;
    elements.exportButton.disabled = unavailable;
    elements.confirmDeleteButton.disabled = unavailable;
    elements.logoutButton.disabled = state.authBusy || state.chartSaveBusy;
    elements.accountButton.disabled = state.authBusy || state.chartSaveBusy || !state.authReady;
    const submit = elements.chartForm.querySelector("button[type='submit']");
    if (submit) submit.disabled = unavailable;
  }

  function coefficientTenthsFor(achievement) {
    if (achievement >= 100.5) return 224;
    if (achievement >= 100.4999) return 222;
    if (achievement >= 100) return 216;
    if (achievement >= 99.9999) return 214;
    if (achievement >= 99.5) return 211;
    if (achievement >= 99) return 208;
    if (achievement >= 98.9999) return 206;
    if (achievement >= 98) return 203;
    if (achievement >= 97) return 200;
    if (achievement >= 96.9999) return 176;
    if (achievement >= 94) return 168;
    if (achievement >= 90) return 152;
    if (achievement >= 80) return 136;
    if (achievement >= 79.9999) return 128;
    if (achievement >= 75) return 120;
    if (achievement >= 70) return 112;
    if (achievement >= 60) return 96;
    if (achievement >= 50) return 80;
    if (achievement >= 40) return 64;
    if (achievement >= 30) return 48;
    if (achievement >= 20) return 32;
    if (achievement >= 10) return 16;
    return 0;
  }

  function calculateRating(chart) {
    const levelTenths = Math.round(chart.level * 10);
    const achievementTenThousandths = Math.round(
      Math.min(chart.achievement, 100.5) * 10000
    );
    const coefficientTenths = coefficientTenthsFor(chart.achievement);
    return Math.floor(
      levelTenths * achievementTenThousandths * coefficientTenths / 100000000
    );
  }

  function compareText(left, right) {
    if (left < right) return -1;
    if (left > right) return 1;
    return 0;
  }

  function normalizedSongTitle(value) {
    return String(value ?? "")
      .normalize("NFKC")
      .trim()
      .toLocaleLowerCase("zh-CN")
      .replace(/[\s\p{P}\p{S}]+/gu, "");
  }

  function rankingComparator(left, right) {
    return (
      right.rating - left.rating ||
      right.achievement - left.achievement ||
      right.level - left.level ||
      compareText(left.songId, right.songId) ||
      CHART_TYPES.indexOf(left.chartType) - CHART_TYPES.indexOf(right.chartType) ||
      compareText(left.difficulty, right.difficulty) ||
      compareText(left.title, right.title)
    );
  }

  function calculateLocally() {
    const charts = state.charts.map((chart) => ({
      ...chart,
      rating: calculateRating(chart),
      selected: false,
      categoryRank: null,
      overallRank: null
    }));

    const assignCategory = (version, limit) => {
      charts
        .filter((chart) => chart.version === version)
        .sort(rankingComparator)
        .forEach((chart, index) => {
          chart.categoryRank = index + 1;
          chart.selected = index < limit;
        });
    };

    assignCategory("legacy", 35);
    assignCategory("current", 15);

    charts
      .slice()
      .sort(rankingComparator)
      .forEach((chart, index) => {
        chart.overallRank = index + 1;
      });

    const oldSelected = charts.filter((chart) => chart.version === "legacy" && chart.selected);
    const newSelected = charts.filter((chart) => chart.version === "current" && chart.selected);
    const oldTotal = oldSelected.reduce((total, chart) => total + chart.rating, 0);
    const newTotal = newSelected.reduce((total, chart) => total + chart.rating, 0);

    return {
      total: oldTotal + newTotal,
      oldTotal,
      newTotal,
      oldCount: oldSelected.length,
      newCount: newSelected.length,
      charts
    };
  }

  function signature(chart) {
    return `${String(chart.songId)}\u0000${chart.chartType}\u0000${normalizeDifficulty(chart.difficulty)}`;
  }

  function findVersionConflict(charts) {
    const versions = new Map();
    for (const chart of charts) {
      const key = signature(chart);
      const knownVersion = versions.get(key);
      if (knownVersion && knownVersion !== chart.version) {
        return chart.songId;
      }
      versions.set(key, chart.version);
    }
    return null;
  }

  function mergeApiCalculation(payload, localCalculation) {
    if (!payload || !Array.isArray(payload.charts)) {
      throw new Error("后端响应缺少 charts");
    }

    const localBuckets = new Map();
    localCalculation.charts.forEach((chart) => {
      const key = signature(chart);
      if (!localBuckets.has(key)) localBuckets.set(key, []);
      localBuckets.get(key).push(chart);
    });

    const merged = payload.charts.map((remoteChart, index) => {
      const bucket = localBuckets.get(signature(remoteChart));
      const localChart = bucket && bucket.length ? bucket.shift() : localCalculation.charts[index];
      if (!localChart) {
        return null;
      }
      return {
        ...localChart,
        rating: finiteNumber(remoteChart.rating, localChart.rating),
        selected: typeof remoteChart.selected === "boolean" ? remoteChart.selected : localChart.selected,
        categoryRank: finiteNumber(remoteChart.categoryRank, localChart.categoryRank) || null,
        overallRank: finiteNumber(remoteChart.overallRank, localChart.overallRank) || null
      };
    }).filter(Boolean);

    if (merged.length !== localCalculation.charts.length) {
      throw new Error("后端响应谱面数量不一致");
    }

    return {
      total: finiteNumber(payload.total, localCalculation.total),
      oldTotal: finiteNumber(payload.oldTotal, localCalculation.oldTotal),
      newTotal: finiteNumber(payload.newTotal, localCalculation.newTotal),
      oldCount: finiteNumber(payload.oldCount, localCalculation.oldCount),
      newCount: finiteNumber(payload.newCount, localCalculation.newCount),
      charts: merged
    };
  }

  function setSyncStatus(status, text) {
    elements.syncStatus.dataset.state = status;
    elements.syncStatusText.textContent = text;
    if (status === "server") {
      elements.syncStatus.title = "B50 结果来自 JDK 后端";
    } else if (status === "syncing") {
      elements.syncStatus.title = "正在与 JDK 后端同步";
    } else {
      elements.syncStatus.title = "后端不可用时使用浏览器内置公式";
    }
  }

  function scheduleApiCalculation() {
    window.clearTimeout(state.apiTimer);
    const sequence = ++state.requestSequence;
    const localCalculation = calculateLocally();
    state.calculation = localCalculation;
    render();

    if (state.charts.length === 0) {
      setSyncStatus("local", "本地计算");
      return;
    }

    setSyncStatus("syncing", "正在同步");
    state.apiTimer = window.setTimeout(
      () => requestApiCalculation(localCalculation, sequence),
      180
    );
  }

  async function requestApiCalculation(localCalculation, sequence) {
    const params = new URLSearchParams();

    state.charts.forEach((chart) => {
      params.append("songId", chart.songId);
      params.append("title", chart.title);
      params.append("chartType", chart.chartType);
      params.append("difficulty", chart.difficulty);
      params.append("level", String(chart.level));
      params.append("achievement", String(chart.achievement));
      params.append("version", chart.version);
    });

    try {
      const response = await fetch(API_ENDPOINT, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
        body: params
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const payload = await response.json();
      if (sequence !== state.requestSequence) return;
      state.calculation = mergeApiCalculation(payload, localCalculation);
      setSyncStatus("server", "后端已同步");
      render();
    } catch (error) {
      if (sequence !== state.requestSequence) return;
      state.calculation = calculateLocally();
      setSyncStatus("local", "本地计算");
      render();
      console.info("JDK 后端暂不可用，已使用本地 B50 公式", error);
    }
  }

  function formatInteger(value) {
    return Math.round(finiteNumber(value)).toLocaleString("zh-CN");
  }

  function escapeHtml(value) {
    return String(value)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#039;");
  }

  function difficultyClass(difficulty) {
    const classes = {
      BASIC: "difficulty-basic",
      ADVANCED: "difficulty-advanced",
      EXPERT: "difficulty-expert",
      MASTER: "difficulty-master",
      "RE:MASTER": "difficulty-remaster"
    };
    return classes[difficulty] || "difficulty-master";
  }

  function displayDifficulty(difficulty) {
    return difficulty === "RE:MASTER" ? "Re:MASTER" : difficulty;
  }

  function formatPoolAverage(total, count) {
    if (!count) return "—";
    return Math.round(finiteNumber(total) / count).toLocaleString("zh-CN");
  }

  function createTextElement(tagName, className, text) {
    const element = document.createElement(tagName);
    if (className) element.className = className;
    element.textContent = String(text ?? "");
    return element;
  }

  function achievementRankKey(achievement) {
    const value = finiteNumber(achievement, 0);
    if (value < 50) return "d";
    if (value < 60) return "c";
    if (value < 70) return "b";
    if (value < 75) return "bb";
    if (value < 80) return "bbb";
    if (value < 90) return "a";
    if (value < 94) return "aa";
    if (value < 97) return "aaa";
    if (value < 98) return "s";
    if (value < 99) return "sp";
    if (value < 99.5) return "ss";
    if (value < 100) return "ssp";
    if (value < 100.5) return "sss";
    return "sssp";
  }

  function scoreStatusDescriptors(chart) {
    const rank = achievementRankKey(chart.achievement);
    const comboStatus = normalizeComboStatus(chart.comboStatus);
    const syncStatus = normalizeSyncStatus(chart.syncStatus);
    return [
      {
        kind: "rank",
        key: rank,
        className: "score-status-rank",
        src: `${LXNS_MAIMAI_ASSET_ROOT}/music_rank/${rank}.webp`,
        alt: `达成等级 ${ACHIEVEMENT_RANK_LABELS[rank]}`
      },
      {
        kind: "combo",
        key: comboStatus || "blank",
        className: "score-status-icon combo-status-icon",
        src: comboStatus
          ? `${LXNS_MAIMAI_ASSET_ROOT}/music_icon/${comboStatus}.webp`
          : LXNS_BLANK_ICON_URL,
        alt: COMBO_STATUS_LABELS[comboStatus]
      },
      {
        kind: "sync",
        key: syncStatus || "blank",
        className: "score-status-icon sync-status-icon",
        src: syncStatus
          ? `${LXNS_MAIMAI_ASSET_ROOT}/music_icon/${syncStatus}.webp`
          : LXNS_BLANK_ICON_URL,
        alt: SYNC_STATUS_LABELS[syncStatus]
      }
    ];
  }

  function setLxnsIconSource(image, descriptor) {
    image.dataset.lxnsIcon = descriptor.kind;
    image.dataset.status = descriptor.key;
    image.src = descriptor.src;
    image.alt = descriptor.alt;
    image.decoding = "async";
    image.width = descriptor.kind === "rank" ? 64 : 30;
    image.height = 30;
    image.addEventListener("error", () => {
      if (descriptor.kind === "rank") {
        image.hidden = true;
        return;
      }
      if (!image.src.endsWith("/music_icon/blank.webp")) {
        image.src = LXNS_BLANK_ICON_URL;
      }
    }, { once: true });
  }

  function createScoreStatusStrip(
      chart,
      compact = false,
      extraClass = "",
      kinds = ["rank", "combo", "sync"]) {
    const strip = document.createElement("span");
    strip.className = [
      "score-status-strip",
      compact ? "is-compact" : "",
      extraClass
    ].filter(Boolean).join(" ");
    strip.setAttribute("aria-label", "成绩徽章");
    scoreStatusDescriptors(chart)
      .filter((descriptor) => kinds.includes(descriptor.kind))
      .forEach((descriptor) => {
      const image = document.createElement("img");
      image.className = descriptor.className;
      image.loading = compact ? "lazy" : "eager";
      setLxnsIconSource(image, descriptor);
      strip.append(image);
      });
    return strip;
  }

  function setScoreCover(image, chart, catalogSong = null) {
    const coverUrls = [chart?.coverUrl, catalogSong?.coverUrl]
      .map((value) => normalizeCoverUrl(value || ""))
      .filter((value, index, values) => value && values.indexOf(value) === index);
    let coverIndex = 0;
    const useNextCover = () => {
      image.src = coverIndex < coverUrls.length
        ? coverUrls[coverIndex++]
        : COVER_PLACEHOLDER_URL;
    };
    image.addEventListener("error", () => {
      if (coverIndex < coverUrls.length) {
        useNextCover();
        return;
      }
      if (!image.src.endsWith(COVER_PLACEHOLDER_URL)) {
        image.src = COVER_PLACEHOLDER_URL;
      }
    });
    useNextCover();
  }

  function createScoreCompositionCard(chart) {
    const poolName = chart.version === "legacy" ? "B35" : "B15";
    const poolRank = chart.categoryRank ? `#${chart.categoryRank}` : "未入选";
    const catalogSong = catalogMatchForChart(chart);
    const article = document.createElement("article");
    article.className = `score-card-item lxns-score-card-item ${difficultyClass(chart.difficulty)}-card`;
    article.setAttribute("role", "listitem");

    const button = document.createElement("button");
    button.type = "button";
    button.className = `score-card-button lxns-score-card lxns-score-card--maimai ${difficultyClass(chart.difficulty)}-card`;
    button.dataset.scoreDetailKey = chart.key;
    button.dataset.difficulty = difficultyClass(chart.difficulty).replace("difficulty-", "");
    button.setAttribute("aria-haspopup", "dialog");
    button.setAttribute(
      "aria-label",
      `查看 ${poolName} 第 ${chart.categoryRank || "—"} 名，${chart.title}，${displayDifficulty(chart.difficulty)} 成绩详情`
    );

    const cover = document.createElement("img");
    cover.className = "score-card-cover-background lxns-score-card__cover";
    cover.alt = `${chart.title} 封面`;
    cover.loading = "lazy";
    cover.decoding = "async";
    setScoreCover(cover, chart, catalogSong);

    const backdrop = document.createElement("span");
    backdrop.className = "score-card-backdrop lxns-score-card__shade";
    backdrop.setAttribute("aria-hidden", "true");

    const topbar = document.createElement("span");
    topbar.className = `score-card-topbar lxns-score-card__header ${difficultyClass(chart.difficulty)}`;
    const title = createTextElement("strong", "score-card-title lxns-score-card__title", chart.title);
    title.title = chart.title;
    const chartType = createTextElement(
      "span",
      "score-card-chart-type lxns-score-card__difficulty",
      chart.chartType === "standard" ? "STD" : "DX"
    );
    topbar.append(title, chartType);

    const main = document.createElement("span");
    main.className = "score-card-main lxns-score-card__content";
    const meta = document.createElement("span");
    meta.className = "score-card-meta-row";
    meta.append(
      createTextElement(
        "span",
        "score-card-rank-label",
        `${poolName} ${poolRank} · SongID ${chart.songId}`
      ),
      createScoreStatusStrip(chart, true, "score-card-status-strip")
    );
    const achievement = createTextElement(
      "strong",
      "score-card-achievement lxns-score-card__achievement",
      `${chart.achievement.toFixed(4)}%`
    );
    const rating = createTextElement(
      "span",
      "score-card-rating lxns-score-card__rating",
      `DX Rating: ${formatInteger(chart.rating)}`
    );
    const constant = createTextElement(
      "span",
      "score-card-constant lxns-score-card__constant",
      chart.level.toFixed(1)
    );
    constant.setAttribute("aria-label", `谱面定数 ${chart.level.toFixed(1)}`);
    main.append(meta, achievement, rating, constant);

    button.append(cover, backdrop, topbar, main);
    article.append(button);
    return article;
  }

  function selectedPoolCharts(calculation, version) {
    return calculation.charts
      .filter((chart) => chart.version === version && chart.selected)
      .slice()
      .sort((left, right) =>
        finiteNumber(left.categoryRank, Number.MAX_SAFE_INTEGER) -
          finiteNumber(right.categoryRank, Number.MAX_SAFE_INTEGER) ||
        rankingComparator(left, right)
      );
  }

  function renderScorePool(list, emptyState, charts) {
    const fragment = document.createDocumentFragment();
    charts.forEach((chart) => fragment.append(createScoreCompositionCard(chart)));
    list.replaceChildren(fragment);
    emptyState.hidden = charts.length > 0;
  }

  function renderScoreComposition(calculation) {
    const oldCharts = selectedPoolCharts(calculation, "legacy");
    const newCharts = selectedPoolCharts(calculation, "current");
    const selectedCount = oldCharts.length + newCharts.length;

    elements.compositionTotalRating.textContent = formatInteger(calculation.total);
    elements.compositionSelectedCount.textContent = `已入选 ${selectedCount} / 50`;
    elements.compositionOldTotal.textContent = formatInteger(calculation.oldTotal);
    elements.compositionOldAverage.textContent = formatPoolAverage(
      calculation.oldTotal,
      oldCharts.length
    );
    elements.compositionNewTotal.textContent = formatInteger(calculation.newTotal);
    elements.compositionNewAverage.textContent = formatPoolAverage(
      calculation.newTotal,
      newCharts.length
    );
    elements.compositionPoolCount.textContent = formatInteger(selectedCount);
    elements.b35CompositionCount.textContent = `${oldCharts.length} / 35`;
    elements.b15CompositionCount.textContent = `${newCharts.length} / 15`;
    renderScorePool(elements.b35CompositionList, elements.b35CompositionEmpty, oldCharts);
    renderScorePool(elements.b15CompositionList, elements.b15CompositionEmpty, newCharts);
  }

  function scoreListMatches(chart, query) {
    if (!query) return true;
    const song = catalogMatchForChart(chart);
    const haystack = [
      chart.title,
      chart.songId,
      chart.artist,
      song?.title,
      song?.artist,
      song?.category,
      song?.version
    ].map((value) => normalizedSongTitle(value)).join("\u0000");
    return haystack.includes(query);
  }

  function scoreListComparator(sort) {
    const textCompare = (left, right) => String(left ?? "")
      .localeCompare(String(right ?? ""), "zh-CN", { numeric: true, sensitivity: "base" });
    return (left, right) => {
      if (sort === "achievement") {
        return right.achievement - left.achievement || right.rating - left.rating;
      }
      if (sort === "level") {
        return right.level - left.level || right.rating - left.rating;
      }
      if (sort === "title") {
        return textCompare(left.title, right.title) || textCompare(left.songId, right.songId);
      }
      if (sort === "songId") {
        return textCompare(left.songId, right.songId) || textCompare(left.title, right.title);
      }
      return right.rating - left.rating || right.achievement - left.achievement ||
        textCompare(left.title, right.title);
    };
  }

  function filteredScoreListCharts(calculation) {
    const filters = state.scoreList;
    const query = normalizedSongTitle(filters.query);
    return calculation.charts
      .filter((chart) => filters.version === "all" || chart.version === filters.version)
      .filter((chart) => filters.difficulty === "all" || chart.difficulty === filters.difficulty)
      .filter((chart) => filters.chartType === "all" || chart.chartType === filters.chartType)
      .filter((chart) => scoreListMatches(chart, query))
      .slice()
      .sort(scoreListComparator(filters.sort));
  }

  function renderScoreList(calculation) {
    const allCharts = calculation?.charts || [];
    const filtered = filteredScoreListCharts(calculation || { charts: [] });
    const pageSize = state.scoreList.pageSize;
    const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize));
    state.scoreList.page = Math.min(Math.max(1, state.scoreList.page), pageCount);
    const start = (state.scoreList.page - 1) * pageSize;
    const visible = filtered.slice(start, start + pageSize);
    const fragment = document.createDocumentFragment();
    visible.forEach((chart) => fragment.append(createScoreCompositionCard(chart)));
    elements.scoreListGrid.replaceChildren(fragment);
    elements.scoreListEmpty.hidden = filtered.length > 0;
    elements.scoreListGrid.hidden = filtered.length === 0;
    elements.scoreListSummary.textContent = filtered.length === allCharts.length
      ? `共 ${allCharts.length} 条成绩`
      : `筛选出 ${filtered.length} / ${allCharts.length} 条成绩`;
    elements.scoreListPagination.hidden = pageCount <= 1 || filtered.length === 0;
    elements.scoreListPageLabel.textContent = `第 ${state.scoreList.page} / ${pageCount} 页`;
    elements.scoreListPrev.disabled = state.scoreList.page <= 1;
    elements.scoreListNext.disabled = state.scoreList.page >= pageCount;
  }

  function setScoreView(view) {
    state.scoreView = ["composition", "list", "history"].includes(view)
      ? view
      : "composition";
    const composition = state.scoreView === "composition";
    const list = state.scoreView === "list";
    const history = state.scoreView === "history";
    elements.compositionViewPanel.hidden = !composition;
    elements.scoreListViewPanel.hidden = !list;
    elements.playHistoryViewPanel.hidden = !history;
    elements.compositionViewTab.classList.toggle("is-active", composition);
    elements.scoreListViewTab.classList.toggle("is-active", list);
    elements.playHistoryViewTab.classList.toggle("is-active", history);
    elements.compositionViewTab.setAttribute("aria-selected", String(composition));
    elements.scoreListViewTab.setAttribute("aria-selected", String(list));
    elements.playHistoryViewTab.setAttribute("aria-selected", String(history));
    elements.compositionViewTab.tabIndex = composition ? 0 : -1;
    elements.scoreListViewTab.tabIndex = list ? 0 : -1;
    elements.playHistoryViewTab.tabIndex = history ? 0 : -1;
    if (list) renderScoreList(state.calculation || calculateLocally());
    if (history) void loadPlayHistoryOverview();
  }

  function createScoreDetailMetric(label, value, wide = false) {
    const field = document.createElement("div");
    field.className = wide
      ? "score-detail-metric lxns-detail-metric lxns-detail-metric--wide"
      : "score-detail-metric lxns-detail-metric";
    const term = createTextElement("dt", "", label);
    const description = createTextElement("dd", "", value);
    field.append(term, description);
    return field;
  }

  function catalogEntriesForSongId(songId) {
    const canonical = String(songId ?? "").trim();
    return state.catalog
      .filter((song) => song.songId === canonical)
      .sort((left, right) => CHART_TYPES.indexOf(left.chartType) - CHART_TYPES.indexOf(right.chartType));
  }

  function catalogNoteValue(chart, key) {
    const value = Number(chart?.[key]);
    return Number.isSafeInteger(value) && value >= 0
      ? value.toLocaleString("zh-CN")
      : "—";
  }

  function catalogChartConstant(chart) {
    const value = Number(chart?.constant ?? chart?.levelValue);
    return Number.isFinite(value) && value >= 0 && value <= 20 ? value : null;
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
    table.append(head, document.createElement("tbody"));
    viewport.append(table);
    return { viewport, table, body: table.querySelector("tbody") };
  }

  function createCatalogChartDetails(chart) {
    const section = document.createElement("section");
    section.className = "catalog-chart-details";
    const heading = document.createElement("div");
    heading.className = "catalog-chart-details-heading";
    heading.append(
      createTextElement("span", "section-kicker", "曲库资料"),
      createTextElement("h3", "", "谱面详情")
    );
    section.append(heading);

    const entries = catalogEntriesForSongId(chart.songId);
    if (entries.length === 0) {
      section.append(createTextElement(
        "p",
        "catalog-chart-details-empty",
        "当前曲库尚未提供这首歌的谱面物量与谱师资料。"
      ));
      return section;
    }

    const ratingAchievements = [
      ["SSS+", 100.5], ["SSS", 100], ["SS+", 99.5],
      ["SS", 99], ["S+", 98], ["S", 97]
    ];
    entries.forEach((song) => {
      const group = document.createElement("section");
      group.className = "catalog-chart-group";
      group.dataset.chartType = song.chartType;
      group.append(createTextElement(
        "h4",
        "catalog-chart-type-heading",
        song.chartType === "dx" ? "DX 谱面" : "STANDARD 谱面"
      ));

      const charts = Array.isArray(song.charts)
        ? song.charts.slice().sort((left, right) =>
            DIFFICULTIES.indexOf(normalizeDifficulty(left?.difficulty)) -
            DIFFICULTIES.indexOf(normalizeDifficulty(right?.difficulty)))
        : [];
      const notes = createCatalogTable(
        ["难度 / 定数", "TOTAL", "TAP", "HOLD", "SLIDE", "TOUCH", "BREAK", "谱师"],
        "catalog-chart-table catalog-chart-note-table"
      );
      charts.forEach((catalogChart) => {
        const difficulty = normalizeDifficulty(catalogChart?.difficulty);
        if (!difficulty) return;
        const row = document.createElement("tr");
        row.className = difficultyClass(difficulty);
        const difficultyCell = document.createElement("th");
        difficultyCell.scope = "row";
        const constant = catalogChartConstant(catalogChart);
        const displayLevel = String(catalogChart?.displayLevel ?? catalogChart?.level ?? "").trim();
        difficultyCell.append(
          createTextElement("strong", "", displayDifficulty(difficulty)),
          createTextElement(
            "span",
            "catalog-chart-level",
            [displayLevel ? `Lv ${displayLevel}` : "", constant === null ? "定数 —" : `定数 ${constant.toFixed(1)}`]
              .filter(Boolean).join(" · ")
          )
        );
        row.append(difficultyCell);
        ["total", "tap", "hold", "slide", "touch", "break"].forEach((key) => {
          row.append(createTextElement("td", "", catalogNoteValue(catalogChart, key)));
        });
        row.append(createTextElement("td", "catalog-chart-charter", String(catalogChart?.charter ?? "").trim() || "—"));
        notes.body.append(row);
      });
      group.append(notes.viewport);

      const ratingCharts = charts.filter((catalogChart) =>
        ["EXPERT", "MASTER", "RE:MASTER"].includes(normalizeDifficulty(catalogChart?.difficulty))
      );
      if (ratingCharts.length > 0) {
        const ratings = createCatalogTable(
          ["难度", "谱师", ...ratingAchievements.map(([label]) => label)],
          "catalog-chart-table catalog-chart-rating-table"
        );
        ratingCharts.forEach((catalogChart) => {
          const difficulty = normalizeDifficulty(catalogChart?.difficulty);
          const constant = catalogChartConstant(catalogChart);
          const row = document.createElement("tr");
          row.className = difficultyClass(difficulty);
          const label = document.createElement("th");
          label.scope = "row";
          label.textContent = displayDifficulty(difficulty);
          row.append(label, createTextElement(
            "td", "catalog-chart-charter", String(catalogChart?.charter ?? "").trim() || "—"
          ));
          ratingAchievements.forEach(([, achievement]) => {
            row.append(createTextElement(
              "td",
              "",
              constant === null ? "—" : formatInteger(calculateRating({ level: constant, achievement }))
            ));
          });
          ratings.body.append(row);
        });
        group.append(ratings.viewport);
      }
      section.append(group);
    });
    return section;
  }

  function normalizePlayDetails(value) {
    if (!value || typeof value !== "object" || Array.isArray(value)) return null;
    const allowedRootFields = new Set([
      "fast", "late", "maxCombo", "maxSync", "dxScore", "rating", "partners"
    ]);
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
      return Object.values(normalized).some((item) => item !== null) ? normalized : null;
    };
    const rating = (() => {
      const raw = value.rating;
      if (!raw || typeof raw !== "object" || Array.isArray(raw) ||
          Object.keys(raw).some((key) =>
            !["value", "playerTotal", "delta", "frame", "frameImageUrl"].includes(key))) {
        return null;
      }
      const frame = typeof raw.frame === "string" && DX_RATING_FRAMES.has(raw.frame)
        ? raw.frame
        : null;
      const frameImageUrl = normalizeCoverUrl(raw.frameImageUrl);
      const normalizedRating = {
        value: safeCount(raw.value),
        playerTotal: safeCount(raw.playerTotal),
        delta: safeCount(raw.delta, true),
        frame,
        frameImageUrl: frameImageUrl || null
      };
      return Object.values(normalizedRating).some((item) => item !== null)
        ? normalizedRating
        : null;
    })();
    const partners = Array.isArray(value.partners) && value.partners.length <= 5
      ? value.partners.map((partner) => {
          if (!partner || typeof partner !== "object" || Array.isArray(partner) ||
              Object.keys(partner).length !== 3 ||
              !["stars", "level", "imageUrl"].every((key) => Object.hasOwn(partner, key))) {
            return null;
          }
          const imageUrl = normalizeCoverUrl(partner.imageUrl);
          if (!Number.isSafeInteger(partner.stars) || partner.stars < 0 ||
              !Number.isSafeInteger(partner.level) || partner.level < 0 || !imageUrl) {
            return null;
          }
          return { stars: partner.stars, level: partner.level, imageUrl };
        })
      : [];
    const normalized = {
      fast: safeCount(value.fast),
      late: safeCount(value.late),
      maxCombo: metric(value.maxCombo),
      maxSync: metric(value.maxSync),
      dxScore: metric(value.dxScore),
      rating,
      partners: partners.length > 0 && partners.every(Boolean) ? partners : []
    };
    return Object.entries(normalized).some(([, item]) => Array.isArray(item) ? item.length > 0 : item !== null)
      ? normalized
      : null;
  }

  function createDxRatingFrame(rating) {
    if (!rating || (rating.value === null && rating.delta === null &&
        rating.frame === null && rating.frameImageUrl === null)) return null;
    const frame = document.createElement("div");
    frame.className = "play-dx-rating-frame";
    if (rating.frame) frame.dataset.frame = rating.frame;
    if (rating.frameImageUrl) {
      const image = document.createElement("img");
      image.className = "play-dx-rating-frame-image";
      image.src = rating.frameImageUrl;
      image.alt = "";
      image.decoding = "async";
      image.referrerPolicy = "no-referrer";
      image.addEventListener("error", () => image.remove(), { once: true });
      frame.append(image);
    }
    const copy = document.createElement("div");
    copy.className = "play-dx-rating-frame-copy";
    copy.append(
      createTextElement("span", "", "本局后 DX RATING"),
      createTextElement(
        "strong",
        "",
        rating.value === null ? "—" : rating.value.toLocaleString("zh-CN")
      )
    );
    if (rating.delta !== null) {
      copy.append(createTextElement(
        "em",
        "",
        `变化 ${rating.delta >= 0 ? "+" : ""}${rating.delta}`
      ));
    }
    frame.append(copy);
    frame.setAttribute(
      "aria-label",
      `本局后 DX Rating ${rating.value === null ? "未知" : rating.value}` +
        `${rating.delta === null ? "" : `，变化 ${rating.delta >= 0 ? "+" : ""}${rating.delta}`}`
    );
    return frame;
  }

  function createPlayDetailsSection(playDetails) {
    const section = document.createElement("section");
    section.className = "play-result-details";
    section.append(createTextElement("h3", "", "本局详细信息"));
    if (!playDetails) {
      section.append(createTextElement("p", "play-result-details-empty", "本局尚未抓取到额外结果信息。"));
      return section;
    }
    const ratingFrame = createDxRatingFrame(playDetails.rating);
    if (ratingFrame) section.append(ratingFrame);
    const grid = document.createElement("dl");
    grid.className = "play-result-details-grid";
    const append = (label, value) => {
      if (value === null || value === undefined) return;
      grid.append(createScoreDetailMetric(label, String(value)));
    };
    append("FAST", playDetails.fast);
    append("LATE", playDetails.late);
    const appendMetric = (label, metric) => {
      if (!metric) return;
      const main = metric.current === null ? "—" : metric.current.toLocaleString("zh-CN");
      const maximum = metric.maximum === null ? "" : ` / ${metric.maximum.toLocaleString("zh-CN")}`;
      append(label, `${main}${maximum}`);
    };
    appendMetric("MAX COMBO", playDetails.maxCombo);
    appendMetric("MAX SYNC", playDetails.maxSync);
    appendMetric("DX SCORE", playDetails.dxScore);
    if (playDetails.rating) {
      append("本局后 DX Rating", playDetails.rating.value);
      append(
        "Rating 变化",
        playDetails.rating.delta === null
          ? null
          : `${playDetails.rating.delta >= 0 ? "+" : ""}${playDetails.rating.delta}`
      );
      append("同步时总 Rating", playDetails.rating.playerTotal);
    }
    section.append(grid);
    if (playDetails.partners?.length) {
      const partners = document.createElement("section");
      partners.className = "play-partners";
      partners.append(createTextElement("h4", "", "旅行伙伴"));
      const list = document.createElement("ul");
      playDetails.partners.forEach((partner) => {
        const item = document.createElement("li");
        const image = document.createElement("img");
        image.src = partner.imageUrl;
        image.alt = "旅行伙伴头像";
        image.width = 64;
        image.height = 64;
        image.loading = "lazy";
        image.referrerPolicy = "no-referrer";
        image.addEventListener("error", () => {
          if (!image.src.endsWith(COVER_PLACEHOLDER_URL)) image.src = COVER_PLACEHOLDER_URL;
        }, { once: true });
        item.append(
          image,
          createTextElement("span", "play-partner-stars", `★ ${partner.stars}`),
          createTextElement("strong", "", `等级 ${partner.level}`)
        );
        list.append(item);
      });
      partners.append(list);
      section.append(partners);
    }
    return section;
  }

  function createScoreHistoryShell(chart) {
    const section = document.createElement("section");
    section.className = "score-play-history";
    section.dataset.songId = chart.songId;

    const heading = document.createElement("div");
    heading.className = "score-play-history-heading";
    const copy = document.createElement("div");
    copy.append(
      createTextElement("span", "section-kicker", "最近游玩"),
      createTextElement("h3", "", "游玩历史记录")
    );
    const source = createTextElement("span", "score-play-history-source", "舞萌 NET · 最近窗口");
    heading.append(copy, source);

    const body = document.createElement("div");
    body.className = "score-play-history-body";
    body.setAttribute("role", "status");
    body.setAttribute("aria-live", "polite");
    body.append(createTextElement(
      "p",
      "score-play-history-empty",
      state.authenticated
        ? "正在读取这张谱面的最近游玩记录…"
        : "登录并完成一次舞萌微信同步后，这里会显示逐局记录。"
    ));
    if (!state.authenticated) {
      const syncLink = createTextElement("a", "score-play-history-link", "前往同步游戏数据");
      syncLink.href = "/sync.html";
      body.append(syncLink);
    }
    section.append(heading, body);
    return section;
  }

  function normalizeHistoryRecord(value) {
    if (!value || typeof value !== "object") return null;
    const achievement = finiteNumber(value.achievement, Number.NaN);
    const dxScore = finiteNumber(value.dxScore, Number.NaN);
    const playedAt = String(value.playedAt ?? "").trim();
    const timestamp = Date.parse(playedAt);
    if (!Number.isFinite(achievement) || achievement < 0 || achievement > 101 ||
        !Number.isFinite(timestamp)) {
      return null;
    }
    return {
      sourceRecordId: String(value.sourceRecordId ?? "").trim(),
      songId: String(value.songId ?? "").trim(),
      title: String(value.title ?? "").trim(),
      chartType: normalizeChartType(value.chartType) || "standard",
      difficulty: normalizeDifficulty(value.difficulty) || "MASTER",
      achievement,
      dxScore: Number.isSafeInteger(dxScore) && dxScore >= 0 ? dxScore : null,
      playedAt,
      timestamp,
      comboStatus: normalizeComboStatus(value.comboStatus),
      syncStatus: normalizeSyncStatus(value.syncStatus),
      rank: String(value.rank ?? "").trim(),
      playDetails: normalizePlayDetails(value.playDetails),
      judgmentDetails: JUDGMENT_DETAILS.normalizeRecord(value, "maimai")
    };
  }

  function formatPlayedAt(record) {
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

  function createHistoryPlot(records) {
    const figure = document.createElement("figure");
    figure.className = "score-play-history-chart";
    const caption = createTextElement(
      "figcaption",
      "visually-hidden",
      `最近 ${records.length} 局达成率变化`
    );
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 560 112");
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", `最近 ${records.length} 局达成率变化图`);
    svg.classList.add("score-play-history-plot");

    const ordered = records.slice().sort((left, right) => left.timestamp - right.timestamp);
    const minimum = Math.max(0, Math.min(...ordered.map((record) => record.achievement)) - 0.25);
    const maximum = Math.max(101, Math.max(...ordered.map((record) => record.achievement)));
    const span = Math.max(0.01, maximum - minimum);
    const point = (record, index) => {
      const x = ordered.length === 1 ? 280 : 16 + index / (ordered.length - 1) * 528;
      const y = 94 - (record.achievement - minimum) / span * 76;
      return { x, y };
    };
    const points = ordered.map(point);

    const grid = document.createElementNS("http://www.w3.org/2000/svg", "path");
    grid.setAttribute("d", "M16 18H544 M16 56H544 M16 94H544");
    grid.classList.add("score-play-history-grid");
    const area = document.createElementNS("http://www.w3.org/2000/svg", "path");
    area.setAttribute(
      "d",
      `M${points[0].x.toFixed(1)} 94 ` +
        points.map((entry) => `L${entry.x.toFixed(1)} ${entry.y.toFixed(1)}`).join(" ") +
        ` L${points.at(-1).x.toFixed(1)} 94 Z`
    );
    area.classList.add("score-play-history-area");
    const line = document.createElementNS("http://www.w3.org/2000/svg", "polyline");
    line.setAttribute(
      "points",
      points.map((entry) => `${entry.x.toFixed(1)},${entry.y.toFixed(1)}`).join(" ")
    );
    line.classList.add("score-play-history-line");
    svg.append(grid, area, line);
    points.forEach((entry) => {
      const dot = document.createElementNS("http://www.w3.org/2000/svg", "circle");
      dot.setAttribute("cx", entry.x.toFixed(1));
      dot.setAttribute("cy", entry.y.toFixed(1));
      dot.setAttribute("r", "3");
      dot.classList.add("score-play-history-dot");
      svg.append(dot);
    });
    figure.append(caption, svg);
    return figure;
  }

  function createHistoryRecordRow(record) {
    const item = document.createElement("li");
    item.className = "score-play-history-item";
    const time = createTextElement("time", "score-play-history-time", formatPlayedAt(record));
    time.dateTime = record.playedAt;
    const result = document.createElement("div");
    result.className = "score-play-history-result";
    result.append(
      createTextElement("strong", "", `${record.achievement.toFixed(4)}%`),
      createTextElement(
        "span",
        "",
        record.dxScore === null ? "DX 分未提供" : `DX 分 ${formatInteger(record.dxScore)}`
      )
    );
    result.append(createScoreStatusStrip(record, true, "score-play-history-badges"));
    item.append(
      time,
      result,
      JUDGMENT_DETAILS.createDetails(document, record.judgmentDetails)
    );
    return item;
  }

  function renderHistoryRecords(container, records) {
    if (records.length === 0) {
      container.replaceChildren(
        createTextElement(
          "p",
          "score-play-history-empty",
          "这张谱面还没有同步到逐局记录，玩家判定详情也尚未抓取。"
        )
      );
      return;
    }
    const sorted = records.slice().sort((left, right) => right.timestamp - left.timestamp);
    const note = createTextElement(
      "p",
      "score-play-history-note",
      `已保存 ${sorted.length} 局；每局谱面详情只显示实际抓取到的玩家音符判定。`
    );
    const list = document.createElement("ol");
    list.className = "score-play-history-list";
    sorted.forEach((record) => list.append(createHistoryRecordRow(record)));
    container.replaceChildren(createHistoryPlot(sorted), note, list);
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
      game: "maimai",
      songId: chart.songId,
      chartType: chart.chartType,
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
      if (!response.ok) throw new Error(payload.error || "无法读取游玩历史");
      const records = Array.isArray(payload.records)
        ? payload.records.map(normalizeHistoryRecord).filter(Boolean)
        : [];
      renderHistoryRecords(body, records);
    } catch (error) {
      if (error?.name === "AbortError" || sequence !== state.scoreHistorySequence) return;
      body.replaceChildren(
        createTextElement(
          "p",
          "score-play-history-empty is-error",
          error instanceof Error ? error.message : "无法读取游玩历史"
        )
      );
    }
  }

  function historyRecordChart(record) {
    return state.calculation?.charts.find((chart) =>
      chart.songId === record.songId &&
      chart.chartType === record.chartType &&
      normalizeDifficulty(chart.difficulty) === record.difficulty
    ) || null;
  }

  function createPlayHistoryOverviewItem(record, index) {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.className = "play-history-overview-item";
    button.dataset.playHistoryIndex = String(index);
    button.setAttribute("aria-label", `${record.title || `SongID ${record.songId}`}，${formatPlayedAt(record)}，查看本局详情`);

    const song = catalogSongById(record.songId, record.chartType);
    const cover = document.createElement("img");
    cover.className = "play-history-overview-cover";
    cover.width = 58;
    cover.height = 58;
    cover.alt = "";
    cover.loading = "lazy";
    cover.src = song?.coverUrl || historyRecordChart(record)?.coverUrl || COVER_PLACEHOLDER_URL;
    cover.addEventListener("error", () => {
      if (!cover.src.endsWith(COVER_PLACEHOLDER_URL)) cover.src = COVER_PLACEHOLDER_URL;
    }, { once: true });

    const copy = document.createElement("span");
    copy.className = "play-history-overview-copy";
    const meta = document.createElement("span");
    meta.className = "play-history-overview-meta";
    meta.append(
      createTextElement("span", `difficulty-tag ${difficultyClass(record.difficulty)}`, displayDifficulty(record.difficulty)),
      createTextElement("span", "", record.chartType === "dx" ? "DX" : "STD"),
      createTextElement("span", "", `SongID ${record.songId || "—"}`)
    );
    const title = createTextElement("strong", "play-history-overview-title", record.title || song?.title || "未知曲目");
    const time = createTextElement("time", "play-history-overview-time", formatPlayedAt(record));
    time.dateTime = record.playedAt;
    copy.append(meta, title, time);

    const result = document.createElement("span");
    result.className = "play-history-overview-result";
    result.append(
      createTextElement("strong", "", `${record.achievement.toFixed(4)}%`),
      createTextElement("span", "", record.dxScore === null ? "DX 分 —" : `DX 分 ${formatInteger(record.dxScore)}`),
      createScoreStatusStrip(record, true, "play-history-overview-badges")
    );
    button.append(cover, copy, result);
    item.append(button);
    return item;
  }

  function renderPlayHistoryOverview() {
    const records = state.playHistoryRecords;
    const fragment = document.createDocumentFragment();
    records.forEach((record, index) => fragment.append(createPlayHistoryOverviewItem(record, index)));
    elements.playHistoryOverviewList.replaceChildren(fragment);
    elements.playHistoryOverviewList.hidden = records.length === 0;
    elements.playHistoryOverviewEmpty.hidden = records.length !== 0;
    elements.playHistoryViewStatus.textContent = records.length > 0
      ? `共显示最近 ${records.length} 局，按游玩时间从新到旧排列。`
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
    elements.playHistoryViewStatus.textContent = "正在读取舞萌游玩记录…";
    try {
      const query = new URLSearchParams({ game: "maimai", limit: "500" });
      const response = await fetch(`${PLAY_HISTORY_ENDPOINT}?${query}`, {
        method: "GET",
        headers: { Accept: "application/json" },
        signal: controller.signal
      });
      const payload = await readJsonResponse(response);
      if (sequence !== state.playHistorySequence) return;
      if (!response.ok) throw new Error(payload.error || "无法读取游玩记录");
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

  function renderPlayRecordDetail(record) {
    const song = catalogSongById(record.songId, record.chartType);
    const savedChart = historyRecordChart(record);
    const catalogChart = song?.charts?.find((item) =>
      normalizeDifficulty(item?.difficulty) === record.difficulty
    ) || null;
    const constant = catalogChartConstant(catalogChart) ?? savedChart?.level ?? null;
    const title = record.title || song?.title || savedChart?.title || "未知曲目";
    const chartLike = {
      songId: record.songId,
      title,
      chartType: record.chartType,
      difficulty: record.difficulty,
      coverUrl: song?.coverUrl || savedChart?.coverUrl || ""
    };
    elements.scoreDetailDialog.dataset.difficulty = difficultyClass(record.difficulty).replace("difficulty-", "");
    elements.scoreDetailTitle.textContent = "游玩详情";
    elements.scoreDetailPool.textContent = "游玩记录";
    elements.scoreDetailSummary.textContent = "本局官方结果页已抓取信息";
    elements.editScoreDetailButton.hidden = true;
    elements.deleteScoreDetailButton.hidden = true;

    const detail = document.createElement("section");
    detail.className = "score-detail-lxns lxns-score-detail play-record-detail";
    const songRow = document.createElement("section");
    songRow.className = "score-detail-song-row lxns-detail-song";
    const cover = document.createElement("img");
    cover.className = "score-detail-cover lxns-detail-cover";
    cover.width = 94;
    cover.height = 94;
    cover.alt = `${title} 封面`;
    setScoreCover(cover, chartLike, song);
    const copy = document.createElement("div");
    copy.className = "score-detail-song-copy lxns-detail-song-copy";
    copy.append(
      createTextElement("span", "score-detail-song-id", `SongID ${record.songId}`),
      createTextElement("strong", "score-detail-song-title lxns-detail-song-title", title),
      createTextElement("span", "score-detail-song-artist lxns-detail-song-meta", song?.artist || "艺术家信息未提供")
    );
    const constantBlock = document.createElement("div");
    constantBlock.className = "score-detail-constant lxns-detail-constant";
    constantBlock.append(
      createTextElement("span", "", "定数"),
      createTextElement("strong", "", constant === null ? "—" : Number(constant).toFixed(1))
    );
    songRow.append(cover, copy, constantBlock);

    const resultRow = document.createElement("section");
    resultRow.className = "score-detail-result-row lxns-detail-result";
    resultRow.append(createScoreStatusStrip(record, false, "score-detail-rank-slot", ["rank"]));
    const achievement = document.createElement("div");
    achievement.className = "score-detail-result-achievement";
    achievement.append(
      createTextElement("span", "", "达成率"),
      createTextElement("strong", "score-detail-achievement", `${record.achievement.toFixed(4)}%`)
    );
    resultRow.append(achievement);

    const metrics = document.createElement("dl");
    metrics.className = "score-detail-metrics lxns-detail-metrics";
    metrics.append(
      createScoreDetailMetric("游玩时间", formatPlayedAt(record)),
      createScoreDetailMetric("谱面", `${record.chartType === "dx" ? "DX" : "STD"} · ${displayDifficulty(record.difficulty)}`),
      createScoreDetailMetric("DX 分", record.dxScore === null ? "—" : formatInteger(record.dxScore)),
      createScoreDetailMetric("成绩等级", record.rank || ACHIEVEMENT_RANK_LABELS[achievementRankKey(record.achievement)] || "—")
    );
    const judgments = JUDGMENT_DETAILS.createDetails(document, record.judgmentDetails);
    judgments.open = true;
    detail.append(
      songRow,
      resultRow,
      metrics,
      createPlayDetailsSection(record.playDetails),
      judgments
    );
    elements.scoreDetailContent.replaceChildren(detail);
  }

  function openPlayRecordDetail(record, trigger) {
    state.selectedPlayRecord = record;
    state.playHistoryTrigger = trigger instanceof HTMLElement ? trigger : null;
    state.scoreDetailKey = null;
    state.scoreDetailTrigger = null;
    renderPlayRecordDetail(record);
    showModal(elements.scoreDetailDialog);
    window.requestAnimationFrame(() => elements.closeScoreDetailButton.focus());
  }

  function renderScoreDetail(chart) {
    const song = catalogMatchForChart(chart);
    const catalogChart = Array.isArray(song?.charts)
      ? song.charts.find((item) =>
          normalizeDifficulty(item?.difficulty) === normalizeDifficulty(chart.difficulty)
        ) || null
      : null;
    const catalogConstant = finiteNumber(catalogChart?.constant, Number.NaN);
    const detailConstant = Number.isFinite(catalogConstant) &&
        catalogConstant >= 1 && catalogConstant <= 15.9
      ? catalogConstant
      : chart.level;
    const rawDisplayLevel = catalogChart?.displayLevel;
    const displayLevel = ["string", "number"].includes(typeof rawDisplayLevel) &&
        String(rawDisplayLevel).trim()
      ? String(rawDisplayLevel).trim()
      : "曲库未提供";
    const poolName = chart.version === "legacy" ? "旧曲 B35" : "新曲 B15";
    const poolLimit = chart.version === "legacy" ? 35 : 15;
    const poolRank = chart.categoryRank
      ? `第 ${chart.categoryRank} 名 / ${poolLimit}`
      : "暂无名次";
    const unavailable = "曲库未提供";

    elements.scoreDetailDialog.dataset.difficulty = difficultyClass(chart.difficulty)
      .replace("difficulty-", "");
    elements.scoreDetailTitle.textContent = "成绩详情";
    elements.scoreDetailPool.textContent = chart.categoryRank
      ? `${poolName} · 第 ${chart.categoryRank} 名`
      : poolName;
    elements.scoreDetailSummary.textContent = "已保存成绩与已抓取的逐局玩家判定";

    const detail = document.createElement("section");
    detail.className = "score-detail-lxns lxns-score-detail";

    const songRow = document.createElement("section");
    songRow.className = "score-detail-song-row lxns-detail-song";
    songRow.setAttribute("aria-label", "歌曲信息");
    const cover = document.createElement("img");
    cover.className = "score-detail-cover lxns-detail-cover";
    cover.width = 94;
    cover.height = 94;
    cover.alt = `${chart.title} 封面`;
    cover.decoding = "async";
    setScoreCover(cover, chart, song);

    const songCopy = document.createElement("div");
    songCopy.className = "score-detail-song-copy lxns-detail-song-copy";
    const songBadges = document.createElement("div");
    songBadges.className = "score-detail-song-badges";
    songBadges.append(
      createTextElement(
        "span",
        "score-detail-chart-type",
        chart.chartType === "standard" ? "STD" : "DX"
      ),
      createTextElement("span", "score-detail-song-id", `SongID ${chart.songId}`)
    );
    songCopy.append(
      songBadges,
      createTextElement("strong", "score-detail-song-title lxns-detail-song-title", chart.title),
      createTextElement(
        "span",
        "score-detail-song-artist lxns-detail-song-meta",
        song?.artist || unavailable
      ),
      createScoreStatusStrip(
        chart,
        false,
        "score-detail-status-slots",
        ["combo", "sync"]
      )
    );

    const constant = document.createElement("div");
    constant.className = "score-detail-constant lxns-detail-constant";
    constant.append(
      createTextElement("span", "", "定数"),
      createTextElement("strong", "", detailConstant.toFixed(1))
    );
    songRow.append(cover, songCopy, constant);

    const resultRow = document.createElement("section");
    resultRow.className = "score-detail-result-row lxns-detail-result";
    resultRow.setAttribute("aria-label", "成绩");
    resultRow.append(
      createScoreStatusStrip(chart, false, "score-detail-rank-slot", ["rank"])
    );
    const achievement = document.createElement("div");
    achievement.className = "score-detail-result-achievement";
    achievement.append(
      createTextElement("span", "", "达成率"),
      createTextElement("strong", "score-detail-achievement", `${chart.achievement.toFixed(4)}%`)
    );
    resultRow.append(achievement);

    const metrics = document.createElement("dl");
    metrics.className = "score-detail-metrics lxns-detail-metrics";
    metrics.append(
      createScoreDetailMetric("DX Rating", formatInteger(chart.rating)),
      createScoreDetailMetric(
        "难度",
        displayLevel === "曲库未提供"
          ? `${displayDifficulty(chart.difficulty)} · ${displayLevel}`
          : `${displayDifficulty(chart.difficulty)} · Lv ${displayLevel}`
      ),
      createScoreDetailMetric("分池名次", `${poolName} · ${poolRank}`, true)
    );

    const history = createScoreHistoryShell(chart);
    detail.append(
      songRow,
      resultRow,
      metrics,
      createCatalogChartDetails(chart),
      history
    );
    elements.scoreDetailContent.replaceChildren(detail);
    void loadScoreHistory(chart, history);
  }

  function openScoreDetail(key) {
    const chart = state.calculation?.charts.find((item) => item.key === key);
    if (!chart) return;
    state.scoreDetailKey = key;
    state.selectedPlayRecord = null;
    state.playHistoryTrigger = null;
    elements.editScoreDetailButton.hidden = false;
    elements.deleteScoreDetailButton.hidden = false;
    state.scoreDetailTrigger = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
    renderScoreDetail(chart);
    showModal(elements.scoreDetailDialog);
    window.requestAnimationFrame(() => elements.closeScoreDetailButton.focus());
  }

  function closeScoreDetail() {
    const playReturnFocus = state.playHistoryTrigger?.isConnected
      ? state.playHistoryTrigger
      : null;
    const detailKey = state.scoreDetailKey;
    const returnFocus = state.scoreDetailTrigger?.isConnected
      ? state.scoreDetailTrigger
      : Array.from(document.querySelectorAll("button[data-score-detail-key]"))
          .find((button) => button.dataset.scoreDetailKey === detailKey) || null;
    state.scoreDetailKey = null;
    state.scoreDetailTrigger = null;
    state.selectedPlayRecord = null;
    state.playHistoryTrigger = null;
    state.scoreHistoryController?.abort();
    state.scoreHistoryController = null;
    state.scoreHistorySequence++;
    closeModal(elements.scoreDetailDialog);
    const focusTarget = playReturnFocus || returnFocus;
    if (focusTarget?.isConnected) {
      window.requestAnimationFrame(() => focusTarget.focus());
    }
  }

  function useScoreDetailAction(action) {
    const key = state.scoreDetailKey;
    if (!key) return;
    state.scoreDetailKey = null;
    state.scoreDetailTrigger = null;
    closeModal(elements.scoreDetailDialog);
    action(key);
  }

  function render() {
    if (!state.calculation) {
      state.calculation = calculateLocally();
    }

    const calculation = state.calculation;
    const selectedTotal = calculation.oldCount + calculation.newCount;
    elements.totalRating.textContent = formatInteger(calculation.total);
    elements.oldRating.textContent = formatInteger(calculation.oldTotal);
    elements.newRating.textContent = formatInteger(calculation.newTotal);
    elements.selectedCount.textContent = `已入选 ${selectedTotal} / 50`;
    elements.oldCount.textContent = `${calculation.oldCount} / 35`;
    elements.newCount.textContent = `${calculation.newCount} / 15`;
    elements.libraryCount.textContent = formatInteger(state.charts.length);
    elements.exportButton.disabled = state.charts.length === 0 ||
      (state.authenticated && !state.authReady);

    renderScoreComposition(calculation);
    if (state.scoreView === "list") renderScoreList(calculation);

    if (state.scoreDetailKey) {
      const detailChart = calculation.charts.find((chart) => chart.key === state.scoreDetailKey);
      if (detailChart) {
        renderScoreDetail(detailChart);
      } else {
        closeScoreDetail();
      }
    }
  }

  function showModal(dialog) {
    if (typeof dialog.showModal === "function") {
      dialog.showModal();
    } else {
      dialog.setAttribute("open", "");
    }
  }

  function closeModal(dialog) {
    if (typeof dialog.close === "function") {
      dialog.close();
    } else {
      dialog.removeAttribute("open");
    }
  }

  function clearFormError() {
    elements.formError.hidden = true;
    elements.formError.textContent = "";
  }

  function hideSongSearchResults() {
    elements.songSearchResults.hidden = true;
    elements.songSearchResults.replaceChildren();
    elements.titleInput.setAttribute("aria-expanded", "false");
    state.songSearchResults = [];
  }

  function createSongResultButton(song, index) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "song-search-option";
    button.setAttribute("role", "option");
    button.setAttribute("aria-selected", "false");
    button.dataset.songIndex = String(index);
    const image = document.createElement("img");
    image.src = song.coverUrl || COVER_PLACEHOLDER_URL;
    image.alt = "";
    image.loading = "lazy";
    image.addEventListener("error", () => {
      if (!image.src.endsWith(COVER_PLACEHOLDER_URL)) image.src = COVER_PLACEHOLDER_URL;
    });
    button.append(image);
    const copy = document.createElement("span");
    copy.className = "song-option-copy";
    const title = document.createElement("strong");
    title.textContent = song.title;
    const meta = document.createElement("span");
    const typeText = song.chartType === "standard" ? "STD" : "DX";
    const idSourceText = song.idSource === "diving-fish" ? "联网 ID" : "本地 ID";
    meta.textContent = [song.artist, typeText, song.version, idSourceText, `SongID ${song.songId}`]
      .filter(Boolean).join(" · ");
    copy.append(title, meta);
    button.append(copy);
    return button;
  }

  function renderSongSearchResults(songs, query) {
    state.songSearchResults = songs.slice(0, 20);
    elements.songSearchResults.replaceChildren();
    if (state.songSearchResults.length === 0) {
      const empty = document.createElement("p");
      empty.className = "song-search-empty";
      empty.textContent = query ? "曲库中没有匹配曲目，可继续手动填写。" : "请输入曲名。";
      elements.songSearchResults.append(empty);
    } else {
      state.songSearchResults.forEach((song, index) => {
        elements.songSearchResults.append(createSongResultButton(song, index));
      });
    }
    elements.songSearchResults.hidden = false;
    elements.titleInput.setAttribute("aria-expanded", "true");
  }

  function updateSelectedSongPreview(song = null) {
    state.selectedCatalogSong = song;
    const coverUrl = normalizeCoverUrl(song?.coverUrl ?? state.editingCoverUrl);
    const title = String(song?.title ?? elements.titleInput.value).trim();
    if (!title) {
      elements.selectedSong.hidden = true;
      elements.selectedSongCover.removeAttribute("src");
      return;
    }
    elements.selectedSong.hidden = false;
    elements.selectedSongTitle.textContent = title;
    elements.selectedSongMeta.textContent = song
      ? [
          song.artist,
          song.chartType === "standard" ? "STD" : "DX",
          song.version,
          `SongID ${song.songId}`
        ].filter(Boolean).join(" · ")
      : `SongID ${elements.idInput.value || "未填写"}`;
    elements.selectedSongCover.src = coverUrl || COVER_PLACEHOLDER_URL;
    elements.selectedSongCover.alt = `${title} 封面`;
    elements.selectedSongCover.hidden = false;
  }

  function applyCatalogChartDefaults(song) {
    if (!song || !Array.isArray(song.charts)) return;
    const level = constantForSong(song, elements.difficultyInput.value);
    if (Number.isFinite(level) && level >= 1 && level <= 15.9) {
      elements.levelInput.value = level.toFixed(1);
    }
  }

  function selectCatalogSong(song) {
    if (!song) return;
    elements.titleInput.value = song.title;
    elements.idInput.value = song.songId;
    elements.chartForm.elements.chartType.value = song.chartType;
    elements.chartForm.elements.version.value = versionCategoryForSong(song);
    state.editingCoverUrl = song.coverUrl;
    updateSelectedSongPreview(song);
    applyCatalogChartDefaults(song);
    hideSongSearchResults();
    elements.idInput.focus();
  }

  function scheduleSongSearch() {
    window.clearTimeout(state.songSearchTimer);
    if (state.songSearchController) state.songSearchController.abort();
    const query = elements.titleInput.value.trim();
    if (!query) {
      hideSongSearchResults();
      return;
    }
    state.songSearchTimer = window.setTimeout(async () => {
      const controller = new AbortController();
      state.songSearchController = controller;
      renderSongSearchResults(localSongSearch(query), query);
      try {
        const songs = await fetchSongSearch(query, controller.signal);
        if (controller.signal.aborted || elements.titleInput.value.trim() !== query) return;
        renderSongSearchResults(songs, query);
      } catch (error) {
        if (!(error instanceof DOMException && error.name === "AbortError")) {
          console.info("曲目搜索失败", error);
        }
      } finally {
        if (state.songSearchController === controller) state.songSearchController = null;
      }
    }, 180);
  }

  function openChartDialog(key = null) {
    state.editingKey = key;
    clearFormError();
    elements.chartForm.reset();
    elements.difficultyInput.value = "MASTER";
    elements.levelInput.value = "13.0";
    elements.achievementInput.value = "100.0000";
    elements.comboStatusInput.value = "";
    elements.syncStatusInput.value = "";
    elements.chartForm.elements.chartType.value = "dx";
    elements.chartForm.elements.version.value = "current";
    state.editingCoverUrl = "";
    state.selectedCatalogSong = null;
    hideSongSearchResults();
    updateSelectedSongPreview();

    if (key) {
      const chart = state.charts.find((item) => item.key === key);
      if (!chart) return;
      elements.dialogMode.textContent = "编辑谱面";
      elements.titleInput.value = chart.title;
      elements.idInput.value = chart.songId;
      elements.difficultyInput.value = chart.difficulty;
      elements.levelInput.value = chart.level.toFixed(1);
      elements.achievementInput.value = chart.achievement.toFixed(4);
      elements.comboStatusInput.value = chart.comboStatus;
      elements.syncStatusInput.value = chart.syncStatus;
      elements.chartForm.elements.chartType.value = chart.chartType;
      elements.chartForm.elements.version.value = chart.version;
      state.editingCoverUrl = chart.coverUrl;
      const catalogSong = catalogMatchForChart(chart);
      updateSelectedSongPreview(catalogSong || null);
    } else {
      elements.dialogMode.textContent = "新增谱面";
    }

    showModal(elements.chartDialog);
    window.requestAnimationFrame(() => elements.titleInput.focus());
  }

  async function handleChartSubmit(event) {
    event.preventDefault();
    clearFormError();

    if (!elements.chartForm.reportValidity()) {
      return;
    }

    const formData = new FormData(elements.chartForm);
    const chart = normalizeChart({
      key: state.editingKey || createKey(),
      songId: formData.get("songId"),
      title: formData.get("title"),
      chartType: formData.get("chartType"),
      difficulty: formData.get("difficulty"),
      level: formData.get("level"),
      achievement: formData.get("achievement"),
      version: formData.get("version"),
      coverUrl: state.editingCoverUrl,
      comboStatus: formData.get("comboStatus"),
      syncStatus: formData.get("syncStatus")
    });

    if (!isValidChart(chart)) {
      elements.formError.textContent = "请检查曲名、定数和达成率。";
      elements.formError.hidden = false;
      return;
    }

    const duplicate = state.charts.some((item) =>
      item.key !== state.editingKey && signature(item) === signature(chart)
    );
    if (duplicate) {
      elements.formError.textContent = "相同曲目 ID、谱面类型与难度的成绩已经存在。";
      elements.formError.hidden = false;
      return;
    }

    const nextCharts = state.editingKey
      ? state.charts.map((item) => item.key === state.editingKey ? chart : item)
      : [...state.charts, chart];
    const versionConflict = findVersionConflict(nextCharts);
    if (versionConflict) {
      elements.formError.textContent = `曲目 ID ${versionConflict} 的所有谱面必须属于同一版本分类。`;
      elements.formError.hidden = false;
      return;
    }

    const editing = Boolean(state.editingKey);
    if (!await commitCharts(nextCharts)) return;
    closeModal(elements.chartDialog);
    state.editingKey = null;
    showToast(editing ? "谱面已更新并保存" : "谱面已添加并保存");
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
    elements.accountButton.removeAttribute("aria-haspopup");
    elements.logoutButton.hidden = !loggedIn;
    elements.logoutButton.disabled = state.authBusy || state.chartSaveBusy;
    elements.accountButton.disabled = state.authBusy || state.chartSaveBusy || !state.authReady;
    elements.storageDescription.textContent = loggedIn
      ? `成绩保存在用户 ${state.user.displayName} 的服务器空间`
      : state.authReady ? "请登录后使用个人成绩空间" : "正在确认当前用户的数据空间";
    if (loggedIn && !profileReady) {
      void window.B50ProfileTheme?.refresh({ force: true });
    }
    updateMutationControls();
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

  function openAccountPage() {
    window.location.assign(state.authenticated ? "/profile.html" : "/login.html");
  }

  function requireAuthentication() {
    document.body.classList.add("auth-checking");
    window.location.replace("/login.html");
  }

  function releaseAuthenticationGate() {
    document.body.classList.remove("auth-required");
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
        throw new Error(apiErrorMessage(payload, `读取用户谱面失败（HTTP ${response.status}）`));
      }
      const expectedUserId = String(state.user?.id ?? "");
      if (String(payload.userId ?? "") !== expectedUserId) {
        handleSessionExpired("服务器返回的用户身份不一致，请重新登录");
        throw new DOMException("用户身份不一致", "AbortError");
      }
      state.userRevision = revisionFromPayload(payload);
      let charts = normalizeChartCollection(payload.charts);
      const guestCharts = loadLegacyLocalCharts();
      if (offerMigration && charts.length === 0 && guestCharts.length > 0) {
        const migrate = window.confirm(
          `用户「${state.user.displayName}」的谱面库为空。\n\n` +
          `检测到旧版浏览器本地保存的 ${guestCharts.length} 条成绩，是否复制到该用户？\n\n` +
          "选择“取消”会保留旧数据，但本站不会在未登录状态下使用它。"
        );
        if (sessionToken !== state.sessionSequence || !state.authenticated) return false;
        if (migrate) {
          await saveRemoteCharts(guestCharts, sessionToken);
          charts = guestCharts;
          showToast(`已将 ${guestCharts.length} 条旧版本地成绩复制到用户空间`);
        }
      }
      if (sessionToken !== state.sessionSequence || !state.authenticated) return false;
      state.charts = charts;
      state.authReady = true;
      hydrateChartCovers();
      renderAccount();
      scheduleApiCalculation();
      return true;
    } catch (error) {
      if (sessionToken !== state.sessionSequence) return false;
      if (error instanceof DOMException && error.name === "AbortError") return false;
      if (error instanceof RevisionConflictError) {
        return await resynchronizeSessionAfterConflict();
      }
      state.charts = [];
      state.authReady = false;
      renderAccount();
      scheduleApiCalculation();
      showToast(error instanceof Error ? error.message : "无法读取用户谱面库");
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
      state.userRevision = null;
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
      if (response.ok && payload.authenticated === true && user) {
        finishAuthStartup();
        state.authenticated = true;
        state.user = user;
        state.userRevision = null;
        releaseAuthenticationGate();
        await loadUserCharts(sessionToken, true);
      } else {
        requireAuthentication();
      }
    } catch (error) {
      if (sessionToken !== state.sessionSequence) return;
      console.info("账户服务暂不可用，转到登录页面", error);
      requireAuthentication();
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
      scheduleApiCalculation();
      window.location.replace("/login.html");
    } catch (error) {
      showToast(error instanceof Error ? error.message : "退出失败");
    } finally {
      state.authBusy = false;
      renderAccount();
    }
  }

  function extractSongs(payload) {
    const values = Array.isArray(payload)
      ? payload
      : payload && [payload.songs, payload.results, payload.items].find(Array.isArray);
    if (!Array.isArray(values)) return [];
    return values.flatMap((source) => {
      const song = source && typeof source === "object" ? source : {};
      const rawCharts = Array.isArray(song.charts) ? song.charts : [];
      const chartTypes = Array.from(new Set(rawCharts
        .map((chart) => normalizeChartType(chart?.chartType))
        .filter(Boolean)));
      const legacyType = normalizeChartType(song.chartType);
      if (chartTypes.length === 0 && legacyType) chartTypes.push(legacyType);
      return chartTypes.map((chartType) => ({
        songId: String(song.songId ?? song.id ?? "").trim(),
        title: String(song.title ?? song.name ?? "").trim(),
        artist: String(song.artist ?? "").trim(),
        category: String(song.category ?? song.genre ?? "").trim(),
        version: String(song.version ?? "").trim(),
        chartType,
        aliases: Array.isArray(song.aliases)
          ? song.aliases.map((alias) => String(alias ?? "").trim()).filter(Boolean)
          : [],
        idSource: String(song.idSource ?? "").trim(),
        coverUrl: normalizeCoverUrl(song.coverUrl ?? song.cover ?? song.jacketUrl ?? ""),
        charts: rawCharts.filter((chart) =>
          (normalizeChartType(chart?.chartType) || legacyType) === chartType
        )
      }));
    }).filter((song) => song.songId && song.title && CHART_TYPES.includes(song.chartType));
  }

  function catalogIdentity(songId, chartType) {
    return `${String(songId)}\u0000${normalizeChartType(chartType) || ""}`;
  }

  function catalogTitleIdentity(title, chartType) {
    return `${normalizedSongTitle(title)}\u0000${normalizeChartType(chartType) || ""}`;
  }

  function catalogSongById(songId, chartType) {
    return state.catalogById.get(catalogIdentity(songId, chartType)) || null;
  }

  function exactCatalogSongs(title, chartType) {
    if (!normalizeChartType(chartType)) return [];
    return state.catalogByTitle.get(catalogTitleIdentity(title, chartType)) || [];
  }

  function uniqueCatalogSong(title, chartType) {
    const matches = exactCatalogSongs(title, chartType);
    return matches.length === 1 ? matches[0] : null;
  }

  function versionCategoryForSong(song) {
    return song?.version === "舞萌DX 2026" ? "current" : "legacy";
  }

  function constantForSong(song, difficulty) {
    const match = song?.charts?.find((chart) =>
      normalizeDifficulty(chart?.difficulty) === normalizeDifficulty(difficulty)
    );
    const constant = finiteNumber(match?.constant, Number.NaN);
    return Number.isFinite(constant) && constant >= 1 && constant <= 15.9
      ? constant
      : Number.NaN;
  }

  function uniqueDifficultyForConstant(song, level) {
    if ((typeof level !== "number" && typeof level !== "string") ||
        String(level).trim() === "") {
      return null;
    }
    const target = Number(level);
    if (!Number.isFinite(target)) return null;

    const matches = new Set();
    const charts = Array.isArray(song?.charts) ? song.charts : [];
    charts.forEach((chart) => {
      const difficulty = normalizeDifficulty(chart?.difficulty);
      const rawConstant = chart?.constant;
      if (!difficulty || (typeof rawConstant !== "number" && typeof rawConstant !== "string") ||
          String(rawConstant).trim() === "") {
        return;
      }
      const constant = Number(rawConstant);
      if (Number.isFinite(constant) && constant === target) matches.add(difficulty);
    });
    return matches.size === 1 ? matches.values().next().value : null;
  }

  function rebuildCatalogIndexes() {
    state.catalogById = new Map();
    state.catalogByTitle = new Map();
    state.catalog.forEach((song) => {
      state.catalogById.set(catalogIdentity(song.songId, song.chartType), song);
      const titleKey = catalogTitleIdentity(song.title, song.chartType);
      const songs = state.catalogByTitle.get(titleKey) || [];
      songs.push(song);
      state.catalogByTitle.set(titleKey, songs);
    });
  }

  function mergeCatalogSongs(songs) {
    const merged = new Map(state.catalog.map((song) => [
      catalogIdentity(song.songId, song.chartType), song
    ]));
    songs.forEach((song) => merged.set(catalogIdentity(song.songId, song.chartType), song));
    state.catalog = Array.from(merged.values());
    rebuildCatalogIndexes();
  }

  function catalogMatchForChart(chart) {
    const byId = catalogSongById(chart.songId, chart.chartType);
    if (byId) return byId;
    return uniqueCatalogSong(chart.title, chart.chartType);
  }

  function hydrateChartCovers() {
    if (!state.catalogReady || state.catalog.length === 0) return;
    let changed = false;
    state.charts = state.charts.map((chart) => {
      if (chart.coverUrl) return chart;
      const song = catalogMatchForChart(chart);
      if (!song?.coverUrl) return chart;
      changed = true;
      return { ...chart, coverUrl: song.coverUrl };
    });
    if (changed) {
      state.calculation = calculateLocally();
      render();
    }
  }

  async function loadSongCatalog() {
    try {
      const response = await fetch(SONG_CATALOG_ENDPOINT, {
        method: "GET",
        headers: { Accept: "application/json" }
      });
      const payload = await readJsonResponse(response);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      mergeCatalogSongs(extractSongs(payload));
      state.catalogReady = true;
      hydrateChartCovers();
      if (state.scoreDetailKey) {
        const detailChart = state.calculation?.charts.find(
          (chart) => chart.key === state.scoreDetailKey
        );
        if (detailChart) renderScoreDetail(detailChart);
      }
    } catch (error) {
      state.catalogReady = false;
      console.info("曲库目录暂不可用，仍可手动填写曲目 ID", error);
    }
  }

  function localSongSearch(query) {
    const normalized = normalizedSongTitle(query);
    if (!normalized) return [];
    return state.catalog
      .filter((song) => normalizedSongTitle(song.title).includes(normalized) ||
        song.aliases.some((alias) => normalizedSongTitle(alias).includes(normalized)) ||
        song.songId.toLocaleLowerCase("zh-CN").includes(normalized) ||
        normalizedSongTitle(song.artist).includes(normalized) ||
        normalizedSongTitle(song.version).includes(normalized) ||
        normalizedSongTitle(song.category).includes(normalized) ||
        song.chartType.includes(normalized))
      .slice(0, 20);
  }

  async function fetchSongSearch(query, signal) {
    const params = new URLSearchParams({ q: query, online: "false", limit: "20" });
    try {
      const response = await fetch(`${SONG_SEARCH_ENDPOINT}?${params}`, {
        method: "GET",
        headers: { Accept: "application/json" },
        signal
      });
      const payload = await readJsonResponse(response);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const songs = extractSongs(payload);
      mergeCatalogSongs(songs);
      if (songs.length > 0) {
        state.catalogReady = true;
        hydrateChartCovers();
      }
      return songs;
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") throw error;
      return localSongSearch(query);
    }
  }

  function openDeleteDialog(key) {
    const chart = state.charts.find((item) => item.key === key);
    if (!chart) return;
    state.deletingKey = key;
    elements.deleteChartName.textContent = chart.title;
    showModal(elements.deleteDialog);
  }

  async function confirmDelete() {
    if (!state.deletingKey) return;
    const deletingKey = state.deletingKey;
    const nextCharts = state.charts.filter((chart) => chart.key !== deletingKey);
    if (!await commitCharts(nextCharts)) return;
    state.deletingKey = null;
    closeModal(elements.deleteDialog);
    showToast("谱面已删除并保存");
  }

  function parseCsv(text) {
    const rows = [];
    let row = [];
    let cell = "";
    let quoted = false;

    for (let index = 0; index < text.length; index += 1) {
      const character = text[index];
      if (quoted) {
        if (character === '"' && text[index + 1] === '"') {
          cell += '"';
          index += 1;
        } else if (character === '"') {
          quoted = false;
        } else {
          cell += character;
        }
      } else if (character === '"') {
        quoted = true;
      } else if (character === ",") {
        row.push(cell);
        cell = "";
      } else if (character === "\n") {
        row.push(cell.replace(/\r$/, ""));
        if (row.some((value) => value.trim())) rows.push(row);
        row = [];
        cell = "";
      } else {
        cell += character;
      }
    }

    if (quoted) {
      throw new Error("CSV 存在未闭合的引号");
    }

    row.push(cell.replace(/\r$/, ""));
    if (row.some((value) => value.trim())) rows.push(row);
    return rows;
  }

  function findHeaderIndex(headers, aliases) {
    return headers.findIndex((header) => aliases.includes(header));
  }

  function chartsFromCsv(text) {
    const rows = parseCsv(text.replace(/^\uFEFF/, ""));
    if (rows.length < 2) {
      throw new Error("CSV 中没有可导入的数据");
    }

    const headers = rows[0].map((header) => header.trim().toLowerCase());
    const indexes = {
      songId: findHeaderIndex(headers, ["songid", "song_id", "id", "曲目id", "谱面id"]),
      title: findHeaderIndex(headers, ["title", "song", "曲名", "曲目"]),
      chartType: findHeaderIndex(headers, ["charttype", "chart_type", "type", "谱面类型", "譜面類型"]),
      difficulty: findHeaderIndex(headers, ["difficulty", "难度"]),
      level: findHeaderIndex(headers, ["level", "constant", "定数", "谱面定数"]),
      achievement: findHeaderIndex(headers, ["achievement", "achievementrate", "达成率"]),
      comboStatus: findHeaderIndex(headers, [
        "combostatus", "combo_status", "fcstatus", "fc_status", "fc", "combo状态"
      ]),
      syncStatus: findHeaderIndex(headers, [
        "syncstatus", "sync_status", "fsstatus", "fs_status", "sync", "同步状态"
      ]),
      version: findHeaderIndex(headers, ["version", "category", "版本", "分类"]),
      coverUrl: findHeaderIndex(headers, ["coverurl", "cover_url", "cover", "封面"]),
      format: findHeaderIndex(headers, ["format", "_format", "b50format"])
    };

    const requiredColumns = [
      "songId", "title", "chartType", "difficulty", "level", "achievement", "version"
    ];
    if (requiredColumns.some((name) => indexes[name] < 0)) {
      throw new Error("CSV 表头需包含 songId、title、chartType、difficulty、level、achievement、version");
    }

    const imported = [];
    rows.slice(1).forEach((values, rowIndex) => {
      const isWorkbenchExport = indexes.format >= 0 &&
        values[indexes.format] === "maimai-b50-v1";
      const chart = normalizeChart({
        songId: isWorkbenchExport
          ? restoreSpreadsheetSafeText(values[indexes.songId])
          : values[indexes.songId],
        title: isWorkbenchExport
          ? restoreSpreadsheetSafeText(values[indexes.title])
          : values[indexes.title],
        chartType: values[indexes.chartType],
        difficulty: values[indexes.difficulty],
        level: values[indexes.level],
        achievement: values[indexes.achievement],
        comboStatus: indexes.comboStatus >= 0 ? values[indexes.comboStatus] : "",
        syncStatus: indexes.syncStatus >= 0 ? values[indexes.syncStatus] : "",
        version: values[indexes.version],
        coverUrl: indexes.coverUrl >= 0 ? values[indexes.coverUrl] : ""
      });
      if (!isValidChart(chart)) {
        throw new Error(`CSV 第 ${rowIndex + 2} 行数据无效`);
      }
      imported.push(chart);
    });
    return imported;
  }

  async function importCsv(file) {
    try {
      const imported = chartsFromCsv(await file.text());
      const combined = new Map(state.charts.map((chart) => [signature(chart), chart]));
      let updated = 0;
      let added = 0;

      imported.forEach((chart) => {
        const key = signature(chart);
        const existing = combined.get(key);
        if (existing) {
          combined.set(key, {
            ...chart,
            key: existing.key,
            coverUrl: chart.coverUrl || existing.coverUrl,
            comboStatus: chart.comboStatus || existing.comboStatus,
            syncStatus: chart.syncStatus || existing.syncStatus
          });
          updated += 1;
        } else {
          combined.set(key, chart);
          added += 1;
        }
      });

      const mergedCharts = Array.from(combined.values());
      const versionConflict = findVersionConflict(mergedCharts);
      if (versionConflict) {
        throw new Error(`曲目 ID ${versionConflict} 同时被分入新曲与旧曲`);
      }
      if (!await commitCharts(mergedCharts)) return;
      showToast(`CSV 导入完成：新增 ${added} 条，更新 ${updated} 条`);
    } catch (error) {
      showToast(error instanceof Error ? error.message : "CSV 导入失败");
    } finally {
      elements.csvInput.value = "";
    }
  }

  function csvCell(value) {
    const text = String(value ?? "");
    return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
  }

  function spreadsheetSafeText(value) {
    const text = String(value ?? "");
    return text.startsWith("'") || /^[=+\-@]/.test(text) ? `'${text}` : text;
  }

  function restoreSpreadsheetSafeText(value) {
    const text = String(value ?? "");
    if (text.startsWith("''")) {
      return text.slice(1);
    }
    return /^'[=+\-@]/.test(text) ? text.slice(1) : text;
  }

  function exportCsv() {
    if (state.charts.length === 0) return;
    const calculatedByKey = new Map(state.calculation.charts.map((chart) => [chart.key, chart]));
    const rows = [[
      "songId", "title", "chartType", "difficulty", "level", "achievement",
      "comboStatus", "syncStatus", "version", "coverUrl", "rating", "selected", "format"
    ]];

    state.charts.forEach((chart) => {
      const calculated = calculatedByKey.get(chart.key) || chart;
      rows.push([
        spreadsheetSafeText(chart.songId),
        spreadsheetSafeText(chart.title),
        chart.chartType,
        chart.difficulty,
        chart.level.toFixed(1),
        chart.achievement.toFixed(4),
        chart.comboStatus,
        chart.syncStatus,
        chart.version,
        spreadsheetSafeText(chart.coverUrl),
        calculated.rating ?? calculateRating(chart),
        calculated.selected ? "true" : "false",
        "maimai-b50-v1"
      ]);
    });

    const csv = `\uFEFF${rows.map((row) => row.map(csvCell).join(",")).join("\r\n")}`;
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `maimai-b50-${new Date().toISOString().slice(0, 10)}.csv`;
    document.body.append(anchor);
    anchor.click();
    anchor.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
    showToast("CSV 已导出");
  }

  function showToast(message) {
    window.clearTimeout(state.toastTimer);
    elements.toast.textContent = message;
    elements.toast.hidden = false;
    state.toastTimer = window.setTimeout(() => {
      elements.toast.hidden = true;
    }, 3200);
  }

  function bindEvents() {
    [
      elements.b35CompositionList,
      elements.b15CompositionList,
      elements.scoreListGrid
    ].forEach((list) => {
      list.addEventListener("click", (event) => {
        const button = event.target.closest("button[data-score-detail-key]");
        if (button && list.contains(button)) openScoreDetail(button.dataset.scoreDetailKey);
      });
    });
    const scoreViewTabs = [
      elements.compositionViewTab,
      elements.scoreListViewTab,
      elements.playHistoryViewTab
    ];
    scoreViewTabs.forEach((tab, index) => {
      tab.addEventListener("click", () => setScoreView(tab.dataset.scoreView));
      tab.addEventListener("keydown", (event) => {
        if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
        event.preventDefault();
        const offset = event.key === "ArrowLeft" ? -1 : 1;
        const next = scoreViewTabs[(index + offset + scoreViewTabs.length) % scoreViewTabs.length];
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
    elements.scoreListSearchInput.addEventListener("input", () => {
      state.scoreList.query = elements.scoreListSearchInput.value;
      state.scoreList.page = 1;
      renderScoreList(state.calculation || calculateLocally());
    });
    elements.scoreListVersionFilter.addEventListener("change", () => {
      state.scoreList.version = elements.scoreListVersionFilter.value;
      state.scoreList.page = 1;
      renderScoreList(state.calculation || calculateLocally());
    });
    elements.scoreListDifficultyFilter.addEventListener("change", () => {
      state.scoreList.difficulty = elements.scoreListDifficultyFilter.value;
      state.scoreList.page = 1;
      renderScoreList(state.calculation || calculateLocally());
    });
    elements.scoreListChartTypeFilter.addEventListener("change", () => {
      state.scoreList.chartType = elements.scoreListChartTypeFilter.value;
      state.scoreList.page = 1;
      renderScoreList(state.calculation || calculateLocally());
    });
    elements.scoreListSort.addEventListener("change", () => {
      state.scoreList.sort = elements.scoreListSort.value;
      state.scoreList.page = 1;
      renderScoreList(state.calculation || calculateLocally());
    });
    elements.scoreListPrev.addEventListener("click", () => {
      state.scoreList.page = Math.max(1, state.scoreList.page - 1);
      renderScoreList(state.calculation || calculateLocally());
    });
    elements.scoreListNext.addEventListener("click", () => {
      state.scoreList.page += 1;
      renderScoreList(state.calculation || calculateLocally());
    });
    elements.editScoreDetailButton.addEventListener("click", () => {
      useScoreDetailAction((key) => openChartDialog(key));
    });
    elements.deleteScoreDetailButton.addEventListener("click", () => {
      useScoreDetailAction((key) => openDeleteDialog(key));
    });
    elements.closeScoreDetailButton.addEventListener("click", closeScoreDetail);
    elements.scoreDetailDialog.addEventListener("click", (event) => {
      if (event.target === elements.scoreDetailDialog) closeScoreDetail();
    });
    elements.scoreDetailDialog.addEventListener("cancel", (event) => {
      event.preventDefault();
      closeScoreDetail();
    });

    elements.addButton.addEventListener("click", () => openChartDialog());
    elements.closeDialogButton.addEventListener("click", () => closeModal(elements.chartDialog));
    elements.cancelDialogButton.addEventListener("click", () => closeModal(elements.chartDialog));
    elements.chartForm.addEventListener("submit", handleChartSubmit);
    elements.titleInput.addEventListener("input", () => {
      if (state.selectedCatalogSong &&
          elements.titleInput.value !== state.selectedCatalogSong.title) {
        state.selectedCatalogSong = null;
        state.editingCoverUrl = "";
        elements.idInput.value = "";
        updateSelectedSongPreview();
      }
      scheduleSongSearch();
    });
    elements.titleInput.addEventListener("keydown", (event) => {
      if (event.key === "Escape") {
        hideSongSearchResults();
        return;
      }
      if (event.key === "ArrowDown" && !elements.songSearchResults.hidden) {
        const first = elements.songSearchResults.querySelector("button");
        if (first) {
          event.preventDefault();
          first.focus();
        }
      }
    });
    elements.songSearchResults.addEventListener("keydown", (event) => {
      const buttons = Array.from(elements.songSearchResults.querySelectorAll("button"));
      const index = buttons.indexOf(document.activeElement);
      if (event.key === "ArrowDown" && index >= 0) {
        event.preventDefault();
        buttons[Math.min(index + 1, buttons.length - 1)]?.focus();
      } else if (event.key === "ArrowUp" && index >= 0) {
        event.preventDefault();
        if (index === 0) elements.titleInput.focus();
        else buttons[index - 1]?.focus();
      } else if (event.key === "Escape") {
        hideSongSearchResults();
        elements.titleInput.focus();
      }
    });
    elements.songSearchResults.addEventListener("click", (event) => {
      const button = event.target.closest("button[data-song-index]");
      if (!button) return;
      selectCatalogSong(state.songSearchResults[Number(button.dataset.songIndex)]);
    });
    elements.idInput.addEventListener("change", () => {
      const song = catalogSongById(
        elements.idInput.value.trim(),
        elements.chartForm.elements.chartType.value
      );
      if (song && normalizedSongTitle(song.title) === normalizedSongTitle(elements.titleInput.value)) {
        state.editingCoverUrl = song.coverUrl;
        updateSelectedSongPreview(song);
      } else {
        state.selectedCatalogSong = null;
        updateSelectedSongPreview();
      }
    });
    elements.selectedSongCover.addEventListener("error", () => {
      if (!elements.selectedSongCover.src.endsWith(COVER_PLACEHOLDER_URL)) {
        elements.selectedSongCover.src = COVER_PLACEHOLDER_URL;
      }
    });
    elements.difficultyInput.addEventListener("change", () => applyCatalogChartDefaults(state.selectedCatalogSong));
    elements.chartForm.addEventListener("change", (event) => {
      if (event.target.name === "chartType") {
        const selected = state.selectedCatalogSong;
        if (selected && selected.chartType !== event.target.value) {
          const sibling = uniqueCatalogSong(selected.title, event.target.value);
          if (sibling) {
            elements.idInput.value = sibling.songId;
            elements.chartForm.elements.version.value = versionCategoryForSong(sibling);
            state.editingCoverUrl = sibling.coverUrl;
            updateSelectedSongPreview(sibling);
            applyCatalogChartDefaults(sibling);
            return;
          }
          state.selectedCatalogSong = null;
          state.editingCoverUrl = "";
          elements.idInput.value = "";
          updateSelectedSongPreview();
        } else {
          applyCatalogChartDefaults(selected);
        }
      }
    });

    elements.accountButton.addEventListener("click", openAccountPage);
    elements.logoutButton.addEventListener("click", logout);


    elements.csvInput.addEventListener("change", () => {
      const [file] = elements.csvInput.files;
      if (file) importCsv(file);
    });
    elements.importButton.addEventListener("click", () => elements.csvInput.click());
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
      elements.storageDescription.textContent =
        `成绩保存在用户 ${displayName} 的服务器空间`;
    });

    elements.cancelDeleteButton.addEventListener("click", () => {
      state.deletingKey = null;
      closeModal(elements.deleteDialog);
    });
    elements.confirmDeleteButton.addEventListener("click", confirmDelete);

    [elements.chartDialog, elements.deleteDialog].forEach((dialog) => {
      dialog.addEventListener("click", (event) => {
        if (event.target === dialog) closeModal(dialog);
      });
    });
    document.addEventListener("pointerdown", (event) => {
      if (!event.target.closest("#chart-title, #song-search-results")) {
        hideSongSearchResults();
      }
    });
  }

  bindEvents();
  state.calculation = calculateLocally();
  render();
  scheduleApiCalculation();
  renderAccount();
  loadSongCatalog();
  checkAuthStatus();
  if (startupWarning) {
    showToast(startupWarning);
  }
})();
