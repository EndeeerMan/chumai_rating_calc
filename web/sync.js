(() => {
  "use strict";

  const AUTH_STATUS_ENDPOINT = "/api/auth/status";
  const SYNC_SESSIONS_ENDPOINT = "/api/sync/sessions";
  const SYNC_IMPORT_ENDPOINT = "/api/sync/import";
  const HISTORY_ENDPOINT = "/api/history";
  const CLASH_CONFIG_ENDPOINT = "/api/sync/clash-config";
  const PROXY_PORT = 8081;
  const HELPER_HEALTH_TIMEOUT_MS = 4000;
  const SESSION_TTL_MS = 15 * 60 * 1000;
  const POLL_INTERVAL_MS = 3000;
  const MAX_UPLOAD_ITEMS = 5;
  const MAX_FILE_BYTES = 5 * 1024 * 1024;

  const SESSION_STATUS_META = Object.freeze({
    created: ["会话已创建", "等待辅助程序启动并接收微信授权。", 18, "active"],
    pending: ["等待连接", "等待本地辅助程序连接短时会话。", 22, "active"],
    waiting: ["等待微信授权", "请复制或打开上方链接，并在微信中完成授权。", 30, "active"],
    waiting_helper: ["等待辅助程序", "请确认本地辅助程序正在监听 8081 端口。", 25, "active"],
    waiting_auth: ["等待微信授权", "请在微信中打开链接并完成授权。", 34, "active"],
    awaiting_authorization: ["等待微信授权", "请在微信中打开链接并完成授权。", 34, "active"],
    callback_received: ["已收到授权回调", "正在交换本次同步所需的临时凭证。", 48, "active"],
    authorized: ["授权成功", "正在读取所选游戏的成绩数据。", 58, "active"],
    fetching: ["正在抓取数据", "正在获取最佳成绩与最近游玩记录。", 72, "active"],
    syncing: ["正在同步", "数据已获取，正在写入当前玩家账号。", 84, "active"],
    importing: ["正在保存", "正在合并最佳成绩并追加独立游玩历史。", 90, "active"],
    completed: ["同步完成", "最佳成绩与最近游玩记录已经更新。", 100, "success"],
    succeeded: ["同步完成", "最佳成绩与最近游玩记录已经更新。", 100, "success"],
    success: ["同步完成", "最佳成绩与最近游玩记录已经更新。", 100, "success"],
    failed: ["同步失败", "本次会话未能完成，请查看提示后重新创建会话。", 100, "error"],
    error: ["同步失败", "本次会话未能完成，请查看提示后重新创建会话。", 100, "error"],
    expired: ["会话已过期", "15 分钟短时会话已经失效，请重新创建。", 100, "error"],
    cancelled: ["会话已取消", "请重新创建短时会话后再试。", 100, "error"]
  });

  const DETAIL_FAILURE_LABELS = Object.freeze({
    "missing-source-id": "官网未提供详情入口",
    "invalid-source-id": "详情入口格式无效",
    "request-timeout": "详情请求超时",
    "request-failed": "详情请求失败",
    "request-error": "详情请求失败",
    "transport-failure": "网络传输失败",
    "invalid-response": "官网响应无效",
    "invalid-detail-template": "官网返回的不是详情页",
    "detail-validation-failure": "详情页无法解析",
    "response-too-large": "官网响应过大",
    "non-retryable-status": "官网拒绝详情请求",
    "rate-limited": "官网请求限流",
    "upstream-error": "官网暂时异常",
    "row-timeout": "单条详情抓取超时",
    "unexpected-row-failure": "单条详情抓取异常",
    "unavailable": "详情暂不可用"
  });

  const state = {
    authenticated: false,
    authReady: false,
    authBusy: false,
    proxyHost: "",
    proxyHostValid: false,
    helperPath: "",
    sessionBusy: false,
    session: null,
    sessionGame: null,
    sessionId: null,
    sessionExpiresAt: 0,
    sessionPollTimer: null,
    sessionCountdownTimer: null,
    sessionRequestSequence: 0,
    lastDetailProgress: null,
    lastRenderedProgress: 0,
    historyFetchedForSession: null,
    uploadItems: [],
    uploadBusy: false,
    uploadSequence: 0,
    pasteSequence: 0,
    globalMessageTimer: null
  };

  const elements = {};

  document.addEventListener("DOMContentLoaded", initialize, { once: true });

  function initialize() {
    cacheElements();
    configureProxyDetails();
    bindTabs();
    bindCopyButtons();
    bindSessionActions();
    bindUploadActions();
    renderUploadQueue();
    updateActionAvailability();
    void checkAuthStatus();
  }

  function cacheElements() {
    const ids = [
      "account-state", "account-label", "auth-startup-mask", "auth-banner", "auth-title", "auth-message", "login-link",
      "retry-auth-button", "proxy-tab", "upload-tab", "proxy-panel", "upload-panel",
      "proxy-host", "proxy-host-label", "proxy-port", "proxy-host-hint", "copy-proxy-host-button",
      "clash-yaml-code", "copy-clash-button", "open-clash-button",
      "copy-http-proxy-host-button", "copy-http-proxy-port-button",
      "create-session-button", "replace-session-button", "session-step-state", "session-box", "session-box-title",
      "session-countdown", "helper-token", "oauth-url", "open-oauth-link",
      "session-status-pill", "sync-progress", "sync-progress-bar", "sync-progress-details",
      "sync-progress-game", "sync-progress-count", "sync-progress-percent", "sync-progress-reasons",
      "session-status", "proxy-result",
      "proxy-history-count", "proxy-result-link", "json-drop-zone", "choose-json-button", "json-file-input",
      "json-paste-input", "add-pasted-json-button", "clear-upload-button",
      "upload-queue-list", "upload-queue-summary", "empty-upload-queue",
      "start-upload-button", "upload-status", "upload-result", "upload-history-count", "upload-result-link",
      "global-message"
    ];
    ids.forEach((id) => {
      elements[toCamelCase(id)] = document.getElementById(id);
    });
  }

  function toCamelCase(value) {
    return value.replace(/-([a-z])/g, (_match, letter) => letter.toUpperCase());
  }

  function configureProxyDetails() {
    elements.proxyHost.value = "";
    elements.proxyPort.value = String(PROXY_PORT);
    elements.proxyHost.addEventListener("input", () => {
      refreshProxyDetails();
    });
    elements.openClashButton.addEventListener("click", (event) => {
      if (elements.openClashButton.getAttribute("aria-disabled") === "true") {
        event.preventDefault();
      }
    });
    refreshProxyDetails();
  }

  function isLoopbackHost(hostname) {
    const normalized = String(hostname).trim().toLowerCase().replace(/\.$/u, "");
    if (normalized === "localhost" || normalized.endsWith(".localhost") || normalized === "::1") {
      return true;
    }
    const ipv4 = parseIpv4(normalized);
    return Boolean(ipv4 && ipv4[0] === 127);
  }

  function parseIpv4(value) {
    const text = String(value || "").trim();
    if (!/^\d{1,3}(?:\.\d{1,3}){3}$/u.test(text)) return null;
    const parts = text.split(".").map(Number);
    return parts.every((part) => Number.isInteger(part) && part >= 0 && part <= 255)
      ? parts
      : null;
  }

  function normalizeProxyHost(value) {
    const parts = parseIpv4(value);
    return parts ? parts.join(".") : "";
  }

  function usableProxyHost(value) {
    const hostname = normalizeProxyHost(value);
    return hostname === "127.0.0.1" || isPrivateIpv4(hostname) ? hostname : "";
  }

  function isPrivateIpv4(value) {
    const parts = parseIpv4(value);
    if (!parts) return false;
    return parts[0] === 10
      || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31)
      || (parts[0] === 192 && parts[1] === 168);
  }

  function refreshProxyDetails() {
    const rawHost = elements.proxyHost.value;
    const hostname = usableProxyHost(rawHost);
    state.proxyHost = hostname;
    state.proxyHostValid = Boolean(hostname);
    elements.proxyHost.setAttribute("aria-invalid", String(!state.proxyHostValid));
    elements.proxyHost.closest(".copy-field")?.classList.toggle("is-invalid", !state.proxyHostValid);
    elements.copyProxyHostButton.disabled = !state.proxyHostValid;
    elements.copyHttpProxyHostButton.disabled = !state.proxyHostValid;
    elements.copyHttpProxyPortButton.disabled = !state.proxyHostValid;
    elements.copyClashButton.disabled = !state.proxyHostValid;
    elements.clashYamlCode.textContent = state.proxyHostValid
      ? buildClashYaml(hostname)
      : "# 请先填写 127.0.0.1 或运行 Helper 的电脑私网 IPv4。";

    const clashInstallUrl = state.proxyHostValid ? buildClashInstallUrl(hostname) : "";
    elements.openClashButton.href = clashInstallUrl || "#";
    elements.openClashButton.setAttribute("aria-disabled", String(!clashInstallUrl));
    elements.openClashButton.tabIndex = clashInstallUrl ? 0 : -1;

    if (!String(rawHost).trim()) {
      setProxyHostHint("请填写 127.0.0.1 或运行 Helper 的电脑私网 IPv4。", true);
    } else if (normalizeProxyHost(rawHost) === "127.0.0.1") {
      setProxyHostHint("127.0.0.1 仅代表当前设备；跨设备连接时请填写运行 Helper 的电脑私网 IPv4。", false);
    } else if (isLoopbackHost(rawHost)) {
      setProxyHostHint("回环地址只接受 127.0.0.1；跨设备连接请填写电脑私网 IPv4。", true);
    } else if (parseIpv4(rawHost) && !isPrivateIpv4(rawHost)) {
      setProxyHostHint("只接受 127.0.0.1、10.x、172.16–31.x 或 192.168.x 地址。", true);
    } else if (!hostname) {
      setProxyHostHint("地址格式无效：只填写 IPv4，不要包含 http://、端口、主机名或路径。", true);
    } else {
      setProxyHostHint("已使用电脑私网 IPv4；Clash、HTTP 代理与当前会话链接会同步更新。", false);
    }

    refreshHelperStartLink();
    updateActionAvailability();
  }

  function setProxyHostHint(message, warning) {
    elements.proxyHostHint.textContent = message;
    elements.proxyHostHint.classList.toggle("is-warning", warning);
  }

  function buildClashYaml(hostname) {
    const quotedHost = JSON.stringify(String(hostname));
    return [
      "proxies:",
      "  - name: wahlap-wechat-local",
      "    type: http",
      `    server: ${quotedHost}`,
      `    port: ${PROXY_PORT}`,
      "",
      "rules:",
      "  - AND,((DOMAIN,tgk-wcaime.wahlap.com),(DST-PORT,80)),wahlap-wechat-local",
      "  - MATCH,DIRECT"
    ].join("\n");
  }

  function buildClashConfigUrl(hostname) {
    try {
      const backendOrigin = new URL(window.location.origin);
      if (backendOrigin.protocol !== "http:" && backendOrigin.protocol !== "https:") return "";
      const configUrl = new URL(CLASH_CONFIG_ENDPOINT, backendOrigin);
      configUrl.searchParams.set("helperHost", hostname);
      return configUrl.href;
    } catch (_error) {
      return "";
    }
  }

  function buildClashInstallUrl(hostname) {
    const configUrl = buildClashConfigUrl(hostname);
    if (!configUrl) return "";
    const name = encodeURIComponent("Wahlap WeChat Helper（导入后请点选启用）");
    return `clash://install-config?url=${encodeURIComponent(configUrl)}&name=${name}`;
  }

  function bindTabs() {
    const tabs = [elements.proxyTab, elements.uploadTab];
    const requestedTab = new URLSearchParams(window.location.search).get("tab");
    const requestedElement = requestedTab === "upload" ? elements.uploadTab : elements.proxyTab;
    activateTab(requestedElement, false);

    tabs.forEach((tab) => {
      tab.addEventListener("click", () => activateTab(tab, true));
      tab.addEventListener("keydown", (event) => {
        let nextIndex = -1;
        const index = tabs.indexOf(tab);
        if (event.key === "ArrowRight") nextIndex = (index + 1) % tabs.length;
        if (event.key === "ArrowLeft") nextIndex = (index - 1 + tabs.length) % tabs.length;
        if (event.key === "Home") nextIndex = 0;
        if (event.key === "End") nextIndex = tabs.length - 1;
        if (nextIndex < 0) return;
        event.preventDefault();
        activateTab(tabs[nextIndex], true);
        tabs[nextIndex].focus();
      });
    });
  }

  function activateTab(activeTab, updateUrl) {
    const tabs = [elements.proxyTab, elements.uploadTab];
    tabs.forEach((tab) => {
      const selected = tab === activeTab;
      const panel = document.getElementById(tab.getAttribute("aria-controls"));
      tab.classList.toggle("is-active", selected);
      tab.setAttribute("aria-selected", String(selected));
      tab.tabIndex = selected ? 0 : -1;
      panel.hidden = !selected;
    });

    if (updateUrl) {
      const url = new URL(window.location.href);
      const tabName = activeTab === elements.uploadTab ? "upload" : "proxy";
      url.searchParams.set("tab", tabName);
      window.history.replaceState(null, "", `${url.pathname}${url.search}${url.hash}`);
    }
  }

  function bindCopyButtons() {
    document.querySelectorAll("[data-copy-target]").forEach((button) => {
      button.addEventListener("click", () => {
        const target = document.getElementById(button.dataset.copyTarget);
        const value = target && "value" in target ? target.value : target?.textContent;
        void copyText(value || "", button);
      });
    });
    elements.copyClashButton.addEventListener("click", () => {
      void copyText(elements.clashYamlCode.textContent || "", elements.copyClashButton);
    });
  }

  async function copyText(value, button) {
    if (!value) {
      showGlobalMessage("没有可复制的内容。", "error");
      return;
    }
    try {
      if (window.isSecureContext && navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
      } else {
        fallbackCopy(value);
      }
      showTemporaryButtonText(button, "已复制");
    } catch (_error) {
      try {
        fallbackCopy(value);
        showTemporaryButtonText(button, "已复制");
      } catch (_fallbackError) {
        showGlobalMessage("复制失败，请选中文本后手动复制。", "error");
      }
    }
  }

  function fallbackCopy(value) {
    const textarea = document.createElement("textarea");
    textarea.value = value;
    textarea.setAttribute("readonly", "");
    textarea.style.position = "fixed";
    textarea.style.opacity = "0";
    document.body.append(textarea);
    textarea.select();
    const copied = document.execCommand("copy");
    textarea.remove();
    if (!copied) throw new Error("copy command failed");
  }

  function showTemporaryButtonText(button, text) {
    const previous = button.textContent;
    button.textContent = text;
    window.setTimeout(() => {
      button.textContent = previous;
    }, 1400);
  }

  async function checkAuthStatus() {
    if (state.authBusy) return;
    state.authBusy = true;
    state.authReady = false;
    renderAuthState("checking");
    updateActionAvailability();
    try {
      const payload = await requestJson(AUTH_STATUS_ENDPOINT, { method: "GET" });
      const authenticated = payload?.authenticated === true && payload?.user;
      if (authenticated && redirectIfEmailRequired(payload)) return;
      if (!authenticated) {
        requireAuthentication();
        return;
      }
      finishAuthStartup();
      state.authenticated = true;
      state.authReady = true;
      renderAuthState("signed-in", payload.user);
      releaseAuthenticationGate();
    } catch (error) {
      state.authenticated = false;
      state.authReady = false;
      console.info("账户服务暂不可用，转到登录页面", error);
      requireAuthentication();
    } finally {
      state.authBusy = false;
      updateActionAvailability();
    }
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

  function requireAuthentication() {
    document.body.classList.add("auth-checking");
    window.location.replace("/login.html");
  }

  function releaseAuthenticationGate() {
    document.body.classList.remove("auth-required");
  }

  function renderAuthState(mode, user, detail) {
    elements.authBanner.className = `auth-banner is-${mode}`;
    elements.loginLink.hidden = mode !== "signed-out";
    elements.retryAuthButton.hidden = mode !== "error";

    if (mode === "signed-in") {
      const name = String(user?.displayName || user?.username || "已登录玩家");
      elements.authTitle.textContent = `已登录：${name}`;
      elements.authMessage.textContent = "同步结果将保存到这个账号，并与其他玩家数据隔离。";
      renderHeaderAccount(name, Array.from(name)[0]?.toLocaleUpperCase("zh-CN") || "用");
      if (document.documentElement.dataset.profileReady !== "true") {
        void window.B50ProfileTheme?.refresh({ force: true });
      }
      return;
    }
    if (mode === "signed-out") {
      elements.authTitle.textContent = "请先登录账号";
      elements.authMessage.textContent = "本站不提供游客模式。请返回登录后再继续。";
      renderHeaderAccount("尚未登录", "游");
      return;
    }
    if (mode === "error") {
      elements.authTitle.textContent = "无法确认登录状态";
      elements.authMessage.textContent = detail || "请确认后端服务正在运行，然后重新检查。";
      renderHeaderAccount("账号状态未知", "!");
      return;
    }
    elements.authTitle.textContent = "正在确认账号状态";
    elements.authMessage.textContent = "同步数据前需要登录，以便将成绩保存到正确的玩家账号。";
    renderHeaderAccount("正在检查…", "…");
  }

  function renderHeaderAccount(label, avatarText) {
    elements.accountLabel.textContent = label;
    const avatar = elements.accountState.querySelector(".account-avatar");
    const profileReady = document.documentElement.dataset.profileReady === "true";
    if (avatar && (!state.authenticated || !profileReady)) {
      avatar.textContent = avatarText;
    }
  }

  function bindSessionActions() {
    elements.retryAuthButton.addEventListener("click", () => void checkAuthStatus());
    elements.createSessionButton.addEventListener("click", () => void createSession());
    elements.replaceSessionButton.addEventListener("click", () => void createSession());
    document.querySelectorAll('input[name="proxy-game"]').forEach((radio) => {
      radio.addEventListener("change", refreshProxyGameSelection);
    });
    refreshProxyGameSelection();
    window.addEventListener("pagehide", clearSessionTimers);
  }

  window.addEventListener("b50:profile-applied", (event) => {
    const displayName = String(event.detail?.displayName || "").trim();
    if (!displayName || !state.authenticated) return;
    elements.accountLabel.textContent = displayName;
    elements.authTitle.textContent = `已登录：${displayName}`;
  });

  function refreshProxyGameSelection() {
    const selectedGame = selectedRadioValue("proxy-game") || "maimai";
    document.body.classList.toggle(
      "sync-chunithm-background", selectedGame === "chunithm"
    );
    document.querySelectorAll('input[name="proxy-game"]').forEach((radio) => {
      radio.closest(".game-option")?.classList.toggle("is-selected", radio.checked);
    });
    if (!state.sessionBusy) {
      elements.createSessionButton.textContent = `创建${displayGame(selectedGame)}同步会话`;
    }
  }

  async function createSession() {
    if (!state.authenticated || state.sessionBusy || !state.proxyHostValid) return;
    const game = selectedRadioValue("proxy-game") || "maimai";
    const proxyHost = state.proxyHost;
    state.sessionBusy = true;
    updateActionAvailability();
    elements.createSessionButton.textContent = "正在检查 Helper…";
    elements.sessionStepState.textContent = "检查 Helper";
    setSessionStatus("pending", `正在确认 ${proxyHost}:${PROXY_PORT} 已运行本项目 Helper。`, 6);

    try {
      await probeHelper(proxyHost);
      state.sessionGame = game;
      state.historyFetchedForSession = null;
      clearSessionTimers();
      state.sessionRequestSequence += 1;
      const requestSequence = state.sessionRequestSequence;
      resetSessionPresentation();
      elements.createSessionButton.textContent = "正在创建…";
      elements.sessionStepState.textContent = "正在创建";
      setSessionStatus("pending", "Helper 已就绪，正在向后端申请一次性同步会话。", 12);

      const payload = await requestJson(SYNC_SESSIONS_ENDPOINT, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ game })
      });
      const session = sessionFromPayload(payload);
      const sessionId = session?.id;
      if (sessionId === null || sessionId === undefined || String(sessionId).trim() === "") {
        throw new Error("后端没有返回同步会话编号。请检查同步接口版本。");
      }
      const responseGame = normalizeGameName(session?.game);
      if (responseGame && responseGame !== game) {
        throw new Error("后端返回的同步游戏与当前选择不一致。");
      }

      state.session = session;
      state.sessionGame = responseGame || game;
      state.sessionId = String(sessionId);
      state.sessionExpiresAt = parseExpiry(session?.expiresAt);
      renderCreatedSession(payload, session);
      renderSessionStatus(session);
      startSessionTimers(requestSequence);
    } catch (error) {
      if (error?.status === 401) handleUnauthorized();
      resetSessionPresentation();
      elements.sessionStepState.textContent = "创建失败";
      setSessionStatus("failed", friendlyError(error), 100);
      showGlobalMessage(friendlyError(error), "error");
    } finally {
      state.sessionBusy = false;
      refreshProxyGameSelection();
      updateActionAvailability();
    }
  }

  async function probeHelper(value) {
    const hostname = usableProxyHost(value);
    if (!hostname) throw new Error("请先填写有效的 Helper 地址。");
    const urlHost = hostname.includes(":") ? `[${hostname}]` : hostname;
    const healthUrl = `http://${urlHost}:${PROXY_PORT}/health`;
    const controller = new AbortController();
    let timedOut = false;
    const timeout = window.setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, HELPER_HEALTH_TIMEOUT_MS);

    let response;
    try {
      response = await fetch(healthUrl, {
        method: "GET",
        mode: "cors",
        credentials: "omit",
        cache: "no-store",
        redirect: "error",
        referrerPolicy: "no-referrer",
        headers: { Accept: "application/json" },
        signal: controller.signal
      });
    } catch (_error) {
      if (timedOut) {
        throw new Error(`Helper 在 ${HELPER_HEALTH_TIMEOUT_MS / 1000} 秒内没有响应，请确认 ${hostname}:${PROXY_PORT} 正在监听。`);
      }
      throw new Error(`无法连接 ${hostname}:${PROXY_PORT} 的本项目 Helper；请先运行 .\\run-wechat-helper.ps1，并关闭会抢占代理的 Fiddler 或 Charles。`);
    } finally {
      window.clearTimeout(timeout);
    }

    let payload;
    try {
      payload = await response.json();
    } catch (_error) {
      throw new Error(`${hostname}:${PROXY_PORT} 返回的不是本项目 Helper，请检查 IP 和端口。`);
    }
    if (
      !response.ok
      || payload?.status !== "ok"
      || payload?.service !== "wahlap-wechat-helper"
    ) {
      throw new Error(`${hostname}:${PROXY_PORT} 没有通过 Helper 健康检查，请检查程序和代理链。`);
    }
  }

  function resetSessionPresentation() {
    state.session = null;
    state.sessionId = null;
    state.sessionExpiresAt = 0;
    state.helperPath = "";
    state.lastDetailProgress = null;
    state.lastRenderedProgress = 0;
    elements.sessionBox.hidden = true;
    elements.sessionBoxTitle.textContent = "会话已创建";
    elements.sessionCountdown.textContent = "剩余时间计算中…";
    elements.helperToken.value = "";
    elements.proxyHistoryCount.textContent = "—";
    elements.proxyResult.hidden = true;
    refreshHelperStartLink();
  }

  function sessionFromPayload(payload) {
    if (payload?.session && typeof payload.session === "object") return payload.session;
    return payload && typeof payload === "object" ? payload : null;
  }

  function parseExpiry(value) {
    const timestamp = Date.parse(String(value || ""));
    return Number.isFinite(timestamp) ? timestamp : Date.now() + SESSION_TTL_MS;
  }

  function renderCreatedSession(payload, session) {
    const helperToken = String(payload?.helperToken || session?.helperToken || "");
    const fallbackOauthUrl = payload?.oauthUrl || session?.oauthUrl || "";
    const helperPath = normalizeHelperPath(payload?.helperPath || session?.helperPath)
      || helperPathFromUrl(fallbackOauthUrl);
    if (!helperToken) throw new Error("后端没有返回辅助程序令牌。请检查同步接口版本。");
    if (!helperPath) throw new Error("后端没有返回有效的 Helper /start 路径。请检查同步接口版本。");
    state.helperPath = helperPath;

    elements.helperToken.value = helperToken;
    elements.sessionBoxTitle.textContent = `${displayGame(state.sessionGame)}会话已创建`;
    refreshHelperStartLink();
    elements.sessionBox.hidden = false;
    elements.sessionStepState.textContent = "会话有效 15 分钟";
    elements.proxyResult.hidden = true;
    updateCountdown();
  }

  function normalizeHelperPath(value) {
    const text = String(value || "").trim();
    if (!text || text.startsWith("//")) return "";
    try {
      const url = new URL(text, "http://helper.invalid");
      if (url.pathname !== "/start") return "";
      return `${url.pathname}${url.search}${url.hash}`;
    } catch (_error) {
      return "";
    }
  }

  function helperPathFromUrl(value) {
    try {
      const url = new URL(String(value || ""));
      if (url.protocol !== "http:" && url.protocol !== "https:") return "";
      return normalizeHelperPath(`${url.pathname}${url.search}${url.hash}`);
    } catch (_error) {
      return "";
    }
  }

  function buildLocalHelperUrl(helperPathValue) {
    const helperPath = normalizeHelperPath(helperPathValue);
    const hostname = usableProxyHost(state.proxyHost);
    if (!helperPath || !hostname) return "";
    const urlHost = hostname.includes(":") ? `[${hostname}]` : hostname;
    return `http://${urlHost}:${PROXY_PORT}${helperPath}`;
  }

  function refreshHelperStartLink() {
    const startUrl = buildLocalHelperUrl(state.helperPath);
    elements.oauthUrl.value = startUrl;
    elements.openOauthLink.href = startUrl || "#";
    elements.openOauthLink.setAttribute("aria-disabled", String(!startUrl));
    elements.openOauthLink.tabIndex = startUrl ? 0 : -1;
  }

  function startSessionTimers(sequence) {
    state.sessionCountdownTimer = window.setInterval(updateCountdown, 1000);
    void pollSession(sequence);
  }

  function clearSessionTimers() {
    if (state.sessionPollTimer !== null) window.clearTimeout(state.sessionPollTimer);
    if (state.sessionCountdownTimer !== null) window.clearInterval(state.sessionCountdownTimer);
    state.sessionPollTimer = null;
    state.sessionCountdownTimer = null;
  }

  function stopSessionPolling() {
    if (state.sessionPollTimer !== null) window.clearTimeout(state.sessionPollTimer);
    state.sessionPollTimer = null;
  }

  function updateCountdown() {
    if (!state.sessionExpiresAt) return;
    const remaining = Math.max(0, state.sessionExpiresAt - Date.now());
    const minutes = Math.floor(remaining / 60000);
    const seconds = Math.floor((remaining % 60000) / 1000);
    elements.sessionCountdown.textContent = remaining > 0
      ? `剩余 ${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`
      : "会话已到期";

    if (remaining === 0 && state.session && !isTerminalStatus(state.session.status)) {
      stopSessionPolling();
      if (state.sessionCountdownTimer !== null) window.clearInterval(state.sessionCountdownTimer);
      state.sessionCountdownTimer = null;
      state.session = { ...state.session, status: "expired" };
      renderSessionStatus(state.session);
    }
  }

  async function pollSession(sequence) {
    if (!state.sessionId || sequence !== state.sessionRequestSequence) return;
    try {
      const encodedId = encodeURIComponent(state.sessionId);
      const payload = await requestJson(`${SYNC_SESSIONS_ENDPOINT}/${encodedId}`, { method: "GET" });
      if (sequence !== state.sessionRequestSequence) return;
      const session = sessionFromPayload(payload);
      if (!session) throw new Error("同步状态响应格式不正确。请检查后端同步接口。");
      const responseGame = normalizeGameName(session.game);
      if (responseGame && state.sessionGame && responseGame !== state.sessionGame) {
        const message = "同步状态对应了另一款游戏，本次轮询已停止。";
        state.session = { ...session, status: "failed", message };
        renderSessionStatus(state.session);
        clearSessionTimers();
        elements.sessionCountdown.textContent = "同步已停止";
        showGlobalMessage(message, "error");
        return;
      }
      state.session = session;
      state.sessionGame = responseGame || state.sessionGame;
      if (session.expiresAt) state.sessionExpiresAt = parseExpiry(session.expiresAt);
      renderSessionStatus(session);

      if (isTerminalStatus(session.status)) {
        stopSessionPolling();
        if (state.sessionCountdownTimer !== null) window.clearInterval(state.sessionCountdownTimer);
        state.sessionCountdownTimer = null;
        if (isSuccessStatus(session.status)) {
          elements.sessionCountdown.textContent = "同步已完成";
          const completedGame = state.sessionGame || "maimai";
          elements.proxyResultLink.href = gamePage(completedGame);
          void loadHistoryCount(completedGame, elements.proxyHistoryCount, "proxy");
        } else {
          elements.sessionCountdown.textContent = normalizeStatus(session.status) === "expired"
            ? "会话已到期"
            : "同步已结束";
        }
        return;
      }
    } catch (error) {
      if (sequence !== state.sessionRequestSequence) return;
      if (error?.status === 401) {
        handleUnauthorized();
        clearSessionTimers();
        return;
      }
      if ([400, 404, 409].includes(error?.status)) {
        clearSessionTimers();
        const message = friendlyError(error);
        state.session = { ...(state.session || {}), status: "failed", message };
        renderSessionStatus(state.session);
        elements.sessionCountdown.textContent = error.status === 404 ? "会话不存在" : "同步已停止";
        showGlobalMessage(message, "error");
        return;
      }
      setSessionStatus(
        "pending",
        `暂时无法获取状态：${friendlyError(error)}，稍后会自动重试。`,
        state.lastRenderedProgress,
        undefined,
        undefined,
        state.lastDetailProgress
      );
    }

    state.sessionPollTimer = window.setTimeout(() => void pollSession(sequence), POLL_INTERVAL_MS);
  }

  function renderSessionStatus(session) {
    const normalized = normalizeStatus(session?.status);
    const meta = SESSION_STATUS_META[normalized] || [
      "同步处理中",
      "后端正在处理本次同步，请稍候。",
      55,
      "active"
    ];
    const completionCounts = isSuccessStatus(normalized)
      ? formatImportCounts(session)
      : "";
    const message = completionCounts
      ? `同步完成：${completionCounts}。`
      : typeof session?.message === "string" && session.message.trim()
        ? session.message.trim()
        : meta[1];
    const reportedProgress = normalizeDetailProgress(session?.progress);
    if (reportedProgress) state.lastDetailProgress = reportedProgress;
    const detailProgress = reportedProgress
      || (isTerminalStatus(normalized) ? state.lastDetailProgress : null);
    setSessionStatus(normalized, message, meta[2], meta[0], meta[3], detailProgress);
    elements.sessionStepState.textContent = meta[0];

    if (isSuccessStatus(normalized)) {
      elements.proxyResult.hidden = false;
    }
  }

  function setSessionStatus(status, detail, progress, title, visualState, detailProgress = null) {
    const normalized = normalizeStatus(status);
    const meta = SESSION_STATUS_META[normalized];
    const resolvedTitle = title || meta?.[0] || "同步处理中";
    const resolvedState = visualState || meta?.[3] || "active";
    elements.sessionStatusPill.className = `status-pill is-${resolvedState}`;
    elements.sessionStatusPill.textContent = resolvedTitle;
    renderSyncProgress(normalized, progress, detailProgress);
    const titleElement = elements.sessionStatus.querySelector("strong");
    const detailElement = elements.sessionStatus.querySelector("p");
    titleElement.textContent = resolvedTitle;
    detailElement.textContent = detail;
  }

  function normalizeDetailProgress(value) {
    if (!value || typeof value !== "object" || Array.isArray(value)) return null;
    if (normalizeStatus(value.stage) !== "play_details") return null;
    const completed = nonNegativeInteger(value.completed);
    const total = positiveInteger(value.total);
    const succeededValue = nonNegativeInteger(value.succeeded);
    const succeeded = succeededValue === null || completed === null
      ? succeededValue
      : Math.min(succeededValue, completed);
    const skippedValue = nonNegativeInteger(value.skipped);
    const skipped = skippedValue === null && completed !== null && succeeded !== null
      ? completed - succeeded
      : skippedValue;
    if (completed !== null && succeeded !== null && skipped !== completed - succeeded) return null;
    const failureReasons = normalizeFailureReasons(value.failureReasons, skipped);
    if (failureReasons === null) return null;
    const game = normalizeGameName(value.game) || state.sessionGame;
    return {
      game,
      stage: "play_details",
      completed: completed === null ? 0 : total === null ? completed : Math.min(completed, total),
      total,
      succeeded,
      skipped,
      failureReasons
    };
  }

  function normalizeFailureReasons(value, skipped) {
    if (value === undefined && skipped !== null) {
      return skipped > 0 ? { unavailable: skipped } : {};
    }
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      return skipped === 0 ? {} : null;
    }
    const reasons = {};
    let total = 0;
    for (const [reason, rawCount] of Object.entries(value)) {
      const count = positiveInteger(rawCount);
      if (!Object.prototype.hasOwnProperty.call(DETAIL_FAILURE_LABELS, reason) || count === null) return null;
      reasons[reason] = count;
      total += count;
    }
    return skipped !== null && total === skipped ? reasons : null;
  }

  function nonNegativeInteger(value) {
    if (value === null || value === undefined || typeof value === "boolean" || String(value).trim() === "") {
      return null;
    }
    const number = Number(value);
    return Number.isFinite(number) && number >= 0 ? Math.floor(number) : null;
  }

  function positiveInteger(value) {
    const number = nonNegativeInteger(value);
    return number !== null && number > 0 ? number : null;
  }

  function renderSyncProgress(status, fallbackProgress, detailProgress) {
    const normalized = normalizeStatus(status);
    const successful = isSuccessStatus(normalized);
    const failed = ["failed", "error", "expired", "cancelled"].includes(normalized);
    const game = detailProgress?.game || state.sessionGame;
    let percent = Math.max(0, Math.min(100, Number(fallbackProgress) || 0));
    let indeterminate = normalized === "fetching" && !detailProgress;

    if (detailProgress?.total) {
      percent = Math.round((detailProgress.completed / detailProgress.total) * 100);
    } else if (detailProgress) {
      indeterminate = true;
    } else if (failed) {
      percent = state.lastRenderedProgress;
    }
    if (successful) {
      percent = 100;
      indeterminate = false;
    }

    elements.syncProgress.classList.toggle("is-indeterminate", indeterminate);
    elements.syncProgress.classList.toggle("is-success", successful);
    elements.syncProgress.classList.toggle("is-error", failed);
    elements.syncProgressBar.style.width = indeterminate ? "36%" : `${percent}%`;

    if (indeterminate) {
      elements.syncProgress.removeAttribute("aria-valuenow");
    } else {
      elements.syncProgress.setAttribute("aria-valuenow", String(percent));
      state.lastRenderedProgress = percent;
    }

    if (detailProgress) {
      const gameName = displayGame(game) || "游戏";
      const hasTotal = detailProgress.total !== null;
      const countText = hasTotal
        ? `已处理 ${detailProgress.completed} / ${detailProgress.total}`
        : `已处理 ${detailProgress.completed} 条`;
      const percentText = indeterminate
        ? "总数计算中"
        : `${percent}%`;
      const successText = detailProgress.succeeded !== null
        ? ` · 详情成功 ${detailProgress.succeeded}`
        : "";
      const skippedText = detailProgress.skipped !== null
        ? ` · 跳过 ${detailProgress.skipped}`
        : "";
      const reasonText = Object.entries(detailProgress.failureReasons || {})
        .map(([reason, count]) => `${DETAIL_FAILURE_LABELS[reason]} ${count}`)
        .join("；");
      elements.syncProgressDetails.hidden = false;
      elements.syncProgressDetails.setAttribute("aria-hidden", "false");
      elements.syncProgressGame.textContent = gameName;
      elements.syncProgressCount.textContent = `${countText}${successText}${skippedText}`;
      elements.syncProgressPercent.textContent = percentText;
      elements.syncProgressReasons.textContent = reasonText ? `跳过原因：${reasonText}` : "";
      elements.syncProgressReasons.hidden = !reasonText;
      elements.syncProgress.setAttribute(
        "aria-valuetext",
        `${gameName}游玩详情，${countText}${successText}${skippedText}`
          + `${reasonText ? `，跳过原因：${reasonText}` : ""}`
          + `${indeterminate ? "，总数计算中" : `，${percent}%`}`
      );
      return;
    }

    elements.syncProgressDetails.hidden = true;
    elements.syncProgressDetails.setAttribute("aria-hidden", "true");
    const gameName = displayGame(game) || "游戏";
    elements.syncProgress.setAttribute(
      "aria-valuetext",
      indeterminate ? `${gameName}正在抓取数据，总数计算中` : `${gameName}同步进度 ${percent}%`
    );
  }

  function normalizeStatus(value) {
    return String(value || "created").trim().toLowerCase().replace(/[\s-]+/g, "_");
  }

  function isSuccessStatus(value) {
    return ["completed", "succeeded", "success"].includes(normalizeStatus(value));
  }

  function isTerminalStatus(value) {
    return ["completed", "succeeded", "success", "failed", "error", "expired", "cancelled"]
      .includes(normalizeStatus(value));
  }

  function bindUploadActions() {
    elements.chooseJsonButton.addEventListener("click", (event) => {
      event.stopPropagation();
      if (!state.uploadBusy && state.uploadItems.length < MAX_UPLOAD_ITEMS) elements.jsonFileInput.click();
    });
    elements.jsonDropZone.addEventListener("click", (event) => {
      if (event.target.closest("button") || state.uploadBusy || state.uploadItems.length >= MAX_UPLOAD_ITEMS) return;
      elements.jsonFileInput.click();
    });
    elements.jsonFileInput.addEventListener("change", () => {
      void addFiles(elements.jsonFileInput.files);
      elements.jsonFileInput.value = "";
    });

    ["dragenter", "dragover"].forEach((eventName) => {
      elements.jsonDropZone.addEventListener(eventName, (event) => {
        event.preventDefault();
        if (!state.uploadBusy) elements.jsonDropZone.classList.add("is-dragging");
      });
    });
    ["dragleave", "drop"].forEach((eventName) => {
      elements.jsonDropZone.addEventListener(eventName, (event) => {
        event.preventDefault();
        elements.jsonDropZone.classList.remove("is-dragging");
      });
    });
    elements.jsonDropZone.addEventListener("drop", (event) => {
      if (state.uploadBusy) return;
      void addFiles(event.dataTransfer?.files);
    });

    elements.addPastedJsonButton.addEventListener("click", () => addPastedJson());
    elements.jsonPasteInput.addEventListener("keydown", (event) => {
      if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
        event.preventDefault();
        addPastedJson();
      }
    });
    elements.clearUploadButton.addEventListener("click", clearUploadQueue);
    elements.uploadQueueList.addEventListener("click", (event) => {
      const button = event.target.closest("[data-remove-upload]");
      if (!button || state.uploadBusy) return;
      removeUploadItem(button.dataset.removeUpload);
    });
    elements.startUploadButton.addEventListener("click", () => void startUpload());
  }

  async function addFiles(fileList) {
    const files = Array.from(fileList || []);
    if (!files.length) return;
    const available = MAX_UPLOAD_ITEMS - state.uploadItems.length;
    if (available <= 0) {
      setUploadStatus(`一次最多添加 ${MAX_UPLOAD_ITEMS} 份 JSON。`, "error");
      return;
    }
    if (files.length > available) {
      setUploadStatus(`只添加前 ${available} 个文件；一次最多 ${MAX_UPLOAD_ITEMS} 份。`, "error");
    }

    for (const file of files.slice(0, available)) {
      if (!isJsonFile(file)) {
        setUploadStatus(`“${file.name}”不是 JSON 文件，已跳过。`, "error");
        continue;
      }
      if (file.size > MAX_FILE_BYTES) {
        setUploadStatus(`“${file.name}”超过 5 MB，已跳过。`, "error");
        continue;
      }
      try {
        const text = await file.text();
        addJsonText(text, file.name, `${formatBytes(file.size)} · 文件`);
      } catch (error) {
        setUploadStatus(`无法读取“${file.name}”：${friendlyError(error)}`, "error");
      }
    }
  }

  function isJsonFile(file) {
    const name = String(file?.name || "").toLowerCase();
    const type = String(file?.type || "").toLowerCase();
    return name.endsWith(".json") || type === "application/json" || type.endsWith("+json");
  }

  function addPastedJson() {
    const value = elements.jsonPasteInput.value.trim();
    if (!value) {
      setUploadStatus("请先粘贴 JSON 文本。", "error");
      elements.jsonPasteInput.focus();
      return;
    }
    state.pasteSequence += 1;
    if (addJsonText(value, `粘贴内容 ${state.pasteSequence}`, `${formatBytes(new Blob([value]).size)} · 文本`)) {
      elements.jsonPasteInput.value = "";
    }
  }

  function addJsonText(text, name, sourceDescription) {
    if (state.uploadItems.length >= MAX_UPLOAD_ITEMS) {
      setUploadStatus(`一次最多添加 ${MAX_UPLOAD_ITEMS} 份 JSON。`, "error");
      return false;
    }

    try {
      const parsed = JSON.parse(text);
      const normalized = normalizeImportDocument(parsed);
      state.uploadSequence += 1;
      state.uploadItems.push({
        id: String(state.uploadSequence),
        name: String(name || `JSON ${state.uploadSequence}`),
        sourceDescription,
        records: normalized.records,
        charts: normalized.charts,
        declaredGame: normalized.game
      });
      renderUploadQueue();
      setUploadStatus(`已添加“${name}”。`, "success");
      return true;
    } catch (error) {
      setUploadStatus(`“${name}”无法添加：${friendlyError(error)}`, "error");
      return false;
    }
  }

  function normalizeImportDocument(parsed) {
    if (Array.isArray(parsed)) {
      validateObjectArray(parsed, "成绩记录");
      if (!parsed.length) throw new Error("成绩记录数组不能为空。");
      return { records: parsed, charts: [], game: null };
    }
    if (!parsed || typeof parsed !== "object") {
      throw new Error("顶层内容必须是对象或成绩记录数组。");
    }

    const records = parsed.records === undefined ? [] : parsed.records;
    const charts = parsed.charts === undefined ? [] : parsed.charts;
    if (!Array.isArray(records) || !Array.isArray(charts)) {
      throw new Error("records 与 charts 字段必须是数组。");
    }
    validateObjectArray(records, "records");
    validateObjectArray(charts, "charts");
    if (!records.length && !charts.length) {
      throw new Error("JSON 中没有可导入的 records 或 charts。 ");
    }
    return {
      records,
      charts,
      game: normalizeGameName(parsed.game)
    };
  }

  function validateObjectArray(items, label) {
    if (items.some((item) => !item || typeof item !== "object" || Array.isArray(item))) {
      throw new Error(`${label} 数组中的每一项都必须是对象。`);
    }
  }

  function normalizeGameName(value) {
    if (value === null || value === undefined || String(value).trim() === "") return null;
    const normalized = String(value).trim().toLowerCase().replace(/[\s_-]+/g, "");
    if (["maimai", "maimaidx", "舞萌", "舞萌dx"].includes(normalized)) return "maimai";
    if (["chunithm", "chuni", "中二节奏"].includes(normalized)) return "chunithm";
    return String(value).trim().toLowerCase();
  }

  function renderUploadQueue() {
    elements.uploadQueueList.replaceChildren();
    let totalRecords = 0;
    let totalCharts = 0;

    state.uploadItems.forEach((item) => {
      totalRecords += item.records.length;
      totalCharts += item.charts.length;
      const listItem = document.createElement("li");
      listItem.className = "queue-item";

      const copy = document.createElement("div");
      copy.className = "queue-item-copy";
      const title = document.createElement("strong");
      title.textContent = item.name;
      const source = document.createElement("span");
      source.textContent = item.sourceDescription;
      copy.append(title, source);

      const counts = document.createElement("span");
      counts.className = "queue-item-counts";
      counts.textContent = `${item.records.length} 条记录 · ${item.charts.length} 张谱面`;

      const removeButton = document.createElement("button");
      removeButton.className = "queue-remove";
      removeButton.type = "button";
      removeButton.dataset.removeUpload = item.id;
      removeButton.setAttribute("aria-label", `移除 ${item.name}`);
      removeButton.textContent = "×";
      removeButton.disabled = state.uploadBusy;

      listItem.append(copy, counts, removeButton);
      elements.uploadQueueList.append(listItem);
    });

    elements.emptyUploadQueue.hidden = state.uploadItems.length > 0;
    elements.uploadQueueSummary.textContent = state.uploadItems.length
      ? `${state.uploadItems.length} / ${MAX_UPLOAD_ITEMS} 份 · ${totalRecords} 条记录 · ${totalCharts} 张谱面`
      : "尚未添加 JSON";
    updateActionAvailability();
  }

  function removeUploadItem(id) {
    state.uploadItems = state.uploadItems.filter((item) => item.id !== String(id));
    renderUploadQueue();
    setUploadStatus(state.uploadItems.length ? "已移除一份 JSON。" : "等待添加 JSON 数据");
  }

  function clearUploadQueue() {
    if (state.uploadBusy) return;
    state.uploadItems = [];
    elements.uploadResult.hidden = true;
    renderUploadQueue();
    setUploadStatus("已清空待上传数据。");
  }

  async function startUpload() {
    if (!state.authenticated || state.uploadBusy || !state.uploadItems.length) return;
    const game = selectedRadioValue("upload-game") || "maimai";

    for (const item of state.uploadItems) {
      if (item.declaredGame && item.declaredGame !== game) {
        setUploadStatus(`“${item.name}”声明为 ${displayGame(item.declaredGame)}，与当前选择不一致。`, "error");
        return;
      }
    }

    const records = state.uploadItems.flatMap((item) => item.records);
    const charts = state.uploadItems.flatMap((item) => item.charts);
    const requestBody = { game, records };
    if (charts.length) requestBody.charts = charts;

    state.uploadBusy = true;
    elements.uploadResult.hidden = true;
    updateActionAvailability();
    renderUploadQueue();
    setUploadStatus(`正在导入 ${records.length} 条记录与 ${charts.length} 张谱面…`);

    try {
      const payload = await requestJson(SYNC_IMPORT_ENDPOINT, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(requestBody)
      });
      setUploadStatus(formatImportResult(payload, records.length, charts.length), "success");
      state.uploadItems = [];
      renderUploadQueue();
      elements.uploadResult.hidden = false;
      elements.uploadResultLink.href = gamePage(game);
      void loadHistoryCount(game, elements.uploadHistoryCount, "upload");
    } catch (error) {
      if (error?.status === 401) handleUnauthorized();
      setUploadStatus(friendlyError(error), "error");
    } finally {
      state.uploadBusy = false;
      renderUploadQueue();
      updateActionAvailability();
    }
  }

  function selectedRadioValue(name) {
    return document.querySelector(`input[name="${name}"]:checked`)?.value || null;
  }

  function displayGame(game) {
    return game === "maimai" ? "舞萌 DX" : game === "chunithm" ? "中二节奏" : game;
  }

  function gamePage(game) {
    return game === "chunithm" ? "/chunithm.html" : "/";
  }

  function formatImportResult(payload, recordCount, chartCount) {
    const counts = formatImportCounts(payload);
    if (counts) return `导入完成：${counts}。`;
    return `导入完成：已提交 ${recordCount} 条记录与 ${chartCount} 张谱面。`;
  }

  function formatImportCounts(payload) {
    const imported = firstFiniteNumber(
      payload?.recordsAdded,
      payload?.imported,
      payload?.importedCount,
      payload?.saved
    );
    const ignored = firstFiniteNumber(
      payload?.recordsIgnored,
      payload?.ignored,
      payload?.ignoredCount,
      payload?.duplicates
    );
    const enriched = firstFiniteNumber(
      payload?.recordsEnriched,
      payload?.enriched,
      payload?.enrichedCount
    );
    const parts = [];
    if (imported !== null) parts.push(`写入 ${imported} 条`);
    if (enriched !== null && enriched > 0) parts.push(`补全判定详情 ${enriched} 条`);
    if (ignored !== null) parts.push(`忽略重复 ${ignored} 条`);
    return parts.join("，");
  }

  async function loadHistoryCount(game, target, source) {
    if (source === "proxy" && state.historyFetchedForSession === state.sessionId) return;
    if (source === "proxy") state.historyFetchedForSession = state.sessionId;
    target.textContent = "读取中…";
    try {
      const query = new URLSearchParams({ game });
      const payload = await requestJson(`${HISTORY_ENDPOINT}?${query.toString()}`, { method: "GET" });
      const count = historyCountFromPayload(payload);
      target.textContent = count === null ? "已更新" : `${count} 局`;
    } catch (error) {
      if (error?.status === 401) {
        handleUnauthorized();
        target.textContent = "需重新登录";
      } else {
        target.textContent = "已更新";
      }
    }
  }

  function historyCountFromPayload(payload) {
    if (Array.isArray(payload)) return payload.length;
    const direct = firstFiniteNumber(payload?.total, payload?.count, payload?.totalCount);
    if (direct !== null) return direct;
    for (const key of ["history", "records", "items", "data"]) {
      if (Array.isArray(payload?.[key])) return payload[key].length;
    }
    return null;
  }

  function firstFiniteNumber(...values) {
    for (const value of values) {
      const number = Number(value);
      if (value !== null && value !== undefined && value !== "" && Number.isFinite(number)) return number;
    }
    return null;
  }

  function formatBytes(bytes) {
    const value = Math.max(0, Number(bytes) || 0);
    if (value < 1024) return `${value} B`;
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
    return `${(value / (1024 * 1024)).toFixed(1)} MB`;
  }

  function setUploadStatus(message, kind) {
    elements.uploadStatus.textContent = message;
    elements.uploadStatus.className = kind ? `is-${kind}` : "";
  }

  function updateActionAvailability() {
    const canWrite = state.authReady && state.authenticated;
    const canCreateProxySession = canWrite && state.proxyHostValid;
    elements.createSessionButton.disabled = !canCreateProxySession || state.sessionBusy;
    elements.replaceSessionButton.disabled = !canCreateProxySession || state.sessionBusy;
    elements.proxyHost.disabled = state.sessionBusy;
    elements.startUploadButton.disabled = !canWrite || state.uploadBusy || state.uploadItems.length === 0;
    elements.clearUploadButton.disabled = state.uploadBusy || state.uploadItems.length === 0;
    elements.chooseJsonButton.disabled = state.uploadBusy || state.uploadItems.length >= MAX_UPLOAD_ITEMS;
    elements.addPastedJsonButton.disabled = state.uploadBusy || state.uploadItems.length >= MAX_UPLOAD_ITEMS;
    elements.jsonFileInput.disabled = state.uploadBusy || state.uploadItems.length >= MAX_UPLOAD_ITEMS;
    elements.jsonPasteInput.disabled = state.uploadBusy || state.uploadItems.length >= MAX_UPLOAD_ITEMS;
    elements.jsonDropZone.classList.toggle(
      "is-disabled",
      state.uploadBusy || state.uploadItems.length >= MAX_UPLOAD_ITEMS
    );
  }

  function handleUnauthorized() {
    state.authenticated = false;
    state.authReady = true;
    renderAuthState("signed-out");
    updateActionAvailability();
    requireAuthentication("登录状态已失效，请重新登录。");
  }

  async function requestJson(url, options) {
    let response;
    try {
      response = await fetch(url, {
        ...(options || {}),
        credentials: options?.credentials || "same-origin",
        headers: { Accept: "application/json", ...(options?.headers || {}) }
      });
    } catch (_error) {
      throw new Error("无法连接后端服务，请确认程序仍在运行。");
    }

    const text = await response.text();
    let payload = {};
    if (text.trim()) {
      try {
        payload = JSON.parse(text);
      } catch (_error) {
        if (response.ok) throw new Error("后端返回了无法解析的数据。");
      }
    }

    if (!response.ok) {
      const serverMessage = typeof payload?.message === "string"
        ? payload.message
        : typeof payload?.error === "string" ? payload.error : "";
      const error = new Error(serverMessage || statusMessage(response.status));
      error.status = response.status;
      throw error;
    }
    return payload;
  }

  function statusMessage(status) {
    if (status === 400) return "提交的数据格式不正确。";
    if (status === 401) return "登录状态已失效，请重新登录。";
    if (status === 404) return "同步接口尚未就绪，或会话不存在。";
    if (status === 409) return "当前同步会话状态不允许执行此操作。";
    if (status === 413) return "提交的数据体积过大。";
    if (status >= 500) return "后端处理失败，请稍后重试。";
    return `请求失败（HTTP ${status}）。`;
  }

  function friendlyError(error) {
    return error instanceof Error && error.message ? error.message : "发生未知错误，请重试。";
  }

  function showGlobalMessage(message, kind) {
    if (state.globalMessageTimer !== null) window.clearTimeout(state.globalMessageTimer);
    elements.globalMessage.textContent = message;
    elements.globalMessage.hidden = false;
    elements.globalMessage.dataset.kind = kind || "info";
    state.globalMessageTimer = window.setTimeout(() => {
      elements.globalMessage.hidden = true;
      state.globalMessageTimer = null;
    }, 7000);
  }
})();
