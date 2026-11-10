// Runs the WhatsApp call page script (CallBridgeJs.HOOKS in app/.../browser/CallBridge.kt) against a made-up page,
// in Node, with no browser: disarmed it must pass everything through; armed it must hand out the fake microphone,
// tap the speaker, send 16 kHz frames to the app and play what the app sends. Installed (the switch is on) it must
// list one named microphone, say the microphone permission is granted and make the fake microphone mono at 48 kHz;
// not installed (the switch is off) it must change nothing. While a call voice runs it must also keep the call's own
// story: a microphone that is restarted when the browser stops it and is never perfectly silent, microphone tracks
// that are watched, page errors, counters from the call's connections, and WhatsApp's own call events read on their
// way through and passed on untouched; and it must stop all of that when the voice is gone.
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
class MediaStreamAudioDestinationNode extends AudioNode { constructor(ctx) { super(ctx); this.channelCount = 2; this.channelCountMode = 'explicit'; this.stream = { getAudioTracks: () => [{ clone: () => { const t = { kind: 'audio', fake: true, on: {}, addEventListener(k, f) { this.on[k] = f; } }; tracks.push(t); return t; } }] }; } }
const tracks = [];
class GainNode extends AudioNode { constructor(ctx) { super(ctx); this.gain = { value: 1 }; } }
class ConstantSourceNode extends AudioNode { constructor(ctx) { super(ctx); this.offset = { value: 1 }; } start() {} }
class ScriptProcessorNode extends AudioNode {}
class BufferSourceNode extends AudioNode { start(t) { this.startedAt = t; (this.loop ? loops : started).push(this); } stop() { this.stopped = true; } }
const started = [];
const loops = [];
let resumes = 0;
class AudioContext {
  constructor(o) { this.sampleRate = (o && o.sampleRate) || 48000; this.state = 'running'; this.currentTime = 1; this.destination = new AudioDestinationNode(this); }
  createMediaStreamDestination() { return new MediaStreamAudioDestinationNode(this); }
  createGain() { return new GainNode(this); }
  createConstantSource() { return new ConstantSourceNode(this); }
  createScriptProcessor() { return new ScriptProcessorNode(this); }
  createBuffer(ch, n, rate) { return { duration: n / rate, n, rate, copyToChannel(f) { this.data = Float32Array.from(f); } }; }
  createBufferSource() { return new BufferSourceNode(this); }
  resume() { resumes++; return Promise.resolve(); }
  addEventListener(k, f) { (this.on = this.on || {})[k] = f; }
}
// The page's timers, held by hand: nothing here waits for a real second.
const intervals = [];
const timeouts = [];
const realTimers = { setInterval: globalThis.setInterval, clearInterval: globalThis.clearInterval, setTimeout: globalThis.setTimeout, clearTimeout: globalThis.clearTimeout };
globalThis.setInterval = (f, ms) => { const t = { f, ms }; intervals.push(t); return t; };
globalThis.clearInterval = (t) => { const i = intervals.indexOf(t); if (i >= 0) intervals.splice(i, 1); };
globalThis.setTimeout = (f, ms) => { const t = { f, ms }; timeouts.push(t); return t; };
globalThis.clearTimeout = (t) => { const i = timeouts.indexOf(t); if (i >= 0) timeouts.splice(i, 1); };
const pageListeners = {};
globalThis.addEventListener = (k, f) => { pageListeners[k] = f; };
// The connections of a call: what WhatsApp calls on them must go through unchanged.
const pcCalls = [];
class RTCPeerConnection {
  constructor(stats) { this.stats = stats || []; this.connectionState = 'connected'; this.signalingState = 'stable'; this.iceConnectionState = 'connected'; }
  createDataChannel(label) { pcCalls.push(['createDataChannel', label]); return 'CHANNEL ' + label; }
  setLocalDescription(d) { pcCalls.push(['setLocalDescription', d]); return 'LOCAL'; }
  setRemoteDescription(d) { pcCalls.push(['setRemoteDescription', d]); return 'REMOTE'; }
  async getStats() { return { forEach: (f) => this.stats.forEach(f) }; }
}
globalThis.RTCPeerConnection = RTCPeerConnection;
const realPc = { createDataChannel: RTCPeerConnection.prototype.createDataChannel, setLocalDescription: RTCPeerConnection.prototype.setLocalDescription };
// WhatsApp's own modules, as far as the script reads them.
const waHandled = [];
const waEvents = { handleWAWebVoipNativeCallEvent: function (type, data) { waHandled.push([type, data, this]); return 'HANDLED ' + type; } };
const realHandler = waEvents.handleWAWebVoipNativeCallEvent;
const waNames = { 10: 'CallTerminateReceived', 38: 'CallEnding', 100: 'SpeakerStatusChanged' };
const waCol = { isInConnectedCall: false, lastEndedCall: { id: 'OLD', callLogResult: 4 } };
const waModules = { WAWebVoipHandleNativeCallEvent: waEvents, WAWebVoipWaCallEnums: { CallEvent: { getName: (n) => waNames[n] } }, WAWebCallCollection: { default: waCol } };
globalThis.require = (n) => { if (!waModules[n]) throw new Error('no module ' + n); return waModules[n]; };
class MediaStream { constructor(tracks) { this.tracks = tracks; } }
let realGum = 0;
const g = globalThis;
g.window = g; g.top = g;
Object.assign(g, { AudioNode, AudioDestinationNode, MediaStreamAudioDestinationNode, AudioContext, MediaStream });
// What Android's WebView hands a page: devices without ids or names, and a microphone permission that reads "prompt".
const anonymous = [
  { kind: 'audioinput', deviceId: '', label: '', groupId: '' }, { kind: 'audioinput', deviceId: '', label: '', groupId: '' },
  { kind: 'videoinput', deviceId: '', label: '', groupId: '' }, { kind: 'audiooutput', deviceId: '', label: '', groupId: '' },
];
const realGumArgs = [];
const realQueries = [];
const realEnumerate = async () => anonymous.map(d => ({ ...d }));
const realQuery = async (d) => { realQueries.push(d.name); return { name: d.name, state: 'prompt', onchange: null }; };
Object.defineProperty(g, 'navigator', { value: {
  mediaDevices: { getUserMedia: async (c) => { realGum++; realGumArgs.push(c); return 'REAL'; }, enumerateDevices: realEnumerate },
  permissions: { query: realQuery },
}, configurable: true });

