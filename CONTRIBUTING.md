# Contributing to burp-ai-bridge

Everything here is written for whoever touches the code next — human or agent.
The extension is deliberately boring infrastructure: one file, no dependencies,
tests that run anywhere. Keep it that way and every change stays cheap to
review.

## Repository layout

| path | what it is |
|---|---|
| `src/aibridge/AiAgentBridge.java` | the entire extension — single file by design; the BApp Store reviewer and every grepping agent read one file |
| `test/aibridge/*Test.java` | 9 test suites, plain `main()` classes, zero test dependencies |
| `build.sh` / `build.ps1` | plain-javac path (Linux/macOS, Windows); auto-discovers suites |
| `pom.xml` | Maven path — what the BApp Store build pipeline and CI run |
| `docs/agent-protocol.md` | the agent-facing file contract (v2) |
| `docs/ai-bridge-tab.svg` | UI mock, kept in sync with the real tab |
| `docs/architecture.svg` | data flow: Burp → inbox → agent → results |
| `inbox-samples/` | real-shaped samples of every inbox artifact |

## Build

```sh
mvn clean package        # -> target/AiAgentBridge.jar, runs all suites
./build.sh               # -> AiAgentBridge.jar, runs all suites (build.ps1 on Windows)
```

Both paths must stay working — they are independent toolchains over the same
source, and CI (`.github/workflows/ci.yml`) enforces shell + Maven on ubuntu
and `build.ps1` on windows-latest.

## Testing

- Suites are dependency-free `main()` classes. They print `checks=N fails=M`,
  end with `PASS`/`FAIL`, and exit non-zero on any failed assert. No JUnit.
- Run everything: `./build.sh` or `mvn clean package`. Run one suite directly:

  ```sh
  javac -cp "$HOME/.m2/repository/net/portswigger/burp/extensions/montoya-api/2025.5/montoya-api-2025.5.jar:build:build-test" -d build-test test/aibridge/ViewerTest.java
  java -cp "..." aibridge.ViewerTest
  ```

- **Adding a suite:** create `test/aibridge/FooTest.java` following the pattern
  above. `build.sh` / `build.ps1` auto-discover `*Test.java`; `pom.xml` keeps an
  explicit `<run-suite name="FooTest"/>` list — add yours there too, or Maven
  will silently skip it.
- **The fakes pattern:** Montoya interfaces are stubbed with
  `java.lang.reflect.Proxy` handlers (see `RendererResetTest.fakeReq`,
  `ViewerTest.fakeResp`). Filesystem behavior uses real temp directories
  (`Files.createTempDirectory`) — no mocks of the filesystem itself.
- Tests run headless everywhere (`java.awt.headless=true`): no Swing windows,
  no display, no Burp. If your feature needs a visible component, test its
  pure logic and wire the component thin.
- Write the failing test first when you can; every suite in this repo was
  authored red-then-green and some caught real design bugs mid-write (the
  `results.jsonl` watermark needed a content anchor because mtime lacked
  millisecond resolution — the test found it before any user did).

## Code conventions

- **Single file, static nested classes.** `AiAgentBridge` holds everything.
- **Test seams:** `api` and `inboxDir` are package-private on purpose — tests
  inject fakes/temp dirs. Keep them that way.
- **Threading:** Swing only on the EDT. Disk I/O for user actions goes through
  `sendAsync()` (worker thread → status back via `invokeLater`). The Swing
  timer (2 s) is the only poller; long ticks are a bug.
- **Untrusted content:** cell text originates from raw HTTP. Swing renders
  `<html`-led strings as rich text — `BridgeTable.prepareRenderer` switches
  that off (`html.disable` client property) on every component it hands back;
  never remove it. Never interpolate message content into markup contexts.
- **State codec:** tab-separated, one line per sent item, decoded tolerantly
  (missing trailing fields stay blank). Additive changes only; bump
  `STATE_KEY` when the shape changes so old state loads cleanly.
- **Resource discipline:** the timer stops in `extensionUnloaded()`. Anything
  new that starts must have a stop there.

## The file contract is append-only

`manifest.jsonl` and `results.jsonl` are append-only journals shared with
agents we don't control and can't coordinate with. If you change the contract:

- additive only — new fields, never renamed ones; readers tolerate unknown keys
- `PROTOCOL_MD` (constant in source) is the byte-level source of truth; it is
  what the extension writes into the inbox as `README.md`. If you edit it,
  regenerate the live inbox copy so disk and source stay identical
- `docs/agent-protocol.md` must agree with it in human form
- rotation must stay crash-safe: raced appends survive in either the live tail
  or the archive (see `RotationTest`)

## Keep the docs in sync

| if you changed… | also update |
|---|---|
| the tab UI (columns, panes, toolbar) | `docs/ai-bridge-tab.svg` (render it and check text containment), README usage section |
| inbox artifacts or agent steps | `PROTOCOL_MD` + regenerate inbox README + `docs/agent-protocol.md` |
| state codec or persistence | README "survives a restart" claims, `STATE_KEY` |
| build/test wiring | this file, CI workflow |

Regenerating the SVG: edit the source (hand-written, every string inside a
measured box), `rsvg-convert -w 1880 docs/ai-bridge-tab.svg -o /tmp/tab.png`,
then visually verify — headers over their columns, tints legible, nothing
clipped.

## Releasing

1. Bump `<version>` in `pom.xml`.
2. All suites green via both build paths; CI green on both OSes.
3. `git tag vX.Y.Z && gh release create vX.Y.Z AiAgentBridge.jar` with notes
   that lead with the user-visible change and end with the test counts.
4. If a BApp Store submission is in review, leave a short version note on the
   submission issue (plain comment; `/resubmit` only for pipeline failures).

## PR checklist

- [ ] all suites green through `./build.sh` **and** `mvn clean package`
- [ ] new behavior arrived with a failing-first test
- [ ] no new dependencies — the jar must stay dependency-free (Montoya is
      provided by Burp and never bundled)
- [ ] docs synced per the table above
- [ ] no real hostnames, tokens, or captures in code, docs, or fixtures —
      `api.example.com` is the house fake
- [ ] agent contract unchanged, or additive with docs updated
