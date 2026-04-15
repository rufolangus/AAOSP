# AAOSP Changelog

Backward-looking record of what shipped in each tag. Forward-looking work
lives in [`ROADMAP.md`](./ROADMAP.md). System design lives in
[`AAOSP_ARCHITECTURE.md`](./AAOSP_ARCHITECTURE.md).

Dates are when the change was merged to the default branch of the affected repo, not public announcement dates. Only `platform_frameworks_base` and `aaosp_platform_build` are actual GitHub forks; see [`AAOSP_ARCHITECTURE.md`](./AAOSP_ARCHITECTURE.md) §"Repository inventory".

---

## Unreleased

- **`AAOSP` (umbrella) `ad65588`** — ROADMAP truth pass after v0.5.1. User spotted that several pre-v0.5.1 ROADMAP entries were actually closed by v0.5.1 but I hadn't graduated them. Four fixes: (a) "Two-layer permission flow" entry's Fix-minimum block (BAL bypass + latch bump) collapsed since shipped; remaining "Fix, proper" (PendingIntent-proxy) is now the entry's only content under a cleaner heading. (b) "Phantom `cancel()`" marked dormant — v0.5.1 diagnostic silent across 5 live submits, keeping the log as regression guard, no active investigation. (c) "Launcher readiness polling" entry removed — re-query-on-submit (option b) shipped in v0.5.1. (d) Standalone "Consent Allow should also grant runtime permission" deleted and merged into the PendingIntent-proxy item — same fix from different angles. Net -104/+56 lines.
- **`AAOSP` (umbrella) `4c6b660`** + **`ContactsMcp` `b2a3688`** + **`CalendarMcp` `a4d1821`** — doc truth pass extending the ROADMAP fix above. User asked "where else could you have made the same mistake?" and the answer was: several places across repos. Fixed: umbrella README "Consent / audit ⚠️ not yet wired" line (shipped in v0.5); umbrella README "HITL wiring not yet wired in LlmManagerService" contributing bullet (shipped); umbrella README "Execution 10s timeout" line (60s in v0.5.1); umbrella README status table (renamed "Designed / not-yet-wired" → "Shipped in v0.5.1" + "Still deferred to v0.6+"); AGENTS.md §6.1a example commit message was using a hypothetical "10s → 35s" that never happened (real was 10s → 60s); ARCHITECTURE permission-flow narrative treated `PermissionRequestActivity` + BAL block as the live path (v0.5.1 deletes that path, returns `needs_permission` immediately); ARCHITECTURE repo-inventory row for ContactsMcp was stale on the in-app permission dialog; ContactsMcp README described `PermissionRequestActivity` as a live "host for `requestPermissions` in-app"; CalendarMcp README same narrative in its walkthrough. All aligned to v0.5.1 reality — `PermissionRequestActivity` stays in-tree but is dead code marked TODO(v0.6) pending the PendingIntent-proxy work.
- **`AAOSP` (umbrella) `d0189de`** — `AGENTS.md` §6.5 ("Tagging a release") hardened with a **pre-tag doc-staleness grep** step and a **pre-tag ROADMAP graduation** step. Lessons from today: v0.5.1 shipped with 5 docs across 3 repos still describing pre-v0.5.1 behavior because nothing forced a sweep at tag time. The grep catches obvious terms (`not yet wired`, `designed in docs/`, `TODO`, `scaffolded`) plus release-specific ones (API/constant/version strings the release moved). The ROADMAP graduation step forces an entry-by-entry "did this release close any of this?" walk so items like v0.5.1's latch-bump fix don't remain listed as future work after shipping.
- **`AAOSP` (umbrella) `5b78ff0`** — `AGENTS.md` §2 "which repo for which change" table: the SELinux / cuttlefish row said "once we unblock the push; until then, build VM local only" — stale since today's orphan-root snapshot push. Updated to reflect the current topology (both repos live on GitHub as orphan snapshots; edit on the VM's `repo`-managed checkout, re-snapshot + force-push the `aaosp` branch on change; full-history local branch stays put for `repo` compatibility). This is exactly the kind of line the §6.5 pre-tag staleness grep is designed to catch on future releases — regex terms like `once we` and `until then` would match.
- **`AAOSP` (umbrella) `db552b9`** — three more fixes from running the §6.5 staleness grep on the full umbrella: (a) Cuttlefish `launch_cvd` in AGENTS.md §3 and ARCHITECTURE bumped `memory_mb=8192 → 16384` (8 GB was survivable on v0.1.0's 0.5B, tight under v0.5.1's 3B + multi-MCP; VM has 125 GB, so no stress) with an added rationale paragraph; (b) README v0.5 status-table row for the tool-call loop was still quoting the v0.1.0 bring-up verification (Qwen 0.5B + ContactsMcp) even though v0.5.1 testing today verified with 3B + ContactsMcp + CalendarMcp including the HITL write flow — row updated to show both history and the current verification state; (c) CHANGELOG entry shas for two earlier follow-up commits were written as `(this commit)` — backfilled to the actual shas (`5b78ff0`, `d0189de`) so the reference is concrete.
- **`AAOSP` (this commit)** — **README + ROADMAP + DESIGN_NOTES framing correction.** Deep research on what Android 16 actually ships (platform feature vs GMS vs AOSP) surfaced that the previous framing mis-identified App Functions as AAOSP's adversary. It isn't: App Functions is an Apache-2.0 AOSP platform feature that does on-device Binder dispatch with an `@AppFunction` annotation and an `EXECUTE_APP_FUNCTIONS` signature-level permission — the same architectural layer where AAOSP already does MCP-style dispatch, and Google's own docs describe it as *"the mobile equivalent of tools within the Model Context Protocol (MCP)."* The actual closed, GMS-only Google dependency is the *runtime tier above the dispatch layer* — **AICore** (closed system service that wraps Gemini Nano on-device), **Gemini Nano** (closed weights, non-swappable), and **Google Assistant** (closed reference caller). On pure AOSP Android 16 with no GMS there is no on-device LLM runtime at all. AAOSP is therefore the open alternative to **AICore + Gemini Nano + Google Assistant** (the runtime + model + orchestrator stack), not to App Functions (the dispatch plumbing). README §"Why AAOSP, not just App Functions?" rewritten + retitled "How AAOSP relates to App Functions, AICore, and Google Assistant" with a layer-by-layer table showing that App Functions is complementary and the real closed dependency is AICore. Second table contrasts vanilla AOSP / stock+GMS / AAOSP. Tagline updated ("The open AICore"). Lede no longer says "cloud orchestrator" (AICore runs Nano on-device — just closed). OEM pitch renamed from "renting the runtime from Google" to "shipping the AICore tier without AICore." ROADMAP App Functions adapter entry rationale corrected: the adapter exists because App Functions is complementary, not because it's a competitor we're "dual-publishing against." DESIGN_NOTES orchestrator section's mention of "a claim App Functions cannot make" corrected to "a claim the stock Android 16 agentic stack (AICore + Gemini Nano + Google Assistant) cannot make." This correction is a framing sharpening — the underlying value AAOSP provides is unchanged and arguably strengthened (there is no AOSP-native on-device LLM runtime; AAOSP is the only open one).
- **`AAOSP` (prior commit)** — `docs/DESIGN_NOTES.md` role-based-orchestrators section tightened. Option B reframed from "orchestrator declares policy" → "orchestrator *proposes*, framework *validates and enforces*." Policy lives in the framework; the orchestrator is a proposer of configuration, not an owner of policy. New explicit subsection, "The non-negotiable invariants," enumerates eight rules (caller permissions dominate; write-intent tools always HITL; `SCOPE_FOREVER` auto-downgrade; tool invocations go through `dispatchOneTool`; audit unconditional; framework system-prompt sections immutable; model selection advisory; iteration cap and timeout framework-owned). This list is the actual policy specification under Option B — everything else is configuration inside the invariants. Refactor-implications section updated to match the new vocabulary (each item is now a proposal + validation step, not a parameterization). Section heading updated to match: "orchestrator proposes, framework validates and enforces."
- **`AAOSP` (prior commit)** — `docs/DESIGN_NOTES.md` gains a new section, "Role-based orchestrators (default agent) — framework runs the loop, orchestrator declares policy." Status: strong candidate for implementation, not decided yet. Named as the likely answer to the UX-metaphor gap raised in the earlier grants section; most S2 threats (compute drain, permission fatigue, trust UX metaphor) collapse under "one orchestrator at a time, user-picked via `ROLE_ASSISTANT`." Establishes LLM × orchestrator as orthogonal axes ("pick your model, pick your assistant"), clarifies that today's "orchestration" already lives in `LlmManagerService` (runChain, composeSystemPrompt, ConsentGate, dispatch, audit — the launcher is the face not the brain), and takes a position on the three "what's configurable" splits (Option B: framework owns the loop + invariants, orchestrator declares system prompt, tool allowlist, HITL policy, UI, model preference). Cross-references the grants section in both directions. Also includes a concrete refactor-implications list (moderate scope — parameterize inputs to `runChain`, not rewrite it), open questions (session continuity, concurrency, GMS coexistence under `ROLE_ASSISTANT` reuse), and places it as a likely v0.8 / v0.9 theme paired with voice.
- **`AAOSP` (prior commit)** — `docs/DESIGN_NOTES.md` gains two new parked sections: "Voice in + voice out (on-device)" and "Built-in tool surface — the primitives of Android." Voice note maps the pipeline (Whisper → LLM → TTS) vs native-multimodal (Qwen-Omni) trade-off, lands on pipeline for first cut, proposes `ILlmVoiceService` alongside `ILlmService` rather than extending it (same keep-extension-points-modular reasoning we've been applying), defers wake-word / always-listening as v1.0+ with its own threat model. Built-in tool surface note establishes the governing principle ("primitives of Android belong in the framework; app-domain tools belong to apps"), proposes `fire_intent` as the "Bash of Android" foundation, enumerates the full candidate surface grouped by role (intent primitives / system state / system actions / hardware triggers / deliberately-not-built-in), and promotes three near-term candidates (`fire_intent`, `get_device_state`, `set_alarm`/`set_timer`) to ROADMAP as standalone entries. Rest of the surface stays parked pending `DynamicToolRegistry` so prompt-budget doesn't bloat.
- **`AAOSP` (prior commit)** — `docs/DESIGN_NOTES.md` gains a new parked section, "Opening up `SUBMIT_LLM_REQUEST` and `BIND_LLM_MCP_SERVICE`." Maps the scenario space (S0 status quo → S1 open tools → S2 open submitters → S3 both → S4 + apps bring models) as a comparison table, names the load-bearing invariant ("LLM cannot be used as a permission-laundering proxy"), and lists the two open questions that block deciding (credible defense against adversarial tool descriptions; UX metaphor users will grok). Status: open question, no decision, no implementation queued — captured publicly because closed thinking on a project that's open all the way down would undermine the positioning. ROADMAP gains a one-line pointer entry so the question is discoverable from the active work doc.
- **`AAOSP` (prior commit)** — README positioning sharpening pass after deeper analysis of where AAOSP contradicts App Functions. Tagline rewrite ("Android's agentic era, without renting the runtime from Google"). Lede leans into the "OS runs the model" line that App Functions structurally cannot say. "What This Is" gets the AICore parallel ("AAOSP is to AICore what AOSP is to GMS"). Comparison table gains two rows — agent-traffic gatekeeping (Google's ranker vs user's launcher) and "source-available all the way down" (AOSP yes, AICore no, Gemini Nano weights no, orchestrator no; AAOSP yes everywhere). New "Open all the way down" callout under the table. New "What AAOSP won't do" section (no telemetry, no model license, no third-party orchestrator, no developer-program gate). Regulatory tailwind sentence added to OEM section (EU DMA / state privacy laws / China data-residency / India DPDP all push toward on-device + user-controlled). Demo caption tightened (drops the defensive "predates v0.5" phrasing; corrected to v0.3 recording).
- **`AAOSP` (prior commit)** — README + ROADMAP positioning pass after surveying Android 16's `androidx.appfunctions`. README gains a "Why AAOSP, not just App Functions?" section and a "Same playing field for OEMs" subsection. ROADMAP gains an "Android App Functions adapter" exploratory entry — auto-expose registered MCP tools as `@AppFunction`s on Android 16+ devices, gated on rebasing the framework fork to Android 16.
- **`AAOSP` (prior commit)** — README demo caption clarity: the GIF thumbnail only shows the first few seconds (prompt + "Thinking…") but the caption described the full tool-call round-trip as if it were visible inline. Readers who didn't click through to the Loom video saw the description not match the thumbnail. Caption now explicitly says "click through to watch the full demo" and notes the video contents are what the technical description covers. Also flagged that a refreshed recording for v0.5.1 (3B + 2 MCPs + HITL write flow) is queued.

---

## v0.5.1 — HITL wiring fixes; BAL workaround; ctx instrumentation; docs truth pass (2026-04-14)

The "v0.5 we thought we shipped." v0.5 landed all the HITL consent
*scaffolding* (ConsentGate, HitlConsentStore, ConsentPromptCard,
`mcpRequiresConfirmation`, runtime-permission dance) but the
end-to-end write-tool flow was broken in three concrete places. All
three plus a measurement harness and a negative-example routing
pass are in v0.5.1.

### Framework — `platform_frameworks_base` `695fe48` (aaosp-v15)

- **`invokeMcpTool` dispatcher latch `10s → 60s`.** Matches
  `CONSENT_TIMEOUT_MS` so the MCP side gets the full human-response
  window. In v0.5 the 10s cap fired before legitimate slow tools
  could respond, masking the MCP-authored error JSON with a generic
  "tool timeout" upstream.
- **Negative-example routing in `composeSystemPrompt()`**
  (`TOOL_ROUTING_RULES`). Steers Qwen 2.5 3B away from pre-trained
  shell / `adb` / `content://` habits toward the listed MCP tools.
  Pattern taken from the Claude Code system-prompt study; applies
  cleanly to the AAOSP context.
- **Prompt-size instrumentation.** Rolling histogram of system-
  prompt and full-ChatML character counts (100-sample window),
  surfaced via `dumpsys llm`. Budget math reads the actual loaded
  `mNativeModelCtxSize` rather than a hardcoded value. Live
  measurement: system prompt is `~7897 chars ≈ 1975 tokens` (~50% of
  the 4096-token runtime context) before the user's first word —
  evidence-supporting the `DynamicToolRegistry` roadmap item at
  v0.6.
- **`runChain` short-circuits on `tool_result` `needs_permission`.**
  The launcher's `PermissionRequiredCard` (keyed on this JSON) is
  the correct user-facing element. Running a final answer pass
  after a `needs_permission` result produces redundant prose
  ("Please go to Settings...") that replaces the actionable card
  in the chat, stealing the Open-Settings affordance. Now the
  chain exits at iter=0 with `onToken("") + onComplete("")`; card
  stays as the last message. Verified end-to-end on Cuttlefish:
  previously 2m+ and prose-replaces-card; now 7s and card persists.

### Launcher — `platform_packages_apps_AgenticLauncher` `2ad4940` (main)

- **`LauncherViewModel.submitQuery()` re-polls `isReady()`** if the
  cached init-time value is false. 3B model mmaps in ~15s on cold
  boot; opening the launcher during that window previously cached
  "not ready" forever. Self-correcting now.
- **`LauncherViewModel.cancel()` diagnostic log** with stack trace
  for the reported phantom-cancel ~283ms after submit. Across five
  submits in the v0.5.1 test session the diagnostic stayed silent
  — the phantom isn't reproducible in current flow, or was
  mis-attributed to slow 3B inference. Keeping the log as a
  regression guard; can drop the `Throwable` allocation in v0.5.2
  if still silent.

### MCPs — `ContactsMcp` `370a6cb` + `CalendarMcp` `f41f3a8` (main)

- **Delete `startActivity(PermissionRequestActivity)` path** in
  `requestWrite{Contacts,Calendar}OrError`. On Android 15 the BAL
  (background-activity-launch) gating silently fails the start
  from a bound-service context; the service then sat on a 30s
  latch waiting for a dialog that never rendered. Return
  `needs_permission` JSON in ~5ms instead. `PermissionRequestActivity`
  stays in-tree as the eventual target of a PendingIntent-proxied
  permission request via the launcher (v0.6 work).

### `aaosp_device_google_cuttlefish` `90d77d1` (aaosp, orphan re-push)

- **Overlay trimmed 2 → 1 line.** The previous snapshot carried a
  `PRODUCT_COPY_FILES` line pushing the 0.5B GGUF to
  `/data/local/llm/` in `shared/device.mk`. That was v0.1.0 legacy
  (model was side-loaded post-boot); v0.5 bakes the 3B GGUF into
  `/product/etc/llm/` via `aaosp_platform_build`. The 0.5B-copy
  line was dead; dropped.
- **Corrected commit message.** v0.1.0's commit message said
  "relax artifact path requirements" but the change actually
  *removes* the `:= relaxed` line. In Android 14+ `:= relaxed` is
  deprecated — its presence elevates the violation. Removing the
  line (as the AAOSP commit does) is the correct modern fix.
  Load-bearing: restoring upstream caused `m -j32` to fail at the
  Make-setup phase on `system/lib64/libllm_jni.so`. The updated
  `AAOSP_OVERLAY.md` documents this correctly.
- **Force-push of the `aaosp` branch** was required to replace the
  2-line orphan snapshot with the 1-line one.

### `AAOSP` umbrella (`d137600` → `6679f17`)

- **Doc fabrication sweep.** Fixed v0.3 and v0.2 dates in
  CHANGELOG (were Feb/March, reality is all 2026-04-12 through
  2026-04-14); removed phantom `v0.2.0` tag references
  throughout; removed unverifiable Snapdragon 8 Gen 3 benchmark;
  rewrote repo inventory (only 2 of 9 are real forks); fixed
  "empty shell / pack-size blocked" framing for sepolicy +
  cuttlefish repos; aligned `ctx=2048` stale references to the
  real runtime `ctx=4096`; updated status table (HITL/chaining/
  launch_app moved from "not yet wired" to "shipped in v0.5").
- **`AGENTS.md` added at repo root.** Encodes the expensive-to-
  rediscover rules: repo topology, don't-fabricate guardrails,
  docs-track-code-live principle, multi-file touch recipes,
  AOSP landmines, pre-commit gate, session start protocol.
- **Claude-code patterns synthesis.** Three parked designs in
  `DESIGN_NOTES` (sub-agent orchestration, user-facing persona
  memory, task tracking as notification surface) + three new
  platform-hardening ROADMAP entries (DynamicToolRegistry,
  inference-based compaction, launcher-side tool-result
  clearing). Patterns extracted from a deep-research pass on
  the public `anthropics/claude-code` repo + Claude Agent SDK
  docs, mapped to AAOSP's actual constraints (3B / 4096 ctx /
  on-device / single-model).
