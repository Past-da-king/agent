// A fake Agent node for testing the phone's Remote mode without the desktop app (devices protocol s2, s3, s5, s5.2, s11).
// Runs two brains ("Desk", priority 0, and "Server", priority 1) that share one mesh and one set of rows, each with its
// own self-signed certificate. A control port on loopback takes them down and up to prove failover.
//
//   node fake-node.mjs --host <address the phone can reach> --ports 17801,17802 --control 17809 --ws <path to the ws package>
//   Voice notes: put a .wav in --notes (default /tmp/fake-node-notes) and say "voice" (or "gone" for a missing one).
//   curl -X POST http://127.0.0.1:17809/down?node=desk     (and /up?node=desk, /offer, /code)
//
// Nothing here is a real secret: certificates and passes are made fresh on every start and live in memory.
import https from 'node:https'
import http from 'node:http'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'

const arg = (name, dflt) => { const i = process.argv.indexOf(`--${name}`); return i > 0 ? process.argv[i + 1] : dflt }
const HOST = arg('host', '127.0.0.1')
const NOTES = arg('notes', '/tmp/fake-node-notes')
const PORTS = arg('ports', '17801,17802').split(',').map(Number)
const CONTROL = Number(arg('control', '17809'))
const require = createRequire(import.meta.url)
const { WebSocketServer } = require(arg('ws', 'ws'))

const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a)
const b64url = (buf) => buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
const rid = (p) => `${p}_${crypto.randomBytes(6).toString('hex')}`
const MESH = rid('mesh')
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'

function makeCert(name) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fake-node-'))
  const key = path.join(dir, 'key.pem'), cert = path.join(dir, 'cert.pem')
  execFileSync('openssl', ['ecparam', '-genkey', '-name', 'prime256v1', '-noout', '-out', key])
  execFileSync('openssl', ['req', '-new', '-x509', '-key', key, '-out', cert, '-days', '7', '-subj', `/CN=${name}`])
  const pem = fs.readFileSync(cert)
  const fp = b64url(crypto.createHash('sha256').update(new crypto.X509Certificate(pem).raw).digest())
  return { key: fs.readFileSync(key), cert: pem, fp }
}

const nodes = [
  { id: 'node_desk', name: 'Desk', priority: 0, port: PORTS[0] },
  { id: 'node_server', name: 'Server', priority: 1, port: PORTS[1] },
].map((n) => ({ ...n, tls: makeCert(n.name), url: `https://${HOST}:${n.port}`, up: true, server: null, sockets: new Set() }))
const brains = () => nodes.map((n) => ({ id: n.id, name: n.name, urls: [n.url], fp: n.tls.fp, priority: n.priority }))

// ---- shared state ----
const passes = new Map() // sha256(pass) -> deviceId
const devices = new Map()
let offer = null // { c, short, until, tries }
const now = Date.now()
const rows = {
  chat_item: [
    { id: rid('ci'), createdAt: now - 60_000, kind: 'user', text: 'What is on today?', meta: '{}' },
    { id: rid('ci'), createdAt: now - 59_000, kind: 'agent', text: 'Two things: the quote for the kitchen and a call at 15:00.', meta: '{}' },
    { id: rid('ci'), createdAt: now - 58_000, kind: 'agent', text: 'This row is in a project chat and must not show on the phone.', meta: '{"chat":"pc_other"}' },
  ],
  memory: [
    { id: rid('m'), createdAt: now - 86_400_000, updatedAt: now - 3_600_000, title: 'Morning routine', body: 'Gym at 06:00, then email.', kind: 'preference', topics: 'routine', pinned: true, source: 'agent' },
    { id: rid('m'), createdAt: now - 86_400_000, updatedAt: now - 7_200_000, title: 'Kitchen quote', body: 'Due Friday.', kind: 'fact', topics: 'work', pinned: false, source: 'agent' },
    { id: rid('m'), createdAt: now, updatedAt: now, title: 'A project page', body: 'Belongs to a project.', kind: 'fact', topics: '', pinned: false, source: 'agent', project: 'p1' },
  ],
  goal: [{ id: 'goal_1', createdAt: now - 86_400_000, title: 'Ship the kitchen quote', why: 'Client waits', status: 'open' }],
  task: [
    { id: rid('t'), createdAt: now - 80_000_000, updatedAt: now, goalId: 'goal_1', title: 'Measure the kitchen', notes: '', status: 'done', dueAt: null, owner: 'user', blocker: '' },
    { id: rid('t'), createdAt: now - 70_000_000, updatedAt: now, goalId: 'goal_1', title: 'Send the quote', notes: '', status: 'todo', dueAt: now + 86_400_000, owner: 'user', blocker: '' },
  ],
  project: [{ id: 'proj_kitchen', createdAt: now - 86_400_000, name: 'Kitchen site', description: 'The client site', aliases: [], icon: { glyph: String.fromCodePoint(0x1f3e0), color: 'ember' }, path: '', profile: '', hidden: false, chats: ['pc_kitchen'] }],
  trigger: [{ id: rid('tr'), createdAt: now - 90_000_000, name: 'Morning brief', kind: 'daily', spec: '07:30', prompt: 'Brief me', enabled: true, lastRunAt: null, lastResult: '' }],
}
let running = false