// ---- the switch is off: the app adds no listener, so the script does nothing at all ----
(0, eval)(js);
assert.equal(g.__hina, undefined, 'switch off: nothing installed');
assert.equal(navigator.mediaDevices.enumerateDevices, realEnumerate, 'switch off: the device list is the browser as it is');
assert.equal(navigator.permissions.query, realQuery, 'switch off: permissions are the browser as it is');
assert.deepEqual(await navigator.mediaDevices.enumerateDevices(), anonymous);
assert.equal((await navigator.permissions.query({ name: 'microphone' })).state, 'prompt');
realQueries.length = 0;
assert.equal(RTCPeerConnection.prototype.createDataChannel, realPc.createDataChannel, 'switch off: connections are the browser as it is');
assert.deepEqual(Object.keys(pageListeners), [], 'switch off: no page error is listened for');
assert.equal(waEvents.handleWAWebVoipNativeCallEvent, realHandler, 'switch off: WhatsApp is not read');

g.hinaCall = { postMessage: (s) => posted.push(JSON.parse(s)), onmessage: null };
const toPage = (o) => g.hinaCall.onmessage({ data: JSON.stringify(o) });
const of = (t) => posted.filter(m => m.t === t);

(0, eval)(js);
assert.deepEqual(posted.map(m => m.t), ['hello'], 'says hello once');
(0, eval)(js);
assert.equal(of('hello').length, 1, 'installs once');