- **Seven UX / hardening ROADMAP items from v0.5.1 live
  testing**: thinking card position; tool-call inline
  threading; consent scope-downgrade transparency;
  `Context.bindService` qualified-user multi-user bug;
  strip-needs_permission-from-history; retry-after-refuse
  affordance; consent-Allow-chains-to-runtime-permission;
  card-disappears-after-click-leaves-empty-chat.
- **`aaosp_system_sepolicy` `c13cc1b` + `aaosp_device_google_cuttlefish`
  `4c4c626` (superseded by `90d77d1`)** — initial orphan-root
  snapshot pushes, closing the v0.1.0 reproducibility gap. These
  repos had been empty shells on GitHub for 2 days while the
  overlays (3 files + 2 files, later 1 file) sat only on the
  build VM. Root cause of the prior "pack-size blocked" framing:
  `repo sync` shallow-clone topology; fix was orphan-root
  snapshots. Each hosts an `AAOSP_OVERLAY.md` documenting base
  commit + delta.
- **`platform_packages_apps_ContactsMcp` `df27b4b`** — fix
  dangling `./CalendarMcp_README.md` link (CalendarMcp is a
  separate repo, not an in-tree file).
- **`platform_frameworks_base` `399b323`** — drop stale
  forward-reference in `revokeToolGrant` javadoc ("forthcoming
  v0.5" / "v0.4 exposes it via `cmd llm revoke`") — AIDL now
  ships on `aaosp-v15`.

### Still broken after v0.5.1 — deferred to v0.6 (see `ROADMAP.md`)

- Consent `Allow` doesn't chain to the runtime Android permission
  grant; user still has a 5-step path through Settings.
- `PermissionRequiredCard` dismisses on tap, leaving empty chat.
- Thinking card renders at top of conversation instead of bottom.
- Tool-call indicators aren't threaded inline with their turn.
- Consent scope downgrade (FOREVER→SESSION for write tools) is
  silent in the UI.
- Model-refuses-from-history when a prior turn hit `needs_permission`.

---

## v0.5 — HITL consent + agentic chaining + bigger model + 2nd MCP (2026-04-14)

AAOSP graduates from "one app + a chatbot" to a real platform. Five
pieces land together because iterating on any one in isolation
exposed the others: HITL consent is hollow without multiple tools to
consent to; chaining is a party trick on one MCP; a better model only
matters if the prompt + tool-use framework is honest enough to use it.

> **Why there is no v0.4 tag.** The original v0.4 plan was HITL
> consent + multi-step chaining only. Mid-iteration it became clear
> shipping that without a second MCP, a competent model, and a way
> to launch non-MCP apps was a narrative hole — the demo would read
> as "we can add contacts (sometimes)" rather than "this is a
> platform." We folded the next three items forward rather than
> publish a half-step. Nothing in the v0.4 plan was dropped; the
> whole delta below was tagged v0.5 instead.

### System service (`frameworks/base/services/core/java/com/android/server/llm/`)

- `LlmManagerService.runChain()` — agentic loop, up to
  `LlmRequest.maxToolCalls` iterations (default 5, hard cap 8).
  Tool-call pass at temperature `min(request.temperature, 0.25f)`,
  final answer pass at the caller's requested temperature (default
  `0.7f`). 2-in-a-row unknown-tool circuit breaker.
