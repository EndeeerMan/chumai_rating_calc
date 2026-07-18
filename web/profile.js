"use strict";

(() => {
  const PROFILE_ENDPOINT = "/api/user/profile";
  const AVATAR_ENDPOINT = "/api/user/profile/avatar";
  const BACKGROUND_ENDPOINT = "/api/user/profile/background";
  const EMAIL_ENDPOINT = "/api/user/email";
  const EMAIL_CODE_ENDPOINT = "/api/auth/email/code";
  const PASSWORD_ENDPOINT = "/api/user/password";
  const ACCOUNT_ENDPOINT = "/api/user/account";
  const LOGOUT_ENDPOINT = "/api/auth/logout";
  const AUTH_STATUS_ENDPOINT = "/api/auth/status";
  const AVATAR_MAX_BYTES = 5 * 1024 * 1024;
  const BACKGROUND_MAX_BYTES = 12 * 1024 * 1024;
  const EMAIL_CODE_RESEND_SECONDS = 120;
  const NEW_PASSWORD_PATTERN = /^[!-~]{6,32}$/u;
  const ACCEPTED_IMAGE_TYPES = new Set(["image/png", "image/jpeg"]);
  const DEFAULT_BACKGROUNDS = Object.freeze({
    maimai: Object.freeze({
      desktop: "/assets/backgrounds/maimai-desktop.png",
      mobile: "/assets/backgrounds/maimai-mobile.jpg",
      label: "舞萌 DX"
    }),
    chunithm: Object.freeze({
      desktop: "/assets/backgrounds/chunithm-desktop.png",
      mobile: "/assets/backgrounds/chunithm-mobile.jpg",
      label: "中二节奏"
    })
  });

  const ids = [
    "profile-loading", "profile-content", "hero-avatar", "profile-display-name",
    "profile-username", "nickname-form", "display-name", "save-nickname-button",
    "avatar-preview", "avatar-input", "remove-avatar-button", "email-card", "email-title",
    "current-email", "email-required-notice", "email-form", "email-address",
    "email-verification-code", "send-email-code-button", "email-current-password",
    "email-code-status", "save-email-button", "maimai-background-preview",
    "maimai-background-preview-image", "maimai-background-preview-label",
    "maimai-background-input", "remove-maimai-background-button",
    "chunithm-background-preview", "chunithm-background-preview-image",
    "chunithm-background-preview-label", "chunithm-background-input",
    "remove-chunithm-background-button", "password-form", "current-password",
    "new-password", "confirm-password", "change-password-button", "profile-logout-button",
    "delete-account-form", "delete-current-password", "delete-username-hint",
    "delete-username-confirmation", "delete-account-button", "profile-message"
  ];
  const elements = Object.fromEntries(ids.map((id) => [
    id.replace(/-([a-z])/g, (_match, letter) => letter.toUpperCase()),
    document.getElementById(id)
  ]));
  const missing = ids.filter((id) => !document.getElementById(id));
  if (missing.length) throw new Error(`个人资料页面缺少元素：${missing.join(", ")}`);

  const backgroundControls = Object.freeze({
    maimai: Object.freeze({
      preview: elements.maimaiBackgroundPreview,
      image: elements.maimaiBackgroundPreviewImage,
      source: elements.maimaiBackgroundPreview.querySelector("source"),
      label: elements.maimaiBackgroundPreviewLabel,
      input: elements.maimaiBackgroundInput,
      removeButton: elements.removeMaimaiBackgroundButton
    }),
    chunithm: Object.freeze({
      preview: elements.chunithmBackgroundPreview,
      image: elements.chunithmBackgroundPreviewImage,
      source: elements.chunithmBackgroundPreview.querySelector("source"),
      label: elements.chunithmBackgroundPreviewLabel,
      input: elements.chunithmBackgroundInput,
      removeButton: elements.removeChunithmBackgroundButton
    })
  });

  let profile = null;
  let emailState = null;
  let busy = false;
  let emailCodeSending = false;
  let emailCodeResendUntil = 0;
  let emailCodeTimer = null;
  let messageTimer = null;
  const previewUrls = new Set();

  function backgroundEndpoint(game) {
    return `${BACKGROUND_ENDPOINT}?game=${encodeURIComponent(game)}`;
  }

  function safeAssetUrl(value, expectedPath, expectedGame = "") {
    if (typeof value !== "string" || !value.trim()) return "";
    try {
      const parsed = new URL(value, window.location.origin);
      if (parsed.origin !== window.location.origin || parsed.pathname !== expectedPath) return "";
      if (expectedGame) {
        const game = parsed.searchParams.get("game");
        if (game && game !== expectedGame) return "";
        return backgroundEndpoint(expectedGame);
      }
      return `${parsed.pathname}${parsed.search}`;
    } catch (_error) {
      return "";
    }
  }

  function normalizeProfile(payload) {
    const value = payload?.profile && typeof payload.profile === "object"
      ? payload.profile
      : payload;
    const username = String(value?.username || "").trim();
    const displayName = String(value?.displayName || username).trim();
    if (!username || !displayName) return null;
    const backgrounds = value?.backgroundUrls && typeof value.backgroundUrls === "object"
      ? value.backgroundUrls
      : {};
    const legacyMaimai = safeAssetUrl(value?.backgroundUrl, BACKGROUND_ENDPOINT)
      ? backgroundEndpoint("maimai")
      : "";
    return {
      userId: String(value?.userId || value?.id || ""),
      username,
      displayName,
      avatarUrl: safeAssetUrl(value?.avatarUrl, AVATAR_ENDPOINT),
      backgroundUrls: {
        maimai: safeAssetUrl(backgrounds.maimai || legacyMaimai, BACKGROUND_ENDPOINT, "maimai"),
        chunithm: safeAssetUrl(backgrounds.chunithm, BACKGROUND_ENDPOINT, "chunithm")
      }
    };
  }

  function normalizeEmailState(payload, authRequiresEmail = false) {
    const value = payload?.email && typeof payload.email === "object"
      ? payload.email
      : payload;
    const email = String(
      typeof value?.address === "string" ? value.address
        : typeof value?.email === "string" ? value.email
          : typeof payload?.email === "string" ? payload.email
            : ""
    ).trim();
    const explicitlyUnverified = value?.verified === false || payload?.verified === false;
    const verified = Boolean(email) && !explicitlyUnverified;
    const required = authRequiresEmail
      || value?.emailRequired === true
      || payload?.emailRequired === true
      || !email
      || !verified;
    return { email, verified, required };
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

  function apiMessage(payload, fallback) {
    for (const key of ["error", "message", "detail"]) {
      if (typeof payload?.[key] === "string" && payload[key].trim()) {
        return payload[key].trim();
      }
    }
    return fallback;
  }

  async function requestJson(url, options = {}) {
    const response = await fetch(url, {
      ...options,
      headers: { Accept: "application/json", ...(options.headers || {}) },
      credentials: "same-origin",
      cache: "no-store"
    });
    const payload = await readJson(response);
    const credentialOperation = response.status === 401 && (
      (url === PASSWORD_ENDPOINT && options.method === "PUT")
      || (url === EMAIL_ENDPOINT && options.method === "PUT")
      || (url === ACCOUNT_ENDPOINT && options.method === "DELETE")
    );
    if (response.status === 401 && !credentialOperation) {
      window.B50ProfileTheme?.clear();
      window.location.replace("/login.html");
      throw new DOMException("登录会话已失效", "AbortError");
    }
    if (!response.ok) {
      const error = new Error(apiMessage(payload, `请求失败（HTTP ${response.status}）`));
      error.status = response.status;
      error.payload = payload;
      throw error;
    }
    return payload;
  }

  function showMessage(message, error = false) {
    window.clearTimeout(messageTimer);
    elements.profileMessage.textContent = message;
    elements.profileMessage.classList.toggle("is-error", error);
    elements.profileMessage.hidden = false;
    messageTimer = window.setTimeout(() => {
      elements.profileMessage.hidden = true;
    }, 3600);
  }

  function fallbackAvatar(container, displayName) {
    container.replaceChildren();
    container.textContent = Array.from(displayName)[0]?.toLocaleUpperCase("zh-CN") || "用";
  }

  function renderAvatar(container, displayName, avatarUrl) {
    fallbackAvatar(container, displayName);
    if (!avatarUrl) return;
    const image = document.createElement("img");
    image.src = avatarUrl;
    image.alt = "";
    image.decoding = "async";
    image.addEventListener("error", () => fallbackAvatar(container, displayName), { once: true });
    container.replaceChildren(image);
  }

  function renderBackground(game) {
    const controls = backgroundControls[game];
    const defaults = DEFAULT_BACKGROUNDS[game];
    const customUrl = profile?.backgroundUrls?.[game] || "";
    controls.removeButton.disabled = busy || !customUrl;
    if (customUrl) {
      controls.source.srcset = customUrl;
      controls.image.src = customUrl;
      controls.label.textContent = `${defaults.label}自定义背景`;
    } else {
      controls.source.srcset = defaults.mobile;
      controls.image.src = defaults.desktop;
      controls.label.textContent = `${defaults.label}默认背景`;
    }
  }

  function renderProfile(nextProfile) {
    profile = nextProfile;
    elements.profileDisplayName.textContent = profile.displayName;
    elements.profileUsername.textContent = `@${profile.username}`;
    elements.displayName.value = profile.displayName;
    elements.deleteUsernameHint.textContent = profile.username;
    renderAvatar(elements.heroAvatar, profile.displayName, profile.avatarUrl);
    renderAvatar(elements.avatarPreview, profile.displayName, profile.avatarUrl);
    elements.removeAvatarButton.disabled = busy || !profile.avatarUrl;
    renderBackground("maimai");
    renderBackground("chunithm");
    window.B50ProfileTheme?.apply(profile);
  }

  function renderEmailState(nextState) {
    emailState = nextState;
    elements.currentEmail.textContent = emailState.email && emailState.verified
      ? emailState.email
      : "尚未绑定";
    elements.emailTitle.textContent = emailState.required ? "绑定邮箱" : "换绑邮箱";
    elements.saveEmailButton.textContent = emailState.required ? "验证并绑定" : "验证并换绑";
    elements.emailRequiredNotice.hidden = !emailState.required;
    document.querySelectorAll("[data-email-protected]").forEach((section) => {
      section.hidden = emailState.required;
    });
  }

  function updateEmailCodeButton() {
    const remaining = Math.max(0, Math.ceil((emailCodeResendUntil - Date.now()) / 1000));
    if (remaining > 0) {
      elements.sendEmailCodeButton.disabled = true;
      elements.sendEmailCodeButton.textContent = `${remaining} 秒后可重发`;
      return;
    }
    if (emailCodeTimer !== null) {
      window.clearInterval(emailCodeTimer);
      emailCodeTimer = null;
    }
    elements.sendEmailCodeButton.disabled = busy || emailCodeSending;
    elements.sendEmailCodeButton.textContent = emailCodeSending ? "正在发送…" : "发送验证码";
  }

  function beginEmailCodeCooldown(seconds = EMAIL_CODE_RESEND_SECONDS) {
    emailCodeResendUntil = Date.now() + Math.max(1, seconds) * 1000;
    if (emailCodeTimer !== null) window.clearInterval(emailCodeTimer);
    emailCodeTimer = window.setInterval(updateEmailCodeButton, 1000);
    updateEmailCodeButton();
  }

  function resumeEmailCodeCooldown() {
    if (Date.now() < emailCodeResendUntil && emailCodeTimer === null) {
      emailCodeTimer = window.setInterval(updateEmailCodeButton, 1000);
    }
    updateEmailCodeButton();
  }

  function setBusy(value) {
    busy = value;
    document.querySelectorAll("button, input, label.button").forEach((control) => {
      if (control instanceof HTMLButtonElement || control instanceof HTMLInputElement) {
        control.disabled = value;
      }
      control.classList.toggle("is-disabled", value);
    });
    if (profile) {
      elements.removeAvatarButton.disabled = value || !profile.avatarUrl;
      for (const game of Object.keys(backgroundControls)) renderBackground(game);
    }
    updateEmailCodeButton();
  }

  function validateImage(file, maximum, label) {
    if (!(file instanceof File)) throw new Error(`请选择${label}文件。`);
    if (!ACCEPTED_IMAGE_TYPES.has(file.type)) throw new Error(`${label}只支持 PNG 或 JPEG。`);
    if (file.size < 1 || file.size > maximum) {
      throw new Error(`${label}文件大小不能超过 ${Math.round(maximum / 1024 / 1024)} MB。`);
    }
    return file;
  }

  function previewFile(file, image) {
    const url = URL.createObjectURL(file);
    previewUrls.add(url);
    const release = () => {
      URL.revokeObjectURL(url);
      previewUrls.delete(url);
    };
    image.addEventListener("load", release, { once: true });
    image.addEventListener("error", release, { once: true });
    image.src = url;
  }

  async function profileFromMutation(payload) {
    const direct = normalizeProfile(payload);
    if (direct) return direct;
    return normalizeProfile(await requestJson(PROFILE_ENDPOINT, { method: "GET" }));
  }

  async function uploadImage(endpoint, file, label) {
    setBusy(true);
    try {
      const payload = await requestJson(endpoint, {
        method: "PUT",
        headers: { "Content-Type": file.type },
        body: file
      });
      const nextProfile = await profileFromMutation(payload);
      if (!nextProfile) throw new Error("服务器没有返回更新后的资料。");
      renderProfile(nextProfile);
      await window.B50ProfileTheme?.refresh({ force: true });
      showMessage(`${label}已更新。`);
    } finally {
      setBusy(false);
    }
  }

  async function removeImage(endpoint, label) {
    setBusy(true);
    try {
      const payload = await requestJson(endpoint, { method: "DELETE" });
      const nextProfile = await profileFromMutation(payload);
      if (!nextProfile) throw new Error("服务器没有返回更新后的资料。");
      renderProfile(nextProfile);
      await window.B50ProfileTheme?.refresh({ force: true });
      showMessage(`${label}已恢复默认。`);
    } finally {
      setBusy(false);
    }
  }

  async function loadProfile() {
    const auth = await requestJson(AUTH_STATUS_ENDPOINT, { method: "GET" });
    if (auth?.authenticated !== true || !auth?.user) {
      window.location.replace("/login.html");
      return;
    }
    const authProfile = normalizeProfile(auth.user);
    if (!authProfile) throw new Error("登录状态缺少用户资料。");
    renderProfile(authProfile);

    const emailPayload = await requestJson(EMAIL_ENDPOINT, { method: "GET" });
    const authRequiresEmail = auth?.emailRequired === true || auth?.user?.emailRequired === true;
    const loadedEmail = normalizeEmailState(emailPayload, authRequiresEmail);
    renderEmailState(loadedEmail);

    if (!loadedEmail.required) {
      const payload = await requestJson(PROFILE_ENDPOINT, { method: "GET" });
      const loaded = normalizeProfile(payload);
      if (!loaded) throw new Error("个人资料响应格式不正确。");
      renderProfile(loaded);
    }
    elements.profileLoading.hidden = true;
    elements.profileContent.hidden = false;
    if (loadedEmail.required || new URLSearchParams(window.location.search).get("bindEmail") === "1") {
      window.requestAnimationFrame(() => elements.emailAddress.focus());
    }
  }

  elements.nicknameForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (busy || emailState?.required || !elements.nicknameForm.reportValidity()) return;
    const displayName = elements.displayName.value.trim();
    if (Array.from(displayName).length < 1 || Array.from(displayName).length > 32) {
      showMessage("昵称须为 1–32 个字符。", true);
      return;
    }
    setBusy(true);
    try {
      const payload = await requestJson(PROFILE_ENDPOINT, {
        method: "PUT",
        headers: { "Content-Type": "application/json;charset=UTF-8" },
        body: JSON.stringify({ displayName })
      });
      const nextProfile = await profileFromMutation(payload);
      if (!nextProfile) throw new Error("服务器没有返回更新后的资料。");
      renderProfile(nextProfile);
      showMessage("昵称已保存。");
    } catch (error) {
      showMessage(error instanceof Error ? error.message : "昵称保存失败。", true);
    } finally {
      setBusy(false);
    }
  });

  elements.avatarInput.addEventListener("change", async () => {
    const file = elements.avatarInput.files?.[0];
    if (!file || emailState?.required) return;
    try {
      validateImage(file, AVATAR_MAX_BYTES, "头像");
      const previewImage = document.createElement("img");
      previewImage.alt = "";
      elements.avatarPreview.replaceChildren(previewImage);
      previewFile(file, previewImage);
      await uploadImage(AVATAR_ENDPOINT, file, "头像");
    } catch (error) {
      if (profile) renderProfile(profile);
      showMessage(error instanceof Error ? error.message : "头像上传失败。", true);
    } finally {
      elements.avatarInput.value = "";
    }
  });

  for (const [game, controls] of Object.entries(backgroundControls)) {
    controls.input.addEventListener("change", async () => {
      const file = controls.input.files?.[0];
      if (!file || emailState?.required) return;
      const gameLabel = DEFAULT_BACKGROUNDS[game].label;
      try {
        validateImage(file, BACKGROUND_MAX_BYTES, `${gameLabel}背景图`);
        controls.source.srcset = "";
        previewFile(file, controls.image);
        controls.label.textContent = `待上传${gameLabel}背景预览`;
        await uploadImage(backgroundEndpoint(game), file, `${gameLabel}背景图`);
      } catch (error) {
        if (profile) renderProfile(profile);
        showMessage(error instanceof Error ? error.message : `${gameLabel}背景上传失败。`, true);
      } finally {
        controls.input.value = "";
      }
    });

    controls.removeButton.addEventListener("click", async () => {
      if (busy || emailState?.required || !profile?.backgroundUrls?.[game]) return;
      const gameLabel = DEFAULT_BACKGROUNDS[game].label;
      try {
        await removeImage(backgroundEndpoint(game), `${gameLabel}背景图`);
      } catch (error) {
        showMessage(error instanceof Error ? error.message : `${gameLabel}背景恢复失败。`, true);
      }
    });
  }

  elements.removeAvatarButton.addEventListener("click", async () => {
    if (busy || emailState?.required || !profile?.avatarUrl) return;
    try {
      await removeImage(AVATAR_ENDPOINT, "头像");
    } catch (error) {
      showMessage(error instanceof Error ? error.message : "头像恢复失败。", true);
    }
  });

  elements.sendEmailCodeButton.addEventListener("click", async () => {
    if (busy || emailCodeSending || Date.now() < emailCodeResendUntil) return;
    if (!elements.emailAddress.reportValidity()) return;
    const email = elements.emailAddress.value.trim().toLocaleLowerCase("en-US");
    emailCodeSending = true;
    elements.emailCodeStatus.textContent = "正在发送验证码…";
    updateEmailCodeButton();
    try {
      await requestJson(EMAIL_CODE_ENDPOINT, {
        method: "POST",
        headers: { "Content-Type": "application/json;charset=UTF-8" },
        body: JSON.stringify({ email, purpose: "bind" })
      });
      beginEmailCodeCooldown(EMAIL_CODE_RESEND_SECONDS);
      elements.emailCodeStatus.textContent = "验证码已发送，10 分钟内有效。";
      elements.emailVerificationCode.focus();
    } catch (error) {
      const retryAfter = Number(error?.payload?.retryAfterSeconds);
      if (error?.status === 429 && Number.isFinite(retryAfter) && retryAfter > 0) {
        beginEmailCodeCooldown(Math.min(EMAIL_CODE_RESEND_SECONDS, Math.ceil(retryAfter)));
      }
      elements.emailCodeStatus.textContent = "验证码为 6 位数字，10 分钟内有效；重新发送需等待 2 分钟。";
      showMessage(error instanceof Error ? error.message : "验证码发送失败。", true);
    } finally {
      emailCodeSending = false;
      updateEmailCodeButton();
    }
  });

  elements.emailForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (busy || !elements.emailForm.reportValidity()) return;
    const email = elements.emailAddress.value.trim().toLocaleLowerCase("en-US");
    const verificationCode = elements.emailVerificationCode.value.trim();
    const currentPassword = elements.emailCurrentPassword.value;
    if (!/^\d{6}$/u.test(verificationCode)) {
      showMessage("请输入邮件中的 6 位数字验证码。", true);
      elements.emailVerificationCode.focus();
      return;
    }
    setBusy(true);
    try {
      const payload = await requestJson(EMAIL_ENDPOINT, {
        method: "PUT",
        headers: { "Content-Type": "application/json;charset=UTF-8" },
        body: JSON.stringify({ email, verificationCode, currentPassword })
      });
      const updatedEmail = normalizeEmailState(payload);
      if (updatedEmail.required) {
        const refreshed = normalizeEmailState(await requestJson(EMAIL_ENDPOINT, { method: "GET" }));
        if (refreshed.required) throw new Error("服务器没有确认新的邮箱绑定状态。");
        renderEmailState(refreshed);
      } else {
        renderEmailState(updatedEmail);
      }
      const refreshedProfile = normalizeProfile(await requestJson(PROFILE_ENDPOINT, { method: "GET" }));
      if (refreshedProfile) renderProfile(refreshedProfile);
      elements.emailForm.reset();
      elements.emailCodeStatus.textContent = "验证码为 6 位数字，10 分钟内有效；重新发送需等待 2 分钟。";
      showMessage("邮箱已验证并保存。");
    } catch (error) {
      showMessage(error instanceof Error ? error.message : "邮箱保存失败。", true);
    } finally {
      setBusy(false);
    }
  });

  elements.passwordForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (busy || emailState?.required || !elements.passwordForm.reportValidity()) return;
    const currentPassword = elements.currentPassword.value;
    const newPassword = elements.newPassword.value;
    const confirmation = elements.confirmPassword.value;
    if (newPassword !== confirmation) {
      showMessage("两次输入的新密码不一致。", true);
      elements.confirmPassword.focus();
      return;
    }
    if (!NEW_PASSWORD_PATTERN.test(newPassword)) {
      showMessage("新密码须为 6–32 位，且只能使用 ASCII 十进制 33–126（! 到 ~）。", true);
      return;
    }
    setBusy(true);
    try {
      await requestJson(PASSWORD_ENDPOINT, {
        method: "PUT",
        headers: { "Content-Type": "application/json;charset=UTF-8" },
        body: JSON.stringify({ currentPassword, newPassword })
      });
      elements.passwordForm.reset();
      showMessage("密码已修改，其他设备的旧会话已经失效。");
    } catch (error) {
      showMessage(error instanceof Error ? error.message : "密码修改失败。", true);
    } finally {
      setBusy(false);
    }
  });

  elements.profileLogoutButton.addEventListener("click", async () => {
    if (busy) return;
    setBusy(true);
    try {
      await requestJson(LOGOUT_ENDPOINT, { method: "POST" });
      window.B50ProfileTheme?.clear();
      window.location.replace("/login.html");
    } catch (error) {
      showMessage(error instanceof Error ? error.message : "退出失败。", true);
      setBusy(false);
    }
  });

  elements.deleteAccountForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (busy || !elements.deleteAccountForm.reportValidity()) return;
    const confirmation = elements.deleteUsernameConfirmation.value.trim();
    if (!profile || confirmation !== profile.username) {
      showMessage(`请输入完整用户名 ${profile?.username || ""} 以确认注销。`, true);
      elements.deleteUsernameConfirmation.focus();
      return;
    }
    const currentPassword = elements.deleteCurrentPassword.value;
    setBusy(true);
    try {
      await requestJson(ACCOUNT_ENDPOINT, {
        method: "DELETE",
        headers: { "Content-Type": "application/json;charset=UTF-8" },
        body: JSON.stringify({ currentPassword })
      });
      window.B50ProfileTheme?.clear();
      window.location.replace("/login.html");
    } catch (error) {
      showMessage(error instanceof Error ? error.message : "账号注销失败。", true);
      setBusy(false);
    }
  });

  window.addEventListener("pagehide", () => {
    previewUrls.forEach((url) => URL.revokeObjectURL(url));
    previewUrls.clear();
    if (emailCodeTimer !== null) {
      window.clearInterval(emailCodeTimer);
      emailCodeTimer = null;
    }
  });
  window.addEventListener("pageshow", resumeEmailCodeCooldown);

  void loadProfile().catch((error) => {
    if (error instanceof DOMException && error.name === "AbortError") return;
    elements.profileLoading.querySelector("strong").textContent = "个人资料加载失败";
    elements.profileLoading.querySelector("p").textContent =
      error instanceof Error ? error.message : "请确认后端服务已经启动。";
    showMessage(error instanceof Error ? error.message : "个人资料加载失败。", true);
  });
})();
