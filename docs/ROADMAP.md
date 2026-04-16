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

### `fire_intent` built-in tool — the "Bash of Android"

**Why**: today AAOSP synthesizes one built-in tool (`launch_app`). Generalizing it to `fire_intent(action, uri, extras)` unlocks *"dial mom"* → `ACTION_DIAL`/`tel:`, *"show 5th Ave on the map"* → `ACTION_VIEW`/`geo:`, *"email John"* → `ACTION_SENDTO`/`mailto:`, and a long tail of per-app deep links — all via Android's existing intent resolver with the existing permission model intact. Highest-leverage single built-in we could ship; most "agent" demos on other platforms are just styled `fire_intent` calls.

**Design note**: full surface + trust reasoning in [`DESIGN_NOTES.md` § "Built-in tool surface"](./DESIGN_NOTES.md). `fire_intent` is always HITL-gated at call time, with the resolved target app + intent shown; caller permissions dominate.

### `get_device_state` built-in tool

**Why**: cheap, low-risk, grounds every answer. Aggregate read of battery level + charging state, network type (wifi/cellular/offline), DND, orientation, timezone, locale — one call returning a small JSON blob instead of six specialized tools. Improves model answers (*"is my phone on wifi?"*, *"am I charging?"*, time-of-day reasoning) at near-zero context cost. Part of the built-in tool surface discussed in [`DESIGN_NOTES.md`](./DESIGN_NOTES.md).

### `set_alarm` / `set_timer` built-in tools

**Why**: *"set a 10 minute timer"* and *"wake me at 7"* are the single most-asked voice-assistant query class on the planet. Both reduce to `AlarmClock.ACTION_SET_TIMER` / `ACTION_SET_ALARM` intents; we could technically do them through `fire_intent` but sugar is worth it for high-frequency verbs. Lands cleanly on top of `fire_intent` infrastructure.

### Streaming tokens to the launcher

**Why**: `runChain()` currently buffers each native inference pass to
completion before emitting. User sees a thinking indicator, then the
full answer appears at once. Qwen 3B on Cuttlefish CPU runs ~3–5 tok/s
— slower than 0.5B, so streaming matters *more* now. Cheap: wire
`NativeTokenCallback.onToken` through to `ILlmResponseCallback.onToken`
directly for the final answer pass. Keep buffering for the tool-call
pass (need the whole tag before dispatch).

### Two-layer permission flow — PendingIntent-proxied runtime grant via launcher

**Status as of v0.5.1**: the minimum fix landed. The BAL-blocked
`startActivity(PermissionRequestActivity)` path is gone from
`ContactsMcp` + `CalendarMcp` (return `needs_permission` JSON
immediately); `LlmManagerService.invokeMcpTool` latch bumped 10s →
60s to match `CONSENT_TIMEOUT_MS`; `runChain` short-circuits on
`needs_permission` so the launcher's `PermissionRequiredCard` + Open
Settings affordance becomes the final chat element instead of being
overwritten by a prose answer turn. Verified end-to-end on
Cuttlefish.

**What's still broken (v0.6 scope)**: tapping **Allow** on the
consent card doesn't chain to granting the Android runtime
permission. User today has a 5-step path (Allow → Open Settings →
navigate → toggle → back → re-ask) for what they meant with one
"yes". Proper fix: MCP returns a `PendingIntent` alongside
`needs_permission`, the launcher (foreground activity, no BAL
block) surfaces `ActivityCompat.requestPermissions` or fires the
pending intent. User sees one dialog, taps Allow, write proceeds.
`PermissionRequestActivity` stays in-tree as the target of this
proxy flow; currently marked dead with a `TODO(v0.6)` comment.

Touches: `ContactsMcp` + `CalendarMcp` services (return a
`PendingIntent` in the error JSON), `AgenticLauncher`
(`PermissionRequiredCard` wires it and refreshes on result), no
framework AIDL change.

### Phantom `cancel()` ~283 ms after submit — dormant

**Status as of v0.5.1**: diagnostic landed
(`Log.w("AgenticLauncher", "cancel() called", Throwable())` first
line of `LauncherViewModel.cancel()`). Across five end-to-end
submits in the v0.5.1 test session the diagnostic **stayed silent**
— no `cancel()` fired. The phantom isn't reproducible in current
flow, so either:
- the original report was misattribution of slow 3B inference
  (~50–60 s per pass on Cuttlefish CPU) to cancellation, or
