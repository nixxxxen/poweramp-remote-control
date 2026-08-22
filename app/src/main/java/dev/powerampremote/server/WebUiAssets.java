package dev.powerampremote.server;

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
                    <html lang="en">
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
                          <p>Enter the credential copied from Poweramp Remote Server.</p>
                          <form id="loginForm">
                            <label for="tokenInput">Credential</label>
                            <input id="tokenInput" name="token" type="password" required
                                   autocomplete="off" autocapitalize="none" spellcheck="false">
                            <button id="loginButton" class="login-button" type="submit">Connect</button>
                          </form>
                          <p id="loginError" class="message error" role="alert" hidden></p>
                        </section>

                        <section id="playerPanel" class="player" aria-label="Current track" hidden>
                          <div class="cover">
                            <img id="artwork" alt="Artwork for the current track" hidden>
                            <div id="artworkPlaceholder" class="cover-placeholder" aria-hidden="true">♪</div>
                          </div>

                          <div class="track-copy">
                            <h1 id="title">No track</h1>
                            <p id="artist" class="artist">—</p>
                            <p id="album" class="album">—</p>
                          </div>

                          <div class="metadata" aria-label="Track details">
                            <p><span class="meta-label">Audio</span><span id="audioInfo">—</span></p>
                            <p><span class="meta-label">Source</span><span id="sourceInfo">—</span></p>
                          </div>

                          <div class="timeline">
                            <input id="seek" type="range" min="0" max="1" value="0" step="1"
                                   aria-label="Playback position" disabled>
                            <div class="times" aria-hidden="true">
                              <span id="position">0:00</span>
                              <span id="duration">—</span>
                            </div>
                          </div>

                          <div id="volumePanel" class="volume" aria-label="Volume on the Poweramp device" hidden>
                            <span>Volume</span>
                            <input id="volume" type="range" min="0" max="1" value="0" step="1" disabled>
                            <span id="volumeValue">0/0</span>
                          </div>

                          <div class="controls" aria-label="Playback controls">
                            <button id="previous" type="button" aria-label="Previous track">⏮</button>
                            <button id="playPause" class="primary" type="button" aria-label="Play">▶</button>
                            <button id="next" type="button" aria-label="Next track">⏭</button>
                          </div>

                          <div class="secondary-controls" aria-label="Rating and shuffle">
                            <button id="dislike" class="icon-control" type="button"
                                    aria-label="Dislike" aria-pressed="false">👎</button>
                            <button id="clearRating" class="chip-control" type="button"
                                    aria-label="Clear rating">Clear · —/5</button>
                            <button id="like" class="icon-control" type="button"
                                    aria-label="Like" aria-pressed="false">👍</button>
                            <button id="shuffle" class="chip-control shuffle-control" type="button"
                                    aria-label="Enable shuffle" aria-pressed="false">Shuffle OFF</button>
                          </div>

                          <p id="connectionStatus" class="message" role="status" aria-live="polite">Connecting…</p>
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

                    .volume {
                      display: grid;
                      grid-template-columns: auto minmax(0, 1fr) 3rem;
                      gap: .45rem;
                      align-items: center;
                      color: #9da4af;
                      font-size: .72rem;
                      font-variant-numeric: tabular-nums;
                    }

                    .volume input { width: 100%; height: 1.1rem; margin: 0; accent-color: #86adf7; }
                    .volume span:last-child { text-align: right; }

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
                    const volumePanel = document.getElementById("volumePanel");
                    const volume = document.getElementById("volume");
                    const volumeValue = document.getElementById("volumeValue");
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
                    let draggingVolume = false;
                    let pendingVolume = null;
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
                      return (value / divisor).toFixed(4).replace(/0+$/, "").replace(/\\.$/, "");
                    }

                    const SOURCE_CATEGORY_NAMES = Object.freeze({
                      0: "Library root",
                      10: "Folder",
                      20: "Folder hierarchy",
                      30: "All tracks",
                      43: "Most played",
                      48: "Top rated",
                      50: "Low rated",
                      53: "Recently added",
                      55: "Long tracks",
                      58: "Recently played",
                      60: "Stream",
                      100: "Playlist",
                      200: "Album",
                      210: "Genre albums",
                      220: "Artist albums",
                      230: "Composer albums",
                      250: "Albums by artist",
                      256: "Album artist albums",
                      320: "Genre",
                      330: "Year",
                      340: "Year albums",
                      500: "Artist",
                      520: "Album artist",
                      600: "Composer",
                      800: "Queue",
                      810: "Bookmarks"
                    });

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
                      if (bits !== null && bits > 0) values.push(`${Math.round(bits)} bit`);

                      const sampleRate = numberOrNull(state.sampleRate);
                      if (sampleRate !== null && sampleRate > 0) {
                        values.push(sampleRate >= 1000000
                          ? `${decimal(sampleRate, 1000000)} MHz`
                          : `${decimal(sampleRate, 1000)} kHz`);
                      }

                      const bitRate = numberOrNull(state.bitRate);
                      if (bitRate !== null && bitRate > 0) {
                        const kiloBits = bitRate >= 10000 ? Math.round(bitRate / 1000) : Math.round(bitRate);
                        values.push(`${kiloBits} kbps`);
                      }
                      return values.length ? values.join(" · ") : "—";
                    }

                    function formatSource(state) {
                      const values = [];
                      const category = numberOrNull(state.sourceCategory);
                      if (category !== null && category >= 0) {
                        values.push(SOURCE_CATEGORY_NAMES[Math.round(category)] || "Other source");
                      }

                      const listPosition = numberOrNull(state.positionInList);
                      const listSize = numberOrNull(state.listSize);
                      if (listPosition !== null && listSize !== null && listSize > 0) {
                        values.push(`${Math.round(listPosition)} / ${Math.round(listSize)}`);
                      } else if (listPosition !== null) {
                        values.push(`position ${Math.round(listPosition)}`);
                      } else if (listSize !== null) {
                        values.push(`total ${Math.round(listSize)}`);
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

                      title.textContent = cleanText(state.title) || "No track";
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

                      const serverVolume = numberOrNull(state.volume);
                      const maximumVolume = numberOrNull(state.volumeMax);
                      const volumeAvailable = serverVolume !== null
                        && maximumVolume !== null && maximumVolume > 0;
                      volumePanel.hidden = !volumeAvailable;
                      if (volumeAvailable) {
                        volume.max = String(Math.floor(maximumVolume));
                        const now = performance.now();
                        const volumeConfirmed = pendingVolume
                          && Math.round(serverVolume) === pendingVolume.value;
                        if (pendingVolume && (volumeConfirmed || now >= pendingVolume.expires)) {
                          pendingVolume = null;
                        }
                        if (!draggingVolume && !pendingVolume) {
                          volume.value = String(clamp(
                            Math.round(serverVolume),
                            0,
                            Math.floor(maximumVolume)
                          ));
                        }
                        volumeValue.textContent = `${volume.value}/${Math.floor(maximumVolume)}`;
                        volume.disabled = state.volumeControlAvailable !== true;
                      }

                      const playing = state.playbackState === "playing";
                      playPause.textContent = playing ? "Ⅱ" : "▶";
                      playPause.setAttribute("aria-label", playing ? "Pause" : "Play");

                      const rating = numberOrNull(state.rating);
                      const liked = rating === 5;
                      const disliked = rating === 1;
                      dislike.disabled = !controllable;
                      like.disabled = !controllable;
                      clearRating.disabled = !controllable || rating === null || rating === 0;
                      setPressed(dislike, disliked);
                      setPressed(like, liked);
                      dislike.setAttribute("aria-label", disliked ? "Remove Dislike" : "Dislike");
                      like.setAttribute("aria-label", liked ? "Remove Like" : "Like");
                      clearRating.textContent = `Clear · ${rating === null ? "—" : Math.round(rating)}/5`;

                      const shuffleEnabled = state.shuffle === true;
                      shuffle.disabled = !controllable;
                      setPressed(shuffle, shuffleEnabled);
                      shuffle.textContent = state.shuffle === null
                        ? "Shuffle —"
                        : `Shuffle ${shuffleEnabled ? "ON" : "OFF"}`;
                      shuffle.setAttribute(
                        "aria-label",
                        shuffleEnabled ? "Disable shuffle" : "Enable shuffle"
                      );

                      connectionStatus.textContent = powerampAvailable ? "Connected" : "Poweramp unavailable";
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
                      connectionStatus.textContent = "Connection lost. Reconnecting…";
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
                          showLogin("The session ended. Enter the credential again.");
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
                      connectionStatus.textContent = "Connecting…";
                      const candidate = new WebSocket(eventsUrl());
                      socket = candidate;

                      candidate.addEventListener("message", event => {
                        if (generation !== socketGeneration || candidate !== socket) return;
                        try {
                          applyState(JSON.parse(event.data));
                          showPlayer();
                          reconnectDelay = 1000;
                        } catch (error) {
                          connectionStatus.textContent = "Invalid data received";
                        }
                      });

                      candidate.addEventListener("close", () => {
                        if (generation !== socketGeneration || candidate !== socket) return;
                        socket = null;
                        handleSocketClose(generation);
                      });

                      candidate.addEventListener("error", () => {
                        if (generation === socketGeneration) {
                          connectionStatus.textContent = "Connection error";
                        }
                      });
                    }

                    async function loadInitialState() {
                      try {
                        const response = await authenticatedFetch(STATE_PATH);
                        if (response.status === 401) {
                          showLogin("Enter the credential to connect.");
                          return;
                        }
                        if (!response.ok) throw new Error(`HTTP ${response.status}`);
                        applyState(await response.json());
                        showPlayer();
                        connectEvents();
                      } catch (error) {
                        showPlayer();
                        connectionStatus.textContent = "The Server is temporarily unavailable";
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
                          throw new Error("Invalid credential.");
                        }
                        if (!response.ok) {
                          throw new Error("The session could not be created.");
                        }
                        loginForm.reset();
                        await loadInitialState();
                      } catch (error) {
                        loginError.textContent = error.message || "Connection error.";
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
                          showLogin("The session ended. Enter the credential again.");
                          return false;
                        }
                        if (!response.ok) throw new Error(`HTTP ${response.status}`);
                        return true;
                      } catch (error) {
                        connectionStatus.textContent = "The command was not sent";
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

                    volume.addEventListener("input", () => {
                      draggingVolume = true;
                      volumeValue.textContent = `${volume.value}/${volume.max}`;
                    });

                    volume.addEventListener("change", async () => {
                      draggingVolume = false;
                      const value = Math.round(Number(volume.value));
                      pendingVolume = { value, expires: performance.now() + 2000 };
                      if (!await sendControl("set_volume", value)) {
                        pendingVolume = null;
                        const serverVolume = numberOrNull(currentState?.volume);
                        if (serverVolume !== null) {
                          volume.value = String(Math.round(serverVolume));
                          volumeValue.textContent = `${volume.value}/${volume.max}`;
                        }
                      }
                      const requested = pendingVolume;
                      if (requested) {
                        setTimeout(() => {
                          if (pendingVolume === requested
                              && performance.now() >= requested.expires) {
                            pendingVolume = null;
                            if (currentState) applyState(currentState);
                          }
                        }, 2050);
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