- `ConsentGate` — race-safe one-shot (CountDownLatch +
  AtomicReference) for parking a dispatcher thread on a HITL prompt.
  60 s timeout → auto-DENY. Woken by `cancel()` / `endSession()` as
  well as user response.
- `HitlConsentStore` — SQLite at `/data/system/llm/llm_consent.db`,
  two tables. `consent_grants` (userId, pkg, sig-hash, tool,
  decision, scope, session_id) and `audit_calls` (per-invocation
  args, result, status, consent_decision, duration, iter_index).
  App-upgrade invalidation via signature-hash mismatch on lookup.
  `SCOPE_ONCE` grants auto-consumed on read. `SCOPE_FOREVER`
  downgraded to `SESSION` for write-intent tools.
- `registerPackageMonitor()` — prunes grants on
  `ACTION_PACKAGE_REMOVED` (sig-hash check handles upgrades).
- Built-in `launch_app` tool, synthesized by the framework — no MCP
  required, works on bare AAOSP. Reserved package name `"android"`;
  dispatcher fires STARTED + COMPLETED events so the launcher
  foreground-starts the app via `getLaunchIntentForPackage`.
- Dispatcher now fires `STATUS_STARTED` **before** the consent gate
  so the tool-call card appears instantly, not after the user taps
  Allow.
