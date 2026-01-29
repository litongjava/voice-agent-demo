const el = (id) => document.getElementById(id);

const wsUrlInput = el("wsUrl");
const btnConnect = el("btnConnect");
const btnDisconnect = el("btnDisconnect");

const btnStartMic = el("btnStartMic");
const btnStopMic = el("btnStopMic");
const btnAudioEnd = el("btnAudioEnd");

const textInput = el("textInput");
const btnSendText = el("btnSendText");

const logEl = el("log");
const playStateEl = el("playState");
const btnClearLog = el("btnClearLog");

function logLine(s) {
    logEl.textContent += s + "\n";
    logEl.scrollTop = logEl.scrollHeight;
}

function setPlayState(obj) {
    playStateEl.textContent = JSON.stringify(obj, null, 2);
}

function defaultWsUrl() {
    const loc = window.location;
    const proto = loc.protocol === "https:" ? "wss:" : "ws:";
    return `${proto}//${loc.host}/api/v1/voice/agent`;
}
wsUrlInput.value = defaultWsUrl();

/** ---------- WebSocket ---------- */
let ws = null;

/** ---------- Audio (Mic) ---------- */
let micStream = null;
let micCtx = null;
let micNode = null;       // AudioWorkletNode 或 ScriptProcessorNode
let micEnabled = false;

/** ---------- Audio (Playback) ---------- */
let playCtx = null;
let nextPlayTime = 0;
let playedChunks = 0;
let droppedChunks = 0;

const INPUT_RATE = 16000;
const OUTPUT_RATE = 24000;

function pcm16ToFloat32(int16) {
    const f32 = new Float32Array(int16.length);
    for (let i = 0; i < int16.length; i++) f32[i] = int16[i] / 32768;
    return f32;
}

function float32ToInt16PCM(f32) {
    const out = new Int16Array(f32.length);
    for (let i = 0; i < f32.length; i++) {
        let s = Math.max(-1, Math.min(1, f32[i]));
        out[i] = s < 0 ? s * 32768 : s * 32767;
    }
    return out;
}

// 把 24k Float32 重采样到 playCtx.sampleRate（通常 48k）
function resampleLinear(input, inRate, outRate) {
    if (inRate === outRate) return input;
    const ratio = inRate / outRate;
    const outLen = Math.floor(input.length / ratio);
    const out = new Float32Array(outLen);
    for (let i = 0; i < outLen; i++) {
        const t = i * ratio;
        const i0 = Math.floor(t);
        const i1 = Math.min(i0 + 1, input.length - 1);
        const frac = t - i0;
        out[i] = input[i0] * (1 - frac) + input[i1] * frac;
    }
    return out;
}

async function ensurePlaybackContext() {
    if (playCtx) return;
    playCtx = new (window.AudioContext || window.webkitAudioContext)();
    nextPlayTime = playCtx.currentTime + 0.05;
    setPlayState({
        playSampleRate: playCtx.sampleRate,
        nextPlayTime,
        playedChunks,
        droppedChunks
    });
}

function schedulePcmPlayback(pcmInt16_24k) {
    if (!playCtx) return;

    const f32_24k = pcm16ToFloat32(pcmInt16_24k);
    const f32 = resampleLinear(f32_24k, OUTPUT_RATE, playCtx.sampleRate);

    const buffer = playCtx.createBuffer(1, f32.length, playCtx.sampleRate);
    buffer.copyToChannel(f32, 0);

    const src = playCtx.createBufferSource();
    src.buffer = buffer;
    src.connect(playCtx.destination);

    // 简单队列：保持连续播放
    const now = playCtx.currentTime;
    if (nextPlayTime < now) {
        // 播放落后了，直接追上（丢弃间隙）
        nextPlayTime = now + 0.01;
        droppedChunks++;
    }

    src.start(nextPlayTime);
    nextPlayTime += buffer.duration;
    playedChunks++;

    setPlayState({
        playSampleRate: playCtx.sampleRate,
        nextPlayTime,
        playedChunks,
        droppedChunks
    });
}

/** ---------- WS Handlers ---------- */
function setUiConnected(connected) {
    btnConnect.disabled = connected;
    btnDisconnect.disabled = !connected;

    btnStartMic.disabled = !connected;
    btnStopMic.disabled = !connected;
    btnAudioEnd.disabled = !connected;

    btnSendText.disabled = !connected;
}