// ---- the switch is on: what WhatsApp asks before it captures a microphone ----
const devices = await navigator.mediaDevices.enumerateDevices();
const mics = devices.filter(d => d.kind === 'audioinput');
assert.equal(mics.length, 1, 'one microphone');
assert.deepEqual({ ...mics[0] }, { kind: 'audioinput', deviceId: 'default', label: 'Agent mic', groupId: 'agent-audio' }, 'a named microphone with an id');
assert.deepEqual(JSON.parse(JSON.stringify(mics[0])), { deviceId: 'default', kind: 'audioinput', label: 'Agent mic', groupId: 'agent-audio' });
assert.equal((await navigator.mediaDevices.enumerateDevices())[0].deviceId, mics[0].deviceId, 'the id stays the same');
assert.deepEqual(devices.filter(d => d.kind === 'audiooutput'), [{ kind: 'audiooutput', deviceId: '', label: '', groupId: '' }], 'speakers are left as the browser gives them');
assert.equal(devices.filter(d => d.kind === 'videoinput').length, 1, 'cameras are left alone');
const perm = await navigator.permissions.query({ name: 'microphone' });
assert.equal(perm.state, 'granted'); assert.equal(perm.name, 'microphone'); assert.equal(perm.onchange, null);
assert.ok(perm instanceof EventTarget && typeof perm.addEventListener === 'function', 'a status WhatsApp can listen to');
assert.equal((await navigator.permissions.query({ name: 'camera' })).state, 'prompt', 'other permissions are the browser as it is answer');
assert.equal((await navigator.permissions.query({ name: 'notifications' })).state, 'prompt');
assert.deepEqual(realQueries, ['camera', 'notifications'], 'the microphone question never reaches the browser');
assert.equal(window.__hina.state().device_lists, 2); assert.equal(window.__hina.state().mic_asked, 1);

// ---- disarmed: everything passes through ----
assert.equal(await navigator.mediaDevices.getUserMedia({ audio: true }), 'REAL');
// A request that names the made-up microphone while no call voice runs reaches the phone's real one, without the made-up id.
await navigator.mediaDevices.getUserMedia({ audio: { deviceId: { exact: 'default' }, echoCancellation: true } });
assert.deepEqual(realGumArgs.at(-1), { audio: { echoCancellation: true } });
await navigator.mediaDevices.getUserMedia({ audio: { deviceId: 'default' } });
assert.deepEqual(realGumArgs.at(-1), { audio: {} });
await navigator.mediaDevices.getUserMedia({ audio: { deviceId: { exact: 'other' } } });
assert.deepEqual(realGumArgs.at(-1), { audio: { deviceId: { exact: 'other' } } }, 'any other id is passed on as asked');
realGum = 1;
const callCtx = new AudioContext({ sampleRate: 48000 });
const playback = new GainNode(callCtx);
playback.connect(callCtx.destination);
assert.deepEqual(realConnects.at(-1), ['GainNode', 'AudioDestinationNode'], 'disarmed: the speaker is connected for real');
assert.equal(window.__hina.taps, 0);
toPage({ t: 'play', rate: 24000, d: Buffer.from(new Int16Array(2400).buffer).toString('base64') });
assert.equal(started.length, 0, 'disarmed: nothing is played into a microphone');
// Before any call voice: nothing of the call's story is collected, and everything passes through.
const idle = new RTCPeerConnection();
assert.equal(idle.createDataChannel('a'), 'CHANNEL a'); assert.equal(idle.setLocalDescription('x'), 'LOCAL'); assert.equal(idle.setRemoteDescription('y'), 'REMOTE');
assert.deepEqual(pcCalls, [['createDataChannel', 'a'], ['setLocalDescription', 'x'], ['setRemoteDescription', 'y']]);
assert.equal(window.__hina.pcs.length, 0, 'disarmed: no connection is kept');
pageListeners.error({ message: 'boom before' });
assert.equal(of('err').length, 0, 'disarmed: page errors are not sent');
assert.equal(intervals.length, 0, 'disarmed: nothing runs every second');
assert.equal(waEvents.handleWAWebVoipNativeCallEvent, realHandler, 'disarmed: WhatsApp is not read');
toPage({ t: 'sample' }); await null;
assert.equal(of('sample').length, 0, 'disarmed: no counters');

// ---- armed ----
toPage({ t: 'arm', on: true });
assert.equal(of('state').at(-1).armed, true);
const mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true } });
assert.ok(mic instanceof MediaStream && mic.tracks[0].fake, 'armed: WhatsApp gets the fake microphone');
assert.equal(realGum, 1, 'the real microphone was not opened again');
assert.ok((await navigator.mediaDevices.getUserMedia({ audio: { deviceId: { exact: 'default' } } })).tracks[0].fake, 'armed: asking for the listed microphone by id gets the fake one');
assert.equal(window.__hina.micCtx.sampleRate, 48000, 'the fake microphone runs at 48 kHz');
assert.equal(window.__hina.micDest.channelCount, 1, 'the fake microphone is mono');
assert.equal(window.__hina.micDest.channelCountMode, 'explicit');
assert.equal(window.__hina.state().mic_channels, 1);
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

