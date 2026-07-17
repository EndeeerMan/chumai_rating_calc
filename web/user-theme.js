"use strict";

(() => {
  const PROFILE_ENDPOINT = "/api/user/profile";
  const AVATAR_PATH = "/api/user/profile/avatar";
  const BACKGROUND_PATH = "/api/user/profile/background";
  const CACHE_MS = 30_000;
  let cachedProfile = null;
  let cachedAt = 0;
  let pending = null;

  function safeAssetUrl(value, expectedPath, expectedGame = "") {
    if (typeof value !== "string" || !value.trim()) return "";
    try {
      const parsed = new URL(value, window.location.origin);
      if (parsed.origin !== window.location.origin || parsed.pathname !== expectedPath) return "";
      if (expectedGame && parsed.searchParams.get("game") !== expectedGame) return "";
      return `${parsed.pathname}${parsed.search}`;
    } catch (_error) {
      return "";
    }
  }

  function profileFromPayload(payload) {
    const value = payload?.profile && typeof payload.profile === "object"
      ? payload.profile
      : payload;
    const username = String(value?.username || "").trim();
    const displayName = String(value?.displayName || username).trim();
    if (!username || !displayName) return null;
    const backgroundUrls = value?.backgroundUrls && typeof value.backgroundUrls === "object"
      ? value.backgroundUrls
      : {};
    const legacyMaimaiBackground = safeAssetUrl(value?.backgroundUrl, BACKGROUND_PATH)
      ? `${BACKGROUND_PATH}?game=maimai`
      : "";
    return {
      userId: String(value?.userId || ""),
      username,
      displayName,
      avatarUrl: safeAssetUrl(value?.avatarUrl, AVATAR_PATH),
      backgroundUrls: {
        maimai: safeAssetUrl(
          backgroundUrls.maimai || legacyMaimaiBackground,
          BACKGROUND_PATH,
          "maimai"
        ),
        chunithm: safeAssetUrl(backgroundUrls.chunithm, BACKGROUND_PATH, "chunithm")
      }
    };
  }

  function fallbackAvatar(avatar, displayName) {
    avatar.replaceChildren();
    avatar.textContent = Array.from(displayName)[0]?.toLocaleUpperCase("zh-CN") || "用";
  }

  function renderAvatar(avatar, profile) {
    fallbackAvatar(avatar, profile.displayName);
    if (!profile.avatarUrl) return;
    const image = document.createElement("img");
    image.src = profile.avatarUrl;
    image.alt = "";
    image.decoding = "async";
    image.addEventListener("error", () => fallbackAvatar(avatar, profile.displayName), {
      once: true
    });
    avatar.replaceChildren(image);
  }

  function apply(profile) {
    if (!profile) return;
    cachedProfile = profile;
    cachedAt = Date.now();
    document.body.classList.toggle(
      "has-maimai-background", Boolean(profile.backgroundUrls?.maimai)
    );
    document.body.classList.toggle(
      "has-chunithm-background", Boolean(profile.backgroundUrls?.chunithm)
    );
    document.querySelectorAll(".account-avatar").forEach((avatar) => {
      renderAvatar(avatar, profile);
    });
    document.querySelectorAll("#account-label").forEach((label) => {
      label.textContent = profile.displayName;
    });
    document.documentElement.dataset.profileReady = "true";
    window.dispatchEvent(new CustomEvent("b50:profile-applied", {
      detail: { ...profile }
    }));
  }

  function clear() {
    cachedProfile = null;
    cachedAt = 0;
    document.body.classList.remove("has-maimai-background", "has-chunithm-background");
    delete document.documentElement.dataset.profileReady;
    document.querySelectorAll(".account-avatar").forEach((avatar) => {
      fallbackAvatar(avatar, "用户");
    });
  }

  async function refresh(options = {}) {
    const force = options?.force === true;
    if (cachedProfile) apply(cachedProfile);
    if (!force && cachedProfile && Date.now() - cachedAt < CACHE_MS) {
      return cachedProfile;
    }
    if (pending) return pending;
    pending = (async () => {
      try {
        const response = await fetch(PROFILE_ENDPOINT, {
          method: "GET",
          headers: { Accept: "application/json" },
          cache: "no-store"
        });
        if (response.status === 401) {
          clear();
          return null;
        }
        const payload = await response.json();
        if (!response.ok) return cachedProfile;
        const profile = profileFromPayload(payload);
        if (profile) apply(profile);
        return profile;
      } catch (_error) {
        return cachedProfile;
      } finally {
        pending = null;
      }
    })();
    return pending;
  }

  window.B50ProfileTheme = Object.freeze({ refresh, apply, clear });
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => void refresh({ force: true }), {
      once: true
    });
  } else {
    void refresh({ force: true });
  }
})();
