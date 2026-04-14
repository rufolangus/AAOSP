# AGENTS.md

**Audience:** AI coding agents (Claude Code, Codex, etc.) working on AAOSP. Humans are welcome to read it too, but the tone is written for agents — direct, imperative, load-bearing.

**Goal of this file:** encode the things that are expensive to rediscover on this project. Read it before you touch anything, and re-read §4 and §5 before you write prose or commit.

---

## 1. What AAOSP is, for the purposes of this file

AAOSP is a fork of Android 15 (`trunk_staging`, rebased on `android-15.0.0_r1`) that integrates:

- **MCP (Model Context Protocol)** as a native OS service — apps declare tools in their `AndroidManifest.xml` via an `<mcp-server>` block; the OS parses them at package-install time.
- **An on-device LLM** (Qwen 2.5, default 3B Q4_K_M) running via llama.cpp inside `system_server`, exposed through `LlmManagerService` over a binder `ILlmService` API.
- **An agentic loop** (`LlmManagerService.runChain()`) that dispatches model-emitted `<tool_call>` JSON to MCP services over binder, round-trips results, and runs a final natural-language pass.
- **HITL consent** (`HitlConsentStore` + `ConsentGate`) for tools declared `mcpRequiresConfirmation="true"`.

Canonical source of truth: **`docs/AAOSP_ARCHITECTURE.md`**. If this file and that file disagree, that file wins.

---

## 2. Repo topology

