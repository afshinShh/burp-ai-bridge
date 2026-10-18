# Use cases

Real stories, not specs. These are the moments people reach for the bridge —
feel free to steal the playbooks.

---

## 1. The mid-hunt deceiver — "IDOR hunt while I keep fuzzing"

3 hours into an API. You've flagged 40 requests that look like classic
IDOR territory — sequential `/api/v2/order/{id}`, mixed 200s, a cool
new beta endpoint nobody has fuzzed.

Instead of an investigation mid-air, you select all 40 rows in the AI
Bridge tab, drop a note — `sequential order IDs, 403s look like
client-side checks, try cross-account GET/PUT` — hit Send, and tell
your agent: *"check the burp inbox, hunt IDOR, don't trip the WAF."*

You go back to fuzzing. Five minutes later the agent reports:
three confirmed cross-account reads (low-effort local replay), two
401-vs-403 differential candidates, and one order endpoint that belongs
to *somebody else's user id*. You verify, write the report, collect.

**Why it's good:** the agent works *with* your context and judgement
(the note), not a blind crawl of the entire history.

## 2. The second pair of eyes — "two models, one inbox"

Send the same 12 authentication-flow requests twice. Session A hears
"check the burp inbox, think OAuth misconfig"; session B hears
"check the burp inbox, think race conditions." Compare outputs.
Machines that see the same bytes disagree in useful ways.

**Tip:** mention which findings you want *opinions* on, and review both
lists yourself while your coffee is still warm.

## 3. The continuity device — "tomorrow, same hunt, zero context loss"

You stop hunting at 2 AM. The inbox keeps: `manifest.jsonl` is a
journal of everything you've flagged, and `archive/` is what has
already been handled. A fresh agent session tomorrow reads the folder
and knows exactly where you left off — including your notes about
*why* each piece was flagged.

Steal this: it's a hunt diary that writes itself.

## 4. The pair-testing table — "human fast, AI thorough"

You drive Burp; approach every form, every flow. After each meaningful
sweep, send the last N requests with a short note. The agent treats
each batch like a reviewer with a checklist: parameter exploration
(what happens on `userId` vs `user_id` vs `uid`?), missing field
suggestions (send this PUT with `"role":"admin"` injected), suspicious
responses that shimmer with data you never validated.

You hunt breadth; it checks depth. Neither of you watches the other's
screen.

## 5. The evidence packager — "from history to report"

Hunting is done. Frame it as: send every request that's part of
Finding #3, note = `part of finding 3, token replay set`.
The agent collates request/response pairs, redacts tokens, produces a
staged markdown block per pair — and the report writes itself with
clean, human-reviewable evidence.

## 6. The plumbing of weird tools — "agent-first ingest"

If you're building an agent (or a pipeline), this folder *is* an API:
`.http` files in, standardized metadata out, `archive/` as the
consumer's cursor. No MCP server to keep alive, no auth tokens to leak.
It's the boring interface that quite deliberately works forever.

**Good housekeeping:**

- one job, one note — the memory of *why* beats pile-on sends
- archive aggressively; a clean inbox is a fast inbox
- treat the inbox folder as part of your evidence chain, not a junk drawer
