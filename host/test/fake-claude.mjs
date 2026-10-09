#!/usr/bin/env node
// A stand-in for `claude -p ... --output-format stream-json`: emits the same event shapes, on a script.
const a = process.argv.slice(2)
if (a.includes('--version')) { console.log('0.0.0 (fake)'); process.exit(0) }
const prompt = a[a.indexOf('-p') + 1]
const resume = a.includes('--resume') ? a[a.indexOf('--resume') + 1] : null
const sid = resume || 'sess-' + Math.random().toString(36).slice(2, 8)
const out = (o) => process.stdout.write(JSON.stringify(o) + '\n')
const wait = (ms) => new Promise((r) => setTimeout(r, ms))
out({ type: 'system', subtype: 'init', session_id: sid })
out({ type: 'assistant', session_id: sid, message: { content: [{ type: 'text', text: 'working on: ' + prompt.slice(0, 40) }] } })
out({ type: 'assistant', session_id: sid, message: { content: [{ type: 'tool_use', name: 'Bash', input: { command: 'ls' } }] } })
if (process.env.FAKE_MODE === 'slow') await wait(60000)
else await wait(Number(process.env.FAKE_MS ?? 150))
if (process.env.FAKE_MODE === 'fail') { out({ type: 'result', subtype: 'error', is_error: true, result: 'boom' }); process.exit(1) }
out({ type: 'result', subtype: 'success', is_error: false, result: `done (${resume ? 'resumed ' + resume : 'fresh'}): ${prompt.slice(-60)}`, session_id: sid })
