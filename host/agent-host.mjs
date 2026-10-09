#!/usr/bin/env node
// Agent Host: a small background service that runs the Agent app's helpers on a PC or server, so they keep
// working when the phone app is closed or dies. Zero dependencies (Node 18+).
//
//   node agent-host.mjs serve [--port 8787] [--bind 127.0.0.1|tailscale|0.0.0.0] [--trusted]
//   node agent-host.mjs pair       print the address and token to pair the phone
//   node agent-host.mjs engines    list the agents found on this machine
//   node agent-host.mjs install    run as a service that starts at login (systemd user unit / launchd agent)
//
// Everything is a headless child process (Claude Code, Codex, OpenCode, Pi). The Host keeps each job's events in a
// log on disk, so a phone that reconnects asks "everything after #N" and catches up. A job that was running when the
// Host itself restarted is picked up again from its session (twice at most).
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import crypto from 'node:crypto'
import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

export const VERSION = '0.1.0'
export const MAX_RESUMES = 2
const clip = (s, n) => (s.length > n ? s.slice(0, n) + '…' : s)
const j = (x) => JSON.stringify(x)

// ---- engines ---------------------------------------------------------------------------------------------------
// Each engine: how to start a turn (build) and how to read its output lines into the phone's event vocabulary
// (parse): {type:'session',id} {type:'text',text} {type:'tool',name,detail} {type:'done',ok} {type:'error',message}.
const blocksOf = (m) => (Array.isArray(m?.content) ? m.content : typeof m?.content === 'string' ? [{ type: 'text', text: m.content }] : [])

export const ENGINES = {
  claude: {
    name: 'Claude Code', bin: 'claude', tested: true,
    build: (job, host) => ['-p', job.prompt, '--output-format', 'stream-json', '--verbose',
      ...(job.model ? ['--model', job.model] : []), ...(job.system ? ['--append-system-prompt', job.system] : []),
      ...(job.session ? ['--resume', job.session] : []),
      ...(host.trusted ? ['--dangerously-skip-permissions'] : ['--permission-mode', 'acceptEdits'])],
    parse(o, st) {
      const out = []
      if (o.session_id && !st.sessionSeen) { st.sessionSeen = true; out.push({ type: 'session', id: o.session_id }) }
      if (o.type === 'assistant') for (const b of blocksOf(o.message)) {
        if (b.type === 'text' && b.text?.trim()) { st.last = b.text; out.push({ type: 'text', text: b.text }) }
        else if (b.type === 'tool_use') out.push({ type: 'tool', name: b.name, detail: clip(j(b.input ?? {}), 200) })
      }
      if (o.type === 'result') {
        if (o.is_error) out.push({ type: 'error', message: clip(String(o.result ?? o.subtype ?? 'failed'), 400) })
        else { if (o.result) st.last = String(o.result); out.push({ type: 'done', ok: true, result: st.last ?? '' }) }
      }
      return out
    },
  },
  pi: {
    name: 'Pi', bin: 'pi', tested: true,
    build: (job, host) => ['-p', '--mode', 'json', '--no-extensions', '--no-skills', '--session-dir', path.join(host.jobDir(job.id), 'pi'),
      ...(job.model ? ['--model', job.model] : []), ...(job.system ? ['--append-system-prompt', job.system] : []),
      ...(job.session ? ['--session', job.session] : []), job.prompt],
    parse(o, st) {
      const out = []
      if (o.type === 'session' && o.id) out.push({ type: 'session', id: o.id })
      if (o.type === 'message_end' && o.message?.role === 'assistant') {
        for (const b of blocksOf(o.message)) {
          if (b.type === 'text' && b.text?.trim()) { st.last = b.text; out.push({ type: 'text', text: b.text }) }
          else if (b.type === 'toolCall' || b.type === 'tool_use') out.push({ type: 'tool', name: b.name, detail: clip(j(b.arguments ?? b.input ?? {}), 200) })
        }
        if (o.message.stopReason === 'error' || o.message.errorMessage) st.error = String(o.message.errorMessage ?? 'The model returned an error')
      }
      if (o.type === 'tool_execution_start') out.push({ type: 'tool', name: o.toolName ?? 'tool', detail: clip(j(o.args ?? {}), 200) })
      if (o.type === 'agent_end') out.push(st.error ? { type: 'error', message: clip(st.error, 400) } : { type: 'done', ok: true, result: st.last ?? '' })
      return out
    },
  },
  codex: {
    name: 'Codex', bin: 'codex', tested: true,
    build: (job, host) => job.session ? ['exec', 'resume', job.session, '--json', '--skip-git-repo-check', job.prompt]
      : ['exec', '--json', '--skip-git-repo-check', '-C', job.cwd, ...(job.model ? ['-m', job.model] : []), ...(host.trusted ? ['--dangerously-bypass-approvals-and-sandbox'] : ['-s', 'workspace-write']), job.prompt],
    parse(o, st) {
      const out = []
      if (o.type === 'thread.started' && o.thread_id) out.push({ type: 'session', id: o.thread_id })
      const it = o.item
      if (o.type === 'item.completed' && it) {
        if (it.type === 'agent_message' && it.text) { st.last = it.text; out.push({ type: 'text', text: it.text }) }
        else if (it.type === 'command_execution') out.push({ type: 'tool', name: 'shell', detail: clip(String(it.command ?? ''), 200) })
      }
      if (o.type === 'turn.completed') out.push({ type: 'done', ok: true, result: st.last ?? '' })
      if (o.type === 'turn.failed' || o.type === 'error') out.push({ type: 'error', message: clip(String(o.message ?? o.error?.message ?? 'failed'), 400) })
      return out
    },
  },
  opencode: {
    name: 'OpenCode', bin: 'opencode', tested: false,
    build: (job) => ['run', '--format', 'json', ...(job.model ? ['-m', job.model] : []), ...(job.session ? ['-s', job.session] : []), job.prompt],
    parse(o, st) {
      const out = []
      const sid = o.sessionID ?? o.sessionId ?? o.session_id
      if (sid && !st.sessionSeen) { st.sessionSeen = true; out.push({ type: 'session', id: sid }) }
      const text = o.part?.text ?? (o.type === 'text' ? o.text : null)
      if (text?.trim()) { st.last = text; out.push({ type: 'text', text }) }
      if (o.type === 'tool' || o.part?.type === 'tool') out.push({ type: 'tool', name: String(o.part?.tool ?? o.tool ?? 'tool'), detail: '' })
      return out
    },
  },
}

