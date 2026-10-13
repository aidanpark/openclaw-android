#!/usr/bin/env node
// server.mjs — Mock OpenAI-compatible model server (no API key, no dependencies)
//
// Purpose: make an OpenClaw agent run its shell tool (`exec`) without any real model
// provider. The first model call of a turn answers with a tool call (`echo hi`); after the
// tool result arrives, the second call answers with a sentence that quotes the result.
// The tool result is printed to stdout here and appears in the final assistant text, so a
// tester can tell "the command ran" from "the shell could not start".
//
// Wire format: OpenAI Chat Completions (`api: "openai-completions"` in OpenClaw's
// `models.providers.<id>`), streaming (SSE, `stream: true`) and non-streaming.
//
// Usage:
//   node server.mjs                       listen on 127.0.0.1:18080
//   node server.mjs --port 18081 --command "id -un"
//   node server.mjs --selftest            start on a random port, check the wire format, exit
//
// Options (flag or environment variable):
//   --host    MOCK_HOST      bind address              (default 127.0.0.1)
//   --port    MOCK_PORT      TCP port                  (default 18080; 0 = random)
//   --model   MOCK_MODEL     model id to advertise     (default mock-exec)
//   --tool    MOCK_TOOL      preferred tool name       (default exec)
//   --command MOCK_COMMAND   command to ask for        (default "echo hi")
//   --loop-max MOCK_LOOP_MAX tool-call rounds allowed before MOCK-LOOP-GUARD (default 3)
//   --quiet                  only print errors
//
// Result detection: the answer to a tool call is found anywhere in the conversation (the
// latest assistant `tool_calls` followed by a `tool` message with the same tool_call_id);
// the role of the last message does not matter, because OpenClaw appends an internal
// context `user` message after the tool result. A looping agent is stopped by
// MOCK-LOOP-GUARD (history-based and server-side) so that a small device does not run out
// of memory.

import http from 'node:http';

const argv = process.argv.slice(2);
function opt(flag, envName, fallback) {
  const i = argv.indexOf(flag);
  if (i >= 0 && i + 1 < argv.length) return argv[i + 1];
  if (process.env[envName] !== undefined && process.env[envName] !== '') return process.env[envName];
  return fallback;
}
const CFG = {
  host: opt('--host', 'MOCK_HOST', '127.0.0.1'),
  port: Number(opt('--port', 'MOCK_PORT', '18080')),
  model: opt('--model', 'MOCK_MODEL', 'mock-exec'),
  tool: opt('--tool', 'MOCK_TOOL', 'exec'),
  command: opt('--command', 'MOCK_COMMAND', 'echo hi'),
  loopMax: Math.max(1, Number(opt('--loop-max', 'MOCK_LOOP_MAX', '3'))),
  quiet: argv.includes('--quiet'),
};
const MAX_BODY_BYTES = 16 * 1024 * 1024;

function log(...a) {
  if (CFG.quiet) return;
  console.log(new Date().toISOString().slice(11, 23), ...a);
}

// ── Request analysis ──────────────────────────────────────────────────────

function textOf(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content.map((p) => (typeof p === 'string' ? p : p?.text ?? '')).join('');
  }
  return content == null ? '' : JSON.stringify(content);
}

// Walk the whole conversation once and report
//   results    the tool results that answer the latest assistant tool call(s), or null
//   toolRounds how many assistant tool-call messages came since the last assistant message
//              that ended a turn with text
// The role of the LAST message does not matter: OpenClaw appends an internal context `user`
// message after the `tool` message, so "the last message is a tool message" never holds.
// A `user` message therefore does not reset anything; only an assistant text message
// (a finished turn) does.
function analyzeConversation(messages) {
  let pending = null; // Set of tool_call ids of the latest assistant tool-call message
  let results = [];
  let toolRounds = 0;
  for (const m of messages) {
    if (!m || typeof m !== 'object') continue;
    if (m.role === 'assistant') {
      const calls = Array.isArray(m.tool_calls) ? m.tool_calls : [];
      if (calls.length > 0) {
        pending = new Set(calls.map((c) => c?.id).filter((x) => typeof x === 'string'));
        results = [];
        toolRounds += 1;
      } else {
        // the assistant answered in text: that turn is over
        pending = null;
        results = [];
        toolRounds = 0;
      }
    } else if ((m.role === 'tool' || m.role === 'function') && pending) {
      const id = m.tool_call_id;
      // a legacy `function` message or one without an id answers the pending call
      if (typeof id !== 'string' || pending.has(id)) results.push(textOf(m.content));
    }
  }
  return { results: pending && results.length > 0 ? results : null, toolRounds };
}