Nine repos make up AAOSP. **Only two are actual GitHub forks.** The rest are new repos sitting at AOSP tree paths — one build-glue wrapper, two orphan-root snapshots (repo-manifest shallow clones can't push ancestry), and four plain new repos. Do not assume "fork" from the name.

| Repo | AOSP path | Branch | What it actually is |
|---|---|---|---|
| `rufolangus/AAOSP` | — | `main` | Umbrella: `repo` manifests, docs/, this file |
| `rufolangus/platform_frameworks_base` | `frameworks/base` | `aaosp-v15` | **Real fork** of `aosp-mirror/platform_frameworks_base`, rebased on `android-15.0.0_r1` |
| `rufolangus/aaosp_platform_build` | `build/make` | `aaosp` | **Real fork** of `aosp-mirror/platform_build` |
| `rufolangus/platform_external_llamacpp` | `external/llama.cpp` | `main` | **Build-glue only** — `Android.bp` + `jni/llm_jni.cpp` + `scripts/`. No llama.cpp source; `sync_upstream.sh` fetches b4547 at build time |
| `rufolangus/platform_packages_apps_AgenticLauncher` | `packages/apps/AgenticLauncher` | `main` | New repo |
| `rufolangus/platform_packages_apps_ContactsMcp` | `packages/apps/ContactsMcp` | `main` | New repo |
| `rufolangus/platform_packages_apps_CalendarMcp` | `packages/apps/CalendarMcp` | `main` | New repo (added in v0.5) |
| `rufolangus/aaosp_system_sepolicy` | `system/sepolicy` | `aaosp` | **Orphan-root snapshot.** 3-line AAOSP overlay (service_contexts + fuzzer exception). `AAOSP_OVERLAY.md` in the repo documents the base + delta. No ancestry on GitHub — diff against AOSP `android-15.0.0_r1` locally |
| `rufolangus/aaosp_device_google_cuttlefish` | `device/google/cuttlefish` | `aaosp` | **Orphan-root snapshot.** 2-line AAOSP overlay (artifact-path relaxations). Same snapshot topology |

### Which repo for which change

| Change | Goes in |
|---|---|
| LLM service, AIDL, MCP manifest schema/parser, `<mcp-server>` handling | `platform_frameworks_base` |
| `PRODUCT_PACKAGES`, bake-in paths, privapp xml install, model GGUF, default-permissions | `aaosp_platform_build` |
| JNI bridge, llama.cpp `Android.bp`, upstream sync script | `platform_external_llamacpp` |
| Launcher UI, consent cards, chat surface | `platform_packages_apps_AgenticLauncher` |
| New reference MCP app | **New `platform_packages_apps_<Name>Mcp` repo**, mirrored at `packages/apps/<Name>Mcp` on the build VM |
| `repo` manifests, architecture doc, cross-cutting plans, this file | `AAOSP` (umbrella) |
| SELinux, cuttlefish overlays | `aaosp_system_sepolicy` / `aaosp_device_google_cuttlefish` once we unblock the push; until then, build VM local only |

### Verify the topology before citing it

Repo state drifts. Before you write prose that depends on it, run:

```bash
gh api repos/rufolangus/<name> --jq '{fork, parent: (.parent.full_name // "—"), size, default_branch, pushed_at}'
gh api repos/rufolangus/<name>/branches --jq '.[].name'
```

If `fork: false`, it is not a fork. If `size: 0` and branches is empty, it is an empty shell. If there's exactly one commit with no parent, it is an orphan-root snapshot (check `AAOSP_OVERLAY.md` for what the overlay actually is).

---

## 3. Build environment

**You cannot build AOSP on a laptop here.** The user's Mac is 8 GB and cannot complete `m -j32`. All builds happen on a cloud VM:

- VM: `aaosp-builder` in `us-east1-b`, GCP project `aaosp-build`
- User: `blurryrobot`
- AOSP tree: `~/aaosp/`
- **Stop the VM when you're done.** It costs money while running.

Build rules:

- Full build: `source build/envsetup.sh && lunch aosp_cf_x86_64_phone-trunk_staging-userdebug && m -j32`
- **Never `m systemimage` alone.** It skips `boot.img` / `vbmeta.img`, and Cuttlefish drops into dm-verity recovery. Always `m -j32`.
- Incremental is fine *after* the first full build, but any change touching the kernel, selinux, or vbmeta needs the full build again.

Cuttlefish run:

```bash
launch_cvd --daemon \
  --gpu_mode=guest_swiftshader --start_webrtc=true \
  --cpus=8 --memory_mb=8192 \
  --extra_kernel_cmdline='androidboot.selinux=permissive'
```

First boot sometimes drops into recovery with `set_policy_failed:/data/local`. Pick **Wipe data / factory reset**, reboot once, it'll be fine. This is a Cuttlefish quirk, not AAOSP.

---

## 4. Docs track code, live — not at tag time

**Core rule of this project:** documentation is updated as the code changes, in the same commit that lands the code. Not "later," not "at tag time," not "when we write the release notes." Live.

Why: every fabrication we've had to chase on this project came from docs drifting behind code between commits. The only defense is to keep them in lockstep.

What this means concretely:

- If you change observable behavior, update `docs/CHANGELOG.md` (under `## Unreleased`) in the same commit. See §6.1a.
- If you change an AIDL, add/remove a class, rename a constant, swap a file path, or move install targets — update every doc that referenced the old state in the same commit. See §6.1b.
- If you change a version-facing claim (status table, tier table, bake-in path) — update README *and* ARCHITECTURE *and* CHANGELOG. These three diverge if you update only one.
- If you can't update the docs in the same commit (legitimate reason: the doc change needs a separate discussion), write the CHANGELOG entry anyway and add a TODO marker pointing to the unfinished doc.

"I'll do the docs in a follow-up" is not a commitment mechanism on this project. Follow-ups get lost. The commit is the commitment mechanism.

---

## 5. Don't fabricate

**This section exists because LLM-written docs have introduced fabrications into this project.** Before any of the following, verify:

### 5.1 Version tags

**Rule:** never reference a version tag without confirming it exists.

```bash
gh api repos/rufolangus/<repo>/git/refs/tags --jq '.[].ref'
```

Real AAOSP tags (2026-04-14): `v0.1.0`, `v0.3.0`, `v0.5`. There is **no `v0.2`, `v0.2.0`, or `v0.4` tag** — do not cite them. `v0.2` is an untagged milestone (commit `42af2cf`); reference it by sha, not by tag.

### 5.2 Dates

**Rule:** any date in a CHANGELOG, README, or doc must come from `git`, not from your estimate.

```bash
gh api repos/rufolangus/<repo>/git/tags/<sha> --jq .tagger.date   # annotated tag
gh api "repos/rufolangus/<repo>/commits/<sha>" --jq .commit.author.date
```

AAOSP's entire history is days old. If you're about to write a date spanning months, stop — it's wrong.

### 5.3 Fork/new-repo status

**Rule:** do not label a repo as a "fork" without `gh api repos/... --jq .fork` returning `true`. Do not say a file lives "in-tree" if it has its own repo.

### 5.4 Benchmarks and measurements

**Rule:** no number without a device. If we haven't run it, don't cite it. If we have, cite the target.

Today we run on Cuttlefish x86_64 (silvermont) on cloud-build CPU. Observed: ~0.6 tok/s with Qwen 3B Q4_K_M, threads=8. Phone-class numbers do not exist until we have a phone. Don't invent them.

### 5.5 Code symbols and paths

**Rule:** when prose names a class, method, field, constant, or path, fetch the file and grep.

```bash
gh api "repos/rufolangus/<repo>/contents/<path>?ref=<branch>" --jq .content \
  | python3 -c "import sys,base64;sys.stdout.buffer.write(base64.b64decode(sys.stdin.read()))"
```

Do not write from memory. Memory goes stale.

### 5.6 Verification results

**Rule:** only claim "verified on Cuttlefish" if there's a Loom, a video, a log, or a demo you or the user actually ran. "Designed and implemented" is not "verified." Use the honest phrase.

### 5.7 Status language

When unsure, write:
- "Shipped in v0.5, verification pending" — code is there, no recent demo
- "Designed but not yet wired" — spec only
- "Unverified" — literally don't know

Do not paper over uncertainty with confident prose.

---

## 6. When you touch one thing, touch these other things

### 6.1 Promoting a manifest attribute to public (4 files)

New MCP attributes require changes in all four:

1. `core/res/res/values/attrs_manifest_mcp.xml` — declare the attr
2. `core/res/res/values/public-staging.xml` — promote into the **active** staging group (currently `0x01b70000`, **not** the forward-placeholder `0x01fd0000` — that's for the next Android release and leaves the attr `^attr-private/`)
3. `core/api/lint-baseline.txt` — metalava `UnflaggedApi` suppression
4. `core/api/current.txt` — stub surface

Skip any one, you get either aapt2 errors, API lint failures, or an attr that silently falls into private.

### 6.1a Every meaningful change updates CHANGELOG in the same commit

Meaningful = anything a user or agent reading `docs/CHANGELOG.md` would want to know: new/changed AIDL, new tool, new MCP app, behavior change, bug fix that alters observable output, model/version swap, new required permission, perf regression/win. Typo and pure comment edits are not meaningful.

- `docs/CHANGELOG.md` has (or should have — add it if missing) an `## Unreleased` section at the top. Write the entry there in the same commit that lands the change. Don't defer to "I'll update the CHANGELOG at tag time" — that's how drift happens.
- Entry format: one bullet per change, reference the code symbol/file, say *why* in half a sentence. Example (real v0.5.1 change): `- \`LlmManagerService.invokeMcpTool\` binder wait 10s → 60s so the dispatcher matches \`CONSENT_TIMEOUT_MS\` and legitimately slow tools aren't cut off before they can respond.`
- When tagging a release, the `## Unreleased` block gets renamed to `## vX.Y — <title> (<YYYY-MM-DD>)` and a fresh empty `## Unreleased` section is added. See §6.5.
- If you're about to write "(updating the CHANGELOG is out of scope for this commit)," stop. It isn't.

### 6.1b Design changes require self-verification before commit

A "design change" is any change that alters an AIDL surface, a persisted schema, a consent/permission boundary, a binder contract, a manifest attribute, the `runChain` loop semantics, temperature-split logic, or the threading model in §6.6. In other words: anything the rest of the tree or the rest of the docs might reasonably depend on.

Before you commit a design change, verify **against the new code** (not your intent, not the diff, not your memory):

- [ ] Fetch/read the final state of every file you changed. Confirm the thing you claim to have done is actually in the file.
- [ ] Grep the whole umbrella + sibling repos for the old symbol/constant/path. Any remaining reference is either stale, needs updating in this commit, or needs a note in the CHANGELOG entry as a known gap.
- [ ] If the change affects an AIDL: the `.aidl`, any generated stub assumptions, the service impl, every client in the tree, and javadoc forward-references (today we found `revokeToolGrant` javadoc still referencing "forthcoming v0.5" after v0.5 shipped).
- [ ] If the change affects a persisted schema: write a migration or document why none is needed. Don't silently bump the schema version.
- [ ] If the change affects permissions/consent: walk the full flow — `LlmManagerService.runChain` → `ConsentGate` → `HitlConsentStore` grant lookup → dispatcher → MCP service → Android runtime permission — and confirm each hop still holds.
- [ ] Update every doc that referenced the old behavior *in the same commit*. README tier tables, ARCHITECTURE inventory, CHANGELOG `Unreleased`, any `docs/DESIGN_NOTES.md` entry. A design change with stale docs is a fabrication waiting to happen next session.
- [ ] Run §8's prose checklist on anything you wrote.

If you're not sure the change is really a design change, assume yes and run the checklist anyway. False positives are cheap; false negatives are what produced today's doc-fabrication sweep.

### 6.2 Adding a new reference MCP app

1. New repo `rufolangus/platform_packages_apps_<Name>Mcp` with the usual `AndroidManifest.xml`, `src/`, `Android.bp`
2. `<mcp-server>` block in manifest; `IMcpToolProvider` service implementation
3. Write-intent tools must declare `android:mcpRequiresConfirmation="true"`
4. `PRODUCT_PACKAGES += <Name>Mcp` in `aaosp_platform_build/target/product/handheld_system_ext.mk`
5. Privapp perms → add a `privapp-permissions-<name>mcp.xml` and install it via the same `.mk`
6. Runtime Android perms → either grant via `default-permissions-aaosp.xml` OR leave ungranted to demo the two-layer consent flow (ContactsMcp does the latter intentionally)
7. Update umbrella `docs/AAOSP_ARCHITECTURE.md` repo inventory

### 6.3 Write-intent / destructive tool

- Manifest: `android:mcpRequiresConfirmation="true"` on the `<tool>` element
- Service: handle the case where runtime Android permission is missing. Return `{"error":"needs_permission","permission":"<perm>","package":"<pkg>"}` JSON so the launcher's `PermissionRequiredCard` can fire the "Open settings" CTA
- `HitlConsentStore` automatically downgrades `SCOPE_FOREVER` grants to `SESSION` for write-intent tools — don't work around this

### 6.4 Bumping the baked-in model

Model swaps are multi-file:
- `aaosp_platform_build/target/product/handheld_system_ext.mk` — GGUF filename
- `LlmModelConfig.java` in `platform_frameworks_base` — any default const
- `AAOSP/README.md` — tier table and prose references to the model version/size
- `platform_external_llamacpp/scripts/download_model.sh` — tier filename
- `docs/CHANGELOG.md` — note the swap in the next version's entry

### 6.5 Tagging a release

1. **Pre-tag doc staleness grep.** Before updating CHANGELOG or cutting the tag, sweep for documentation that describes the *old* behavior of anything this release changed. Items shipped and docs untouched are the failure mode that produced the v0.5.1 follow-up cleanup. Run at least:

   ```bash
   # In the umbrella repo
   grep -rnE 'not yet wired|not yet built|designed in .docs/|TODO|scaffolded' README.md docs/ AGENTS.md
   # Any hit is suspect — fixed in the release? Strike/update. Still true? Fine.

   # Repo-specific: grep for any API / path / constant this release moved
   # (examples from v0.5.1 — adapt per release)
   grep -rnE '10 ?s|35 ?s|\bstartActivity\b|\bPermissionRequestActivity\b' README.md docs/
   grep -rnE '2048|0\.5B|qwen2\.5-0\.5b' .    # model/ctx drift from the v0.5 3B swap
   ```

   Any hit that still describes pre-release behavior must be updated *in the same tag commit*. Cross-repo: run the same grep in `platform_frameworks_base`, `platform_packages_apps_*`, and any MCP repo whose surface changed.

2. Update `docs/CHANGELOG.md` with a new section; the date must be the date you're actually tagging, not your guess. Graduate the `## Unreleased` block into `## vX.Y — <title> (<YYYY-MM-DD>)` and reset a fresh empty `## Unreleased`.
3. **Pre-tag ROADMAP graduation.** Walk `docs/ROADMAP.md` and for each item ask: did this release close any part of it? If yes, update the entry — either remove it (clean close), mark **✅ shipped in vX.Y** with the remaining-work delta, or collapse duplicate entries that the release revealed to be the same fix. User spotted the v0.5.1 version of this omission; don't repeat it.
4. Push the commit (docs + CHANGELOG + ROADMAP all updated).
5. `git tag -a vX.Y -m "..."` **after** the commit is on `main`.
6. `git push --tags`.
7. Verify: `gh api repos/rufolangus/<repo>/git/refs/tags --jq '.[].ref'`.
8. Cross-repo version bumps land together — don't tag the umbrella `v0.6` if `platform_frameworks_base` still has v0.5 code.

### 6.6 Changing the HITL threading model

**Load-bearing invariants. Do not break these:**

- `ConsentGate` uses `CountDownLatch(1)` + `AtomicReference` for race-safety between the dispatcher thread parking on consent and the user's response thread arriving. Replace carefully or not at all.
- `CONSENT_TIMEOUT_MS = 60_000L` — auto-DENY on timeout. Don't lower this below the user's realistic response window.
- `endSession(sessionId)` must both clear `SCOPE_SESSION` grants **and** wake any parked gates (via `cancel()`). Missing either leaks threads or grants.
- Dispatcher thread must fire `STATUS_STARTED` *before* the consent gate parks, so the launcher's tool-call card appears immediately instead of after the user taps Allow.

### 6.7 Changing the temperature logic

Two passes, two temperatures:
- Tool-call pass: `min(request.temperature, 0.25f)` — `<tool_call>` JSON must be deterministic
- Answer pass: caller-controlled, default `0.7f`

If you "helpfully" unify them, Qwen at 0.7 drifts on tool arguments (names, numbers, email addresses) and you get hallucinated tool args. Leave the split alone.

---

## 7. AOSP landmines (earned the hard way)

- `<tool>` / `<input>` `android:description` is `format="reference"` only — **inline strings are rejected by aapt2**. Use `@string/...`.
- `<input>` required flag is `android:mcpRequired`, **not** `android:required`.
- SQLite forbids expressions inside `PRIMARY KEY`. Using `COALESCE(session_id, '')` in a PK crashes the DB at `onCreate`. Normalize nullable cols to `""` at write/delete time and keep `NOT NULL` in the schema.
- `adb push` to `/system` hits read-only. Need `adb disable-verity` → `adb reboot` → `adb remount` first. On FDE builds, this is a real detour.
- At sampling temperature `0.1` with few-shot examples that include *trailing prose*, the model learns to skip the tool call and jump to a fabricated success line. Fix: truncate each example at the `<tool_call>` closing tag, and floor temperature at `0.25`.
- Cuttlefish `m systemimage` without rebuilding `boot.img`/`vbmeta.img` → dm-verity recovery loop. Always `m -j32`.
- Android 15 BAL (background-activity-launch) silently blocks in-app permission dialogs launched from a bound-service context — without throwing. Do not assume a `startActivity` from `invokeTool` will render; return `needs_permission` JSON and let the launcher proxy the request.
- The AIDL for write-tool `confirmToolCall(sessionId, toolName, decision, scope)` is *asynchronous wake-up*, not request/response — the parked dispatcher thread wakes via `ConsentGate`, not via AIDL return value.

---

## 8. Verification checklist

Before saying "done," run through:

- [ ] Every file path I cited exists on the stated branch (`gh api .../contents/<path>?ref=<branch>`)
- [ ] Every class/method/field I named shows up in the file
- [ ] Every constant I quoted matches the value in code
- [ ] Every version tag I cited returns from `gh api .../git/refs/tags`
- [ ] Every date I wrote matches `git` (tagger date or commit date)
- [ ] Every "verified on X" claim has a demo/log/video behind it
- [ ] Any benchmark has a device; if Cuttlefish, say so
- [ ] Docs I changed don't contradict docs I didn't change (README vs ARCHITECTURE vs CHANGELOG)

---

## 9. Commit and push conventions

Observed style from git log; follow it:

- Commit subject: `<area>: <imperative present-tense summary>`
  - Good: `docs: fix fabrications — fork framing, phantom v0.2.0 refs`
  - Good: `ILlmService: update revokeToolGrant javadoc`
  - Good: `v0.5: HITL consent, agentic chaining, 3B model, two MCPs, launch_app built-in`
  - Bad: `Updated README`, `Fixed stuff`, `WIP`
- Body (if used): explain *why*, list the concrete changes. Reference commit shas, not tag names that may not exist yet.
- One commit per concern. Don't mash "fix bug + refactor + rename" into one commit.
- Trailer: `Co-Authored-By: <model name> <email>` for AI-assisted commits.
- Push: directly to the repo's default branch (`main` / `aaosp-v15` / `aaosp`). No PR gate on this project today.

Do not force-push shared branches. Do not rebase already-pushed commits.

### Pre-commit gate

Before `git commit` on any change that touches code or surfaces, ask yourself — and answer honestly — all four:

1. **Does this change observable behavior?** → CHANGELOG `## Unreleased` entry in this commit (§6.1a).
2. **Is this a design change per §6.1b?** → run §6.1b's self-verification checklist in this commit.
3. **Did I touch anything a README, ARCHITECTURE, or DESIGN_NOTES line currently references?** → grep those docs for the symbol/path/constant, update them in this commit.
4. **Did I add a `TODO` / `FIXME` / placeholder?** → mention it in the CHANGELOG entry so it doesn't vanish.

If any answer is "yes" and you haven't addressed it, your commit is not ready. Amend the staged changes; don't split the doc update to "next commit."

---

## 10. Session start protocol

When a new session begins, before citing anything from memory:

1. `gh api repos/rufolangus/AAOSP/git/refs/tags --jq '.[].ref'` — what's the current tag set?
2. `gh api "repos/rufolangus/AAOSP/commits?sha=main&per_page=10" --jq '.[]|"\(.commit.author.date) \(.sha[0:7]) \(.commit.message|split("\n")[0])"'` — what's happened since the last session?
3. Compare to the AAOSP entry in your memory. If tags / recent commits are newer than memory, update memory after the session.
4. If the user refers to "the latest," "v0.X," or "what we shipped," confirm via `gh` before answering.

Memory on this project decays quickly because the project moves fast (4 days, 3 tags). Trust git, not memory.

---

## 11. When in doubt

- **Can't verify a claim?** Write "unverified" or ask the user. Don't guess.
- **Docs and code disagree?** Code wins. Fix the docs. Don't silently change the code to match stale docs.
- **About to push to `main`?** If the change is hard to revert (deleting files, rewriting history, modifying shared infra), confirm with the user first. If it's a local text edit with a clear diff, go ahead.
- **Found a fabrication in your own prose?** Flag it to the user explicitly. Don't quietly rewrite.
- **User says "do X"** and X is unsafe (force-push, destructive rm, skip hooks)? Ask before acting.

---

*Last revised: 2026-04-14. If you changed AAOSP in a way that invalidates a rule above, update this file in the same commit.*