const binFor = (id) => process.env[`AGENT_HOST_BIN_${id.toUpperCase()}`] || ENGINES[id].bin

/** The agents installed on this machine. */
export function scanEngines() {
  return Object.entries(ENGINES).map(([id, e]) => {
    const r = spawnSync(binFor(id), ['--version'], { encoding: 'utf8', timeout: 8000, shell: process.platform === 'win32' })
    const found = !r.error && r.status === 0
    return { id, name: e.name, found, version: found ? (r.stdout || r.stderr).trim().split('\n')[0] : null, tested: e.tested }
  })
}

// ---- the host --------------------------------------------------------------------------------------------------
export class Host {
  constructor({ home = process.env.AGENT_HOST_HOME || path.join(os.homedir(), '.agent-host'), trusted = false } = {}) {
    this.home = home; this.trusted = trusted
    fs.mkdirSync(path.join(home, 'jobs'), { recursive: true, mode: 0o700 })
    this.token = this.#loadToken()
    this.jobs = new Map()      // id -> { meta, events[], proc, subs:Set, stderr }
    this.#load()
  }
  jobDir(id) { return path.join(this.home, 'jobs', id) }
  #loadToken() {
    const f = path.join(this.home, 'token')
    try { return fs.readFileSync(f, 'utf8').trim() } catch {}
    const t = crypto.randomBytes(24).toString('base64url'); fs.writeFileSync(f, t + '\n', { mode: 0o600 }); return t
  }
  #load() {
    for (const id of fs.readdirSync(path.join(this.home, 'jobs'))) {
      try {
        const meta = JSON.parse(fs.readFileSync(path.join(this.jobDir(id), 'job.json'), 'utf8'))
        const events = fs.existsSync(path.join(this.jobDir(id), 'events.jsonl'))
          ? fs.readFileSync(path.join(this.jobDir(id), 'events.jsonl'), 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)) : []
        this.jobs.set(id, { meta, events, proc: null, subs: new Set(), stderr: '' })
      } catch { /* a half-written job is skipped */ }
    }
  }
  #save(job) { fs.writeFileSync(path.join(this.jobDir(job.meta.id), 'job.json'), j(job.meta), { mode: 0o600 }) }
  #emit(job, ev) {
    const e = { seq: job.events.length + 1, t: Date.now(), ...ev }
    job.events.push(e)
    fs.appendFileSync(path.join(this.jobDir(job.meta.id), 'events.jsonl'), j(e) + '\n')
    for (const send of job.subs) send(e)
    return e
  }
  /** Pick the agents up again that were running when this Host stopped. */
  recover() {
    for (const job of this.jobs.values()) {
      if (job.meta.state !== 'running') continue
      if ((job.meta.resumes ?? 0) >= MAX_RESUMES) { this.#finish(job, 'failed', `The host restarted and it didn't get further after ${job.meta.resumes} tries.`); continue }
      job.meta.resumes = (job.meta.resumes ?? 0) + 1
      this.#emit(job, { type: 'note', text: 'Picked up again after the host restarted' })
      const note = "[The machine was restarted while you were working, so you were cut off. Carry on from where you got to; don't redo what is already done.]"
      this.#run(job, job.meta.session ? note : job.meta.task + '\n\n' + note)
    }
  }
  create({ task, system = '', engine, model = '', cwd, env = {}, id }) {
    if (!task?.trim()) throw httpError(400, 'task is required')
    engine ||= scanEngines().find((e) => e.found)?.id
    if (!ENGINES[engine]) throw httpError(400, `unknown engine "${engine}"`)
    id = (id && /^[\w-]{1,64}$/.test(id)) ? id : crypto.randomBytes(6).toString('hex')
    if (this.jobs.has(id)) throw httpError(409, 'a job with that id exists')
    fs.mkdirSync(this.jobDir(id), { recursive: true, mode: 0o700 })
    const meta = { id, task, system, engine, model, cwd: cwd || path.join(this.jobDir(id), 'work'), env, state: 'running', session: null, resumes: 0, createdAt: Date.now(), result: '' }
    fs.mkdirSync(meta.cwd, { recursive: true })
    const job = { meta, events: [], proc: null, subs: new Set(), stderr: '' }
    this.jobs.set(id, job); this.#save(job)
    this.#emit(job, { type: 'state', state: 'running', engine })
    this.#run(job, task)
    return id
  }
  #run(job, prompt) {
    const { meta } = job; const eng = ENGINES[meta.engine]
    meta.state = 'running'; delete meta.endedAt; this.#save(job)
    const args = eng.build({ ...meta, prompt }, this)
    const st = {}; job.stderr = ''
    let proc
    try { proc = spawn(binFor(meta.engine), args, { cwd: meta.cwd, env: { ...process.env, ...meta.env }, stdio: ['ignore', 'pipe', 'pipe'], shell: process.platform === 'win32' }) }
    catch (e) { return this.#finish(job, 'failed', `Couldn't start ${eng.name}: ${e.message}`) }
    job.proc = proc; let ended = false, buf = ''
    const line = (l) => {
      let o; try { o = JSON.parse(l) } catch { return }
      for (const ev of eng.parse(o, st)) {
        if (ev.type === 'session') { meta.session = ev.id; this.#save(job) }
        if (ev.type === 'done') { ended = true; this.#finish(job, 'done', ev.result ?? '') }
        else if (ev.type === 'error') { ended = true; this.#finish(job, 'failed', ev.message) }
        else this.#emit(job, ev)
      }
    }
    proc.stdout.on('data', (d) => { buf += d; let i; while ((i = buf.indexOf('\n')) >= 0) { line(buf.slice(0, i)); buf = buf.slice(i + 1) } })
    proc.stderr.on('data', (d) => { job.stderr = (job.stderr + d).slice(-4000) })
    proc.on('error', (e) => { if (!ended) { ended = true; this.#finish(job, 'failed', `Couldn't start ${eng.name}: ${e.message}`) } })
    proc.on('close', (code) => {
      if (buf.trim()) line(buf)
      if (job.proc === proc) job.proc = null
      if (!ended && job.meta.state === 'running' && !job.restarting) this.#finish(job, st.last && code === 0 ? 'done' : 'failed', st.last && code === 0 ? st.last : (job.stderr.trim().split('\n').slice(-3).join(' ') || `exit ${code}`))
    })
  }
  #finish(job, state, result) {
    if (job.meta.state !== 'running') return
    job.meta.state = state; job.meta.result = clip(result ?? '', 6000); job.meta.endedAt = Date.now(); this.#save(job)
    this.#emit(job, { type: 'state', state, result: job.meta.result })
    if (job.proc) { try { job.proc.kill() } catch {} }
  }
  stop(id) {
    const job = this.#get(id); if (job.meta.state !== 'running') return false
    job.stopping = true; const p = job.proc; this.#finish(job, 'stopped', 'Stopped before it finished.'); if (p) try { p.kill() } catch {}
    return true
  }
  /** Send a running or finished job more to do. A running one is restarted on its session with the message. */
  async message(id, text) {
    const job = this.#get(id); if (!text?.trim()) throw httpError(400, 'text is required')
    if (job.meta.state === 'running' && job.proc) {
      job.restarting = true; const p = job.proc
      await new Promise((res) => { p.once('close', res); try { p.kill() } catch { res() } }); job.restarting = false
    }
    job.meta.pushes = (job.meta.pushes ?? 0) + 1
    this.#emit(job, { type: 'note', text: 'Told it: ' + clip(text, 140) })
    this.#emit(job, { type: 'state', state: 'running' })
    this.#run(job, job.meta.session ? text : job.meta.task + '\n\n[The user added:] ' + text)
  }
  #get(id) { const job = this.jobs.get(id); if (!job) throw httpError(404, 'no such job'); return job }
  view(job) { const m = job.meta; return { id: m.id, task: clip(m.task, 300), engine: m.engine, model: m.model, state: m.state, result: m.result, session: m.session, resumes: m.resumes, createdAt: m.createdAt, endedAt: m.endedAt ?? null, lastSeq: job.events.length } }
  list() { return [...this.jobs.values()].map((x) => this.view(x)) }
  get(id) { return this.view(this.#get(id)) }
  eventsAfter(id, after = 0) { return this.#get(id).events.filter((e) => e.seq > after) }
  subscribe(id, send) { const job = this.#get(id); job.subs.add(send); return () => job.subs.delete(send) }
}

const httpError = (status, message) => Object.assign(new Error(message), { status })

// ---- http api --------------------------------------------------------------------------------------------------
export function createServer(host) {
  const authed = (req) => {
    const m = /^Bearer (.+)$/.exec(req.headers.authorization || ''); if (!m) return false
    const a = Buffer.from(m[1]), b = Buffer.from(host.token)
    return a.length === b.length && crypto.timingSafeEqual(a, b)
  }
  const body = (req) => new Promise((res, rej) => { let s = ''; req.on('data', (d) => { s += d; if (s.length > 1e6) rej(httpError(413, 'too large')) }); req.on('end', () => { try { res(s ? JSON.parse(s) : {}) } catch { rej(httpError(400, 'bad json')) } }) })
  const send = (res, status, obj) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(j(obj)) }
  return http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://x'); const p = url.pathname.replace(/\/+$/, '')
      if (!authed(req)) return send(res, 401, { error: 'unauthorised' })
      if (req.method === 'GET' && p === '/v1/health') return send(res, 200, { ok: true, version: VERSION, host: os.hostname(), platform: process.platform, jobs: host.jobs.size, time: Date.now() })
      if (req.method === 'GET' && p === '/v1/engines') return send(res, 200, { engines: scanEngines() })
      if (req.method === 'GET' && p === '/v1/jobs') return send(res, 200, { jobs: host.list() })
      if (req.method === 'POST' && p === '/v1/jobs') return send(res, 201, { id: host.create(await body(req)) })
      const m = /^\/v1\/jobs\/([\w-]+)(?:\/(events|message|stop))?$/.exec(p)
      if (m) {
        const [, id, sub] = m
        if (!sub && req.method === 'GET') return send(res, 200, host.get(id))
        if (sub === 'stop' && req.method === 'POST') return send(res, 200, { stopped: host.stop(id) })
        if (sub === 'message' && req.method === 'POST') { await host.message(id, (await body(req)).text); return send(res, 200, { ok: true }) }
        if (sub === 'events' && req.method === 'GET') {
          const after = Number(url.searchParams.get('after') || 0)
          if (url.searchParams.get('follow') !== '1') return send(res, 200, { events: host.eventsAfter(id, after), job: host.get(id) })
          host.get(id)
          res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' })
          let last = after
          const push = (e) => { if (e.seq > last) { last = e.seq; res.write(`id: ${e.seq}\ndata: ${j(e)}\n\n`) } }
          const off = host.subscribe(id, push)            // subscribe first, then replay, so nothing falls in the gap
          host.eventsAfter(id, after).forEach(push)
          const ping = setInterval(() => res.write(': ping\n\n'), 15000)
          req.on('close', () => { off(); clearInterval(ping) })
          return
        }
      }
      send(res, 404, { error: 'not found' })
    } catch (e) { send(res, e.status || 500, { error: e.message }) }
  })
}

// ---- cli -------------------------------------------------------------------------------------------------------
async function bindAddress(bind) {
  if (bind !== 'tailscale') return bind
  const r = spawnSync('tailscale', ['ip', '-4'], { encoding: 'utf8' })
  const ip = r.stdout?.trim().split('\n')[0]; if (!ip) throw new Error('Tailscale is not running here (tailscale ip -4 gave nothing)')
  return ip
}

function installService(argv) {
  const self = fileURLToPath(import.meta.url), node = process.execPath, home = os.homedir()
  const args = argv.filter((a) => a !== 'install').join(' ')
  if (process.platform === 'linux') {
    const dir = path.join(home, '.config/systemd/user'); fs.mkdirSync(dir, { recursive: true })
    fs.writeFileSync(path.join(dir, 'agent-host.service'), `[Unit]\nDescription=Agent Host\nAfter=network-online.target\n\n[Service]\nExecStart=${node} ${self} serve ${args}\nRestart=always\nRestartSec=3\n\n[Install]\nWantedBy=default.target\n`)
    spawnSync('systemctl', ['--user', 'daemon-reload']); const r = spawnSync('systemctl', ['--user', 'enable', '--now', 'agent-host'], { encoding: 'utf8' })
    spawnSync('loginctl', ['enable-linger', os.userInfo().username])
    console.log(r.status === 0 ? 'Installed and running (systemd user service "agent-host").' : `Wrote the unit, but systemd said: ${r.stderr}`)
  } else if (process.platform === 'darwin') {
    const f = path.join(home, 'Library/LaunchAgents/com.agent.host.plist')
    fs.writeFileSync(f, `<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>Label</key><string>com.agent.host</string><key>ProgramArguments</key><array><string>${node}</string><string>${self}</string><string>serve</string>${args.split(' ').filter(Boolean).map((a) => `<string>${a}</string>`).join('')}</array><key>RunAtLoad</key><true/><key>KeepAlive</key><true/></dict></plist>`)
    spawnSync('launchctl', ['load', '-w', f]); console.log('Installed (launchd agent com.agent.host).')
  } else console.log(`Windows: run once in a terminal to start at sign-in:\n  schtasks /Create /SC ONLOGON /TN AgentHost /TR "\\"${node}\\" \\"${self}\\" serve ${args}"`)
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const [cmd = 'serve', ...rest] = process.argv.slice(2)
  const flag = (n, d) => { const i = rest.indexOf(`--${n}`); return i >= 0 ? rest[i + 1] : d }
  const host = new Host({ trusted: rest.includes('--trusted') })
  const port = Number(flag('port', 8787))
  if (cmd === 'serve') {
    const addr = await bindAddress(flag('bind', '127.0.0.1')); host.recover()
    createServer(host).listen(port, addr, () => console.log(`Agent Host ${VERSION} listening on http://${addr}:${port}`))
  } else if (cmd === 'pair') {
    const addr = await bindAddress(flag('bind', '127.0.0.1')); console.log(j({ url: `http://${addr}:${port}`, token: host.token }))
  } else if (cmd === 'engines') console.table(scanEngines())
  else if (cmd === 'install') installService(process.argv.slice(2))
  else { console.error('usage: agent-host serve|pair|engines|install'); process.exit(1) }
}