- `mBinder.dump()` — `dumpsys llm` reports model state, pending
  gates, canceled sessions, tool registry (pulled from
  `McpPackageHandler.getRegistry()`, includes `[consent]` and
  `[builtin]` markers), and the 20 most recent audit rows.
- New AIDL methods on `ILlmService`:
  `confirmToolCall(sessionId, toolName, decision, scope)`,
  `revokeToolGrant(pkg, tool)`,
  `getRecentAuditCalls(limit)` → JSON,
  `endSession(sessionId)` (clears SESSION-scoped grants + wakes
  parked gates).
- Incoming prompt + conversation history logged for debugging.
- `LlmRequest.conversationJson` wired into the prompt at build time
  (prior-turn context now reaches the model; before, it was
  launcher-built and thrown away server-side).

### Framework AIDL / parcelables / resources

- `LlmRequest` — new fields `sessionId` (nullable, multi-turn
  continuity) and `maxToolCalls` (clamped [1, 8]).
- `McpToolCallInfo` — new fields `iterationIndex` and
  `STATUS_PERMISSION_REQUIRED` status code.
- `attrs_manifest_mcp.xml` — `mcpRequiresConfirmation` attribute
  declared.
- `public-staging.xml` — `mcpRequiresConfirmation` and `mcpRequired`
  promoted to public (in the *live* `0x01b70000` staging group, not
  the forward-placeholder `0x01fd0000` — four-file attr promotion
  dance is documented in `AAOSP_ARCHITECTURE.md`).