// A second, server-side safety net for clients that do not echo our tool calls back in the
// history: consecutive tool-call answers without a text answer in between. It resets on
// every text answer and after LOOP_WINDOW_MS without a tool call.
const LOOP_WINDOW_MS = 60_000;
const guard = { consecutiveCalls: 0, lastCallAt: 0 };
function resetGuard() {
  guard.consecutiveCalls = 0;
  guard.lastCallAt = 0;
}

function toolList(body) {
  const out = [];
  for (const t of Array.isArray(body.tools) ? body.tools : []) {
    const fn = t?.function ?? t;
    if (fn && typeof fn.name === 'string') out.push(fn);
  }
  return out;
}

// Which tool and which argument name to use: the configured name when the request offers
// it, otherwise the first tool that looks like a shell tool. The argument is `command`
// when the schema has it, else the first required string property.
function chooseTool(tools) {
  let fn = tools.find((t) => t.name === CFG.tool);
  if (!fn) fn = tools.find((t) => /^(exec|bash|shell|run_command|execute_command)$/i.test(t.name));
  if (!fn) return null;
  const props = fn.parameters?.properties ?? {};
  let arg = 'command';
  if (!(arg in props)) {
    const required = Array.isArray(fn.parameters?.required) ? fn.parameters.required : [];
    arg = required.find((k) => props[k]?.type === 'string') ?? Object.keys(props).find((k) => props[k]?.type === 'string') ?? 'command';
  }
  return { name: fn.name, arg };
}

function decide(body) {
  const messages = Array.isArray(body.messages) ? body.messages : [];
  const { results, toolRounds } = analyzeConversation(messages);
  if (results) {
    resetGuard();
    const joined = results.join('\n---\n');
    const shown = joined.length > 2000 ? joined.slice(0, 2000) + '...(truncated)' : joined;
    return { kind: 'final', text: `MOCK-FINAL: the tool returned: ${shown}`, toolResult: shown };
  }
  // Loop guard 1 (from the history): the conversation already holds LOOP_MAX tool-call rounds
  // that this server could not match with a result.
  if (toolRounds >= CFG.loopMax) {
    resetGuard();
    return {
      kind: 'text',
      text: `MOCK-LOOP-GUARD: ${toolRounds} tool-call round(s) in this conversation have no matching tool result `
        + `(tool_call_id mismatch or a client that drops tool messages). Stopping instead of calling the tool again.`,
    };
  }
  const tools = toolList(body);
  const pick = chooseTool(tools);
  if (!pick) {
    resetGuard();
    return {
      kind: 'text',
      text: `MOCK-NO-TOOL: the request offered ${tools.length} tool(s) (${tools.map((t) => t.name).join(', ') || 'none'}) and none looks like a shell tool.`,
    };
  }
  // Loop guard 2 (server-side): more than LOOP_MAX tool-call answers in a row.
  const now = Date.now();
  if (now - guard.lastCallAt > LOOP_WINDOW_MS) guard.consecutiveCalls = 0;
  if (guard.consecutiveCalls >= CFG.loopMax) {
    const n = guard.consecutiveCalls;
    resetGuard();
    return {
      kind: 'text',
      text: `MOCK-LOOP-GUARD: ${n} tool calls in a row without a final answer. Stopping instead of calling the tool again.`,
    };
  }
  guard.consecutiveCalls += 1;
  guard.lastCallAt = now;
  return { kind: 'tool_call', name: pick.name, args: { [pick.arg]: CFG.command } };
}

// ── Response builders ─────────────────────────────────────────────────────

let seq = 0;
function newId(prefix) {
  seq += 1;
  return `${prefix}_mock_${Date.now().toString(36)}_${seq}`;
}
function usageFor(body, outChars) {
  const promptChars = JSON.stringify(body.messages ?? []).length;
  const p = Math.max(1, Math.ceil(promptChars / 4));
  const c = Math.max(1, Math.ceil(outChars / 4));
  return { prompt_tokens: p, completion_tokens: c, total_tokens: p + c };
}

