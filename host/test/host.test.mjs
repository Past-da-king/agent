import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { Host, createServer, MAX_RESUMES } from '../agent-host.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
process.env.AGENT_HOST_BIN_CLAUDE = path.join(here, 'fake-claude.mjs')
fs.chmodSync(process.env.AGENT_HOST_BIN_CLAUDE, 0o755)

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'ah-'))
const until = async (f, ms = 8000) => { const t = Date.now(); for (;;) { const v = await f(); if (v) return v; if (Date.now() - t > ms) throw new Error('timed out'); await new Promise((r) => setTimeout(r, 25)) } }
async function boot(home = tmp()) {
  const host = new Host({ home }); const srv = createServer(host)
  await new Promise((r) => srv.listen(0, '127.0.0.1', r))
  const base = `http://127.0.0.1:${srv.address().port}`
  const call = (method, p, body, token = host.token) => fetch(base + p, { method, headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
  return { host, srv, base, call, home, close: () => new Promise((r) => srv.close(r)) }
}

test('refuses a caller without the token', async () => {
  const h = await boot()
  assert.equal((await h.call('GET', '/v1/health', null, 'wrong')).status, 401)
  assert.equal((await fetch(h.base + '/v1/health')).status, 401)
  assert.equal((await h.call('GET', '/v1/health')).status, 200)
  assert.equal(fs.statSync(path.join(h.home, 'token')).mode & 0o077, 0, 'token file is private')
  await h.close()
})

test('runs a job to the end and keeps its events, session and result', async () => {
  const h = await boot()
  const { id } = await (await h.call('POST', '/v1/jobs', { task: 'find the cheapest flight', engine: 'claude' })).json()
  const job = await until(async () => { const j = await (await h.call('GET', `/v1/jobs/${id}`)).json(); return j.state === 'done' && j })
  assert.match(job.result, /done \(fresh\)/); assert.ok(job.session.startsWith('sess-'))
  const { events } = await (await h.call('GET', `/v1/jobs/${id}/events?after=0`)).json()
  assert.deepEqual(events.map((e) => e.type), ['state', 'session', 'text', 'tool', 'state'].filter((t) => t !== 'session' || events.some((e) => e.type === 'session')))
  assert.deepEqual(events.map((e) => e.seq), events.map((_, i) => i + 1))
  assert.equal(events.find((e) => e.type === 'tool').name, 'Bash')
  // A phone that reconnects asks for everything after the last event it saw.
  const tail = await (await h.call('GET', `/v1/jobs/${id}/events?after=${events.length - 1}`)).json()
  assert.equal(tail.events.length, 1); assert.equal(tail.events[0].type, 'state')
  await h.close()
})

test('streams live events over SSE and replays from a cursor', async () => {
  const h = await boot(); process.env.FAKE_MS = '600'
  const { id } = await (await h.call('POST', '/v1/jobs', { task: 'stream me', engine: 'claude' })).json()
  const res = await h.call('GET', `/v1/jobs/${id}/events?follow=1&after=0`)
  const reader = res.body.getReader(); const dec = new TextDecoder(); let buf = ''; const got = []
  const t0 = Date.now()
  while (Date.now() - t0 < 8000) {
    const { value, done } = await reader.read(); if (done) break
    buf += dec.decode(value); let i
    while ((i = buf.indexOf('\n\n')) >= 0) { const block = buf.slice(0, i); buf = buf.slice(i + 2); const d = /^data: (.*)$/m.exec(block); if (d) got.push(JSON.parse(d[1])) }
    if (got.some((e) => e.type === 'state' && e.state === 'done')) break
  }
  await reader.cancel(); delete process.env.FAKE_MS
  assert.ok(got.some((e) => e.type === 'text')); assert.equal(got.at(-1).state, 'done')
  assert.deepEqual(got.map((e) => e.seq), got.map((_, i) => i + 1), 'no gaps or repeats')
  await h.close()
})

test('stop ends a running job and reports it stopped', async () => {
  const h = await boot(); process.env.FAKE_MODE = 'slow'
  const { id } = await (await h.call('POST', '/v1/jobs', { task: 'long one', engine: 'claude' })).json()
  await until(async () => (await h.call('GET', `/v1/jobs/${id}/events?after=0`).then((r) => r.json())).events.some((e) => e.type === 'tool'))
  assert.equal((await (await h.call('POST', `/v1/jobs/${id}/stop`)).json()).stopped, true)
  const job = await (await h.call('GET', `/v1/jobs/${id}`)).json(); delete process.env.FAKE_MODE
  assert.equal(job.state, 'stopped')
  await until(() => h.host.jobs.get(id).proc === null)   // the child is really gone, not just marked
  await h.close()
})

test('a message to a running job restarts it on its own session with the message', async () => {
  const h = await boot(); process.env.FAKE_MODE = 'slow'
  const { id } = await (await h.call('POST', '/v1/jobs', { task: 'first thing', engine: 'claude' })).json()
  const sess = await until(async () => (await (await h.call('GET', `/v1/jobs/${id}`)).json()).session)
  delete process.env.FAKE_MODE
  assert.equal((await h.call('POST', `/v1/jobs/${id}/message`, { text: 'actually do it for Sunday' })).status, 200)
  const job = await until(async () => { const x = await (await h.call('GET', `/v1/jobs/${id}`)).json(); return x.state === 'done' && x })
  assert.match(job.result, new RegExp(`resumed ${sess}`)); assert.match(job.result, /Sunday/)
  await h.close()
})

test('a failing agent reports why, and a missing agent is a clean error', async () => {
  const h = await boot(); process.env.FAKE_MODE = 'fail'
  const { id } = await (await h.call('POST', '/v1/jobs', { task: 'x', engine: 'claude' })).json(); delete process.env.FAKE_MODE
  const job = await until(async () => { const x = await (await h.call('GET', `/v1/jobs/${id}`)).json(); return x.state === 'failed' && x })
  assert.match(job.result, /boom/)
  assert.equal((await h.call('POST', '/v1/jobs', { task: 'x', engine: 'nope' })).status, 400)
  assert.equal((await h.call('POST', '/v1/jobs', {})).status, 400)
  assert.equal((await h.call('GET', '/v1/jobs/zzz')).status, 404)
  await h.close()
})

test('a host restart picks a running job up again, and gives up after the cap', async () => {
  const home = tmp(); process.env.FAKE_MODE = 'slow'
  let h = await boot(home)
  const { id } = await (await h.call('POST', '/v1/jobs', { task: 'survive a restart', engine: 'claude' })).json()
  const sess = await until(async () => (await (await h.call('GET', `/v1/jobs/${id}`)).json()).session)
  h.host.jobs.get(id).proc.kill(); await h.close()      // the host dies with the job mid-run; the log says "running"
  delete process.env.FAKE_MODE
  const h2 = await boot(home); h2.host.recover()
  const job = await until(async () => { const x = await (await h2.call('GET', `/v1/jobs/${id}`)).json(); return x.state === 'done' && x })
  assert.equal(job.resumes, 1); assert.match(job.result, new RegExp(`resumed ${sess}`))
  const { events } = await (await h2.call('GET', `/v1/jobs/${id}/events?after=0`)).json()
  assert.ok(events.some((e) => e.type === 'note' && /Picked up again/.test(e.text)))
  // A job that has used up its tries is failed, not started a third time.
  const meta = h2.host.jobs.get(id).meta; Object.assign(meta, { state: 'running', resumes: MAX_RESUMES }); await h2.close()
  fs.writeFileSync(path.join(home, 'jobs', id, 'job.json'), JSON.stringify(meta))
  const h3 = await boot(home); h3.host.recover()
  const j3 = await (await h3.call('GET', `/v1/jobs/${id}`)).json()
  assert.equal(j3.state, 'failed'); assert.match(j3.result, /didn't get further/)
  await h3.close()
})

test('scans for installed agents', async () => {
  const h = await boot(); const { engines } = await (await h.call('GET', '/v1/engines')).json()
  assert.deepEqual(engines.map((e) => e.id).sort(), ['claude', 'codex', 'opencode', 'pi'])
  assert.equal(engines.find((e) => e.id === 'claude').found, true)   // the fake answers --version at once
  await h.close()
})
