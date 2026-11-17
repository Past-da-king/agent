// Scripted call against the REAL Gemini Live model: the exact instructions, tools, tool answer and framed replies the app sends
// (dumped by LiveTruthTest.dumpWhatTheRealSessionSendsForTheScriptedCall). Text in, the voice's output transcription out.
// usage: GEMINI_KEY=... node scripts/live-proof.mjs /tmp/live-prompt-dump.json [model]
import { readFileSync } from 'node:fs'

const dump = JSON.parse(readFileSync(process.argv[2], 'utf8'))
const model = process.argv[3] || 'gemini-3.1-flash-live-preview'
const key = process.env.GEMINI_KEY
const url = `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${key}`
const ws = new WebSocket(url)
let waiters = []
let buf = { said: '', calls: [], done: false }
const log = []
function reset() { buf = { said: '', calls: [], done: false } }
ws.onmessage = async (ev) => {
  const text = typeof ev.data === 'string' ? ev.data : ev.data instanceof Blob ? await ev.data.text() : Buffer.from(ev.data).toString('utf8')
  const m = JSON.parse(text)
  if (m.setupComplete) waiters.forEach((w) => w('setup'))
  const sc = m.serverContent
  if (sc?.outputTranscription?.text) buf.said += sc.outputTranscription.text
  if (m.toolCall) { for (const c of m.toolCall.functionCalls || []) buf.calls.push(c) }
  if (sc?.turnComplete || m.toolCall) { buf.done = true; waiters.forEach((w) => w('turn')); }
}
const once = (what) => new Promise((res) => { const f = (v) => { if (v === what) { waiters = waiters.filter((x) => x !== f); res() } }; waiters.push(f) })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const send = (o) => ws.send(JSON.stringify(o))
async function say(label, text) {
  reset()
  send({ clientContent: { turns: [{ role: 'user', parts: [{ text }] }], turnComplete: true } })
  await turnEnd()
  const out = { step: label, input: text.slice(0, 90), voiceSaid: buf.said.trim(), toolCalls: buf.calls.map((c) => ({ name: c.name, args: c.args })) }
  log.push(out); console.log(JSON.stringify(out)); return out
}
async function turnEnd() { // wait for turnComplete (or a tool call), then a short quiet
  const t0 = Date.now()
  while (Date.now() - t0 < 45000) { await sleep(400); if (buf.done) { await sleep(2500); return } }
}
ws.onopen = () => {
  send({ setup: { model: `models/${model}`, generationConfig: { responseModalities: ['AUDIO'], speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: 'Kore' } } } },
    systemInstruction: { parts: [{ text: dump.system }] }, tools: dump.tools, outputAudioTranscription: {}, inputAudioTranscription: {} } })
}
ws.onerror = (e) => { console.error('ws error', e.message || e); process.exit(2) }
ws.onclose = (e) => { if (!process.exitCode) console.error('closed', e.code, e.reason) }
await once('setup')
console.log('MODEL', model)
// 1. He asks. The voice must hand off and say only a holding line.
const first = await say('1 ask', "Yo, my guy, did we upload Thuli's PDF to NSFAS?")
const call = buf.calls[0]
if (call) {
  reset()
  send({ toolResponse: { functionResponses: [{ id: call.id, name: call.name, response: { status: 'sent_not_done', done: false, sent_to: 'your main chat', note: dump.ack } }] } })
  await turnEnd()
  const out = { step: '1b holding line after tool answer', voiceSaid: buf.said.trim() }; log.push(out); console.log(JSON.stringify(out))
}
// 2. While it runs he asks what is done: no outcome may be claimed.
await say('2 what is done', 'What is done?')
// 3. The agent is late: the late line.
await say('3 late line', dump.late)
// 4. The agent replies (started a helper): read as written.
await say('4 agent reply 1', dump.frame1)
// 5. Meanwhile he asks again.
await say('5 is it done yet', 'Is it done yet?')
// 6. Minutes later the helper result arrives: read as written.
await say('6 follow-up result', dump.frame2)
ws.close(); await sleep(300); process.exit(0)
