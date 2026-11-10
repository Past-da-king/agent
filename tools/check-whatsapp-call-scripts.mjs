// Runs the page scripts of place_whatsapp_call (WhatsAppCallJs in app/.../browser/WhatsAppCall.kt) against a made-up
// WhatsApp Web, in Node, with no browser: reading the page, opening a chat, pressing Voice call from page script,
// reading the call's state, hanging up, and switching calling on ONLY for the number it is handed when that number
// is the one signed in. The numbers are made up.
//   node tools/check-whatsapp-call-scripts.mjs
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const kt = readFileSync(new URL('../app/src/main/java/com/past9/phoneaos/browser/WhatsAppCall.kt', import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const obj = kt.slice(kt.indexOf('object WhatsAppCallJs'), kt.indexOf('class WhatsAppCall('));
assert.ok(!obj.includes('$'), 'no dollar signs (Kotlin would read them as templates)');
const raw = (from) => { const a = obj.indexOf('"""', obj.indexOf(from)) + 3; return obj.slice(a, obj.indexOf('"""', a)).trim(); };
const Q = raw('private val Q =') + '\n';
const script = (name) => Q + raw('val ' + name + ' = Q +');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const run = (name, args = {}) => new AsyncFunction('args', script(name))(args);

// ---- a made-up page ----
class El {
  constructor(tag, attrs = {}, kids = []) { this.tag = tag; this.attrs = attrs; this.kids = kids; this.parent = null; this.clicks = 0; this.innerText = attrs.text || ''; kids.forEach(k => { k.parent = this; }); }
  getAttribute(n) { return n in this.attrs ? this.attrs[n] : null; }
  click() { this.clicks++; if (this.onclick) this.onclick(); }
  all() { return this.kids.flatMap(k => [k, ...k.all()]); }
  matches1(sel) {
    const m = /^([a-z]*)(#[\w-]+)?((?:\[[^\]]+\])*)$/.exec(sel); if (!m) throw new Error('selector not understood: ' + sel);
    if (m[1] && m[1] !== this.tag) return false;
    if (m[2] && this.attrs.id !== m[2].slice(1)) return false;
    for (const a of (m[3].match(/\[[^\]]+\]/g) || [])) { const [k, v] = a.slice(1, -1).split('='); if (!(k in this.attrs)) return false; if (v !== undefined && this.attrs[k] !== v.replace(/^"|"$/g, '')) return false; }
    return true;
  }
  matches(sel) { const parts = sel.trim().match(/(?:\[[^\]]*\]|[^\s\[])+/g); if (!this.matches1(parts.at(-1))) return false; let p = this.parent; for (let i = parts.length - 2; i >= 0; i--) { while (p && !p.matches1(parts[i])) p = p.parent; if (!p) return false; p = p.parent; } return true; }
  querySelectorAll(sel) { const alts = sel.split(','); return this.all().filter(e => alts.some(a => e.matches(a))); }
  querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
  closest(sel) { let e = this; const alts = sel.split(','); while (e) { if (alts.some(a => e.matches1(a.trim()))) return e; e = e.parent; } return null; }
}
const g = globalThis;
const page = (kids) => { g.document = new El('html', {}, kids); };
g.window = g; g.self = g;
g.location = { origin: 'https://web.whatsapp.com' };

// ---- before WhatsApp has loaded: nothing throws, nothing is claimed ----
page([]);
assert.deepEqual(await run('INFO'), { on: true, loaded: false, hooks: false, me: null, calling: false, unsupported: null });
assert.deepEqual(await run('HAS_BUTTON'), { button: false, disabled: false });
assert.deepEqual(await run('PRESS_CALL'), { ok: false });
assert.deepEqual(await run('HANG_UP'), { ok: false });
assert.equal((await run('OPEN_CHAT', { chat: 'Sam' })).ok, false);
assert.equal((await run('ENABLE_CALLING', { number: '15550100' })).ok, false);
const idle = await run('STATE');
assert.equal(idle.pending, false); assert.equal(idle.connected, false); assert.equal(idle.active, null); assert.equal(idle.ended, null); assert.equal(idle.end_button, false);

// ---- a made-up WhatsApp ----
const CODE = 777; // whatever WhatsApp numbers the setting in this build: the script must find it by name
const props = new Map([[CODE, { configCode: CODE, configValue: false }], [15461, { configCode: 15461, configValue: false }]]);
let signedIn = { user: '15550100', _serialized: '15550100@c.us' };
const chat = (id, name, extra = {}) => ({ id: { _serialized: id, user: id.split('@')[0] }, formattedTitle: name, ...extra });
const chats = [chat('111@lid', 'Sam Jones', { contact: { phoneNumber: { user: '15550123' } } }), chat('15550144@c.us', 'Alex'), chat('222@lid', 'Alexandra'), chat('999@g.us', 'Family'), chat('333@lid', 'Robin A'), chat('444@lid', 'Robin B')];
const opened = []; const closed = [];
const collection = { pendingOutgoingCall: null, isInConnectedCall: false, lastActiveCall: null, lastEndedCall: null };
const header = (...kids) => new El('div', { id: 'main' }, [new El('header', {}, kids)]);
const mods = {
  WAWebUserPrefsMeUser: { getMaybeMePnUser: () => signedIn },
  WAWebVoipGatingUtils: { isCallingEnabled: () => props.get(CODE).configValue === true, getUnsupportedBrowserReason: () => null },
  WAWebABPropsConfigs: { ABPropConfigs: { enable_web_calling: [CODE, 'bool', false] } },
  WAWebABPropsCache: { getAllABPropsMap: () => props },
  WAWebChatCollection: { ChatCollection: { getModelsArray: () => chats } },
  WAWebCmd: { Cmd: { openChatBottom: async ({ chat: c }) => { opened.push(c.id._serialized); page([new El('div', { id: 'pane-side' }), header(new El('span', { dir: 'auto', text: c.formattedTitle }))]); }, closeChat: async (c) => { closed.push(c.id._serialized); } } },
  WAWebCallCollection: { CallCollection: collection },
};
g.require = (n) => { if (!(n in mods)) throw new Error('no module ' + n); return mods[n]; };
page([new El('div', { id: 'pane-side' })]);
g.__hina = {};
assert.deepEqual(await run('INFO'), { on: true, loaded: true, hooks: true, me: '15550100', calling: false, unsupported: null });
signedIn = { _serialized: '15550100:12@c.us' };
assert.equal((await run('INFO')).me, '15550100', 'a device suffix is not part of the number');
mods.WAWebVoipGatingUtils.getUnsupportedBrowserReason = () => 'missing_shared_array_buffer';
assert.equal((await run('INFO')).unsupported, 'missing_shared_array_buffer');
mods.WAWebVoipGatingUtils.getUnsupportedBrowserReason = () => null;

// ---- calling is switched on only for the number handed in, and only when that number is signed in ----
signedIn = { user: '15550199' };
assert.equal((await run('ENABLE_CALLING', { number: '15550100' })).ok, false, 'another account is signed in');
assert.equal(props.get(CODE).configValue, false, 'nothing was changed for the other account');
signedIn = null;
assert.equal((await run('ENABLE_CALLING', { number: '15550100' })).ok, false, 'nobody is signed in');
signedIn = { user: '15550100' };
assert.equal((await run('ENABLE_CALLING', {})).ok, false, 'no number handed in');
assert.equal((await run('ENABLE_CALLING', { number: '5550100' })).ok, false, 'a part of the number is another number');
assert.equal(props.get(CODE).configValue, false);
assert.deepEqual(await run('ENABLE_CALLING', { number: '15550100' }), { ok: true, calling: true });
assert.equal(props.get(CODE).configValue, true);
assert.equal(props.get(15461).configValue, false, 'the setting is found by its name, not by a number written into the script');
props.delete(CODE);
assert.equal((await run('ENABLE_CALLING', { number: '15550100' })).ok, false, 'a build without the setting is said so');
props.set(CODE, { configCode: CODE, configValue: true });

// ---- opening a chat ----
assert.deepEqual(await run('OPEN_CHAT', { chat: 'sam jones' }), { ok: true, id: '111@lid', name: 'Sam Jones' });
assert.deepEqual(await run('OPEN_CHAT', { chat: '+1 555 0123' }), { ok: true, id: '111@lid', name: 'Sam Jones' }, 'by the contact number');
assert.equal((await run('OPEN_CHAT', { chat: '15550144' })).id, '15550144@c.us', 'by the number in the chat id');
assert.equal((await run('OPEN_CHAT', { chat: '222@lid' })).name, 'Alexandra', 'by id');
assert.equal((await run('OPEN_CHAT', { chat: 'Alex' })).id, '15550144@c.us', 'an exact name wins over a longer one');
assert.equal((await run('OPEN_CHAT', { chat: 'Alexa' })).name, 'Alexandra', 'a part of one name');
const several = await run('OPEN_CHAT', { chat: 'Robin' });
assert.equal(several.ok, false); assert.ok(several.why.includes('Robin A') && several.why.includes('Robin B'));
assert.equal((await run('OPEN_CHAT', { chat: 'Family' })).ok, false, 'a group is not called');
assert.equal((await run('OPEN_CHAT', { chat: 'Nobody here' })).ok, false);
assert.deepEqual(opened, ['111@lid', '111@lid', '15550144@c.us', '222@lid', '15550144@c.us', '222@lid']);
assert.deepEqual(closed, []);
await run('OPEN_CHAT', { chat: 'Sam Jones', reopen: true });
assert.deepEqual(closed, ['111@lid'], 'reopened after calling was switched on, so the header is drawn again');

// ---- the voice call button: found by its label, pressed from page script ----
assert.deepEqual(await run('HAS_BUTTON'), { button: false, disabled: false }, 'no call button for an account without calling');
const voiceBtn = new El('button', { 'aria-label': 'Voice call' });
page([new El('div', { id: 'pane-side' }), header(new El('button', { 'aria-label': 'Video call' }), voiceBtn), new El('button', { 'aria-label': 'Voice call' })]);
assert.deepEqual(await run('HAS_BUTTON'), { button: true, disabled: false });
assert.deepEqual(await run('PRESS_CALL'), { ok: true });
assert.equal(voiceBtn.clicks, 1, 'the button in the chat header was clicked, once');
const iconBtn = new El('div', { role: 'button' }, [new El('span', { 'data-icon': 'voice-call' })]);
page([header(iconBtn)]);
await run('PRESS_CALL'); assert.equal(iconBtn.clicks, 1, 'found by its icon when the label is in another language');
page([header(new El('button', { 'aria-label': 'Voice call', 'aria-disabled': 'true' }))]);
assert.deepEqual(await run('HAS_BUTTON'), { button: true, disabled: true });

// ---- the call's state ----
collection.pendingOutgoingCall = { id: 'C1' };
collection.lastActiveCall = { id: { _serialized: 'C1' }, getState: () => 'Calling', peerJid: { big: 'object' } };
const endButton = new El('button', { 'aria-label': 'End call' });
page([header(), endButton, new El('div', { role: 'dialog', text: '  Calling Sam Jones  ' })]);
assert.deepEqual(await run('STATE'), { pending: true, connected: false, active: { id: 'C1', state: 'Calling' }, ended: null, end_button: true, dialog: 'Calling Sam Jones' });
collection.pendingOutgoingCall = null; collection.isInConnectedCall = () => true;
assert.equal((await run('STATE')).connected, true, 'a value WhatsApp exposes as a function is read too');
collection.isInConnectedCall = false; collection.lastActiveCall = null;
collection.lastEndedCall = { id: 'C1', callOutcome: 'Completed', callDuration: 21, wasEverConnected: true, userEndedCall: false, callFailedReason: null, callLogResult: 5 };
page([header()]);
assert.deepEqual(await run('STATE'), { pending: false, connected: false, active: null, ended: { id: 'C1', callOutcome: 'Completed', callDuration: 21, wasEverConnected: true, userEndedCall: false, callFailedReason: null, callLogResult: 5 }, end_button: false, dialog: null });

// ---- hanging up ----
page([header(), endButton]);
assert.deepEqual(await run('HANG_UP'), { ok: true, how: 'button' }); assert.equal(endButton.clicks, 1);
page([header()]);
let endedByModel = 0; collection.lastActiveCall = { id: 'C2', endCall: async () => { endedByModel++; } };
assert.deepEqual(await run('HANG_UP'), { ok: true, how: 'endCall' }); assert.equal(endedByModel, 1);
collection.lastActiveCall = null;
assert.deepEqual(await run('HANG_UP'), { ok: false }, 'nothing to hang up is said, so the app can leave the page instead');

// ---- only one script touches WhatsApp's settings ----
for (const name of ['INFO', 'OPEN_CHAT', 'HAS_BUTTON', 'PRESS_CALL', 'STATE', 'HANG_UP']) assert.ok(!raw('val ' + name + ' = Q +').includes('ABProps'), name);
assert.ok(!Q.includes('ABProps'));
console.log('whatsapp call scripts: all checks passed');
