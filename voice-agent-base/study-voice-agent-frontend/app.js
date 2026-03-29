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

// 新增上下文字段元素
const systemPromptEl = el("systemPrompt");
const jobDescriptionEl = el("jobDescription");
const resumeEl = el("resume");
const questionsEl = el("questions");
const greetingEl = el("greeting");

// sessionId 显示元素
const sessionIdEl = el("sessionId");

function logLine(s) {
	logEl.textContent += s + "\n";
	logEl.scrollTop = logEl.scrollHeight;
}

function setPlayState(obj) {
	playStateEl.textContent = JSON.stringify(obj, null, 2);
}

function setSessionId(sessionId) {
	sessionIdEl.textContent = sessionId || "-";
}

function setSessionDisconnected(disconnected) {
	sessionIdEl.classList.toggle("disconnected", !!disconnected);
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
let micNode = null; // AudioWorkletNode 或 ScriptProcessorNode
let micEnabled = false;

/** ---------- Audio (Playback) ---------- */
let playCtx = null;
let masterGain = null;
let nextPlayTime = 0;
let playedChunks = 0;
let droppedChunks = 0;

// 当前允许接收并播放的 assistant turn
let activeAssistantTurnId = null;

// 当前已被打断 / 完成的 turn，后续若再有残留二进制音频，直接丢弃
const interruptedTurnIds = new Set();
const completedTurnIds = new Set();

// 当前已经创建并可能仍在播放/等待播放的 source
const activeSources = new Set();

const INPUT_RATE = 16000;
const OUTPUT_RATE = 24000;

function pcm16ToFloat32(int16) {
	const f32 = new Float32Array(int16.length);
	for (let i = 0; i < int16.length; i++) {
		f32[i] = int16[i] / 32768;
	}
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

function getPlaybackState() {
	return {
		playSampleRate: playCtx?.sampleRate || null,
		nextPlayTime,
		playedChunks,
		droppedChunks,
		activeSources: activeSources.size,
		activeAssistantTurnId,
		interruptedTurnIds: Array.from(interruptedTurnIds),
		completedTurnIds: Array.from(completedTurnIds),
		playCtxState: playCtx?.state || "none"
	};
}

function updatePlayState() {
	setPlayState(getPlaybackState());
}

async function ensurePlaybackContext() {
	if (playCtx) return;

	playCtx = new (window.AudioContext || window.webkitAudioContext)();
	masterGain = playCtx.createGain();
	masterGain.gain.value = 1;
	masterGain.connect(playCtx.destination);

	nextPlayTime = playCtx.currentTime + 0.05;
	updatePlayState();
}

function canAcceptAudioForTurn(turnId) {
	if (!turnId) return false;
	if (!activeAssistantTurnId) return false;
	if (turnId !== activeAssistantTurnId) return false;
	if (interruptedTurnIds.has(turnId)) return false;
	if (completedTurnIds.has(turnId)) return false;
	return true;
}

function clearAllScheduledSources() {
	for (const src of activeSources) {
		try {
			src.stop(0);
		} catch {}
	}
	activeSources.clear();
}

function interruptPlayback(reason = "", turnId = null) {
	clearAllScheduledSources();

	if (playCtx) {
		const now = playCtx.currentTime;
		nextPlayTime = now + 0.02;
	}

	if (turnId && activeAssistantTurnId === turnId) {
		activeAssistantTurnId = null;
	}

	logLine(`[interrupt] playback cleared: ${reason}${turnId ? `, turnId=${turnId}` : ""}`);
	updatePlayState();
}

function resetPlaybackRouting() {
	activeAssistantTurnId = null;
	interruptedTurnIds.clear();
	completedTurnIds.clear();
	clearAllScheduledSources();

	if (playCtx) {
		nextPlayTime = playCtx.currentTime + 0.02;
	} else {
		nextPlayTime = 0;
	}

	updatePlayState();
}

function schedulePcmPlayback(pcmInt16_24k, turnId) {
	if (!playCtx || !masterGain) return;

	// 不是当前 turn 的音频，直接丢弃
	if (!canAcceptAudioForTurn(turnId)) {
		droppedChunks++;
		updatePlayState();
		return;
	}

	const f32_24k = pcm16ToFloat32(pcmInt16_24k);
	const f32 = resampleLinear(f32_24k, OUTPUT_RATE, playCtx.sampleRate);

	const buffer = playCtx.createBuffer(1, f32.length, playCtx.sampleRate);
	buffer.copyToChannel(f32, 0);

	const src = playCtx.createBufferSource();
	src.buffer = buffer;
	src.connect(masterGain);

	const now = playCtx.currentTime;
	if (nextPlayTime < now) {
		nextPlayTime = now + 0.01;
		droppedChunks++;
	}

	const startAt = nextPlayTime;
	nextPlayTime += buffer.duration;

	activeSources.add(src);

	src.onended = () => {
		activeSources.delete(src);
		updatePlayState();
	};

	// start 前再做一次 turn 校验，避免开始前刚好收到 interrupt/complete
	if (!canAcceptAudioForTurn(turnId)) {
		activeSources.delete(src);
		droppedChunks++;
		updatePlayState();
		return;
	}

	src.start(startAt);
	playedChunks++;
	updatePlayState();
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
		setSessionDisconnected(false);
		resetPlaybackRouting();
		await ensurePlaybackContext();

		try {
			const systemPrompt = systemPromptEl.value?.trim() || "";
			const jobDescription = jobDescriptionEl.value?.trim() || "";
			const resume = resumeEl.value?.trim() || "";
			const questions = questionsEl.value?.trim() || "";
			const greeting = greetingEl.value?.trim() || "";

			const setupMsg = {
				type: "setup",
				system_prompt: systemPrompt,
				job_description: jobDescription,
				resume: resume,
				questions: questions,
				greeting: greeting
			};

			ws.send(JSON.stringify(setupMsg));

			logLine(`[send] setup: ${JSON.stringify({
				system_prompt: systemPrompt,
				job_description: jobDescription,
				resume: resume,
				questions: questions,
				greeting: greeting
			})}`);
		} catch (e) {
			logLine("[send] setup error: " + (e?.message || e));
		}
	};

	ws.onclose = (e) => {
		logLine(`[ws] close: code=${e.code} reason=${e.reason || ""}`);
		setUiConnected(false);
		setSessionDisconnected(true);
		resetPlaybackRouting();
		stopMic().catch(() => {});
	};

	ws.onerror = () => {
		logLine("[ws] error");
	};

	ws.onmessage = async (evt) => {
		if (typeof evt.data === "string") {
			try {
				const obj = JSON.parse(evt.data);

				if (obj.type === "SETUP_RECEIVED") {
					setSessionId(obj.sessionId || "-");
					setSessionDisconnected(false);
					logLine(`[setup_received] sessionId=${obj.sessionId || ""}`);
				} else if (obj.type === "assistant_turn_start") {
					const turnId = obj.turnId || null;

					// 新 turn 开始时，先把旧播放彻底打断，避免两轮重叠
					if (activeAssistantTurnId && activeAssistantTurnId !== turnId) {
						interruptPlayback("new assistant_turn_start replaces previous turn", activeAssistantTurnId);
					}

					activeAssistantTurnId = turnId;

					// 新 turn 到来时，把同名 turn 从历史失效集合中清理掉
					if (turnId) {
						interruptedTurnIds.delete(turnId);
						completedTurnIds.delete(turnId);
					}

					logLine(`[turn] assistant_turn_start turnId=${turnId || ""}`);
					updatePlayState();
				} else if (obj.type === "assistant_turn_interrupt") {
					const turnId = obj.turnId || null;
					if (turnId) interruptedTurnIds.add(turnId);

					logLine(`[turn] assistant_turn_interrupt turnId=${turnId || ""}`);
					interruptPlayback("assistant_turn_interrupt", turnId);
				} else if (obj.type === "assistant_turn_complete") {
					const turnId = obj.turnId || null;
					if (turnId) completedTurnIds.add(turnId);

					logLine(`[turn] assistant_turn_complete turnId=${turnId || ""}`);

					// complete 只关闭路由，不主动 stop；
					// 已经进来的最后一小段正常播完，complete 之后若还有残留包会被丢弃
					if (turnId && activeAssistantTurnId === turnId) {
						activeAssistantTurnId = null;
					}

					updatePlayState();
				} else if (obj.type === "speech_started") {
					logLine("[event] speech_started");

					// 用户开始说话，属于打断信号
					if (activeAssistantTurnId) {
						interruptedTurnIds.add(activeAssistantTurnId);
					}
					interruptPlayback("speech_started", activeAssistantTurnId);
				} else if (obj.type === "interrupted") {
					logLine("[event] interrupted");

					if (activeAssistantTurnId) {
						interruptedTurnIds.add(activeAssistantTurnId);
					}
					interruptPlayback("interrupted", activeAssistantTurnId);
				} else if (obj.type === "transcript_in") {
					logLine(`[in ] ${obj.text || ""}`);
				} else if (obj.type === "transcript_out") {
					logLine(`[out] ${obj.text || ""}`);
				} else if (obj.type === "text") {
					logLine(`[txt] ${obj.text || ""}`);
				} else if (obj.type === "turn_transcript") {
					logLine(`[turn_transcript] in=${obj.inputText || ""} | out=${obj.outputText || ""}`);
				} else if (obj.type === "turn_complete") {
					logLine("[turn] complete");
				} else if (obj.type === "setup_complete") {
					logLine("[setup] complete");
				} else if (obj.type === "setup_sent_to_model") {
					logLine("[setup] sent_to_model");
				} else if (obj.type === "gemini_connected") {
					if (obj.sessionId) {
						setSessionId(obj.sessionId);
						setSessionDisconnected(false);
					}
					logLine(`[gemini] connected sessionId=${obj.sessionId || ""}`);
				} else if (obj.type === "usage") {
					logLine(
						`[usage] prompt=${obj.promptTokenCount} response=${obj.responseTokenCount} total=${obj.totalTokenCount}`
					);
				} else if (obj.type === "go_away") {
					logLine(`[goAway] timeLeft=${obj.timeLeft}`);
				} else if (obj.type === "error") {
					logLine(`[err] ${obj.where || ""}: ${obj.message || obj.text || ""}`);
				} else {
					logLine(`[evt] ${evt.data}`);
				}
			} catch {
				logLine(`[text] ${evt.data}`);
			}
			return;
		}

		// 二进制：24k 16-bit PCM mono
		if (evt.data instanceof ArrayBuffer) {
			// 没有激活中的 assistant turn，说明这包音频没有合法归属，直接丢弃
			const turnId = activeAssistantTurnId;
			if (!turnId) {
				droppedChunks++;
				updatePlayState();
				return;
			}

			const bytes = new Uint8Array(evt.data);
			const i16 = new Int16Array(bytes.buffer, bytes.byteOffset, Math.floor(bytes.byteLength / 2));

			if (playCtx && playCtx.state === "suspended") {
				await playCtx.resume();
			}

			schedulePcmPlayback(i16, turnId);
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

	await ensurePlaybackContext();
	if (playCtx.state === "suspended") await playCtx.resume();

	micStream = await navigator.mediaDevices.getUserMedia({
		audio: {
			channelCount: 1,
			echoCancellation: true,
			noiseSuppression: true,
			autoGainControl: true
		}
	});

	micCtx = new (window.AudioContext || window.webkitAudioContext)();
	const source = micCtx.createMediaStreamSource(micStream);

	try {
		await micCtx.audioWorklet.addModule("./mic-worklet.js");
		micNode = new AudioWorkletNode(micCtx, "mic-processor");

		micNode.port.onmessage = (e) => {
			const msg = e.data || {};
			if (msg.type === "pcm_f32_16k") {
				const f32 = new Float32Array(msg.data);
				const i16 = float32ToInt16PCM(f32);
				if (ws && ws.readyState === WebSocket.OPEN) {
					ws.send(i16.buffer);
				}
			}
		};

		source.connect(micNode);
		micNode.connect(micCtx.destination);

		micNode.port.postMessage({ type: "enable" });
		micEnabled = true;
		logLine("[mic] started (AudioWorklet)");
	} catch (err) {
		logLine("[mic] AudioWorklet 不可用，回退到 ScriptProcessor");

		const bufferSize = 4096;
		const sp = micCtx.createScriptProcessor(bufferSize, 1, 1);
		micNode = sp;

		sp.onaudioprocess = (e) => {
			const input = e.inputBuffer.getChannelData(0);
			const resampled = resampleLinear(input, micCtx.sampleRate, INPUT_RATE);
			const i16 = float32ToInt16PCM(resampled);

			if (ws && ws.readyState === WebSocket.OPEN) {
				ws.send(i16.buffer);
			}
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
		micStream.getTracks().forEach((t) => t.stop());
		micStream = null;
	}

	if (micCtx) {
		try {
			await micCtx.close();
		} catch {}
		micCtx = null;
	}

	micNode = null;

	btnStartMic.disabled = !ws || ws.readyState !== WebSocket.OPEN;
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

btnStartMic.onclick = () => startMic().catch((e) => logLine("[mic] start error: " + (e?.message || e)));
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

	ws.send(JSON.stringify({
		type: "text",
		text: t
	}));

	logLine("[send] text: " + t);
	textInput.value = "";
};

btnClearLog.onclick = () => {
	logEl.textContent = "";
};

setUiConnected(false);
btnStopMic.disabled = true;
setSessionId("-");
setSessionDisconnected(true);
updatePlayState();