- `lint-baseline.txt` — metalava `UnflaggedApi:` suppression for the
  two promoted attrs.
- `core/api/current.txt` — the two new public fields added.

### Model

- Qwen 2.5 3B Instruct Q4_K_M (~2 GB) now baked into
  `/product/etc/llm/`, replacing the 0.5B Q8 bring-up model.
- `nativeLoadModel` context size bumped 2048 → 4096 to accommodate
  chained tool calls on 3B.
- Rationale: 0.5B pattern-matches, it doesn't reason about tools.
  With the 3B the model actually picks tools based on their
  descriptions, which is the difference between "scripted demo" and
  "platform." CPU-only inference on Cuttlefish runs roughly 3–5 tok/s
  — slow for streaming but fine for the current buffer-then-render
  UX.

### ContactsMcp (`packages/apps/ContactsMcp/`)

- Two write tools — `add_contact`, `update_contact` — with
  `android:mcpRequiresConfirmation="true"`. Uses
  `ContentProviderOperation` on `RawContacts` / `Data` with
  `accountType=null` so writes work on bare Cuttlefish without a
  Google account.
- `PermissionRequestActivity` — translucent no-UI activity that hosts
  `requestPermissions(WRITE_CONTACTS)` when the service lacks the
  runtime perm. Gates the caller via a `CountDownLatch`. Falls
  through to a structured `{"error":"needs_permission",...}` if
  Android 15 BAL blocks the activity start.
