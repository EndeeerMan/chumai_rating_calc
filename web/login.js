"use strict";

(() => {
  const AUTH_STATUS_ENDPOINT = "/api/auth/status";
  const AUTH_LOGIN_ENDPOINT = "/api/auth/login";
  const AUTH_REGISTER_ENDPOINT = "/api/auth/register";
  const PASSWORD_RESET_ENDPOINT = "/api/auth/password/reset";
  const USERNAME_PATTERN = /^[A-Za-z0-9_]{3,18}$/u;
  const NEW_PASSWORD_PATTERN = /^[!-~]{6,32}$/u;

  const ids = [
    "auth-page-title", "auth-page-subtitle", "auth-notice", "auth-error",
    "auth-loading-state", "login-form", "login-identity", "login-password",
    "login-submit", "register-form", "register-username", "register-email",
    "register-code", "send-register-code", "register-code-status",
    "register-password", "register-password-confirm", "register-submit",
    "forgot-form", "forgot-email", "forgot-code", "send-forgot-code",
    "forgot-code-status", "forgot-password", "forgot-password-confirm",
    "forgot-submit"
  ];
  const elements = Object.fromEntries(ids.map((id) => [
    id.replace(/-([a-z])/g, (_match, letter) => letter.toUpperCase()),
    document.getElementById(id)
  ]));
  const missing = ids.filter((id) => !document.getElementById(id));
  if (missing.length) throw new Error(`登录页面缺少元素：${missing.join(", ")}`);
  if (!window.B50EmailVerification) throw new Error("邮箱验证模块加载失败。");

  let mode = "login";
  let busy = false;
  let navigating = false;

  const registrationController = window.B50EmailVerification.createRegistrationController({
    container: elements.registerForm,
    emailInput: elements.registerEmail,
    codeInput: elements.registerCode,
    sendButton: elements.sendRegisterCode,
    status: elements.registerCodeStatus,
    showError
  });
  const resetController = window.B50EmailVerification.createPasswordResetController({
    container: elements.forgotForm,
    emailInput: elements.forgotEmail,
    codeInput: elements.forgotCode,
    sendButton: elements.sendForgotCode,
    status: elements.forgotCodeStatus,
    showError
  });

  function modeFromLocation() {
    if (window.location.pathname === "/register.html") return "register";
    if (window.location.pathname === "/forgot-password.html") return "forgot";
    const requested = new URLSearchParams(window.location.search).get("mode");
    return requested === "register" || requested === "forgot" ? requested : "login";
  }

  function setMode(nextMode, updateAddress = true) {
    mode = nextMode === "register" || nextMode === "forgot" ? nextMode : "login";
    elements.loginForm.hidden = mode !== "login";
    registrationController.setActive(mode === "register");
    resetController.setActive(mode === "forgot");
    const copy = {
      login: ["欢迎回来", "登录玩家账号后进入舞萌 DX 分数构成。", "登录 · ChuMai Rating Calc"],
      register: ["创建玩家账号", "验证邮箱后即可保存两款游戏的个人成绩。", "注册 · ChuMai Rating Calc"],
      forgot: ["找回密码", "使用账号绑定邮箱接收一次性验证码。", "找回密码 · ChuMai Rating Calc"]
    }[mode];
    elements.authPageTitle.textContent = copy[0];
    elements.authPageSubtitle.textContent = copy[1];
    document.title = copy[2];
    showError("");
    if (updateAddress) {
      const nextUrl = mode === "register"
        ? "/register.html"
        : mode === "forgot" ? "/forgot-password.html" : "/login.html";
      window.history.replaceState(null, "", nextUrl);
    }
    const focusTarget = mode === "register"
      ? elements.registerUsername
      : mode === "forgot" ? elements.forgotEmail : elements.loginIdentity;
    window.requestAnimationFrame(() => focusTarget.focus());
  }

  function showError(message = "") {
    elements.authError.textContent = message;
    elements.authError.hidden = !message;
  }

  function showNotice(message = "") {
    elements.authNotice.textContent = message;
    elements.authNotice.hidden = !message;
  }

  function apiMessage(payload, fallback) {
    for (const key of ["error", "message", "detail"]) {
      if (typeof payload?.[key] === "string" && payload[key].trim()) {
        return payload[key].trim();
      }
    }
    return fallback;
  }

  async function readJson(response) {
    const text = await response.text();
    if (!text) return {};
    try {
      return JSON.parse(text);
    } catch (_error) {
      throw new Error("服务器返回了无法解析的响应。");
    }
  }

  async function requestJson(path, body) {
    const response = await fetch(path, {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json;charset=UTF-8"
      },
      credentials: "same-origin",
      cache: "no-store",
      body: JSON.stringify(body)
    });
    const payload = await readJson(response);
    if (!response.ok) {
      throw new Error(apiMessage(payload, `请求失败（HTTP ${response.status}）`));
    }
    return payload;
  }

  function setBusy(value) {
    busy = value;
    for (const button of [
      elements.loginSubmit, elements.registerSubmit, elements.forgotSubmit
    ]) {
      button.disabled = value;
    }
  }

  function validateNewPassword(password, confirmation) {
    if (!NEW_PASSWORD_PATTERN.test(password)) {
      throw new Error("密码须为 6–32 位，且每个字符必须是 ASCII 十进制 33–126（! 到 ~）。");
    }
    if (password !== confirmation) {
      throw new Error("两次输入的密码不一致。");
    }
  }

  function redirectAfterAuthentication(payload) {
    navigating = true;
    const emailRequired = payload?.emailRequired === true
      || payload?.user?.emailRequired === true;
    window.location.replace(emailRequired ? "/profile.html?bindEmail=1" : "/");
  }

  async function handleLogin(event) {
    event.preventDefault();
    if (busy || !elements.loginForm.reportValidity()) return;
    showError("");
    showNotice("");
    const username = elements.loginIdentity.value.trim();
    const password = elements.loginPassword.value;
    setBusy(true);
    elements.loginSubmit.textContent = "正在登录…";
    try {
      const payload = await requestJson(AUTH_LOGIN_ENDPOINT, { username, password });
      if (payload?.authenticated !== true || !payload?.user) {
        throw new Error("服务器没有确认登录状态。");
      }
      redirectAfterAuthentication(payload);
    } catch (error) {
      showError(error instanceof Error ? error.message : "登录失败，请稍后重试。");
    } finally {
      if (!navigating) {
        setBusy(false);
        elements.loginSubmit.textContent = "登录";
      }
    }
  }

  async function handleRegister(event) {
    event.preventDefault();
    if (busy || !elements.registerForm.reportValidity()) return;
    showError("");
    showNotice("");
    const username = elements.registerUsername.value.trim();
    const password = elements.registerPassword.value;
    const confirmation = elements.registerPasswordConfirm.value;
    try {
      if (!USERNAME_PATTERN.test(username)) {
        throw new Error("用户名须为 3–18 位，只能包含 ASCII 字母、数字和下划线。");
      }
      validateNewPassword(password, confirmation);
      const verification = registrationController.registrationFields();
      setBusy(true);
      elements.registerSubmit.textContent = "正在注册…";
      const payload = await requestJson(AUTH_REGISTER_ENDPOINT, {
        username,
        password,
        ...verification
      });
      if (payload?.authenticated !== true || !payload?.user) {
        throw new Error("服务器没有确认注册后的登录状态。");
      }
      redirectAfterAuthentication(payload);
    } catch (error) {
      showError(error instanceof Error ? error.message : "注册失败，请稍后重试。");
    } finally {
      if (!navigating) {
        setBusy(false);
        elements.registerSubmit.textContent = "注册并登录";
      }
    }
  }

  async function handlePasswordReset(event) {
    event.preventDefault();
    if (busy || !elements.forgotForm.reportValidity()) return;
    showError("");
    showNotice("");
    const newPassword = elements.forgotPassword.value;
    const confirmation = elements.forgotPasswordConfirm.value;
    try {
      validateNewPassword(newPassword, confirmation);
      const verification = resetController.resetFields();
      setBusy(true);
      elements.forgotSubmit.textContent = "正在重置…";
      const payload = await requestJson(PASSWORD_RESET_ENDPOINT, {
        ...verification,
        newPassword
      });
      if (payload?.success !== true) throw new Error("服务器没有确认密码重置结果。");
      const email = verification.email;
      elements.forgotForm.reset();
      elements.loginIdentity.value = email;
      elements.loginPassword.value = "";
      setMode("login");
      showNotice("密码已重置。请使用新密码登录。");
    } catch (error) {
      showError(error instanceof Error ? error.message : "密码重置失败，请稍后重试。");
    } finally {
      setBusy(false);
      elements.forgotSubmit.textContent = "重置密码";
    }
  }

  function bindEvents() {
    elements.loginForm.addEventListener("submit", handleLogin);
    elements.registerForm.addEventListener("submit", handleRegister);
    elements.forgotForm.addEventListener("submit", handlePasswordReset);
    document.querySelectorAll("[data-password-target]").forEach((button) => {
      button.addEventListener("click", () => {
        const input = document.getElementById(button.dataset.passwordTarget);
        if (!(input instanceof HTMLInputElement)) return;
        const reveal = input.type === "password";
        input.type = reveal ? "text" : "password";
        button.textContent = reveal ? "隐藏" : "显示";
        button.setAttribute("aria-label", reveal ? "隐藏密码" : "显示密码");
      });
    });
  }

  async function initialize() {
    bindEvents();
    const initialMode = modeFromLocation();
    try {
      const response = await fetch(AUTH_STATUS_ENDPOINT, {
        method: "GET",
        headers: { Accept: "application/json" },
        credentials: "same-origin",
        cache: "no-store"
      });
      const payload = await readJson(response);
      if (response.ok && payload?.authenticated === true && payload?.user) {
        redirectAfterAuthentication(payload);
        return;
      }
      if (!response.ok) {
        showError(apiMessage(payload, `无法确认账号状态（HTTP ${response.status}）。`));
      }
    } catch (_error) {
      showError("账户服务暂不可用，请确认 JDK 后端已经启动。");
    }
    if (!navigating) {
      setMode(initialMode, false);
      document.body.classList.remove("auth-loading");
      elements.authLoadingState.hidden = true;
    }
  }

  void initialize();
})();