function completionJson(body, d) {
  const id = newId('chatcmpl');
  const created = Math.floor(Date.now() / 1000);
  const message = { role: 'assistant', content: null };
  let finish = 'stop';
  let outChars = 0;
  if (d.kind === 'tool_call') {
    const args = JSON.stringify(d.args);
    message.tool_calls = [{ id: newId('call'), type: 'function', function: { name: d.name, arguments: args } }];
    finish = 'tool_calls';
    outChars = args.length;
  } else {
    message.content = d.text;
    outChars = d.text.length;
  }
  return {
    id,
    object: 'chat.completion',
    created,
    model: CFG.model,
    choices: [{ index: 0, message, finish_reason: finish }],
    usage: usageFor(body, outChars),
  };
}

function sseChunk(id, created, choice, extra = {}) {
  return `data: ${JSON.stringify({
    id,
    object: 'chat.completion.chunk',
    created,
    model: CFG.model,
    choices: choice ? [choice] : [],
    ...extra,
  })}\n\n`;
}

function writeStream(res, body, d) {
  const id = newId('chatcmpl');
  const created = Math.floor(Date.now() / 1000);
  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache',
    connection: 'keep-alive',
  });
  let outChars = 0;
  if (d.kind === 'tool_call') {
    const callId = newId('call');
    const args = JSON.stringify(d.args);
    outChars = args.length;
    res.write(sseChunk(id, created, {
      index: 0,
      delta: { role: 'assistant', content: null, tool_calls: [{ index: 0, id: callId, type: 'function', function: { name: d.name, arguments: '' } }] },
      finish_reason: null,
    }));
    // the arguments arrive in pieces, as real servers send them
    const cut = Math.max(1, Math.floor(args.length / 2));
    for (const piece of [args.slice(0, cut), args.slice(cut)]) {
      if (!piece) continue;
      res.write(sseChunk(id, created, {
        index: 0,
        delta: { tool_calls: [{ index: 0, function: { arguments: piece } }] },
        finish_reason: null,
      }));
    }
    res.write(sseChunk(id, created, { index: 0, delta: {}, finish_reason: 'tool_calls' }));
  } else {
    outChars = d.text.length;
    res.write(sseChunk(id, created, { index: 0, delta: { role: 'assistant', content: '' }, finish_reason: null }));
    for (let i = 0; i < d.text.length; i += 24) {
      res.write(sseChunk(id, created, { index: 0, delta: { content: d.text.slice(i, i + 24) }, finish_reason: null }));
    }
    res.write(sseChunk(id, created, { index: 0, delta: {}, finish_reason: 'stop' }));
  }
  if (body.stream_options?.include_usage) {
    res.write(sseChunk(id, created, null, { usage: usageFor(body, outChars) }));
  }
  res.write('data: [DONE]\n\n');
  res.end();
}

// ── HTTP server ───────────────────────────────────────────────────────────

function sendJson(res, status, obj) {
  const s = JSON.stringify(obj);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(s) });
  res.end(s);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const parts = [];
    let n = 0;
    req.on('data', (c) => {
      n += c.length;
      if (n > MAX_BODY_BYTES) {
        reject(new Error('request body too large'));
        req.destroy();
        return;
      }
      parts.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(parts).toString('utf8')));
    req.on('error', reject);
  });
}

function makeServer() {
  return http.createServer(async (req, res) => {
    const url = (req.url ?? '/').split('?')[0];
    try {
      if (req.method === 'GET' && (url === '/health' || url === '/')) {
        return sendJson(res, 200, { ok: true, model: CFG.model, tool: CFG.tool, command: CFG.command });
      }
      if (req.method === 'GET' && (url === '/v1/models' || url === '/models')) {
        log('GET', url);
        return sendJson(res, 200, {
          object: 'list',
          data: [{ id: CFG.model, object: 'model', created: 0, owned_by: 'mock' }],
        });
      }
      if (req.method === 'POST' && (url === '/v1/chat/completions' || url === '/chat/completions')) {
        const raw = await readBody(req);
        let body;
        try {
          body = JSON.parse(raw);
        } catch {
          log('POST', url, 'invalid JSON');
          return sendJson(res, 400, { error: { message: 'invalid JSON body', type: 'invalid_request_error' } });
        }
        const d = decide(body);
        const roles = (Array.isArray(body.messages) ? body.messages : []).map((m) => m?.role).join(',');
        const names = toolList(body).map((t) => t.name);
        log(
          'POST', url, `stream=${body.stream === true}`, `roles=[${roles}]`,
          `tools=${names.length}${names.length ? `(${names.slice(0, 8).join(',')}${names.length > 8 ? ',...' : ''})` : ''}`,
          '->', d.kind === 'tool_call' ? `tool_call ${d.name} ${JSON.stringify(d.args)}` : d.kind,
        );
        if (d.toolResult !== undefined) log('   tool result:', JSON.stringify(d.toolResult));
        if (body.stream === true) return writeStream(res, body, d);
        return sendJson(res, 200, completionJson(body, d));
      }
      log(req.method, url, '-> 404 (path not served by the mock)');
      return sendJson(res, 404, { error: { message: `mock server does not serve ${req.method} ${url}`, type: 'not_found' } });
    } catch (e) {
      log('error', String(e?.message ?? e));
      if (!res.headersSent) sendJson(res, 500, { error: { message: String(e?.message ?? e), type: 'server_error' } });
      else res.end();
    }
  });
}