- `WRITE_CONTACTS` declared as `uses-permission` but **not** added to
  `default-permissions-aaosp.xml` — intentional, so the first write
  call exercises the two-layer consent flow end-to-end (HITL tool
  consent then Android runtime permission).

### CalendarMcp — new reference app (`packages/apps/CalendarMcp/`)

- Second-MCP proof point. Tools: `list_events(range)`,
  `find_free_time(duration_minutes)`, `create_event(title, start,
  end?, description?)` with `mcpRequiresConfirmation="true"`. Uses
  `CalendarContract` ContentProvider.
- Chains naturally with ContactsMcp — e.g., *"schedule dinner with
  Sarah Chen next Tuesday 7pm"* flows as `search_contacts` →
  `create_event`, stepping through iteration index 0 then 1 in the
  chain, both surfacing their own tool-call cards.
- Same two-layer consent pattern as ContactsMcp: `WRITE_CALENDAR`
  declared but not default-granted; `PermissionRequestActivity`
  handles the runtime dialog; launcher falls back to
  `PermissionRequiredCard` if BAL blocks.
- Includes a best-effort natural-language time parser (`tomorrow
  7pm`, `2026-04-15 19:00`, `tonight 9pm`) so the LLM doesn't have to
  produce rigid ISO-8601.