function connectWs() {
    const url = wsUrlInput.value.trim();
    ws = new WebSocket(url);
    ws.binaryType = "arraybuffer";

    ws.onopen = async () => {
        logLine(`[ws] open: ${url}`);
        setUiConnected(true);
        await ensurePlaybackContext();
    };

    ws.onclose = (e) => {
        logLine(`[ws] close: code=${e.code} reason=${e.reason || ""}`);
        setUiConnected(false);
        stopMic().catch(() => {});
    };

    ws.onerror = (e) => {
        logLine("[ws] error");
    };

    ws.onmessage = async (evt) => {
        if (typeof evt.data === "string") {
            // JSON 文本消息
            try {
                const obj = JSON.parse(evt.data);
                if (obj.type === "transcript_in") logLine(`[in ] ${obj.text || ""}`);
                else if (obj.type === "transcript_out") logLine(`[out] ${obj.text || ""}`);
                else if (obj.type === "text") logLine(`[txt] ${obj.text || ""}`);
                else if (obj.type === "turn_complete") logLine("[turn] complete");
                else if (obj.type === "setup_complete") logLine("[setup] complete");
                else if (obj.type === "usage") logLine(`[usage] prompt=${obj.prompt} response=${obj.response} total=${obj.total}`);
                else if (obj.type === "go_away") logLine(`[goAway] timeLeft=${obj.timeLeft}`);
                else if (obj.type === "error") logLine(`[err] ${obj.where || ""}: ${obj.message || ""}`);
                else logLine(`[evt] ${evt.data}`);
            } catch {
                logLine(`[text] ${evt.data}`);
            }
            return;
        }

        // 二进制：24k 16-bit PCM mono
        if (evt.data instanceof ArrayBuffer) {
            const bytes = new Uint8Array(evt.data);
            // Int16 little-endian
            const i16 = new Int16Array(bytes.buffer, bytes.byteOffset, Math.floor(bytes.byteLength / 2));
            // 浏览器端播放
            if (playCtx && playCtx.state === "suspended") await playCtx.resume();
            schedulePcmPlayback(i16);
        }
    };
}

/** ---------- Mic Capture ---------- */
async function startMic() {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
        logLine("WS 未连接");
        return;
    }
    if (micEnabled) return;

    // 需要用户手势触发
    await ensurePlaybackContext();
    if (playCtx.state === "suspended") await playCtx.resume();

    micStream = await navigator.mediaDevices.getUserMedia({
        audio: {
            channelCount: 1,
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true,
        }
    });

    // 单独的采集 ctx（让 worklet 的 sampleRate 可控，但浏览器未必按你指定）
    micCtx = new (window.AudioContext || window.webkitAudioContext)();
    const source = micCtx.createMediaStreamSource(micStream);

    // 优先 AudioWorklet
    try {
        await micCtx.audioWorklet.addModule("./mic-worklet.js");
        micNode = new AudioWorkletNode(micCtx, "mic-processor");

        micNode.port.onmessage = (e) => {
            const msg = e.data || {};
            if (msg.type === "pcm_f32_16k") {
                // Float32(16k) -> Int16 PCM -> binary WS
                const f32 = new Float32Array(msg.data);
                const i16 = float32ToInt16PCM(f32);
                if (ws && ws.readyState === WebSocket.OPEN) {
                    ws.send(i16.buffer);
                }
            }
        };

        source.connect(micNode);
        // 不需要接到 destination（避免回放啸叫）；但有些浏览器要求链路存在
        micNode.connect(micCtx.destination);

        micNode.port.postMessage({ type: "enable" });
        micEnabled = true;
        logLine("[mic] started (AudioWorklet)");
    } catch (err) {
        // fallback：ScriptProcessor（兼容老浏览器）
        logLine("[mic] AudioWorklet 不可用，回退到 ScriptProcessor");
        const bufferSize = 4096;
        const sp = micCtx.createScriptProcessor(bufferSize, 1, 1);
        micNode = sp;

        sp.onaudioprocess = (e) => {
            const input = e.inputBuffer.getChannelData(0);

            // 把 micCtx.sampleRate 下采样到 16k
            const resampled = resampleLinear(input, micCtx.sampleRate, INPUT_RATE);

            const i16 = float32ToInt16PCM(resampled);
            if (ws && ws.readyState === WebSocket.OPEN) ws.send(i16.buffer);
        };

        source.connect(sp);
        sp.connect(micCtx.destination);

        micEnabled = true;
        logLine("[mic] started (ScriptProcessor)");
    }

    btnStartMic.disabled = true;
    btnStopMic.disabled = false;
}

async function stopMic() {
    micEnabled = false;

    try {
        if (micNode && micNode.port) {
            micNode.port.postMessage({ type: "disable" });
        }
    } catch {}

    if (micStream) {
        micStream.getTracks().forEach(t => t.stop());
        micStream = null;
    }
    if (micCtx) {
        try { await micCtx.close(); } catch {}
        micCtx = null;
    }
    micNode = null;

    btnStartMic.disabled = !ws || ws.readyState !== WebSocket.OPEN ? true : false;
    btnStopMic.disabled = true;

    logLine("[mic] stopped");
}

/** ---------- UI actions ---------- */
btnConnect.onclick = () => {
    if (ws && ws.readyState === WebSocket.OPEN) return;
    connectWs();
};

btnDisconnect.onclick = () => {
    if (ws) ws.close(1000, "client close");
};

btnStartMic.onclick = () => startMic().catch(e => logLine("[mic] start error: " + (e?.message || e)));
btnStopMic.onclick = () => stopMic().catch(() => {});

btnAudioEnd.onclick = () => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    ws.send(JSON.stringify({ type: "audio_end" }));
    logLine("[send] audio_end");
};

btnSendText.onclick = () => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    const t = textInput.value.trim();
    if (!t) return;
    ws.send(JSON.stringify({ type: "text", text: t }));
    logLine("[send] text: " + t);
    textInput.value = "";
};

btnClearLog.onclick = () => {
    logEl.textContent = "";
};
setUiConnected(false);
btnStopMic.disabled = true;
