# AAOSP — known gaps + candidate work

A running inventory of things the project could do next. **Deliberately
unordered** — this isn't a release plan, it's a catalog. Priority depends
on who's working on what, what the next demo needs, and what hurts most
at the time. Each item includes a *Why* so you can judge its urgency
yourself.

Section headings group by *character* of the work (user-visible,
hardening, exploratory), not by priority. Order within a section is
arbitrary and does not imply priority either.

For what's already shipped, see [`CHANGELOG.md`](./CHANGELOG.md).
For system design, see [`AAOSP_ARCHITECTURE.md`](./AAOSP_ARCHITECTURE.md).

---

## User-visible gaps

Things that would show up in a demo or change the way someone uses AAOSP.

### More MCP-providing apps

**Why**: v0.5 ships two reference MCPs (ContactsMcp + CalendarMcp),
which proves multi-MCP + cross-MCP chaining. The platform story gets
stronger with more demos — obvious next candidates: `MessagesMcp`
(`send_message`, `read_recent`), `ClockMcp` (`set_alarm`, `set_timer`),
`NotesMcp` (`create_note`, `search_notes`), `CameraMcp` (`take_photo`).
Each is a self-contained app with a manifest block and a service — no
framework changes required. Good for recruiting contributors.

### Streaming tokens to the launcher

**Why**: `runChain()` currently buffers each native inference pass to
completion before emitting. User sees a thinking indicator, then the
full answer appears at once. Qwen 3B on Cuttlefish CPU runs ~3–5 tok/s
— slower than 0.5B, so streaming matters *more* now. Cheap: wire
`NativeTokenCallback.onToken` through to `ILlmResponseCallback.onToken`
directly for the final answer pass. Keep buffering for the tool-call
pass (need the whole tag before dispatch).

### Two-layer permission flow: route writes to `PermissionRequiredCard` cleanly

**Why**: v0.5 ships the scaffolding (translucent
`PermissionRequestActivity` in each MCP, `requestWriteContactsOrError`
that falls through to `{"error":"needs_permission"}`), but on Android
15 the in-app activity path hits background-activity-launch gating
from a bound-service context — `startActivity` silently returns
`BAL_BLOCK` without throwing. Service sits on a 30 s latch waiting
for a dialog that never renders, while the dispatcher's inner 10 s
binder latch times out first and returns `{"error":"tool timeout"}`
upstream. The launcher's `PermissionRequiredCard` keys on
`"needs_permission"` and never fires, so the user sees a red "tool
failed" card with no path forward.

**Fix, minimum**: in `ContactsMcp.requestWriteContactsOrError` +
`CalendarMcp.requestWriteCalendarOrError`, stop calling
`startActivity` entirely. If the MCP doesn't hold the runtime
permission, return `needsPermissionError()` immediately (~5 ms, not
30 s). The launcher's existing `PermissionRequiredCard` + app-details
Settings deep link is already the right UX for this. Keeps
`PermissionRequestActivity` in the tree as dead code marked TODO.

**Fix, proper (future)**: bound-service MCP requests a permission via
`PendingIntent` the launcher can surface — gets around BAL because
the launcher is a foreground activity. Or: promote to foreground
service temporarily. Not needed if the "Open settings" fallback is
good enough.

Also needed at the same time: bump
`LlmManagerService.invokeMcpTool`'s `latch.await` from 10 s to at
least 60 s so slow tools (ones legitimately waiting on user
interaction) don't time out faster than they can respond. Keep 10 s
as a fast-path for read tools if we add a per-tool declared SLA
later.

### Phantom `cancel()` ~283 ms after submit

**Why**: reproducibly observed in v0.5 testing. User taps Send, view
model submits, something fires `LauncherViewModel.cancel()` 283 ms
later. Native inference is blocking so it runs to completion, the
tool call dispatches, but the chain aborts on the iter-1 boundary
before the final prose turn — user sees a stuck-looking tool-call
card with no narration.

