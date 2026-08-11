package dev.r4remote.poweramp;

import java.nio.charset.StandardCharsets;

/** Immutable, dependency-free assets for the same-origin browser remote. */
final class WebUiAssets {
    static final String ROOT_PATH = "/";
    static final String STYLE_PATH = "/app.css";
    static final String SCRIPT_PATH = "/app.js";

    static final class Asset {
        final String contentType;
        final byte[] body;

        Asset(String contentType, String content) {
            this.contentType = contentType;
            this.body = content.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final Asset INDEX = new Asset(
            "text/html; charset=utf-8",
            """
                    <!doctype html>
                    <html lang="ru">
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
                      <meta name="theme-color" content="#111318">
                      <title>Poweramp Remote</title>
                      <link rel="stylesheet" href="/app.css">
                      <script src="/app.js" defer></script>
                    </head>
                    <body>
                      <main class="shell">
                        <section id="loginPanel" class="card login" aria-labelledby="loginTitle">
                          <h1 id="loginTitle">Poweramp Remote</h1>
                          <p>Введите токен, показанный в приложении на HiBy R4.</p>
                          <form id="loginForm">
                            <label for="tokenInput">Токен</label>
                            <input id="tokenInput" name="token" type="password" required
                                   autocomplete="off" autocapitalize="none" spellcheck="false">
                            <button id="loginButton" class="login-button" type="submit">Подключиться</button>
                          </form>
                          <p id="loginError" class="message error" role="alert" hidden></p>
                        </section>

                        <section id="playerPanel" class="player" aria-label="Текущий трек" hidden>
                          <div class="cover">
                            <img id="artwork" alt="Обложка текущего трека" hidden>
                            <div id="artworkPlaceholder" class="cover-placeholder" aria-hidden="true">♪</div>
                          </div>

                          <div class="track-copy">
                            <h1 id="title">Нет трека</h1>
                            <p id="artist" class="artist">—</p>
                            <p id="album" class="album">—</p>
                          </div>

                          <div class="metadata" aria-label="Сведения о треке">
                            <p><span class="meta-label">Аудио</span><span id="audioInfo">—</span></p>
                            <p><span class="meta-label">Источник</span><span id="sourceInfo">—</span></p>
                          </div>

                          <div class="timeline">
                            <input id="seek" type="range" min="0" max="1" value="0" step="1"
                                   aria-label="Позиция воспроизведения" disabled>
                            <div class="times" aria-hidden="true">
                              <span id="position">0:00</span>
                              <span id="duration">—</span>
                            </div>
                          </div>

                          <div class="controls" aria-label="Управление воспроизведением">
                            <button id="previous" type="button" aria-label="Предыдущий трек">⏮</button>
                            <button id="playPause" class="primary" type="button" aria-label="Воспроизвести">▶</button>
                            <button id="next" type="button" aria-label="Следующий трек">⏭</button>
                          </div>

                          <div class="secondary-controls" aria-label="Оценка и перемешивание">
                            <button id="dislike" class="icon-control" type="button"
                                    aria-label="Не нравится" aria-pressed="false">👎</button>
                            <button id="clearRating" class="chip-control" type="button"
                                    aria-label="Сбросить рейтинг">Сброс · —/5</button>
                            <button id="like" class="icon-control" type="button"
                                    aria-label="Нравится" aria-pressed="false">👍</button>
                            <button id="shuffle" class="chip-control shuffle-control" type="button"
                                    aria-label="Включить перемешивание" aria-pressed="false">Shuffle OFF</button>
                          </div>

                          <p id="connectionStatus" class="message" role="status" aria-live="polite">Подключение…</p>
                        </section>
                      </main>
                    </body>
                    </html>
                    """
    );

    private static final Asset STYLE = new Asset(
            "text/css; charset=utf-8",
            """
                    :root {
                      color-scheme: dark;
                      font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                      background: #111318;
                      color: #f4f5f7;
                      font-synthesis: none;
                    }

                    * { box-sizing: border-box; }
                    [hidden] { display: none !important; }

                    body {
                      min-width: 18rem;
                      min-height: 100vh;
                      min-height: 100dvh;
                      margin: 0;
                      padding: max(.75rem, env(safe-area-inset-top))
                               max(.75rem, env(safe-area-inset-right))
                               max(.75rem, env(safe-area-inset-bottom))
                               max(.75rem, env(safe-area-inset-left));
                      background: radial-gradient(circle at top, #252b36 0, #111318 38rem);
                      overscroll-behavior: none;
                    }

                    button, input { font: inherit; }

                    button {
                      border: 0;
                      background: #303641;
                      color: inherit;
                      cursor: pointer;
                      touch-action: manipulation;
                    }

                    button:disabled, input:disabled {
                      cursor: default;
                      opacity: .4;
                    }

                    button:focus-visible, input:focus-visible {
                      outline: .18rem solid #9dc1ff;
                      outline-offset: .18rem;
                    }

                    button[aria-pressed="true"] {
                      background: #86adf7;
                      color: #10141b;
                    }

                    .shell {
                      width: min(100%, 28rem);
                      min-height: calc(100vh - 1.5rem);
                      min-height: calc(100dvh - 1.5rem);
                      margin: 0 auto;
                      display: grid;
                      place-items: center;
                    }

                    .card {
                      width: 100%;
                      padding: 1.35rem;
                      border: 1px solid #343a46;
                      border-radius: 1.1rem;
                      background: #1a1e25;
                      box-shadow: 0 1.25rem 4rem #0007;
                    }

                    .login h1 { margin: 0 0 .65rem; }
                    .login p { color: #b8bdc7; line-height: 1.4; }
                    .login form { display: grid; gap: .7rem; margin-top: 1.1rem; }

                    .login input {
                      width: 100%;
                      min-height: 3rem;
                      padding: .7rem .85rem;
                      border: 1px solid #4a5362;
                      border-radius: .7rem;
                      background: #111318;
                      color: inherit;
                    }

                    .login .login-button {
                      width: 100%;
                      min-height: 3.1rem;
                      border-radius: .7rem;
                      background: #86adf7;
                      color: #10141b;
                      font-weight: 700;
                    }

                    .player {
                      width: 100%;
                      display: grid;
                      gap: clamp(.5rem, 1.5vh, .8rem);
                      align-content: center;
                    }

                    .cover {
                      width: min(52vw, 25dvh, 13.5rem);
                      aspect-ratio: 1;
                      margin: 0 auto;
                      overflow: hidden;
                      border-radius: 1rem;
                      background: #252a33;
                      box-shadow: 0 .75rem 2.5rem #0008;
                    }

                    .cover img {
                      width: 100%;
                      height: 100%;
                      display: block;
                      object-fit: cover;
                    }

                    .cover-placeholder {
                      width: 100%;
                      height: 100%;
                      display: grid;
                      place-items: center;
                      color: #687181;
                      font-size: clamp(3.5rem, 18vw, 6.5rem);
                    }

                    .track-copy {
                      min-width: 0;
                      text-align: center;
                    }

                    .track-copy h1, .track-copy p {
                      overflow: hidden;
                      margin: 0;
                      text-overflow: ellipsis;
                      white-space: nowrap;
                    }

                    .track-copy h1 {
                      font-size: clamp(1.2rem, 5.4vw, 1.65rem);
                      line-height: 1.12;
                    }

                    .track-copy .artist {
                      margin-top: .2rem;
                      color: #d3d7de;
                      font-size: .98rem;
                    }

                    .track-copy .album {
                      margin-top: .1rem;
                      color: #949ca8;
                      font-size: .82rem;
                    }

                    .metadata {
                      min-width: 0;
                      padding: .4rem .6rem;
                      border: 1px solid #303641;
                      border-radius: .7rem;
                      background: #181b21cc;
                      color: #aeb5c0;
                      font-size: .74rem;
                      line-height: 1.35;
                    }

                    .metadata p {
                      min-width: 0;
                      display: grid;
                      grid-template-columns: 4.35rem minmax(0, 1fr);
                      gap: .35rem;
                      margin: 0;
                    }

                    .metadata p + p { margin-top: .12rem; }

                    .metadata span:last-child {
                      min-width: 0;
                      overflow-wrap: anywhere;
                    }

                    .meta-label {
                      color: #7387a7;
                      font-weight: 700;
                      text-transform: uppercase;
                      letter-spacing: .035em;
                    }

                    .timeline { display: grid; gap: .12rem; }
                    .timeline input { width: 100%; height: 1.2rem; margin: 0; accent-color: #86adf7; }

                    .times {
                      display: flex;
                      justify-content: space-between;
                      color: #9da4af;
                      font-size: .76rem;
                      font-variant-numeric: tabular-nums;
                    }

                    .controls {
                      display: flex;
                      justify-content: center;
                      align-items: center;
                      gap: 1rem;
                    }

                    .controls button {
                      width: 3.2rem;
                      height: 3.2rem;
                      border-radius: 50%;
                      font-size: 1.15rem;
                    }

                    .controls .primary {
                      width: 3.9rem;
                      height: 3.9rem;
                      background: #f4f5f7;
                      color: #111318;
                      font-size: 1.35rem;
                    }

                    .secondary-controls {
                      display: grid;
                      grid-template-columns: 2.75rem minmax(0, 1fr) 2.75rem minmax(0, 1.15fr);
                      gap: .4rem;
                      align-items: stretch;
                    }

                    .secondary-controls button {
                      min-width: 0;
                      min-height: 2.65rem;
                      border-radius: .75rem;
                      font-size: .78rem;
                      font-weight: 700;
                    }

                    .secondary-controls .icon-control { font-size: 1.05rem; }
                    .shuffle-control { color: #b9c7df; }

                    .message {
                      min-height: 1rem;
                      margin: 0;
                      color: #8f98a5;
                      font-size: .73rem;
                      line-height: 1.2;
                      text-align: center;
                    }

                    .error { color: #ffaaa5 !important; text-align: left; }

                    @media (max-width: 23rem), (max-height: 42rem) {
                      body { padding: .55rem; }
                      .shell { min-height: calc(100dvh - 1.1rem); }
                      .player { gap: .45rem; }
                      .cover { width: min(42vw, 20dvh, 8.5rem); border-radius: .8rem; }
                      .metadata { padding-block: .3rem; }
                      .controls button { width: 2.9rem; height: 2.9rem; }
                      .controls .primary { width: 3.55rem; height: 3.55rem; }
                      .secondary-controls button { min-height: 2.45rem; }
                    }

                    @media (prefers-reduced-motion: reduce) {
                      * { scroll-behavior: auto !important; }
                    }
                    """
    );

    private static final Asset SCRIPT = new Asset(
            "text/javascript; charset=utf-8",
            """
                    "use strict";

                    const SESSION_PATH = "/api/v1/session";
                    const STATE_PATH = "/api/v1/state";
                    const CONTROL_PATH = "/api/v1/control";
                    const EVENTS_PATH = "/api/v1/events";

                    const loginPanel = document.getElementById("loginPanel");
                    const loginForm = document.getElementById("loginForm");
                    const loginButton = document.getElementById("loginButton");
                    const loginError = document.getElementById("loginError");
                    const tokenInput = document.getElementById("tokenInput");
                    const playerPanel = document.getElementById("playerPanel");
                    const artwork = document.getElementById("artwork");
                    const artworkPlaceholder = document.getElementById("artworkPlaceholder");
                    const title = document.getElementById("title");
                    const artist = document.getElementById("artist");
                    const album = document.getElementById("album");
                    const audioInfo = document.getElementById("audioInfo");
                    const sourceInfo = document.getElementById("sourceInfo");
                    const seek = document.getElementById("seek");
                    const position = document.getElementById("position");
                    const duration = document.getElementById("duration");
                    const previous = document.getElementById("previous");
                    const playPause = document.getElementById("playPause");
                    const next = document.getElementById("next");
                    const dislike = document.getElementById("dislike");
                    const clearRating = document.getElementById("clearRating");
                    const like = document.getElementById("like");
                    const shuffle = document.getElementById("shuffle");
                    const connectionStatus = document.getElementById("connectionStatus");

                    let currentState = null;
                    let socket = null;
                    let reconnectTimer = 0;
                    let reconnectDelay = 1000;
                    let socketGeneration = 0;
                    let dragging = false;
                    let anchorPosition = 0;
                    let anchorTime = performance.now();
                    let pendingSeek = null;
                    let artworkKey = null;
                    let renderedSecond = -1;

                    function authenticatedFetch(path, options = {}) {
                      return fetch(path, {
                        ...options,
                        cache: "no-store",
                        credentials: "same-origin"
                      });
                    }

                    function showLogin(message = "") {
                      socketGeneration += 1;
                      if (socket) socket.close();
                      socket = null;
                      clearTimeout(reconnectTimer);
                      reconnectTimer = 0;
                      loginPanel.hidden = false;
                      playerPanel.hidden = true;
                      loginError.textContent = message;
                      loginError.hidden = !message;
                      loginButton.disabled = false;
                      tokenInput.focus();
                    }

                    function showPlayer() {
                      loginPanel.hidden = true;
                      playerPanel.hidden = false;
                    }

                    function numberOrNull(value) {
                      return typeof value === "number" && Number.isFinite(value) ? value : null;
                    }

                    function cleanText(value) {
                      return typeof value === "string" && value.trim() ? value.trim() : null;
                    }

                    function clamp(value, minimum, maximum) {
                      return Math.min(maximum, Math.max(minimum, value));
                    }

                    function formatTime(seconds) {
                      if (!Number.isFinite(seconds) || seconds < 0) return "—";
                      const whole = Math.floor(seconds);
                      const hours = Math.floor(whole / 3600);
                      const minutes = Math.floor((whole % 3600) / 60);
                      const remainder = whole % 60;
                      return hours > 0
                        ? `${hours}:${String(minutes).padStart(2, "0")}:${String(remainder).padStart(2, "0")}`
                        : `${minutes}:${String(remainder).padStart(2, "0")}`;
                    }

                    function decimal(value, divisor) {
                      return (value / divisor).toFixed(4).replace(/0+$/, "").replace(/\\.$/, "").replace(".", ",");
                    }

                    function formatAudio(state) {
                      const values = [];
                      const fileType = cleanText(state.fileTypeName);
                      const codecValue = cleanText(state.codec);
                      const codec = codecValue ? codecValue.toUpperCase() : null;
                      if (fileType && codec && fileType.toUpperCase() !== codec) {
                        values.push(`${fileType} / ${codec}`);
                      } else if (fileType || codec) {
                        values.push(fileType || codec);
                      }

                      const bits = numberOrNull(state.bitsPerSample);
                      if (bits !== null && bits > 0) values.push(`${Math.round(bits)} бит`);

                      const sampleRate = numberOrNull(state.sampleRate);
                      if (sampleRate !== null && sampleRate > 0) {
                        values.push(sampleRate >= 1000000
                          ? `${decimal(sampleRate, 1000000)} МГц`
                          : `${decimal(sampleRate, 1000)} кГц`);
                      }

                      const bitRate = numberOrNull(state.bitRate);
                      if (bitRate !== null && bitRate > 0) {
                        const kiloBits = bitRate >= 10000 ? Math.round(bitRate / 1000) : Math.round(bitRate);
                        values.push(`${kiloBits} кбит/с`);
                      }
                      return values.length ? values.join(" · ") : "—";
                    }

                    function formatSource(state) {
                      const values = [];
                      const categoryName = cleanText(state.sourceCategoryName);
                      const category = numberOrNull(state.sourceCategory);
                      if (categoryName) {
                        values.push(categoryName);
                      } else if (category !== null) {
                        values.push(`Категория ${Math.round(category)}`);
                      }

                      const listPosition = numberOrNull(state.positionInList);
                      const listSize = numberOrNull(state.listSize);
                      if (listPosition !== null && listSize !== null && listSize > 0) {
                        const shownPosition = listPosition >= 0 && listPosition < listSize
                          ? listPosition + 1
                          : listPosition;
                        values.push(`${Math.round(shownPosition)} / ${Math.round(listSize)}`);
                      } else if (listPosition !== null) {
                        values.push(`позиция ${Math.round(listPosition)}`);
                      } else if (listSize !== null) {
                        values.push(`всего ${Math.round(listSize)}`);
                      }
                      return values.length ? values.join(" · ") : "—";
                    }

                    function trackIdentity(state) {
                      return JSON.stringify([
                        state.title,
                        state.artist,
                        state.album,
                        state.durationSeconds,
                        state.sourceCategoryUri,
                        state.positionInList
                      ]);
                    }

                    function setPositionAnchor(value) {
                      const total = numberOrNull(currentState?.durationSeconds);
                      anchorPosition = clamp(numberOrNull(value) ?? 0, 0, total && total > 0 ? total : Number.MAX_SAFE_INTEGER);
                      anchorTime = performance.now();
                      renderedSecond = -1;
                    }

                    function displayedPosition(now) {
                      if (!currentState) return 0;
                      const total = numberOrNull(currentState.durationSeconds);
                      let value = anchorPosition;
                      if (currentState.playbackState === "playing") {
                        value += Math.max(0, now - anchorTime) / 1000;
                      }
                      return clamp(value, 0, total && total > 0 ? total : Number.MAX_SAFE_INTEGER);
                    }

                    function renderPosition(now = performance.now()) {
                      if (dragging) return;
                      const value = Math.floor(displayedPosition(now));
                      if (value === renderedSecond) return;
                      renderedSecond = value;
                      seek.value = String(value);
                      position.textContent = formatTime(value);
                    }

                    function updateArtwork(state, identity) {
                      const nextKey = state.artwork ? `${state.artwork}|${identity}` : null;
                      if (nextKey === artworkKey) return;
                      artworkKey = nextKey;
                      if (!state.artwork) {
                        artwork.removeAttribute("src");
                        artwork.hidden = true;
                        artworkPlaceholder.hidden = false;
                        return;
                      }
                      artwork.hidden = true;
                      artworkPlaceholder.hidden = false;
                      const separator = state.artwork.includes("?") ? "&" : "?";
                      artwork.src = `${state.artwork}${separator}revision=${encodeURIComponent(state.revision)}`;
                    }

                    function setPressed(button, pressed) {
                      button.setAttribute("aria-pressed", pressed ? "true" : "false");
                    }

                    function applyState(state) {
                      if (!state || typeof state !== "object") return;
                      const previousIdentity = currentState ? trackIdentity(currentState) : null;
                      const identity = trackIdentity(state);
                      const trackChanged = previousIdentity !== null && previousIdentity !== identity;
                      currentState = state;

                      title.textContent = cleanText(state.title) || "Нет трека";
                      artist.textContent = cleanText(state.artist) || "—";
                      album.textContent = cleanText(state.album) || "—";
                      audioInfo.textContent = formatAudio(state);
                      sourceInfo.textContent = formatSource(state);
                      updateArtwork(state, identity);

                      const total = numberOrNull(state.durationSeconds);
                      const serverPosition = numberOrNull(state.positionSeconds);
                      seek.max = String(total && total > 0 ? Math.floor(total) : 1);
                      duration.textContent = total && total > 0 ? formatTime(total) : "—";

                      if (trackChanged) {
                        pendingSeek = null;
                        dragging = false;
                      }
                      if (!dragging && serverPosition !== null) {
                        const now = performance.now();
                        const seekConfirmed = pendingSeek && Math.abs(serverPosition - pendingSeek.value) <= 2;
                        if (!pendingSeek || seekConfirmed || now >= pendingSeek.expires) {
                          pendingSeek = null;
                          setPositionAnchor(serverPosition);
                        }
                      } else if (!dragging && (trackChanged || state.hasTrack !== true)) {
                        pendingSeek = null;
                        setPositionAnchor(0);
                      }

                      const powerampAvailable = state.powerampAvailable === true;
                      const controllable = powerampAvailable && state.hasTrack === true;
                      previous.disabled = !controllable;
                      next.disabled = !controllable;
                      playPause.disabled = !powerampAvailable;
                      seek.disabled = !controllable || !(total > 0);

                      const playing = state.playbackState === "playing";
                      playPause.textContent = playing ? "Ⅱ" : "▶";
                      playPause.setAttribute("aria-label", playing ? "Пауза" : "Воспроизвести");

                      const rating = numberOrNull(state.rating);
                      const liked = rating === 5;
                      const disliked = rating === 1;
                      dislike.disabled = !controllable;
                      like.disabled = !controllable;
                      clearRating.disabled = !controllable || rating === null || rating === 0;
                      setPressed(dislike, disliked);
                      setPressed(like, liked);
                      dislike.setAttribute("aria-label", disliked ? "Убрать отметку Не нравится" : "Не нравится");
                      like.setAttribute("aria-label", liked ? "Убрать отметку Нравится" : "Нравится");
                      clearRating.textContent = `Сброс · ${rating === null ? "—" : Math.round(rating)}/5`;

                      const shuffleEnabled = state.shuffle === true;
                      shuffle.disabled = !controllable;
                      setPressed(shuffle, shuffleEnabled);
                      shuffle.textContent = state.shuffle === null
                        ? "Shuffle —"
                        : `Shuffle ${shuffleEnabled ? "ON" : "OFF"}`;
                      shuffle.setAttribute(
                        "aria-label",
                        shuffleEnabled ? "Выключить перемешивание" : "Включить перемешивание"
                      );

                      connectionStatus.textContent = powerampAvailable ? "Подключено" : "Poweramp недоступен";
                      renderPosition();
                    }

                    artwork.addEventListener("load", () => {
                      artwork.hidden = false;
                      artworkPlaceholder.hidden = true;
                    });

                    artwork.addEventListener("error", () => {
                      artworkKey = null;
                      artwork.hidden = true;
                      artworkPlaceholder.hidden = false;
                    });

                    function eventsUrl() {
                      const scheme = location.protocol === "https:" ? "wss:" : "ws:";
                      return `${scheme}//${location.host}${EVENTS_PATH}`;
                    }

                    function scheduleReconnect(generation) {
                      if (generation !== socketGeneration || reconnectTimer) return;
                      connectionStatus.textContent = "Связь потеряна. Переподключение…";
                      const delay = reconnectDelay + Math.floor(Math.random() * 250);
                      reconnectDelay = Math.min(reconnectDelay * 2, 15000);
                      reconnectTimer = setTimeout(() => {
                        reconnectTimer = 0;
                        connectEvents();
                      }, delay);
                    }

                    async function handleSocketClose(generation) {
                      if (generation !== socketGeneration) return;
                      try {
                        const response = await authenticatedFetch(STATE_PATH);
                        if (generation !== socketGeneration) return;
                        if (response.status === 401) {
                          showLogin("Сессия завершена. Введите токен снова.");
                          return;
                        }
                        if (response.ok) {
                          applyState(await response.json());
                          showPlayer();
                        }
                      } catch (error) {
                        // A network interruption is handled by the reconnect backoff below.
                      }
                      scheduleReconnect(generation);
                    }

                    function connectEvents() {
                      clearTimeout(reconnectTimer);
                      reconnectTimer = 0;
                      const generation = ++socketGeneration;
                      connectionStatus.textContent = "Подключение…";
                      const candidate = new WebSocket(eventsUrl());
                      socket = candidate;

                      candidate.addEventListener("message", event => {
                        if (generation !== socketGeneration || candidate !== socket) return;
                        try {
                          applyState(JSON.parse(event.data));
                          showPlayer();
                          reconnectDelay = 1000;
                        } catch (error) {
                          connectionStatus.textContent = "Получены некорректные данные";
                        }
                      });

                      candidate.addEventListener("close", () => {
                        if (generation !== socketGeneration || candidate !== socket) return;
                        socket = null;
                        handleSocketClose(generation);
                      });

                      candidate.addEventListener("error", () => {
                        if (generation === socketGeneration) {
                          connectionStatus.textContent = "Ошибка соединения";
                        }
                      });
                    }

                    async function loadInitialState() {
                      try {
                        const response = await authenticatedFetch(STATE_PATH);
                        if (response.status === 401) {
                          showLogin("Введите токен для подключения.");
                          return;
                        }
                        if (!response.ok) throw new Error(`HTTP ${response.status}`);
                        applyState(await response.json());
                        showPlayer();
                        connectEvents();
                      } catch (error) {
                        showPlayer();
                        connectionStatus.textContent = "Сервер временно недоступен";
                        connectEvents();
                      }
                    }

                    loginForm.addEventListener("submit", async event => {
                      event.preventDefault();
                      const token = tokenInput.value.trim();
                      if (!token) return;
                      loginButton.disabled = true;
                      loginError.hidden = true;
                      try {
                        const response = await authenticatedFetch(SESSION_PATH, {
                          method: "POST",
                          headers: { "Content-Type": "application/json" },
                          body: JSON.stringify({ token })
                        });
                        if (response.status === 401) {
                          throw new Error("Неверный токен.");
                        }
                        if (!response.ok) {
                          throw new Error("Не удалось создать сессию.");
                        }
                        loginForm.reset();
                        await loadInitialState();
                      } catch (error) {
                        loginError.textContent = error.message || "Ошибка подключения.";
                        loginError.hidden = false;
                      } finally {
                        loginButton.disabled = false;
                      }
                    });

                    async function sendControl(action, value) {
                      const command = { action };
                      if (value !== undefined) command.value = value;
                      try {
                        const response = await authenticatedFetch(CONTROL_PATH, {
                          method: "POST",
                          headers: { "Content-Type": "application/json" },
                          body: JSON.stringify(command)
                        });
                        if (response.status === 401) {
                          showLogin("Сессия завершена. Введите токен снова.");
                          return false;
                        }
                        if (!response.ok) throw new Error(`HTTP ${response.status}`);
                        return true;
                      } catch (error) {
                        connectionStatus.textContent = "Команда не отправлена";
                        return false;
                      }
                    }

                    previous.addEventListener("click", () => sendControl("previous"));
                    next.addEventListener("click", () => sendControl("next"));
                    playPause.addEventListener("click", () => {
                      sendControl(currentState?.playbackState === "playing" ? "pause" : "play");
                    });
                    dislike.addEventListener("click", () => {
                      sendControl("set_rating", currentState?.rating === 1 ? 0 : 1);
                    });
                    clearRating.addEventListener("click", () => sendControl("set_rating", 0));
                    like.addEventListener("click", () => {
                      sendControl("set_rating", currentState?.rating === 5 ? 0 : 5);
                    });
                    shuffle.addEventListener("click", () => {
                      sendControl(currentState?.shuffle === true ? "shuffle_off" : "shuffle_on");
                    });

                    seek.addEventListener("input", () => {
                      dragging = true;
                      position.textContent = formatTime(Number(seek.value));
                    });

                    seek.addEventListener("change", async () => {
                      dragging = false;
                      const value = Math.round(Number(seek.value));
                      setPositionAnchor(value);
                      pendingSeek = { value, expires: performance.now() + 2000 };
                      if (!await sendControl("seek", value)) {
                        pendingSeek = null;
                        if (currentState) setPositionAnchor(numberOrNull(currentState.positionSeconds) ?? 0);
                      }
                    });

                    function animationFrame(now) {
                      renderPosition(now);
                      requestAnimationFrame(animationFrame);
                    }

                    requestAnimationFrame(animationFrame);
                    loadInitialState();
                    """
    );

    private WebUiAssets() {
    }

    static Asset forPath(String path) {
        switch (path) {
            case ROOT_PATH:
                return INDEX;
            case STYLE_PATH:
                return STYLE;
            case SCRIPT_PATH:
                return SCRIPT;
            default:
                return null;
        }
    }
}
