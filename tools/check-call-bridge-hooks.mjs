// Runs the WhatsApp call page script (CallBridgeJs.HOOKS in app/.../browser/CallBridge.kt) against a made-up page,
// in Node, with no browser: disarmed it must pass everything through; armed it must hand out the fake microphone,
// tap the speaker, send 16 kHz frames to the app and play what the app sends.
//   node tools/check-call-bridge-hooks.mjs
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const kt = readFileSync(new URL('../app/src/main/java/com/past9/phoneaos/browser/CallBridge.kt', import.meta.url), 'utf8');
const js = kt.slice(kt.indexOf('val HOOKS = """') + 15, kt.lastIndexOf('""".trim()'));
assert.ok(js.includes('hinaCall.onmessage'), 'script found');
assert.ok(!js.includes('$'), 'no dollar signs (Kotlin would read them as templates)');

const posted = [];
const realConnects = [];
class AudioNode { constructor(ctx) { this.context = ctx; } connect(dest) { realConnects.push([this.constructor.name, dest.constructor.name]); return dest; } }
class AudioDestinationNode extends AudioNode {}
class MediaStreamAudioDestinationNode extends AudioNode { constructor(ctx) { super(ctx); this.stream = { getAudioTracks: () => [{ clone: () => ({ kind: 'audio', fake: true }) }] }; } }
class GainNode extends AudioNode { constructor(ctx) { super(ctx); this.gain = { value: 1 }; } }
class ConstantSourceNode extends AudioNode { constructor(ctx) { super(ctx); this.offset = { value: 1 }; } start() {} }
class ScriptProcessorNode extends AudioNode {}
class BufferSourceNode extends AudioNode { start(t) { this.startedAt = t; started.push(this); } stop() { this.stopped = true; } }
const started = [];
class AudioContext {
  constructor(o) { this.sampleRate = (o && o.sampleRate) || 48000; this.state = 'running'; this.currentTime = 1; this.destination = new AudioDestinationNode(this); }
  createMediaStreamDestination() { return new MediaStreamAudioDestinationNode(this); }
  createGain() { return new GainNode(this); }
  createConstantSource() { return new ConstantSourceNode(this); }
  createScriptProcessor() { return new ScriptProcessorNode(this); }
  createBuffer(ch, n, rate) { return { duration: n / rate, n, rate, copyToChannel(f) { this.data = Float32Array.from(f); } }; }
  createBufferSource() { return new BufferSourceNode(this); }
  resume() { return Promise.resolve(); }
}
class MediaStream { constructor(tracks) { this.tracks = tracks; } }
let realGum = 0;
const g = globalThis;
g.window = g; g.top = g;
Object.assign(g, { AudioNode, AudioDestinationNode, MediaStreamAudioDestinationNode, AudioContext, MediaStream });
Object.defineProperty(g, 'navigator', { value: { mediaDevices: { getUserMedia: async () => { realGum++; return 'REAL'; } } }, configurable: true });
g.hinaCall = { postMessage: (s) => posted.push(JSON.parse(s)), onmessage: null };
const toPage = (o) => g.hinaCall.onmessage({ data: JSON.stringify(o) });
const of = (t) => posted.filter(m => m.t === t);

(0, eval)(js);
assert.deepEqual(posted.map(m => m.t), ['hello'], 'says hello once');
(0, eval)(js);
assert.equal(of('hello').length, 1, 'installs once');

// ---- disarmed: everything passes through ----
assert.equal(await navigator.mediaDevices.getUserMedia({ audio: true }), 'REAL');
const callCtx = new AudioContext({ sampleRate: 48000 });
const playback = new GainNode(callCtx);
playback.connect(callCtx.destination);
assert.deepEqual(realConnects.at(-1), ['GainNode', 'AudioDestinationNode'], 'disarmed: the speaker is connected for real');
assert.equal(window.__hina.taps, 0);
toPage({ t: 'play', rate: 24000, d: Buffer.from(new Int16Array(2400).buffer).toString('base64') });
assert.equal(started.length, 0, 'disarmed: nothing is played into a microphone');

