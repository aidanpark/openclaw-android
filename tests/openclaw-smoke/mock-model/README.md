# 모의 모델 서버 (`server.mjs`)

OpenClaw 에이전트가 **셸 실행 도구(`exec`)** 로 명령을 실제로 실행하는지, 실제 모델 공급자 키 없이 확인하기 위한 호스트용 스크립트이다. 외부 의존이 없는 Node 단일 파일이다(Node 18 이상).

- 1차 모델 호출에는 `exec` 도구 호출 `{"command":"echo hi"}` 로 응답한다.
- 도구 결과가 돌아온 뒤의 2차 호출에는 `MOCK-FINAL: the tool returned: <결과>` 문장으로 응답한다.
- 도구 결과는 서버 표준 출력에도 기록된다. 「명령이 실행되었다」와 「셸이 시작되지 않았다」를 이 한 줄로 구분한다.
- **결과 판정은 대화 전체에서 한다:** 가장 최근 `assistant` 의 `tool_calls` 뒤에 같은 `tool_call_id` 의 `tool` 메시지가 있으면 결과로 본다. 마지막 메시지의 role 에는 의존하지 않는다 — OpenClaw 는 `tool` 메시지 **뒤에** 내부 문맥 `user` 메시지를 덧붙여 보낸다(2026-10-11 기기 시험에서 이 때문에 도구 호출이 반복되는 결함이 발견되어 수정했다).
- **반복 안전망:** 같은 대화에서 결과를 찾지 못한 도구 호출 라운드가 `--loop-max`(기본 3)회에 이르거나, 서버가 도구 호출을 연속으로 `--loop-max` 회 응답한 뒤에는 도구 호출 대신 `MOCK-LOOP-GUARD: …` 문장으로 응답해 에이전트를 끝낸다(작은 기기의 메모리 부족 방지).

실제 키는 쓰지 않는다. `apiKey` 에는 임의 문자열을 넣는다.

## 1. 호스트에서 서버 실행

```sh
node tests/openclaw-smoke/mock-model/server.mjs                 # 127.0.0.1:18080
node tests/openclaw-smoke/mock-model/server.mjs --port 18081 --command "id -un"
node tests/openclaw-smoke/mock-model/server.mjs --selftest      # 형식 자체 시험(서버를 임시로 띄우고 종료)
```

| 옵션 | 환경 변수 | 기본값 | 의미 |
|---|---|---|---|
| `--host` | `MOCK_HOST` | `127.0.0.1` | 바인딩 주소. `0.0.0.0` 으로 열지 않는다(LAN 에 노출된다) |
| `--port` | `MOCK_PORT` | `18080` | TCP 포트 |
| `--model` | `MOCK_MODEL` | `mock-exec` | 광고하는 모델 id |
| `--tool` | `MOCK_TOOL` | `exec` | 선호하는 도구 이름. 요청의 `tools` 에 없으면 `exec`·`bash`·`shell` 류를 찾는다 |
| `--command` | `MOCK_COMMAND` | `echo hi` | 도구 호출로 요청할 명령. **기기에서 실행되므로** 읽기 전용 명령만 쓴다 |
| `--loop-max` | `MOCK_LOOP_MAX` | `3` | 도구 호출을 이 횟수까지 허용하고 그 뒤 `MOCK-LOOP-GUARD` 로 끝낸다 |
| `--quiet` | | | 요청 로그를 끈다 |

## 2. 기기 · 에뮬레이터에서 접근하는 방법

`adb` 에는 항상 `-s <serial>` 을 지정한다. 다른 시험이 쓰고 있는 기기가 아닌지 확인한다.

```sh
# 기기(또는 에뮬레이터)의 127.0.0.1:18080 을 호스트의 127.0.0.1:18080 으로 연결한다
adb -s <serial> reverse tcp:18080 tcp:18080
# ... 시험 ...
adb -s <serial> reverse --remove tcp:18080     # 끝나면 반드시 제거한다
```

에뮬레이터는 `adb reverse` 없이 `http://10.0.2.2:18080/v1` 로도 호스트의 `127.0.0.1` 에 닿는다(아래 설정의 `baseUrl` 만 바꾼다).

## 3. OpenClaw 설정

기기의 홈(앱 터미널에서 `$HOME`)에 파일 하나를 만든다. 사용자의 실제 설정(`~/.openclaw`)은 건드리지 않는다.