Suspected source: `ImeTracker` / keyboard hide animation racing with
a touch target that also maps to the Cancel button. Boot log for the
test showed `ImeTracker ... onRequestHide` + `FrameTracker ... force
finish cuj, time out: IME_INSETS_HIDE_ANIMATION` ~60 s later, so
there's definitely IME lifecycle churn around submits. Either the
Cancel button is sharing touch territory with Send, or a lifecycle
event (onPause → onResume on keyboard animation end) is clearing
session state.

**First investigation step**: add a stack-trace log in
`LauncherViewModel.cancel()` to see who's calling it. Then either
gate the button, relocate it, or make `cancel()` no-op if no session
is in flight.

### Launcher readiness polling — don't cache one-shot `isReady()`

**Why**: the launcher calls `LlmManager.isReady()` exactly once in
`LauncherViewModel.init` and caches the result. The 3B model takes
~15 s to mmap on a cold boot; if the user opens the launcher during
that window, it caches `false` and never re-checks — UI shows "no
model loaded" forever until the launcher process is force-stopped
and reopened. Observed on first v0.5 boot.

Cheap fixes (any one works): (a) if the first check returns false,
retry every 2 s for ~60 s before giving up; (b) re-query on every
`submitQuery()` — worst case the submit fails with the service's
existing "Model not loaded" error and the UI updates on that signal;
(c) add a readiness-callback AIDL method the launcher subscribes to,
service invokes it on load-complete. (b) is simplest and
self-correcting.

### Settings → AI → Tool Access

**Why**: `HitlConsentStore` persists grants with scope + signature hash,
and `getRecentAuditCalls` / `revokeToolGrant` are wired as AIDL. Without
a Settings UI, users can only review/revoke via `cmd llm revoke` and
`dumpsys llm`. Shipping the UI is the difference between "security model
exists" and "users can actually trust it." Scope: preference screen
listing grants grouped by package, per-tool revoke, recent activity
tab backed by `getRecentAuditCalls(500)`, clear-all affordance.

### Per-app access prompt (vs per-tool-only)

