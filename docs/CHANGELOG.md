# AAOSP Changelog

Backward-looking record of what shipped in each tag. Forward-looking work
lives in [`ROADMAP.md`](./ROADMAP.md). System design lives in
[`AAOSP_ARCHITECTURE.md`](./AAOSP_ARCHITECTURE.md).

Dates are when the change was merged to the default branch of the affected repo, not public announcement dates. Only `platform_frameworks_base` and `aaosp_platform_build` are actual GitHub forks; see [`AAOSP_ARCHITECTURE.md`](./AAOSP_ARCHITECTURE.md) §"Repository inventory".

---

## Unreleased

Post-v0.5 changes landed on `main` / default branches across the AAOSP repos. Will graduate to a `v0.5.1` section when tagged.

- **`AAOSP` (umbrella) `2aaa97c`** — docs: truth pass. Fixed fabricated CHANGELOG dates (v0.3 was `2026-03-22`, actual tagger date is `2026-04-14`; v0.2 was `2026-02-18`, impossibly before v0.1 — reality is the untagged `42af2cf` milestone on `2026-04-13`). Rewrote `docs/AAOSP_ARCHITECTURE.md` repo inventory: only `platform_frameworks_base` + `aaosp_platform_build` are real GitHub forks; `platform_external_llamacpp` is build-glue only (no llama.cpp source checked in — pulled at build time by `scripts/sync_upstream.sh`); `AgenticLauncher`/`ContactsMcp`/`CalendarMcp`/`sepolicy`/`cuttlefish` are new repos, not forks and not in-tree. Removed unverified Snapdragon 8 Gen 3 benchmark from README (no physical target exists; contradicted ROADMAP's Cuttlefish numbers). Replaced phantom `v0.2.0` tag references with real sha `42af2cf` shipped in v0.5. Bake-in path was the 0.5B filename; v0.5 swapped to 3B Q4_K_M. Moved HITL / session AIDL / chaining / `launch_app` from "not yet wired" to "shipped in v0.5" in the status table.
- **`platform_packages_apps_ContactsMcp` `df27b4b`** — README: fix dangling `./CalendarMcp_README.md` link; CalendarMcp lives in its own repo, not as a local file.
- **`platform_frameworks_base` `399b323`** — `ILlmService.aidl`: drop stale forward-reference in `revokeToolGrant` javadoc ("forthcoming v0.5" / "v0.4 exposes it via `cmd llm revoke`") — the AIDL now ships on `aaosp-v15`.
- **`AAOSP` (this commit)** — add `AGENTS.md` at repo root encoding the expensive-to-rediscover rules (repo topology, don't-fabricate rules, docs-track-code-live principle, multi-file touch recipes, AOSP landmines, pre-commit gate). Bootstrap this `## Unreleased` section so future changes have a place to land.

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