```sh
cat > "$HOME/mock-openclaw.json5" <<'EOF'
{
  models: {
    providers: {
      mock: {
        baseUrl: "http://127.0.0.1:18080/v1",
        apiKey: "mock-not-a-key",
        api: "openai-completions",
        models: [
          {
            id: "mock-exec",
            name: "Mock exec",
            reasoning: false,
            input: ["text"],
            cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
            contextWindow: 32000,
            maxTokens: 2048,
          },
        ],
      },
    },
  },
  agents: { defaults: { model: { primary: "mock/mock-exec" } } },
}
EOF
```

한 번의 에이전트 실행(게이트웨이 없이 내장 실행, 임시 상태 디렉터리는 끝나면 지워진다):

```sh
openclaw agent exec "run echo hi" --config "$HOME/mock-openclaw.json5" --model mock/mock-exec --json
```

`--config` 는 그 파일만으로 실행하며, 파일이 없거나 형식이 틀리면 기본값으로 대체하지 않고 실패한다.

## 4. 결과 판정

| 관찰 | 뜻 |
|---|---|
| 서버 로그에 `-> tool_call exec {"command":"echo hi"}` 다음 `tool result: "hi…"`, 최종 출력에 `MOCK-FINAL: the tool returned: hi` | `exec` 가 셸을 시작해 명령을 실행했다 — **정상** |
| `tool result` 가 오류 문구(`CANNOT LINK`·`ENOENT`·`not found`·비정상 종료 코드 등) | 셸 시작 실패. 같은 시각의 `adb -s <serial> logcat -b crash -v threadtime` 에 `CANNOT LINK EXECUTABLE …/usr/bin/bash`(`libandroid-support.so` 를 찾지 못함)가 있으면, OpenClaw 가 자식 프로세스의 `LD_*` 환경 변수를 제거해 앱 환경의 부트스트랩 `bash` 가 자기 라이브러리를 찾지 못한 경우와 일치한다(2026.9.8 번들 코드 판독 기준이며 기기 실행으로 확인한 것은 아니다) |
| 최종 출력에 `MOCK-LOOP-GUARD: …` | 서버가 도구 결과를 대화에서 찾지 못한 채 호출을 반복해서 끝냈다. `tool_call_id` 불일치이거나 클라이언트가 `tool` 메시지를 보내지 않는 경우다. 서버 로그의 `roles=[…]` 줄을 기록해 둔다 |
| 최종 출력에 `MOCK-NO-TOOL: the request offered N tool(s) (…)` | 요청에 셸 도구가 없다. 서버 로그의 `tools=` 목록과 도구 프로필 설정을 확인한다 |
| 서버 로그에 요청이 없음 | 모델 서버에 닿지 못했다. `adb reverse` · 포트 · `baseUrl` 을 확인한다 |
| 설정 검증 오류 | 오류 문구를 그대로 기록해 둔다(설정 키는 번들 문서 기준이며 실행으로 확인하지 못했다) |

기록해 둘 것: 서버 로그 전체 · `--json` 출력 · `logcat -b crash -v threadtime`(시험 시각 전후) · 앱이 디버그인지 릴리스인지.

## 5. 이 스크립트를 검증한 방법과 한계

- `node server.mjs --selftest`: 자체 클라이언트로 22칸(비스트리밍 · SSE 스트리밍 · 도구 이름 선택 · 도구 없음 · 오류 경로 · **`[user, assistant(tool_calls), tool, user]` 회귀** · 다른 `tool_call_id` · 끝난 턴 뒤의 새 턴 · 이력 기반 · 서버 기반 반복 안전망). 이전의 결함 있는 판정 규칙으로 되돌린 사본에서는 회귀 칸이 실패함을 확인했다.
- 공식 `openai` SDK 7.20.0(OpenClaw 2026.9.8 이 의존하는 버전)의 비스트리밍 파서와 스트림 누적기(`chat.completions.stream().finalChatCompletion()`)로 도구 호출 조각 조립 · `finish_reason` · `usage` 를 6칸 확인했다. 이 확인은 저장소에 두지 않았다(격리 디렉터리에서 실행하고 지움).
- **하지 못한 것:** OpenClaw 에이전트 전체를 호스트에서 실행하지 못했다(요구 Node `>=24.16` 이고 이 호스트는 24.13). 따라서 ① 설정 파일의 키와 `agent exec` 플래그는 번들 문서(`docs/providers/vllm.md` · `docs/cli/agent.md` · `docs/tools/exec.md`)에 근거하며 ② 도구 이름 `exec` 와 인자 `command` 도 문서 기준이다(서버는 요청의 `tools` 스키마를 읽어 이름과 인자 이름을 맞춘다). 첫 실행에서 어긋나면 서버 로그가 어긋난 지점을 보여 준다.