### Agentic Launcher (`packages/apps/AgenticLauncher/`)

- **Conversation history.** Every completed turn is appended to a
  `ConversationMessage` list rendered as a chat log —
  right-aligned primary bubbles for the user, left-aligned neutral
  bubbles for AAOSP. No more single-response-overwrite.
- `ConsentPromptCard` — 4-button inline card (*Once* / *This chat* /
  *Always* / *Deny*) with per-arg key:value readout so the user
  reviews what's about to be sent.
- `PermissionRequiredCard` — surfaces when a tool returned
  `"needs_permission"`. One-tap fallback to
  `ACTION_APPLICATION_DETAILS_SETTINGS` for the owning package.
- `LauncherViewModel.handleBuiltinLaunchApp()` — recognizes
  `packageName=="android"` / `toolName=="launch_app"` tool events,
  fuzzy-matches the model's app name against installed launchable
  apps, fires `startActivity` with `getLaunchIntentForPackage`.
- `LauncherViewModel.confirmToolCall(decision, scope)` — reflection
  passthrough to the system service (falls through to raw `mService`
  binder if the `LlmManager` wrapper doesn't expose the method).
- `endServiceSession()` — called on *New chat* / *Clear conversation*
  so SESSION-scoped grants are cleared before the next turn.
- `activeSessionId` threaded through every `submit()` for multi-turn
  continuity.

### System prompt

- "WHEN TO CALL A TOOL" rewritten for dual-direction honesty: direct
  answer when possible, but **must** call a tool for any
  do-something request (add/create/save/send/update/delete/schedule).
- New CHAINING section: at most one `<tool_call>` per turn, stop when
  an answer is possible, hard cap of 5 calls.
- Few-shot examples cover direct answer, launch_app, read, write,
  write-with-append, empty result, and explicit denial. Write-tool
  examples end at `</tool_call>` with no trailing assistant prose, so
  the model doesn't learn the anti-pattern of fabricating a success
  message without actually invoking the tool (the 0.5B pattern-lock
  failure we hit during iteration).
- Tools block enumerates every registered MCP tool plus the
  framework's built-in `launch_app`.

### Known issues shipped with v0.5

Surfaced during on-device testing. None block the v0.5 demo path, but all
have ROADMAP fixes queued.

- **Android 15 BAL silently blocks in-app permission dialogs.** The
  `PermissionRequestActivity` scaffolded in ContactsMcp / CalendarMcp
  calls `startActivity` from a bound-service context; on Android 15,
  background-activity-launch gating blocks the start *without
  throwing*. The service then sits on its 30 s permission-gate latch
  waiting for a dialog that will never render. Workaround for testing:
  grant the perm manually via
  `adb shell pm grant com.android.contacts.mcp android.permission.WRITE_CONTACTS`
  (or Settings → Apps → ContactsMcp → Permissions).
- **Dispatcher inner latch (10 s) < MCP permission gate (30 s).**
  `LlmManagerService.invokeMcpTool`'s binder wait is 10 s; the MCP's
  own permission latch is 30 s. The 10 s wins — the dispatcher
  returns `{"error":"tool timeout"}` instead of the MCP's
  `{"error":"needs_permission"}`. The launcher's
  `PermissionRequiredCard` never fires because it keys on the latter
  string. Net effect: user sees "tool failed" instead of the
  "Open settings" CTA.
- **Phantom `cancel()` fires ~283 ms after submit.** Reproducibly
  observed: user taps Send → ViewModel submits → something triggers
  `LauncherViewModel.cancel()` almost immediately. Native inference
  is uninterruptible so it runs to completion, tool dispatches, but
  the chain aborts on the next iteration boundary before the final
  prose turn. Likely source: keyboard dismiss / focus change causing
  an activity lifecycle event that clears the session. Needs
  investigation.
- **Launcher readiness cache is one-shot.** `isReady()` is queried
  once in `LauncherViewModel.init`. Cold boot: the 3B model takes
  ~15 s to mmap, and if the user opens the launcher during that
  window the "not ready" state is cached forever (until the
  launcher is force-stopped + reopened).
- **3B inference on Cuttlefish is slow (~0.6 tok/s with threads=8).**
  Expected — CPU-only x86 emulation with no SIMD tuning, no GPU,
  no NPU. Real phone hardware (ARM NEON + Vulkan) will be
  dramatically faster. A 42 s inference pass on Cuttlefish would
  be ~1–3 s on a current-gen flagship.

### Bring-up lessons learned during the v0.5 cycle

- `staging-public-group type="attr" first-id="0x01fd0000"` is a
  forward-placeholder for the next Android release, **not** active —
  adding entries there leaves the attr `^attr-private/`. Use the
  active group (currently `0x01b70000`).
- Promoting a new attr to public touches 4 files:
  `attrs_manifest_mcp.xml` (declare), `public-staging.xml` (promote),
  `lint-baseline.txt` (metalava `UnflaggedApi` suppression),
  `core/api/current.txt` (stub surface).
- `<tool>` / `<input>` `android:description` is
  `format="reference"` only — inline strings are rejected by aapt2.
  Must use `@string/...`.
- Tool `<input>` required flag is `android:mcpRequired`, not
  `android:required`.
- SQLite forbids expressions inside `PRIMARY KEY` — the
  `COALESCE(session_id, '')` shortcut crashes the DB at onCreate.
  Normalize nullable columns to `""` at write/delete time and keep
  `NOT NULL` in the schema.
- Cuttlefish adb `push` to `/system/framework/services.jar` hits
  read-only filesystem — need `adb disable-verity` + `adb reboot` +
  `adb remount` first on a full-disk-encrypted build.
- At low sampling temperature (`0.1`), few-shot examples *with
  trailing prose* teach the model to skip the tool call entirely and
  pattern-match straight to the fabricated success line. Fix: truncate
  examples at the `<tool_call>` and bump floor to `0.25`.

---

## v0.3 — typed tool-call events + UI attribution + prompt rewrite (2026-04-14)

Replaced the prior untyped `onToolCall(String, String)` with a rich
`McpToolCallInfo` parcelable. Launcher renders the owning app's icon
and label alongside the tool name — user sees *which app* the model
wants to act through, always.

- `McpToolCallInfo` parcelable: sessionId, toolName, packageName,
  serviceName, argumentsJson, resultJson, timestampMillis, status
  (STARTED / COMPLETED / FAILED), durationMillis.
- `ILlmResponseCallback.onToolCall(info)` / `onToolResult(info)` —
  typed replacement for the prior string-pair callback.
- Launcher `ToolCallIndicator` — app icon + label + tool name + args
  preview + status / duration badge.
- `ThinkingCard` — proof-of-life composable shown between submit and
  first-token / tool-event emission.
- **Temperature split** — tool-call pass runs cool, answer pass runs
  at the caller's temperature. Qwen 0.5B drifts wildly on tool args
  at the default 0.7.
- **System prompt rewrite** — explicit GROUNDING rule ("NEVER invent
  facts; names/numbers/emails MUST come verbatim from a
  `<tool_response>`"), explicit query-normalization examples (strip
  possessives / articles / pronouns when extracting search terms).

---

## v0.2 — bake model + JNI into /system (2026-04-13, untagged milestone)

Never cut as a git tag; this section records the commit-set (`42af2cf` on the umbrella, `de15536` on `aaosp_platform_build`, `6137019` on `platform_external_llamacpp`) that landed between v0.1.0 and v0.3.0.

- Qwen 2.5 0.5B Instruct `.gguf` baked into `/product/etc/llm/` at
  build time via `PRODUCT_COPY_FILES` (no more post-boot `adb push`).
- `libllm_jni.so` built as a Soong `cc_library_shared` and installed
  to `/system/lib64`; `System.loadLibrary("llm_jni")` picks it up
  from the standard path (fallback to `/data/local/llm/` retained
  for dev overrides).
- `READ_CONTACTS` auto-granted to `com.android.contacts.mcp` via
  `default-permissions-aaosp.xml` so cold boots Just Work.

---

## v0.1.0 — first bring-up (2026-04-12 tag)

Verified end-to-end on Cuttlefish `aosp_cf_x86_64_phone-trunk_staging-userdebug`.

- `LlmManagerService` published in `system_server` with
  `android.llm.ILlmService` binder API.
- `McpRegistry` / `McpPackageHandler` + `McpManifestParser` — scans
  installed APKs for `<mcp-server>` declarations at
  `PHASE_BOOT_COMPLETED`.
- `ContactsMcpService` — reference MCP with `search_contacts`,
  `get_contact`, `list_favorites` over `IMcpToolProvider`.
- `AgenticLauncher` — Compose-based chat surface binding `ILlmService`.
- `BIND_LLM_MCP_SERVICE` / `SUBMIT_LLM_REQUEST` permissions declared
  (`signature|privileged`).
- Bring-up fixes documented in `AAOSP_ARCHITECTURE.md` ("Critical
  fixes applied during bring-up" section).