function newOffer() {
  const short = Array.from({ length: 8 }, () => ALPHABET[crypto.randomInt(ALPHABET.length)]).join('')
  offer = { c: b64url(crypto.randomBytes(16)), short, until: Date.now() + 600_000, tries: 0 }
  const n = nodes[0]
  const uri = `agent://pair?v=1&mesh=${MESH}&node=${n.id}&name=${encodeURIComponent(n.name)}&u=${encodeURIComponent(nodes.filter((x) => x.up)[0]?.url ?? n.url)}&fp=${n.tls.fp}&c=${offer.c}`
  log(`OFFER ${uri}`)
  log(`SHORT CODE ${short.slice(0, 4)}-${short.slice(4)} at ${n.url}`)
  return { uri, short }
}

const json = (res, status, body) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(body)) }
const readBody = (req) => new Promise((ok) => { let s = ''; req.on('data', (d) => (s += d)); req.on('end', () => ok(s)) })
function passOf(req) {
  const h = req.headers['authorization'] ?? ''
  const cookie = (req.headers['cookie'] ?? '').split(';').map((x) => x.trim()).find((x) => x.startsWith('agent_pass='))
  const pass = h.startsWith('Bearer ') ? h.slice(7) : cookie ? cookie.slice('agent_pass='.length) : ''
  return passes.get(crypto.createHash('sha256').update(pass).digest('hex')) ?? null
}

function broadcast(name, payload) { for (const n of nodes) for (const s of n.sockets) s.send(JSON.stringify({ type: 'event', name, payload })) }
const changed = (entity, row) => broadcast('agent:evt:data.changed', { entity, row })
function addRow(entity, row) { rows[entity].push(row); changed(entity, row); return row }

function command(deviceId, cmd) {
  for (const n of nodes) for (const s of n.sockets) if (s.deviceId === deviceId) s.send(JSON.stringify({ type: 'event', name: 'device.command', payload: cmd }))
  log(`-> device.command ${cmd.kind}${cmd.tool ? ' ' + cmd.tool : ''} to ${deviceId}`)
}

