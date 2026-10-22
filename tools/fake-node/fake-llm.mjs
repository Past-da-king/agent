// A fake OpenAI-compatible model for testing the phone's own agent (local mode) without a real key.
// First turn: it calls the app's task_add tool (filling required string fields). After the tool result: it answers.
//   node fake-llm.mjs --port 17810        (plain http on 127.0.0.1 only; reach it from a device with adb reverse)
import http from 'node:http'

const arg = (n, d) => { const i = process.argv.indexOf(`--${n}`); return i > 0 ? process.argv[i + 1] : d }
const PORT = Number(arg('port', '17810'))
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a)
const reply = (res, body) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(body)) }

http.createServer((req, res) => {
  let raw = ''
  req.on('data', (d) => (raw += d))
  req.on('end', () => {
    if (req.url.endsWith('/models')) return reply(res, { object: 'list', data: [{ id: 'fake-model', object: 'model' }] })
    if (!req.url.endsWith('/chat/completions')) { res.writeHead(404); return res.end() }
    const body = JSON.parse(raw || '{}')
    const msgs = body.messages ?? []
    const last = msgs[msgs.length - 1] ?? {}
    const tools = (body.tools ?? []).map((t) => t.function)
    const wantTool = tools.find((t) => t.name === 'task_add')
    log(`request: ${msgs.length} messages, ${tools.length} tools, last role ${last.role}`)
    const usage = { prompt_tokens: 10, completion_tokens: 5 }
    if (last.role === 'user' && wantTool && String(JSON.stringify(last.content)).includes('remind')) {
      const args = {}
      for (const k of wantTool.parameters?.required ?? []) args[k] = k === 'owner' ? 'user' : 'Buy milk (from the fake model)'
      log(`-> tool call task_add ${JSON.stringify(args)}`)
      return reply(res, { choices: [{ index: 0, finish_reason: 'tool_calls', message: { role: 'assistant', content: null, tool_calls: [{ id: 'call_1', type: 'function', function: { name: 'task_add', arguments: JSON.stringify(args) } }] } }], usage })
    }
    const text = last.role === 'tool' ? 'Done: it is on your to-do list.' : 'OK'
    log(`-> text "${text}"`)
    reply(res, { choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', content: text } }], usage })
  })
}).listen(PORT, '127.0.0.1', () => log(`fake model on 127.0.0.1:${PORT}`))