**Why**: HITL today is per-tool. First time the model wants
`ContactsMcp.search_contacts`, the user is prompted; later the same
app's `get_contact` prompts separately. For apps with many tools this
gets noisy. A per-app first-use grant ("Let ContactsMcp answer
questions about your contacts?") + per-tool confirmation *only for
write-intent tools* would be closer to the runtime-permission model
users already know.

---

## Platform hardening

Things that aren't user-visible but make the foundation more honest.

### Soong-install `libllm_jni.so` — historical, confirm on v0.5 boot

**Why**: an earlier pipeline habit had us side-loading
`libllm_jni.so` to `/data/local/llm/` post-boot. On inspection in v0.5
the lib is actually already at `/system/lib64/libllm_jni.so` (Soong is
shipping it correctly), and `System.loadLibrary("llm_jni")` picks it
up from the standard classloader path. The `/data/local/llm/` fallback
in `LlmManagerService`'s static block can probably be removed. Confirm
once on a fresh v0.5 boot and, if clean, delete the fallback.

### `@FlaggedApi` proper annotation for AAOSP attrs

**Why**: v0.5 ships `mcpRequiresConfirmation` / `mcpRequired` as public
framework attrs but uses a `lint-baseline.txt` entry to paper over
metalava's `UnflaggedApi` error. That's a workaround — the right fix is
to declare a flag in `aconfig_flags.aconfig` and annotate the attr
reference. Back out the baseline suppression when done.

### `LlmSessionStore` actually writing

**Why**: the schema opens on boot but nothing writes to it. Multi-turn
continuity is driven by the launcher stuffing
`LlmRequest.conversationJson` every submit — works, but it re-sends the
full history over binder every turn, doesn't survive launcher process
death, and duplicates state between launcher and service. Have
`LlmManagerService` append `{role, content}` rows per turn; promote the
reflection-accessed `listSessions` / `getSessionHistory` /
`deleteSession` / `clearAllSessions` to first-class AIDL methods.
Deprecate `LlmRequest.conversationJson` at the same time.

### `McpManifestParser` full wiring audit

**Why**: v0.1 had a hardcoded fallback in `discoverMcpServices()` for
the contacts package because the manifest parser was misbehaving. v0.5
verified 5 tools parse cleanly from the ContactsMcp manifest and the
scan also sees CalendarMcp, but the legacy hardcoded fallback hasn't
been explicitly checked for and removed. One cleanup pass in
`LlmManagerService.discoverMcpServices()` — if the fallback is still
there, delete it.

### Tool result compression

**Why**: a broad `search_contacts` query can return dozens of rows. The
full JSON array goes back into the model's context. With 3B at 4K
context the immediate pressure is lower than on 0.5B, but chained calls
on multiple MCPs still blow the window. Current cap in
`ContactsMcpService` is 20 rows, but the model sees the whole blob.
Options: summarization pass before result re-enters the chain, or
preview mode where the model sees counts + first-N.

### SELinux — `platform_app` → `llm` service `find`

**Why**: boot log shows `avc: denied { find } for ... name=llm
scontext=platform_app ... permissive=1`. Works today only because
Cuttlefish boots with `selinux=permissive`. On any enforcing build
the launcher can't locate the LLM service, and nothing works. One
line in the sepolicy `.te` for `platform_app` allowing `service_manager
find` on an `llm_service` label (which also needs declaring in
`service_contexts`).

---

## Exploratory

Ideas worth prototyping, not obvious wins.

### MCP resources exposed to the model

**Why**: `McpResourceInfo` is declared and parsed, but the model
doesn't see a `<resources>` block in its prompt. Wire
`readResource` / `listResources` into the agent loop the same way tools
are. Interesting for stuff like retrieving a document by URI rather
than calling a tool.

### Multiple MCP providers for the same intent

**Why**: today only the first registered package for a tool name wins.
As we add more MCPs, collisions become possible — two apps both
declaring `send_message`, for instance. Need ranking / user preference
("Which messaging app should AAOSP use?"). Could reuse the existing
Android default-app-picker pattern.

### Tool-result caching

**Why**: identical `(tool, args)` pairs within the same session could
be memoized. Risk: stale data (contacts changed between calls).
Probably only safe for read tools explicitly marked idempotent in the
manifest.

### Confidence thresholding

**Why**: if we expose logprobs through JNI, the launcher could hedge
("I'm not sure which John you meant — did you mean…"). Real value for
disambiguation UX, but requires extending the JNI callback surface and
is only meaningful once we have a bigger model generating better
calibrated logprobs.

### APEX-updatable model

**Why**: the `.gguf` currently lives in `/product/etc/llm/` which is
measured by verity. That's fine, but means model updates require a
full OTA. A shipped-updatable APEX for the model would let us push
model improvements independent of the system image. Non-trivial —
APEX has size and mountpoint constraints.

### Third-party MCP trust model — public AIDL + user-controlled opt-in

**Why**: today an app can only contribute MCP tools if it's
platform-signed or lives in `/system_ext/priv-app/`. The AIDL surface
(`ILlmService`, `IMcpToolProvider`, parcelables) is `@hide`; the
`BIND_LLM_MCP_SERVICE` permission is `signature|privileged`. That
means the "any app can be an MCP" story in the README is aspirational,
not real — only the AAOSP team (and OEMs shipping custom images) can
contribute tools today. This is a real platform gap.

The right target is probably: **user opts in per app, same
affordance as accessibility services.** Registration silent until
the user explicitly enables the app's MCP in Settings → AI → Tool
Access. Read tools from enabled apps run without per-call consent;
write tools still trigger HITL per call. HITL already handles the
per-call fine grain; this adds the per-app coarse grain the user
actually controls.

Implementation split: (a) promote `android.llm` AIDL to
`@SystemApi` or public (four-file public-staging dance + strip
`@hide`), (b) relax `BIND_LLM_MCP_SERVICE` from `signature|privileged`
to something the user can grant at runtime, (c) Settings screen for
the enable/disable + tool-list review.

**Blocking questions** (see [`DESIGN_NOTES.md`](./DESIGN_NOTES.md))
before any code: how do read tools from enabled apps get bounded,
what does discovery UX look like, does every read tool need its own
`mcpRequiresConfirmation` spectrum, does registration-without-
enablement leak anything.

### Expose v0.5 AIDL properly on `LlmManager` client wrapper

**Why**: the launcher currently reaches `confirmToolCall`,
`endSession`, and the session-history methods via reflection on
`LlmManager.mService`. It works (and is resilient if the wrapper ever
diverges), but it's a dev-only shortcut that should become a real
wrapper method surface before anyone else writes a client against
the AIDL. ~30 min of plumbing.

### DynamicToolRegistry — lazy-load MCP tool schemas

**Why**: today `LlmManagerService.composeSystemPrompt()` inlines the
full JSON schema for every registered MCP tool. With v0.5's two MCPs
+ `launch_app` it's ~N tokens (exact number pending the token-count
log landing). Adding MessagesMcp / ClockMcp / NotesMcp / CameraMcp —
the next obvious apps — will blow the 2048-token context before the
user has typed a word. The pattern used by Claude Code (ENABLE_TOOL_SEARCH):
ship a catalog (`{package, tool, oneLineDescription}` tuples) and a
built-in `search_tools(keyword_or_category)` tool; fetch the full
schema into context only when the model reaches for it.

