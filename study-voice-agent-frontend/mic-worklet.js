class MicProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this._srcRate = sampleRate;   // AudioContext 的采样率
        this._dstRate = 16000;
        this._ratio = this._srcRate / this._dstRate;

        this._buffer = new Float32Array(0);
        this._enabled = false;

        this.port.onmessage = (e) => {
            const msg = e.data || {};
            if (msg.type === "enable") this._enabled = true;
            if (msg.type === "disable") this._enabled = false;
        };
    }

    _concat(a, b) {
        const out = new Float32Array(a.length + b.length);
        out.set(a, 0);
        out.set(b, a.length);
        return out;
    }

    // 简单线性重采样到 16k
    _resampleTo16k(input) {
        const inLen = input.length;
        if (inLen === 0) return new Float32Array(0);

        // 目标长度
        const outLen = Math.floor(inLen / this._ratio);
        const out = new Float32Array(outLen);

        for (let i = 0; i < outLen; i++) {
            const t = i * this._ratio;
            const i0 = Math.floor(t);
            const i1 = Math.min(i0 + 1, inLen - 1);
            const frac = t - i0;
            out[i] = input[i0] * (1 - frac) + input[i1] * frac;
        }
        return out;
    }

    process(inputs) {
        if (!this._enabled) return true;

        const input = inputs[0];
        if (!input || !input[0]) return true;

        // 只取单声道
        const chan0 = input[0];

        // 拼接到内部缓冲，避免每帧太短
        this._buffer = this._concat(this._buffer, chan0);

        // 每次至少攒够 ~40ms 再下发（16k * 0.04 = 640 samples）
        // 这里用源采样率对应长度
        const minSrc = Math.floor(this._srcRate * 0.04);
        if (this._buffer.length < minSrc) return true;

        const chunk = this._buffer;
        this._buffer = new Float32Array(0);

        const resampled = this._resampleTo16k(chunk);
        this.port.postMessage({ type: "pcm_f32_16k", data: resampled }, [resampled.buffer]);

        return true;
    }
}

registerProcessor("mic-processor", MicProcessor);
