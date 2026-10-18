# AGENTS.md — for AI agents changing this codebase

Read `CONTRIBUTING.md` first — it has the full layout, build, test, and
release process. The rules below are the ones agents break most often.

1. **Tests are the contract.** New behavior ships with a failing-first test in
   `test/aibridge/` (plain `main()` suite, no JUnit). Add it to the explicit
   `<run-suite>` list in `pom.xml` too — shell builds auto-discover, Maven
   does not.
2. **Two build paths, both green.** `./build.sh` (or `build.ps1` on Windows)
   AND `mvn clean package` must pass before you claim done. CI enforces this.
3. **Single file.** All production code lives in `src/aibridge/AiAgentBridge.java`
   as static nested classes. Do not split it, do not add dependencies.
4. **The inbox contract is append-only.** `manifest.jsonl` / `results.jsonl`
   changes are additive only (new fields, never renamed). `PROTOCOL_MD` in
   source is the byte-level truth; `docs/agent-protocol.md` must agree.
5. **Swing safety.** UI text comes from raw HTTP: keep the `html.disable`
   client property in `prepareRenderer`, touch Swing only on the EDT, disk
   I/O only in workers/timer.
6. **Doc sync.** UI change → `docs/ai-bridge-tab.svg` + README. Protocol
   change → `PROTOCOL_MD` + `docs/agent-protocol.md` + live-inbox README.
7. **No real data.** `api.example.com` is the house fake. Never commit real
   hostnames, tokens, or captures.
8. **Headless only.** Tests run with `java.awt.headless=true` — test pure
   logic, wire visible components thin.