// ── Self test (wire format, no OpenClaw) ──────────────────────────────────

async function parseSse(resp) {
  const text = await resp.text();
  const events = [];
  for (const block of text.split('\n\n')) {
    const line = block.trim();
    if (!line.startsWith('data:')) continue;
    const payload = line.slice(5).trim();
    events.push(payload === '[DONE]' ? '[DONE]' : JSON.parse(payload));
  }
  return { events, contentType: resp.headers.get('content-type') ?? '' };
}

function assembleStream(events) {
  let content = '';
  let finish = null;
  let usage = null;
  const calls = new Map();
  for (const e of events) {
    if (e === '[DONE]') continue;
    if (e.usage) usage = e.usage;
    for (const c of e.choices ?? []) {
      if (c.delta?.content) content += c.delta.content;
      for (const tc of c.delta?.tool_calls ?? []) {
        const cur = calls.get(tc.index) ?? { id: undefined, name: undefined, arguments: '' };
        if (tc.id) cur.id = tc.id;
        if (tc.function?.name) cur.name = tc.function.name;
        if (tc.function?.arguments) cur.arguments += tc.function.arguments;
        calls.set(tc.index, cur);
      }
      if (c.finish_reason) finish = c.finish_reason;
    }
  }
  return { content, finish, usage, calls: [...calls.values()] };
}