**Design sketch**: new `McpToolCatalog` parcelable with lightweight
entries; `LlmManagerService.composeSystemPrompt()` writes catalog
instead of full schemas; built-in `search_tools` tool synthesized by
the framework (same mechanism as `launch_app`); returned schemas
cached in-session to avoid double-fetch on repeated use.

**Trade-off**: one extra inference turn on first use of any tool.
Acceptable; avoids a hard ceiling on ecosystem growth.

**Depends on**: nothing directly. Gating: probably only worth the
plumbing once we have evidence a 4th MCP would push us past 2048 —
see the token-count log landing in v0.5.1.

### Inference-based context compaction

**Why**: `runChain` accumulates turns + tool results across iterations
until it hits `MAX_CHAIN_ITERATIONS_CAP=8`. Naive truncation drops
semantic context. Claude Code triggers a summary-inference turn at
~80% of context and replaces the older range with a `<summary>` block.
For AAOSP on a 2048 window, the trigger would fire at ~1500 tokens.

**Design sketch**: `CompactionHook` in `LlmManagerService` checks
token count before each iteration. At threshold, fire a side
inference call at `temperature=0.0` with a fixed compaction prompt
("summarize in-progress work for continuity"). Replace the compacted
turn range with the summary. Retain the live iteration's `tool_use`
and `tool_result` intact so the model can still act on them.

**Trade-off**: one full extra inference pass at each compaction point
(~15-20 s on Cuttlefish CPU). Worse: 3B-model summary reliability is
unproven — a bad summary can mislead the next turn more than
truncation would. Needs evaluation before committing.

**Depends on**: `LlmSessionStore` actually writing (so the compacted
summary can be persisted as the session's new origin, not just held in
memory). v0.6 work.

### Launcher-side tool-result clearing

**Why**: `tool_result` payloads can be bulky — an MCP search returning
many records, a JSON file read, a large log. After the model has
extracted what it needed, these payloads continue to occupy context
every subsequent turn with no additional value. Claude Code's pattern:
strip the `content` of `tool_result` entries older than N turns,
preserving `tool_use_id` so the model still sees the call happened.

**Design sketch**: `AgenticLauncher`'s `conversationJson` builder
strips `tool_result.content` for any turn older than N=3, replacing
with `[cleared: <tool> returned <status> at turn <k>]`. `tool_use`
metadata stays. No framework change needed — launcher already owns
`conversationJson` composition today.

**Trade-off**: if the model retrospectively needs detail from a
cleared result, it must re-invoke the tool. On Qwen 3B this is a
real cost — the model may not reliably recognize the re-invocation
is needed. Mitigate with a larger N or a more selective trim policy
(only clear over-threshold-byte results).

**Depends on**: nothing structurally. Blocking on *evidence we need
it* — the token-count log landing in v0.5.1 will tell us whether
we're actually hitting ceilings in realistic usage. Don't
speculatively trim.