async function rpc(node, sock, method, args) {
  switch (method) {
    case 'data.list': return rows[args[0]] ?? []
    case 'data.get': return (rows[args[0]] ?? []).find((r) => r.id === args[1]) ?? null
    case 'data.upsert': {
      const [entity, input] = args
      const list = rows[entity]
      const i = input.id ? list.findIndex((r) => r.id === input.id) : -1
      const row = i >= 0 ? (list[i] = { ...list[i], ...input }) : { createdAt: Date.now(), ...input, id: rid(entity) }
      if (i < 0) list.push(row)
      changed(entity, row)
      log(`data.upsert ${entity} "${row.title ?? row.name ?? ''}" status=${row.status ?? ''} enabled=${row.enabled ?? ''}`)
      return row
    }
    case 'data.remove': { const [entity, id] = args; rows[entity] = rows[entity].filter((r) => r.id !== id); broadcast('agent:evt:data.changed', { entity, id, deleted: true }); log(`data.remove ${entity} ${id}`); return null }
    case 'chat.state': return { running, waiting: [] }
    case 'chat.states': return [{ chat: 'main', running, waiting: [], alongside: 0 }]
    case 'chat.stop': running = false; broadcast('agent:evt:chat.event', { type: 'run_finished' }); return null
    case 'chat.answer': log(`chat.answer ${args[0]} -> ${args[1]}`); return null
    case 'chat.decide': log(`chat.decide ${args[0]} -> ${args[1]}`); return null
    case 'routines.runNow': log(`routines.runNow ${args[0]}`); return null
    case 'files.savePasted': { const [name, mime, bytes] = args; log(`files.savePasted ${name} ${mime} ${Buffer.from(bytes.$b64, 'base64').length} bytes`); return { path: `/tmp/${name}`, name, mime } }
    case 'voice.noteAudio': { const f = path.join(NOTES, String(args[0] ?? '')); const ok = /^[\w-]+\.wav$/.test(String(args[0])) && fs.existsSync(f); log(`voice.noteAudio ${args[0]} -> ${ok ? fs.statSync(f).size + ' bytes' : 'null'}`); return ok ? { $b64: fs.readFileSync(f).toString('base64') } : null }
    case 'device.ack': log(`device.ack ${args[0]} ok=${args[1]} note=${args[2]}`); return null
    case 'device.result': log(`device.result ${args[0]} ok=${args[1]} value=${String(args[2]).slice(0, 160).replace(/\n/g, ' ')}`); return null
    case 'chat.send': {
      const [text, attachments] = args
      addRow('chat_item', { id: rid('ci'), createdAt: Date.now(), kind: 'user', text, meta: JSON.stringify({ origin: { deviceId: sock.deviceId, name: sock.facts?.name, kind: sock.facts?.kind } }) })
      log(`chat.send "${text}" from ${sock.facts?.name} (${sock.facts?.kind} ${sock.facts?.screen?.width}x${sock.facts?.screen?.height}dp) attachments=${attachments?.length ?? 0}`)
      running = true
      broadcast('agent:evt:chat.event', { type: 'run_started' })
      setTimeout(() => {
        const t = String(text).toLowerCase()
        if (t.includes('file')) command(sock.deviceId, { id: rid('cmd'), kind: 'send_file', path: '/tmp/fake-node-hello.txt', name: 'hello-from-desk.txt' })
        if (t.includes('link')) command(sock.deviceId, { id: rid('cmd'), kind: 'open_url', url: 'https://example.com/' })
        if (t.includes('notify')) command(sock.deviceId, { id: rid('cmd'), kind: 'notify', title: 'From your computer', body: 'A notification sent by the agent on ' + node.name })
        if (t.includes('picture')) addRow('chat_item', { id: rid('ci'), createdAt: Date.now(), kind: 'activity', text: 'Took a screenshot', meta: JSON.stringify({ tool: 'browser', image: '/tmp/fake-node-pic.png' }) })
        if (t.includes('project')) projectRun()
        // A voice note the way the desktop's voice_note tool writes it: meta.file is a note name on the computer.
        if (t.includes('voice')) { const file = fs.readdirSync(NOTES).find((f) => f.endsWith('.wav')) ?? 'none.wav'; addRow('chat_item', { id: rid('ci'), createdAt: Date.now(), kind: 'voice_note', text: 'This is the spoken script of the voice note.', meta: JSON.stringify({ file, durationMs: 20000, style: 'warm' }) }) }
        if (t.includes('gone')) addRow('chat_item', { id: rid('ci'), createdAt: Date.now(), kind: 'voice_note', text: 'A note whose audio the computer no longer has.', meta: JSON.stringify({ file: '1700000000000-deadbeef.wav', durationMs: 5000, style: 'warm' }) })
        if (t.includes('browse')) command(sock.deviceId, { id: rid('cmd'), kind: 'tool', tool: 'browser_open', args: { url: 'https://example.com/' }, chatId: 'main', deadlineMs: 90000 })
        if (t.includes('ask')) command(sock.deviceId, { id: rid('cmd'), kind: 'tool', tool: 'ask_user', args: { question: 'Shall I book the 15:00 slot?', options: ['Yes', 'No'] }, chatId: 'main', deadlineMs: 120000 })
        if (t.includes('tool')) command(sock.deviceId, { id: rid('cmd'), kind: 'tool', tool: 'notifications_read', args: {}, chatId: 'main', deadlineMs: 30000 })
        addRow('chat_item', { id: rid('ci'), createdAt: Date.now(), kind: 'agent', text: `This is ${node.name}. You said: ${text}`, meta: '{}' })
        running = false
        broadcast('agent:evt:chat.event', { type: 'run_finished' })
      }, 1500)
      return null
    }
    default: throw new Error('not available on this device')
  }
}