// ---- armed: the microphone is one unbroken stream for the whole call ----
const H = window.__hina;
const evs = (k) => of('ev').filter(m => m.k === k);
assert.equal(loops.length, 1, 'one looping source keeps the microphone from being perfectly silent');
const hiss = loops[0].buffer.data; let hissPeak = 0; for (const v of hiss) hissPeak = Math.max(hissPeak, Math.abs(v));
assert.ok(hissPeak > 0 && hissPeak <= 0.0001, 'far too quiet to hear, but not zeros: ' + hissPeak);
assert.ok(!loops[0].stopped, 'talking over the voice does not stop it');
assert.equal(tracks.length, 2); assert.equal(H.state().tracks_out, 2, 'each microphone track handed out is counted');
tracks[0].on.mute(); tracks[0].on.ended();
assert.deepEqual(evs('mic_track').map(m => m.d), ['mute', 'ended'], 'a track that goes mute or ends by itself is reported');
assert.equal(H.state().tracks_ended, 1); assert.equal(H.state().tracks_muted, 1);
const r0 = resumes;
H.micCtx.state = 'suspended'; H.micCtx.onstatechange();
assert.equal(evs('mic_context').at(-1).d, 'suspended'); assert.equal(resumes, r0 + 1, 'a stopped microphone is started again'); assert.equal(H.state().mic_resumes, 1);
H.micCtx.state = 'running'; H.micCtx.onstatechange();
assert.equal(resumes, r0 + 1, 'a running microphone is left alone');
callCtx.state = 'interrupted'; callCtx.on.statechange();
assert.equal(evs('call_context').at(-1).d, 'interrupted'); assert.equal(resumes, r0 + 2, "the call's own sound is started again too");
callCtx.state = 'running';
assert.ok(of('ev').every(m => typeof m.at === 'number' && m.at > 1.6e12), 'every event carries the time');

// ---- armed: page errors, the call's connections, WhatsApp's own call events ----
pageListeners.error({ message: 'voip: [AV] No audio device selected.' });
pageListeners.unhandledrejection({ reason: new Error('rejected') });
assert.deepEqual(of('err').map(m => m.m), ['voip: [AV] No audio device selected.', 'rejected']);
const quietPc = new RTCPeerConnection([{ type: 'transport', bytesSent: 10, bytesReceived: 5 }]);
const busyPc = new RTCPeerConnection([{ type: 'transport', bytesSent: 9088, bytesReceived: 7390 }, { type: 'candidate-pair', nominated: true, bytesSent: 9000, bytesReceived: 7000 }, { type: 'candidate-pair', nominated: false, bytesSent: 99999, bytesReceived: 99999 }]);
const closedPc = new RTCPeerConnection([{ type: 'transport', bytesSent: 1e9, bytesReceived: 1e9 }]); closedPc.connectionState = 'closed';
assert.equal(quietPc.createDataChannel('q'), 'CHANNEL q'); assert.equal(busyPc.setLocalDescription('o'), 'LOCAL'); assert.equal(closedPc.setRemoteDescription('c'), 'REMOTE'); busyPc.createDataChannel('again');
assert.equal(H.pcs.length, 3, 'each connection of the call is kept once');
toPage({ t: 'sample' }); for (let i = 0; i < 20; i++) await null;
const sample = of('sample').at(-1);
assert.equal(sample.pcs, 2, 'closed connections are not counted');
assert.equal(sample.bytes_sent, 9088); assert.equal(sample.bytes_received, 7390); assert.equal(sample.ice, 'connected');
assert.equal(sample.played, 2); assert.equal(sample.remote_frames, 7); assert.equal(sample.mic, 'running'); assert.equal(sample.tracks_ended, 1); assert.equal(sample.errors, 3);