- a prior fix (e.g., the readiness re-poll) closed the lifecycle
  path that was triggering it.

**Disposition**: keep the diagnostic log in as a regression guard —
if it ever fires again we have a stack trace. Can drop the
`Throwable` allocation in a future patch if still silent after
wider testing. No active investigation needed.

**If it re-appears**: inspect logcat for `AgenticLauncher:W`
around submit time, cross-reference with IME / focus events, and
either gate the Cancel button (if shared touch territory with
Send) or make `cancel()` a no-op when no session is in flight.

<!-- "Launcher readiness polling" — closed in v0.5.1; `LauncherViewModel.submitQuery()`
now re-queries `isReady()` if the cached init-time value is false (option b). -->

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

### User-grantable `SUBMIT_LLM_REQUEST` and `BIND_LLM_MCP_SERVICE`

**Why**: Both grants are `signature|privileged` today. Opening either to user-grant is a fundamental change in AAOSP's trust posture, not a phased rollout. The trade space is mapped in [`DESIGN_NOTES.md` § "Opening up `SUBMIT_LLM_REQUEST` and `BIND_LLM_MCP_SERVICE`"](./DESIGN_NOTES.md). No decision yet — read the design note before proposing implementation.

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
full JSON schema for every registered MCP tool. v0.5.1 measurement
(via `dumpsys llm` prompt-size histogram) confirms the system prompt
is **~7897 chars ≈ 1975 tokens** — roughly half of the current 4096-
token runtime context, before the user has typed a word. Adding
MessagesMcp / ClockMcp / NotesMcp / CameraMcp — the next obvious apps
— will push past the safe budget fast. The pattern used by Claude Code (ENABLE_TOOL_SEARCH):
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

**Depends on**: nothing directly. Gating: v0.5.1 measurement already
establishes the problem is real at the current 2 MCPs + built-in;
a 4th MCP is probably where the ceiling actually bites in practice.

### Inference-based context compaction

**Why**: `runChain` accumulates turns + tool results across iterations
until it hits `MAX_CHAIN_ITERATIONS_CAP=8`. Naive truncation drops
semantic context. Claude Code triggers a summary-inference turn at
~80% of context and replaces the older range with a `<summary>` block.
For AAOSP on the current 4096-token runtime context, the trigger would fire at ~3200 tokens.

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

### Strip stale `needs_permission` tool results from conversation history

**Why**: observed during v0.5.1 testing. When a write-intent tool
returns `{"error":"needs_permission"}`, that tool result lands in
`conversationJson`. On the **next** user turn that asks for a
similar write, the model reads the prior failure and refuses to
even try — it short-circuits to prose ("Please go to Settings...")
without emitting a tool call at all. Reasonable caution on the
model's part, but wrong in context: the user may have just granted
the permission before asking again (that's precisely what the
"Open settings" card invited them to do), and the model's
cached-failure refusal denies them a fresh attempt.

Reproduced cleanly in the v0.5.1 test session:

- Turn 1: "Add Sarah Chen..." → consent → Allow → `needs_permission`
  card → user taps Open Settings (but doesn't actually grant).
- Turn 2 (same chat): "Add Kristian..." → model emits prose
  "I couldn't add Kristian — needs WRITE_CONTACTS..." with **no
  tool call attempted**. Chain completes at iter=0.
- Turn 3 (fresh chat after `endSession`, permission NOW granted):
  same prompt → model emits `<tool_call>` → HITL → write succeeds.

The state the user cares about (permission grant) lives outside
the chat and can change between turns; the model's history-driven
caution is blind to that.

**Fix sketch**: when the launcher builds `conversationJson` for
the next submit, skip any `role: "tool"` entry whose `content`
contains `"error":"needs_permission"` for the pkg/tool the user
has since interacted with (minimum: skip any such entry older
than the most recent user turn). Or server-side: same filter in
`LlmManagerService.appendConversationHistory`. Launcher is
lighter-touch.

**Trade-off**: the model loses the fact that a past attempt
failed. Which is fine — the alternative is what we saw in v0.5.1
testing: the model refuses to act on real requests because of
state it can't verify. If the failure condition is still in
effect, the same failure will recur at tool-call time and the
card will re-fire; the user will have lost one inference pass to
learn that, which is better than never attempting.

**Depends on**: nothing structurally. Launcher-only change; can
land independently. Good v0.6 candidate.

### Launcher UX: thinking card at bottom of conversation, not top

**Why**: v0.5.1 test feedback. Current launcher places the thinking
indicator at the top of the chat view while the model is generating.
The convention (all mainstream chat UIs) is new content arrives at
the bottom, pushing old content up as you scroll. Top-anchored
thinking breaks scroll expectations and makes the card disconnected
from the user's latest turn.

**Fix**: render thinking / tool-call indicators inline at the end of
the message list, as ephemeral trailing items that get replaced when
the final response arrives. Launcher-only change in
`AgenticHome.kt` / `ChatMessageBubble`. No framework or AIDL work.

### Launcher UX: tool-call indicators threaded chronologically inline

**Why**: same v0.5.1 testing session. Tool-call badges currently
render separately from the conversation turns that triggered them.
To follow the agent's reasoning, the user has to mentally interleave
tool-call blocks with text turns. Inline threading — tool call as a
child of the turn that produced it — makes the agent's intermediate
steps legible.

**Fix**: `ConversationMessage` gains a `toolCallsForThisTurn: List<...>`
field; `ChatMessageBubble` renders them inline under the model's
turn. Requires re-association in `LauncherViewModel` — currently
tool calls flow on a separate callback path from message content.
Moderate refactor, launcher-only.

### Consent scope downgrade transparency

**Why**: HitlConsentStore correctly downgrades `SCOPE_FOREVER` to
`SCOPE_SESSION` for write-intent tools (observed in v0.5.1 test
logs: `Downgrading FOREVER→SESSION for write-intent tool add_contact`).
The system did the right thing — users shouldn't be able to grant
"always" on destructive operations by accident. But the UI never
told the user their choice was silently modified. Next session the
consent card will re-appear, and the user's model of "I chose Always"
breaks. Trust-surprise.

**Fix, minimum**: on write-intent tools (`mcpRequiresConfirmation=true`),
grey out the "Always" option in `ConsentPromptCard` so the downgrade
never happens silently; users see the constraint up front. Optional
tooltip: "Always isn't available for write tools — they re-ask each
session."

**Fix, alternative**: keep "Always" clickable but explicitly label it
"Always (this chat)" for write tools. Same transparency, slightly
looser UX — user can still feel they chose the maximum grant
available.

### `Context.bindService` qualified-user warning in dispatcher

**Why**: v0.5.1 logcat shows
```
Calling a method in the system process without a qualified user:
  ... invokeMcpTool ... runChain
```
Cosmetic on single-user Cuttlefish; latent bug for multi-user /
work-profile setups, where the dispatcher would bind the MCP
service in the wrong user's context. `mContext.bindService(...)`
should be `mContext.createContextAsUser(userHandle, 0).bindService(...)`
with `userHandle` derived from the submitting UID (already available
as `userId` in `dispatchOneTool`).

**Depends on**: nothing. One-liner API change + tests. Good v0.7
multi-user hardening item; skip until there's a work-profile story.

### Retry affordance when agent refuses from history

**Why**: counterpart to the "strip stale `needs_permission` from
conversation history" entry above. That fix tries to prevent the
model from seeing its own prior failure; this one adds a user-facing
fallback for when the fix misses. If the launcher detects the model
emitted prose about `needs_permission` without firing a tool call
(detectable via the absence of an `onToolCall` callback within the
chain), render a small "Try again" button that starts a fresh
session with stripped history.

**Depends on**: the stripping fix above should land first. This is
defense in depth — even if stripping misses a case, the user has an
out other than knowing to manually start a new chat.

<!-- Merged into "Two-layer permission flow — PendingIntent-proxied runtime
grant via launcher" above (same underlying fix). -->


### Card-disappears-after-click leaves empty chat

**Why**: observed in v0.5.1 testing. When user taps `Open settings`
on `PermissionRequiredCard`, the card dismisses. When control returns
to the launcher, the chat has only the user's original turn and then
empty space where the card was. No indication of what happened or how
to proceed.

**Fix**: don't remove the card on tap-launch. Keep it rendered as a
conversation element showing *"Sent you to Settings → ContactsMcp →
Permissions."* with a small "Try again" affordance. If the user
granted the perm, the retry button re-dispatches the original tool
call (state preserved on the launcher side in `PendingPermission`).
If they didn't, the retry fires the same error flow — but at least
there's something to tap.

Launcher-only change. Pairs naturally with the "Retry affordance when
agent refuses from history" entry above — same retry mechanism.