async function selftest() {
  let pass = 0;
  let fail = 0;
  const check = (desc, ok, detail = '') => {
    if (ok) { pass += 1; console.log(`[PASS] ${desc}`); } else { fail += 1; console.log(`[FAIL] ${desc}${detail ? ` — ${detail}` : ''}`); }
  };
  CFG.quiet = true;
  const server = makeServer();
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}/v1`;
  const post = (body) => fetch(`${base}/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: 'Bearer not-a-key' },
    body: JSON.stringify(body),
  });
  const execTool = {
    type: 'function',
    function: {
      name: 'exec',
      description: 'Run shell commands',
      parameters: { type: 'object', properties: { command: { type: 'string' }, workdir: { type: 'string' } }, required: ['command'] },
    },
  };
  const user = { role: 'user', content: 'run echo hi' };
  const afterTool = [
    user,
    { role: 'assistant', content: null, tool_calls: [{ id: 'call_1', type: 'function', function: { name: 'exec', arguments: '{"command":"echo hi"}' } }] },
    { role: 'tool', tool_call_id: 'call_1', content: 'hi\n' },
  ];

  console.log('=== Mock model server self test ===');
  try {
    const models = await (await fetch(`${base}/models`)).json();
    check('GET /v1/models lists the advertised model', models.object === 'list' && models.data?.[0]?.id === CFG.model);

    // 1) non-streaming, first call -> tool call
    const r1 = await (await post({ model: CFG.model, messages: [user], tools: [execTool] })).json();
    const tc1 = r1.choices?.[0]?.message?.tool_calls?.[0];
    check('non-stream, 1st call: finish_reason is "tool_calls"', r1.choices?.[0]?.finish_reason === 'tool_calls');
    check('non-stream, 1st call: tool call exec with {"command":"echo hi"}',
      tc1?.type === 'function' && tc1?.function?.name === 'exec' && JSON.stringify(JSON.parse(tc1.function.arguments)) === '{"command":"echo hi"}');
    check('non-stream, 1st call: content is null and usage is present', r1.choices?.[0]?.message?.content === null && r1.usage?.total_tokens > 0);
    check('non-stream, 1st call: id and object fields', typeof r1.id === 'string' && r1.object === 'chat.completion' && typeof tc1?.id === 'string');

    // 2) non-streaming, after the tool result -> final text
    const r2 = await (await post({ model: CFG.model, messages: afterTool, tools: [execTool] })).json();
    check('non-stream, 2nd call: finish_reason "stop" and the tool result is quoted',
      r2.choices?.[0]?.finish_reason === 'stop' && typeof r2.choices?.[0]?.message?.content === 'string' && r2.choices[0].message.content.includes('hi'));

    // 3) streaming, first call
    const s1 = await post({ model: CFG.model, messages: [user], tools: [execTool], stream: true, stream_options: { include_usage: true } });
    const p1 = await parseSse(s1);
    const a1 = assembleStream(p1.events);
    check('stream, 1st call: content-type is text/event-stream', p1.contentType.startsWith('text/event-stream'));
    check('stream, 1st call: ends with [DONE]', p1.events.at(-1) === '[DONE]');
    check('stream, 1st call: tool call id and name come in the first fragment, arguments are assembled from pieces',
      a1.calls.length === 1 && a1.calls[0].name === 'exec' && !!a1.calls[0].id && JSON.stringify(JSON.parse(a1.calls[0].arguments)) === '{"command":"echo hi"}');
    check('stream, 1st call: finish_reason "tool_calls" and a usage chunk', a1.finish === 'tool_calls' && a1.usage?.total_tokens > 0);

    // 4) streaming, second call
    const s2 = await post({ model: CFG.model, messages: afterTool, tools: [execTool], stream: true });
    const a2 = assembleStream((await parseSse(s2)).events);
    check('stream, 2nd call: text assembled from deltas quotes the tool result, finish "stop"',
      a2.finish === 'stop' && a2.content.includes('hi') && a2.calls.length === 0);

    // 5) another tool name / argument name
    const bashTool = { type: 'function', function: { name: 'bash', parameters: { type: 'object', properties: { cmd: { type: 'string' } }, required: ['cmd'] } } };
    const r5 = await (await post({ model: CFG.model, messages: [user], tools: [bashTool] })).json();
    const tc5 = r5.choices?.[0]?.message?.tool_calls?.[0];
    check('a differently named shell tool is used with its own argument name',
      tc5?.function?.name === 'bash' && JSON.stringify(JSON.parse(tc5.function.arguments)) === '{"cmd":"echo hi"}');

    // 6) no shell tool offered
    const r6 = await (await post({ model: CFG.model, messages: [user], tools: [{ type: 'function', function: { name: 'read', parameters: { type: 'object', properties: {} } } }] })).json();
    check('no shell tool offered -> plain text that says so (no tool call)',
      r6.choices?.[0]?.finish_reason === 'stop' && String(r6.choices?.[0]?.message?.content).startsWith('MOCK-NO-TOOL'));

    // 7) regression (device test, 2026-10-11): OpenClaw appends an internal context `user` message AFTER
    //    the tool message. The result must still be found, or the agent calls the tool forever.
    resetGuard();
    const withCtx = [...afterTool, { role: 'user', content: '[internal runtime context]' }];
    const r7a = await (await post({ model: CFG.model, messages: withCtx, tools: [execTool] })).json();
    check('[user, assistant(tool_calls), tool, user] -> final text, not another tool call (non-stream)',
      r7a.choices?.[0]?.finish_reason === 'stop' && String(r7a.choices?.[0]?.message?.content).startsWith('MOCK-FINAL')
      && String(r7a.choices[0].message.content).includes('hi'), JSON.stringify(r7a));
    const a7b = assembleStream((await parseSse(await post({ model: CFG.model, messages: withCtx, tools: [execTool], stream: true }))).events);
    check('[user, assistant(tool_calls), tool, user] -> final text, not another tool call (stream)',
      a7b.finish === 'stop' && a7b.calls.length === 0 && a7b.content.startsWith('MOCK-FINAL') && a7b.content.includes('hi'));
    const twoCtx = [...afterTool, { role: 'user', content: 'ctx 1' }, { role: 'user', content: 'ctx 2' }];
    const r7c = await (await post({ model: CFG.model, messages: twoCtx, tools: [execTool] })).json();
    check('several trailing user messages do not hide the tool result',
      r7c.choices?.[0]?.finish_reason === 'stop' && String(r7c.choices?.[0]?.message?.content).startsWith('MOCK-FINAL'));

    // 8) a tool message with another tool_call_id does not answer the call
    resetGuard();
    const wrongId = [afterTool[0], afterTool[1], { role: 'tool', tool_call_id: 'call_other', content: 'x' }, { role: 'user', content: 'ctx' }];
    const r8a = await (await post({ model: CFG.model, messages: wrongId, tools: [execTool] })).json();
    check('a tool result with a different tool_call_id is not taken as the answer (tool call again)',
      r8a.choices?.[0]?.finish_reason === 'tool_calls');

    // 9) a new turn after a finished one asks for the tool again
    resetGuard();
    const newTurn = [...afterTool, { role: 'assistant', content: 'MOCK-FINAL: the tool returned: hi' }, { role: 'user', content: 'again' }];
    const r9 = await (await post({ model: CFG.model, messages: newTurn, tools: [execTool] })).json();
    check('after a finished turn, a new user message gets a new tool call (no stale result)',
      r9.choices?.[0]?.finish_reason === 'tool_calls');

    // 10) loop guard from the history: LOOP_MAX tool-call rounds that never got a matching result
    resetGuard();
    const rounds = [user];
    for (let k = 1; k <= CFG.loopMax; k++) {
      rounds.push({ role: 'assistant', content: null, tool_calls: [{ id: `call_r${k}`, type: 'function', function: { name: 'exec', arguments: '{"command":"echo hi"}' } }] });
      rounds.push({ role: 'tool', tool_call_id: `zzz_${k}`, content: 'x' });
    }
    rounds.push({ role: 'user', content: 'ctx' });
    const r10 = await (await post({ model: CFG.model, messages: rounds, tools: [execTool] })).json();
    check(`history with ${CFG.loopMax} unanswered tool-call rounds -> MOCK-LOOP-GUARD text, no tool call`,
      r10.choices?.[0]?.finish_reason === 'stop' && String(r10.choices?.[0]?.message?.content).startsWith('MOCK-LOOP-GUARD'));

    // 11) loop guard on the server: more than LOOP_MAX tool-call answers in a row
    resetGuard();
    const seen = [];
    for (let k = 0; k < CFG.loopMax + 2; k++) {
      const rr = await (await post({ model: CFG.model, messages: [user], tools: [execTool] })).json();
      seen.push(rr.choices?.[0]?.finish_reason === 'tool_calls' ? 'call' : String(rr.choices?.[0]?.message?.content).slice(0, 15));
    }
    const expected = [...Array(CFG.loopMax).fill('call'), 'MOCK-LOOP-GUARD', 'call'];
    check(`consecutive tool calls: ${CFG.loopMax} allowed, then MOCK-LOOP-GUARD, then it starts over`,
      JSON.stringify(seen) === JSON.stringify(expected), JSON.stringify(seen));
    resetGuard();

    // 12) bad JSON, unknown path
    const r7 = await post({ model: CFG.model }); // no messages: still answers (no tools)
    check('a request without messages does not crash the server', r7.status === 200);
    const r8 = await fetch(`${base}/embeddings`, { method: 'POST', body: '{}' });
    check('an unknown path answers 404 JSON', r8.status === 404 && (await r8.json()).error?.type === 'not_found');
  } catch (e) {
    fail += 1;
    console.log(`[FAIL] self test aborted — ${e?.stack ?? e}`);
  } finally {
    server.close();
  }
  console.log('');
  console.log(`  Results: ${pass} passed / ${fail} failed / ${pass + fail} total`);
  console.log(fail === 0 ? 'MOCK SERVER WIRE FORMAT OK' : 'MOCK SERVER SELF TEST FAILED');
  process.exit(fail === 0 ? 0 : 1);
}

// ── main ──────────────────────────────────────────────────────────────────

if (argv.includes('--selftest')) {
  await selftest();
} else {
  const server = makeServer();
  server.on('error', (e) => {
    console.error(`mock server: ${e.message}`);
    process.exit(1);
  });
  server.listen(CFG.port, CFG.host, () => {
    const a = server.address();
    console.log(`mock model server on http://${a.address}:${a.port}/v1  model=${CFG.model} tool=${CFG.tool} command=${JSON.stringify(CFG.command)}`);
    console.log('no real key is used; any apiKey string is accepted. Ctrl+C to stop.');
  });
  for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => server.close(() => process.exit(0)));
}