// Work handed to a project: it runs in the project's own chat, step by step.
function projectRun() {
  const chat = 'pc_kitchen'
  const tag = JSON.stringify({ chat })
  broadcast('agent:evt:chat.event', { type: 'run_started', chat })
  addRow('chat_item', { id: rid('ci'), createdAt: Date.now(), kind: 'user', text: 'Fix the gallery on the kitchen site', meta: tag })
  const steps = ['Opened the repo', 'Found the broken image path', 'Pushed the fix to dev']
  steps.forEach((s, i) => setTimeout(() => addRow('chat_item', { id: rid('ci'), createdAt: Date.now(), kind: 'activity', text: s, meta: tag }), 2000 * (i + 1)))
  setTimeout(() => { addRow('chat_item', { id: rid('ci'), createdAt: Date.now(), kind: 'agent', text: 'Gallery fixed and on the dev site.', meta: tag }); broadcast('agent:evt:chat.event', { type: 'run_finished', chat }); log('project run finished') }, 9000)
  log('project run started in pc_kitchen')
}

function serve(node) {
  const server = https.createServer({ key: node.tls.key, cert: node.tls.cert }, async (req, res) => {
    const url = new URL(req.url, node.url)
    if (url.pathname === '/v1/health') return json(res, 200, { id: node.id, name: node.name, version: 'fake-0.1', roles: ['brain'], ready: true, busy: running, meshId: MESH })
    if (url.pathname === '/v1/pair' && req.method === 'POST') {
      const body = JSON.parse((await readBody(req)) || '{}')
      const code = String(body.code ?? '')
      const okCode = offer && Date.now() < offer.until && (code === offer.c || code.toUpperCase().replace(/[^A-Z0-9]/g, '') === offer.short)
      if (!okCode) { if (offer) offer.tries++; log(`pair REFUSED code=${code}`); return json(res, 401, { error: 'bad or expired code' }) }
      offer = null
      const deviceId = rid('dev'), pass = b64url(crypto.randomBytes(32))
      passes.set(crypto.createHash('sha256').update(pass).digest('hex'), deviceId)
      devices.set(deviceId, { id: deviceId, ...body.device, roles: body.wantRoles ?? ['screen'] })
      log(`PAIRED ${body.device?.name} (${body.device?.kind}, ${body.device?.platform} ${body.device?.appVersion}, ${body.device?.screen?.width}x${body.device?.screen?.height}dp) as ${deviceId} via ${node.name}`)
      return json(res, 200, { deviceId, pass, meshId: MESH, node: { id: node.id, name: node.name }, brains: brains() })
    }
    const dev = passOf(req)
    if (!dev) return json(res, 401, { error: 'This device is not paired with this computer.' })
    if (url.pathname === '/v1/devices') return json(res, 200, { devices: [...brains().map((b) => ({ ...b, roles: ['brain'], revoked: false })), ...devices.values()], answering: node.id })
    if (url.pathname === '/v1/file') { const p = url.searchParams.get('path'); log(`/v1/file ${p} by ${dev}`); if (p !== '/tmp/fake-node-hello.txt' && p !== '/tmp/fake-node-pic.png') return json(res, 403, { error: 'not a file from a chat' }); res.writeHead(200, { 'content-type': p.endsWith('.png') ? 'image/png' : 'text/plain' }); return res.end(fs.readFileSync(p)) }
    if (url.pathname === '/app/' || url.pathname === '/app') { res.writeHead(200, { 'content-type': 'text/html' }); return res.end(`<h1>${node.name}</h1>`) }
    json(res, 404, { error: 'not found' })
  })
  const wss = new WebSocketServer({ noServer: true })
  server.on('upgrade', (req, socket, head) => {
    const dev = new URL(req.url, node.url).pathname === '/v1/rpc' ? passOf(req) : null
    if (!dev) { socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n'); return socket.destroy() }
    wss.handleUpgrade(req, socket, head, (ws) => {
      ws.deviceId = dev
      node.sockets.add(ws)
      ws.on('close', () => node.sockets.delete(ws))
      ws.on('message', async (data) => {
        const f = JSON.parse(String(data))
        if (f.type === 'hello') {
          const first = !ws.facts
          ws.facts = f.device
          log(`hello on ${node.name} from ${f.device?.name} ${f.device?.kind} ${f.device?.screen?.width}x${f.device?.screen?.height}dp, lends ${f.tools?.length ?? 0} tools: ${(f.tools ?? []).map((t) => t.name).slice(0, 30).join(' ')}`)
          if (first) setTimeout(() => command(dev, { id: rid('cmd'), kind: 'notify', title: `Linked to ${node.name}`, body: 'This notification came from the computer.' }), 2000)
          return
        }
        if (f.type !== 'call') return
        if (!ws.facts) return ws.send(JSON.stringify({ type: 'result', id: f.id, ok: false, error: 'Say hello first.' }))
        try { ws.send(JSON.stringify({ type: 'result', id: f.id, ok: true, value: (await rpc(node, ws, f.method, f.args ?? [])) ?? null })) }
        catch (e) { ws.send(JSON.stringify({ type: 'result', id: f.id, ok: false, error: e.message })) }
      })
    })
  })
  server.listen(node.port, HOST, () => log(`${node.name} up at ${node.url} fp=${node.tls.fp}`))
  node.server = server
}

function down(node) {
  if (!node.up) return
  node.up = false
  for (const s of node.sockets) s.terminate()
  node.server.close(); node.server.closeAllConnections?.()
  log(`${node.name} DOWN`)
}
function up(node) { if (node.up) return; node.up = true; serve(node); log(`${node.name} UP`) }

// A 64x40 picture for the chat, drawn as a PNG here so the source holds no binary blob.
{
  const zlib = await import('node:zlib')
  const w = 64, h = 40, raw = Buffer.alloc((w * 3 + 1) * h)
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const o = y * (w * 3 + 1) + 1 + x * 3; raw[o] = 230; raw[o + 1] = 90 + y * 3; raw[o + 2] = 40 + x * 2 }
  const crc = (b) => { let c = ~0; for (const v of b) { c ^= v; for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1)) } return ~c >>> 0 }
  const chunk = (type, data) => { const t = Buffer.from(type); const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const c = Buffer.alloc(4); c.writeUInt32BE(crc(Buffer.concat([t, data]))); return Buffer.concat([len, t, data, c]) }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2
  fs.writeFileSync('/tmp/fake-node-pic.png', Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]))
}
fs.writeFileSync('/tmp/fake-node-hello.txt', 'Hello from the fake node. This file was sent to the phone with send_file.\n')
nodes.forEach(serve)
http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x')
  const n = nodes.find((x) => x.id.endsWith(url.searchParams.get('node') ?? '-'))
  if (url.pathname === '/down' && n) { down(n); return json(res, 200, { ok: true }) }
  if (url.pathname === '/up' && n) { up(n); return json(res, 200, { ok: true }) }
  if (url.pathname === '/offer') return json(res, 200, newOffer())
  if (url.pathname === '/state') return json(res, 200, { nodes: nodes.map((x) => ({ name: x.name, up: x.up, sockets: x.sockets.size })), devices: [...devices.values()].map((d) => d.name), rows: Object.fromEntries(Object.entries(rows).map(([k, v]) => [k, v.length])) })
  json(res, 404, {})
}).listen(CONTROL, '127.0.0.1', () => log(`control on 127.0.0.1:${CONTROL}`))
newOffer()
