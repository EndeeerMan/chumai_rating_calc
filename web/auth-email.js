"use strict";

(() => {
  const CODE_ENDPOINT = "/api/auth/email/code";
  const RESEND_SECONDS = 120;
  const CODE_PATTERN = /^\d{6}$/u;
  const FLOW_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;

  async function readPayload(response) {
    const text = await response.text();
    if (!text) return {};
    try {
      return JSON.parse(text);
    } catch (_error) {
      throw new Error("服务器返回了无法解析的响应。");
    }
  }

  function payloadMessage(payload, fallback) {
    for (const key of ["error", "message", "detail"]) {
      if (typeof payload?.[key] === "string" && payload[key].trim()) {
        return payload[key].trim();
      }
    }
    return fallback;
  }

  function createController(options) {
    const container = options?.container;
    const emailInput = options?.emailInput;
    const codeInput = options?.codeInput;
    const sendButton = options?.sendButton;
    const status = options?.status;
    const purpose = String(options?.purpose || "").trim();
    const flowLabel = String(options?.flowLabel || "邮箱验证").trim();
    const successMessage = String(
      options?.successMessage || "验证码已发送，10 分钟内有效。"
    );
    const showError = typeof options?.showError === "function" ? options.showError : () => {};
    if (!container || !emailInput || !codeInput || !sendButton || !status || !purpose) {
      throw new Error("邮箱验证控件不完整。");
    }

    let resendUntil = 0;
    let countdownTimer = null;
    let sending = false;
    let verificationFlowId = "";
    let flowEmail = "";

    function normalizedEmail() {
      return emailInput.value.trim().toLocaleLowerCase("en-US");
    }

    function renderCountdown() {
      const remaining = Math.max(0, Math.ceil((resendUntil - Date.now()) / 1000));
      if (remaining > 0) {
        sendButton.disabled = true;
        sendButton.textContent = `${remaining} 秒后可重发`;
        return;
      }
      if (countdownTimer !== null) {
        window.clearInterval(countdownTimer);
        countdownTimer = null;
      }
      sendButton.disabled = sending || container.hidden;
      sendButton.textContent = sending ? "正在发送…" : "发送验证码";
    }

    function beginCooldown(seconds = RESEND_SECONDS) {
      resendUntil = Date.now() + Math.max(1, seconds) * 1000;
      if (countdownTimer !== null) window.clearInterval(countdownTimer);
      countdownTimer = window.setInterval(renderCountdown, 1000);
      renderCountdown();
    }

    function resumeCountdown() {
      if (Date.now() < resendUntil && countdownTimer === null) {
        countdownTimer = window.setInterval(renderCountdown, 1000);
      }
      renderCountdown();
    }

    async function sendCode() {
      if (sending || Date.now() < resendUntil) return;
      if (!emailInput.reportValidity()) return;
      const email = normalizedEmail();
      verificationFlowId = "";
      flowEmail = "";
      sending = true;
      showError("");
      status.textContent = "正在发送验证码…";
      renderCountdown();
      try {
        const response = await fetch(CODE_ENDPOINT, {
          method: "POST",
          headers: {
            Accept: "application/json",
            "Content-Type": "application/json;charset=UTF-8"
          },
          credentials: "same-origin",
          cache: "no-store",
          body: JSON.stringify({ email, purpose })
        });
        const payload = await readPayload(response);
        if (!response.ok) {
          const retryAfter = Number(payload?.retryAfterSeconds);
          if (response.status === 429 && Number.isFinite(retryAfter) && retryAfter > 0) {
            beginCooldown(Math.min(RESEND_SECONDS, Math.ceil(retryAfter)));
          }
          throw new Error(payloadMessage(payload, `验证码发送失败（HTTP ${response.status}）`));
        }
        const nextFlowId = String(payload?.verificationFlowId || "").trim();
        if (!FLOW_ID_PATTERN.test(nextFlowId)) {
          throw new Error(`服务器没有返回有效的${flowLabel}流程，请重新发送。`);
        }
        verificationFlowId = nextFlowId;
        flowEmail = email;
        beginCooldown(RESEND_SECONDS);
        status.textContent = successMessage;
        codeInput.focus();
      } catch (error) {
        status.textContent = "";
        showError(error instanceof Error ? error.message : "验证码发送失败，请稍后重试。");
      } finally {
        sending = false;
        renderCountdown();
      }
    }

    function setActive(active) {
      container.hidden = !active;
      emailInput.required = active;
      codeInput.required = active;
      emailInput.disabled = !active;
      codeInput.disabled = !active;
      sendButton.disabled = !active || sending || Date.now() < resendUntil;
      renderCountdown();
    }

    function verificationFields() {
      const email = normalizedEmail();
      const verificationCode = codeInput.value.trim();
      if (!emailInput.checkValidity()) {
        emailInput.reportValidity();
        throw new Error("请输入有效的邮箱地址。");
      }
      if (!CODE_PATTERN.test(verificationCode)) {
        codeInput.focus();
        throw new Error("请输入邮件中的 6 位数字验证码。");
      }
      if (!verificationFlowId || flowEmail !== email) {
        throw new Error("邮箱已变更，请重新发送验证码。");
      }
      return { email, verificationCode, verificationFlowId };
    }

    sendButton.addEventListener("click", () => void sendCode());
    emailInput.addEventListener("input", () => {
      if (verificationFlowId && normalizedEmail() !== flowEmail) {
        verificationFlowId = "";
        flowEmail = "";
        status.textContent = "邮箱已变更，请重新发送验证码。";
      }
    });
    window.addEventListener("pagehide", () => {
      if (countdownTimer !== null) {
        window.clearInterval(countdownTimer);
        countdownTimer = null;
      }
    });
    window.addEventListener("pageshow", resumeCountdown);
    setActive(false);

    return Object.freeze({ setActive, verificationFields });
  }

  function createRegistrationController(options) {
    const controller = createController({
      ...options,
      purpose: "register",
      flowLabel: "注册验证"
    });
    return Object.freeze({
      setActive: controller.setActive,
      registrationFields: controller.verificationFields
    });
  }

  function createPasswordResetController(options) {
    const controller = createController({
      ...options,
      purpose: "reset-password",
      flowLabel: "密码找回",
      successMessage: "如果该邮箱已绑定账号，验证码将在几分钟内送达。"
    });
    return Object.freeze({
      setActive: controller.setActive,
      resetFields: controller.verificationFields
    });
  }

  window.B50EmailVerification = Object.freeze({
    CODE_ENDPOINT,
    RESEND_SECONDS,
    createRegistrationController,
    createPasswordResetController
  });
})();