// ---- armed ----
toPage({ t: 'arm', on: true });
assert.equal(of('state').at(-1).armed, true);
const mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true } });
assert.ok(mic instanceof MediaStream && mic.tracks[0].fake, 'armed: WhatsApp gets the fake microphone');
assert.equal(realGum, 1, 'the real microphone was not opened again');
assert.equal(await navigator.mediaDevices.getUserMedia({ audio: true, video: true }), 'REAL', 'a video request is left alone');

const before = realConnects.length;
const node = new GainNode(callCtx);
assert.equal(node.connect(callCtx.destination), callCtx.destination);
assert.equal(window.__hina.taps, 1, 'armed: the speaker is tapped');
// The tap (processor into a silent gain into the output, which keeps it running) and the call's sound into the tap. The call's own node never reaches the output.
assert.deepEqual(realConnects.slice(before), [['ScriptProcessorNode', 'GainNode'], ['GainNode', 'AudioDestinationNode'], ['GainNode', 'ScriptProcessorNode']]);

// 0.3 s of a 1 kHz tone at 48 kHz in 2048-sample callbacks -> 16 kHz frames of at least 100 ms
const sp = callCtx.__hinaTap;
let n = 0;
for (let k = 0; k < 7; k++) {
  const f = new Float32Array(2048); for (let i = 0; i < 2048; i++, n++) f[i] = 0.5 * Math.sin(2 * Math.PI * 1000 * n / 48000);
  sp.onaudioprocess({ inputBuffer: { getChannelData: () => f } });
}
const pcm = of('pcm');
assert.equal(pcm.length, 2, 'two frames for 0.3 s');
for (const m of pcm) {
  assert.equal(m.rate, 16000);
  const b = Buffer.from(m.d, 'base64'); assert.ok(b.length >= 3200 && b.length % 2 === 0, 'at least 100 ms, whole samples: ' + b.length);
  const s = new Int16Array(b.buffer, b.byteOffset, b.length / 2); let pk = 0; for (const v of s) pk = Math.max(pk, Math.abs(v));
  assert.ok(pk > 14000 && pk < 16500, 'the tone keeps its loudness: ' + pk);
}

// the agent's voice: 24 kHz PCM queued end to end into the fake microphone
const voice = new Int16Array(2400); voice[1] = 16384; voice[2] = -32768;
const d = Buffer.from(voice.buffer).toString('base64');
toPage({ t: 'play', rate: 24000, d }); toPage({ t: 'play', rate: 24000, d });
assert.equal(started.length, 2);
assert.equal(started[0].buffer.rate, 24000); assert.equal(started[0].buffer.n, 2400);
assert.equal(started[0].buffer.data[1], 0.5); assert.equal(started[0].buffer.data[2], -1);
assert.ok(Math.abs(started[1].startedAt - (started[0].startedAt + 0.1)) < 1e-9, 'the second piece starts where the first ends');
toPage({ t: 'flush' });
assert.ok(started.every(s => s.stopped), 'talking over the voice stops what is queued');

// ---- disarmed again ----
toPage({ t: 'arm', on: false });
assert.equal(of('state').at(-1).armed, false);
assert.equal(await navigator.mediaDevices.getUserMedia({ audio: true }), 'REAL');
const c2 = realConnects.length; new GainNode(callCtx).connect(callCtx.destination);
assert.deepEqual(realConnects[c2], ['GainNode', 'AudioDestinationNode']);
const pcmBefore = of('pcm').length;
for (let k = 0; k < 4; k++) sp.onaudioprocess({ inputBuffer: { getChannelData: () => new Float32Array(2048) } });
assert.equal(of('pcm').length, pcmBefore, 'disarmed: no sound leaves the page');
toPage({ t: 'status' }); assert.equal(of('state').at(-1).taps, 1);
hinaCall.onmessage({ data: 'not json' });
console.log('call bridge hooks: all checks passed (' + posted.length + ' messages)');