assert.equal(intervals.length, 1); assert.equal(intervals[0].ms, 1000, 'the call is looked at once a second');
assert.equal(H.state().wa_events, true, "WhatsApp's call events are read");
assert.equal(evs('wa_ended').length, 0, 'a call that ended before this one is not reported');
intervals[0].f();
assert.equal(evs('wa_connected').length, 0);
waCol.isInConnectedCall = true; intervals[0].f(); intervals[0].f();
assert.equal(evs('wa_connected').length, 1, 'the answer is reported once');
const owner = waEvents;
assert.equal(owner.handleWAWebVoipNativeCallEvent(10, '{"x":1}'), 'HANDLED 10', "WhatsApp's own handler still runs and its answer comes back");
assert.deepEqual(waHandled.at(-1), [10, '{"x":1}', owner], 'with the same arguments and the same this');
assert.equal(evs('wa_event').at(-1).d, 'CallTerminateReceived', 'the other side hanging up is named');
owner.handleWAWebVoipNativeCallEvent(38, '{"result":5,"connect_time":20000}');
assert.equal(evs('wa_event').at(-1).d, 'CallEnding {"result":5,"connect_time":20000}', 'the end of the call carries what WhatsApp wrote down');
for (let i = 0; i < 6; i++) owner.handleWAWebVoipNativeCallEvent(100, 'x');
assert.equal(evs('wa_event').filter(m => m.d.startsWith('SpeakerStatusChanged')).length, 3, 'a chatty event is written down three times, not every time');
owner.handleWAWebVoipNativeCallEvent(777, null);
assert.equal(evs('wa_event').at(-1).d, '777', 'an event with no name is written by its number');
assert.equal(waHandled.length, 9, 'every event reached WhatsApp');
waCol.isInConnectedCall = false;
waCol.lastEndedCall = { id: { _serialized: 'CALL1' }, callOutcome: 'Completed', callDuration: 20, wasEverConnected: true, userEndedCall: false, callFailedReason: null, callLogResult: 5, peer: { big: 'object' } };
intervals[0].f(); intervals[0].f();
assert.equal(evs('wa_not_connected').length, 1);
assert.deepEqual(JSON.parse(evs('wa_ended').at(-1).d), { id: 'CALL1', callOutcome: 'Completed', callDuration: 20, wasEverConnected: true, userEndedCall: false, callFailedReason: null, callLogResult: 5 });
assert.equal(evs('wa_ended').length, 1, 'the end is reported once');

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
// The voice is gone: the page looks for the end of the call a little longer, then collects nothing.
assert.equal(timeouts.length, 1); assert.equal(timeouts[0].ms, 20000); assert.equal(intervals.length, 1);
waCol.lastEndedCall = { id: 'CALL2', callLogResult: 5 }; intervals[0].f();
assert.equal(evs('wa_ended').length, 2, 'an end that comes just after the voice stopped is still reported');
assert.equal(intervals.length, 0, 'and then the page stops looking');
assert.equal(timeouts.length, 0);
const evCount = of('ev').length, errCount = of('err').length, sampleCount = of('sample').length;
pageListeners.error({ message: 'later' });
assert.equal(owner.handleWAWebVoipNativeCallEvent(10, 'y'), 'HANDLED 10');
const later = new RTCPeerConnection(); later.createDataChannel('later');
toPage({ t: 'sample' }); await null;
assert.equal(of('ev').length, evCount); assert.equal(of('err').length, errCount); assert.equal(of('sample').length, sampleCount); assert.equal(H.pcs.length, 0, 'after the call nothing is collected');
// A second call voice: the story starts again, and the wait from the first is not left behind.
toPage({ t: 'arm', on: true }); toPage({ t: 'arm', on: false });
assert.equal(timeouts.length, 1); assert.equal(intervals.length, 1);
toPage({ t: 'arm', on: true });
assert.equal(timeouts.length, 0, 'arming again cancels the wait'); assert.equal(intervals.length, 1);
toPage({ t: 'arm', on: false }); timeouts[0].f();
assert.equal(intervals.length, 0, 'twenty seconds after the voice stopped the page stops looking'); assert.equal(H.watch, false);
Object.assign(globalThis, realTimers);
hinaCall.onmessage({ data: 'not json' });
console.log('call bridge hooks: all checks passed (' + posted.length + ' messages)');
