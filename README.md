<div align="center">

# burp-ai-bridge

**Hand requests from Burp Suite to any AI agent on your machine —
no MCP, no daemon, no copy-pasting blobs.**

*Select requests in Proxy history → your AI agent picks them up like a
message on a desk. Works with every model, every session, every terminal.*

[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)](#build-from-source)
[![Burp Suite Pro / CE](https://img.shields.io/badge/Burp%20Suite-Montoya%20API-orange)](#requirements)
[![no network](https://img.shields.io/badge/TELEMETRY-none-blueviolet)](#privacy)
[![platform](https://img.shields.io/badge/platform-Linux%20%7C%20Windows%20%7C%20macOS-informational)](#requirements)

</div>

---

## The problem it solves

You're three hours into a hunt. Burp's proxy history holds forty requests
you already know are interesting. Your AI agent lives one window away,
able to read files and run tools — but to hand it *that exact request
with its exact response*, the only options are:

- copy → paste → lose formatting → repeat forty times ❌
- export the full history (200 MB of noise for 40 gems) ❌
- build a bespoke integration and never get around to it ❌

`burp-ai-bridge` makes it a single click: **right-click in any message
viewer and "Send request to AI inbox"**, or select rows in the dedicated
**AI Bridge** tab and hit Send. The requests — with their responses,
metadata, and a note explaining *why* you sent them — drop into a
local inbox folder that **any AI agent on the machine can read.**

```
you in chat:  "check the burp inbox"
agent:        reads manifest → reads requests → tests them → reports back
              → archives what it consumed
```

That's the whole protocol. No daemon to babysit, nothing phones home.

---


## How it works

<table><tr><td>

1. **Selecting** happens in Burp's UI, your way — multi-select rows in the
   AI Bridge tab, or right-click any message viewer.
2. **Sending** writes each item as a `.http` file plus one JSON-line of
   metadata into `~/.ai-inbox/burp/` — that's the whole "integration".
3. **Agents** (OpenCode, Claude Code, Codex, a plain grep, *anything that
   can read files*) follow the tiny protocol in
   [`docs/agent-protocol.md`](docs/agent-protocol.md):
   read pending files → work → move them to `archive/`.
   The Burp tab's *pending unread* counter drops when they do.

</td></tr></table>

<p align="center">
  <img src="docs/architecture.svg" alt="architecture: Burp Suite writes to ~/.ai-inbox/burp, any AI agent picks it up" width="760" />
</p>

Because the handoff point is ordinary files, **other sessions and other
models share the same inbox** — hand the same batch to two models and
compare their findings, or pick the hunt up tomorrow in a fresh session.


## Quickstart

**Load it**

1. Download the jar from the [latest release](releases), or [build it](#build-from-source).
2. Burp → **Extender → Extensions → Add** → type **Java** → select the jar.
3. A new **AI Bridge** tab appears. Refresh history, select, send.

**Use it**

| Where | How |
|---|---|
| AI Bridge tab | *Refresh history* → ctrl/shift-select rows → **Send selected** |
| Any message viewer | right-click → **Send request to AI inbox** |
| Proxy history bottom pane | same right-click works there |
| Note field | *why* this batch matters — agents read it before touching anything |

<p align="center">
  <img src="docs/ai-bridge-tab.svg" alt="mock of the AI Bridge tab in Burp Suite with the pending unread counter" width="760" />
</p>

**Then, in your agent's chat:**

> Prompt: `check the burp inbox and hunt what's there`

The inbox protocol is self-documenting: `~/.ai-inbox/burp/README.md`
teaches any fresh agent session what to do, so it works across models
and across weeks.


## The language agents read

One line per item in `manifest.jsonl`:

```json
{"ts":"2026-10-18T02:58:41Z","id":9182,"file":"20261018-025841-431_1001_api.riotgames.com.request.http",
 "host":"api.riotgames.com","port":443,"tls":true,"method":"GET","path":"/lol/platform/v3/player",
 "httpVersion":"HTTP/1.1","proxyTime":"2026-10-18T02:57:58Z","status":200,
 "responseFile":"...-1001_api.riotgames.com.response.http","source":"proxy-history",
 "note":"authz on player id feels fragile, please test idor"}
```

| field | agent uses it for |
|---|---|
| `host` / `port` / `tls` | aim replays (`send_http1_request`, `curl https://...`) |
| `method` / `path` / `httpVersion` | choose the right attack & replay mode |
| `file` / `responseFile` | full raw message bytes, untruncated |
| `status` | spot the interesting ones fast |
| `note` | what *you* suspected — highest-signal field |
| `source` | `proxy-history` or `message-editor` (right-click sends) |

Ready-made samples live in [`inbox-samples/`](inbox-samples/).


## Who needs this

<table>
<tr><td align="center">🔍<br/><b>Hunters</b></td><td>Send an auth-flow batch mid-session and ask the agent to IDOR-hunt while you keep fuzzing.</td></tr>
<tr><td align="center">🧪<br/><b>Pentesters</b></td><td>A "findings queue" a second analyst (machine or human) picks up without touching your mouse.</td></tr>
<tr><td align="center">🤖<br/><b>Agent builders</b></td><td>A boring, universal ingest: one folder of `.http` files beats parsing 12 tools' formats.</td></tr>
<tr><td align="center">👥<br/><b>Teams</b></td><td>Pull requests down the history and share a curated 40-request bundle, not 18 GB of log.</td></tr>
</table>

More (as stories, not specs): **[docs/use-cases.md](docs/use-cases.md)**.


## Requirements

- Burp Suite Pro or Community, 2023.10+ (any Montoya API build). Built and loaded on current releases.
- JDK 21 only if you build from source (the release jar is ready to load).
- An agent that can read files: OpenCode, Claude Code, Codex, Aider, ...


## Build from source

Linux / macOS:

```bash
./build.sh                       # downloads Burp CE API jar, compiles, writes AiAgentBridge.jar
BURP_JAR=/path/to/burpsuite_pro.jar ./build.sh   # compile against your exact Burp
```

Windows: `build.bat` (same behavior).

CI: every push compiles from a clean checkout against a pinned Burp Community jar — [`.github/workflows/ci.yml`](.github/workflows/ci.yml).


## Privacy

Everything stays on your machine. The extension writes to a local folder
and does nothing else — no telemetry endpoints, no update pings. Your
agent's own behavior is whatever you and your agent agree on.

**Care with scope:** curate what you send. The inbox is convenient, so
treat each click on Send like a deliberate commit of that request to
your evidence trail.


## FAQ

**Does the agent get it mid-generation, instantly?**
Agents pull, they can't be pushed. Say *"check the burp inbox"* (or run
your hunt loop; the pending counter tells you files are waiting). The
average latency here is one message — past that, remove one step:
some agents can watch a directory themselves.

**Why files instead of HTTP/queue/socket?**
Files are the lowest common denominator on a machine: every language, every
agent, every gimmick reads them. A server would work for exactly one agent —
files work for all of them, plus outlive it.

**HTTP/2?** HTTP/2 items are tagged `httpVersion: "HTTP/2"` in the manifest so
agents can replay them correctly (e.g. `send_http2_request` via Burp MCP).

**Multiple Burps?** Point each instance at its own folder via the *Inbox
dir* override — it persists per extension install.


## Contributing

Issues and PRs welcome. The entire extension is one file
(`src/aibridge/AiAgentBridge.java`) by design — it fits in an editor window
and is meant to be *your* starting point for weirder things.

## License

[MIT](LICENSE) — © 2026 afshinShh, for the hunter community.
