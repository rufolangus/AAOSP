# AAOSP — Agentic Android Open Source Project

A fork of Android 15 (AOSP `trunk_staging`) that integrates a Model Context
Protocol (MCP) host and an on-device LLM **as native OS services**, so that
any third-party app can declare tools and resources in its manifest and have
them discovered and invokable by the system LLM.

## Vision

Today, MCP runs in app processes and talks to remote LLMs. AAOSP moves both
sides into the platform:

- **`LlmManagerService`** runs inside `system_server` and exposes a binder
  API (`android.llm.ILlmService`) backed by a JNI bridge to llama.cpp
  (Qwen 2.5 0.5B as the bring-up model).
- **`McpRegistry`** is a system-wide registry of MCP servers contributed by
  installed apps via `<mcp-server>` declarations in `AndroidManifest.xml`.
- The launcher (`AgenticLauncher`) is the user-facing chat surface; a
  reference MCP-providing app (`ContactsMcp`) demonstrates the contract.

## Architecture

```
                       ┌────────────────────────────────────────┐
                       │             system_server              │
                       │                                        │
   AgenticLauncher ──► │  LlmManagerService (ILlmService)       │
                       │   ├─ NativeTokenCallback (R8 -keep)    │
                       │   ├─ JNI: libllm_jni / llama.cpp       │
                       │   ├─ runChain(): agentic loop          │
                       │   │   (≤ maxToolCalls iterations,      │
                       │   │    tool-call temp → answer temp)   │
                       │   ├─ ConsentGate (AtomicReference)     │
                       │   │   parks dispatcher on HITL prompt  │
                       │   ├─ HitlConsentStore (SQLite)         │
                       │   │   /data/system/llm/llm_consent.db  │
                       │   │   tables: consent_grants,          │
                       │   │           audit_calls              │
                       │   │   sig-hash invalidation on upgrade │
                       │   ├─ LlmSessionStore (SQLite)          │
                       │   │   conversation history persistence │
                       │   ├─ Built-in tools (no MCP required)  │
                       │   │   launch_app — fuzzy app launch;   │
                       │   │     dispatcher fires events,       │
                       │   │     launcher does startActivity    │
                       │   └─ discoverMcpServices()             │
                       │         │                              │
                       │         ▼                              │
                       │  McpRegistry / McpPackageHandler       │
                       │   ├─ McpManifestParser                 │
                       │   │   parses <mcp-server>/<tool>/      │
                       │   │   <input>/<resource> from APK      │
                       │   │   (incl. mcpRequiresConfirmation)  │
                       │   └─ keyed by package name             │
                       └────────────────────────────────────────┘
                                    │
                                    ▼ binder
              ┌─────────────────────┴─────────────────────┐
              │                                           │
   ┌──────────────────────────────┐   ┌──────────────────────────────┐
   │  ContactsMcpService          │   │  CalendarMcpService          │
   │  (system_ext app)            │   │  (system_ext app)            │
   │  permission = BIND_LLM_MCP_  │   │  permission = BIND_LLM_MCP_  │
   │                   SERVICE    │   │                   SERVICE    │
   │  read  : search_contacts,    │   │  read  : list_events,        │
   │          get_contact,        │   │          find_free_time      │
   │          list_favorites      │   │  write : create_event        │
   │  write : add_contact,        │   │          (mcpRequires-       │
   │          update_contact      │   │           Confirmation)      │
   │          (mcpRequires-       │   │                              │
   │           Confirmation)      │   │  PermissionRequestActivity   │
   │                              │   │    (same pattern, for        │
   │  PermissionRequestActivity   │   │     WRITE_CALENDAR)          │
   │    translucent; hosts        │   │                              │
   │    requestPermissions when   │   └──────────────────────────────┘
   │    the tool needs a runtime  │
   │    perm the service doesn't  │          (adding a new MCP app
   │    yet hold.                 │           = one manifest block +
   └──────────────────────────────┘           a Service; framework
                                              picks it up at boot)

  llama.cpp (libllm_jni.so) ◄─── JNI ── LlmManagerService
  Qwen 2.5 3B (.gguf)       ◄── mmap ── llama.cpp
```

### Tool dispatch flow

Every tool invocation runs through the same pipeline:

1. **Route** — `LlmManagerService` resolves the tool name to an
   `(owningPackage, service)` tuple from the registry. The reserved
   package `"android"` marks framework-synthesized built-in tools
   (currently just `launch_app`), which bypass binding and fire events
   the launcher reacts to directly.
2. **HITL tool consent** — tools declared with
   `mcpRequiresConfirmation="true"` emit
   `McpToolCallInfo.STATUS_PERMISSION_REQUIRED`. The dispatcher parks
   on a `ConsentGate` (60 s timeout, woken by user response,
   `cancel`, or `endSession`). The launcher shows a 4-button card
   — Once / This chat / Always / Deny. Scopes persist in
   `consent_grants`; write-intent tools have FOREVER auto-downgraded
   to SESSION. Signature-hash mismatch (app upgrade) invalidates
   grants on next lookup.
3. **Android runtime permission** — the MCP service may still lack a
   dangerous permission it needs to fulfil the tool. It tries to host
   the system permission dialog itself via a translucent
   `PermissionRequestActivity`. If Android 15 BAL blocks the activity
   start, the service returns a structured
   `{"error":"needs_permission",...}` and the launcher's
   `PermissionRequiredCard` offers a one-tap fallback to the app's
   details-settings page.
4. **Invoke + audit** — on allow, bind to `IMcpToolProvider`, run
   the tool, fire `STATUS_COMPLETED`, append a row to `audit_calls`
   (args, result, status, consent decision, duration, iter index).

Chaining: `runChain()` loops up to `LlmRequest.maxToolCalls` (default
5, hard cap 8). Each iteration generates at a low tool-call
temperature for deterministic JSON; a final pass runs at the caller's
answer temperature for the natural-language response. Unknown-tool
calls in two consecutive iterations short-circuit the loop.

Session continuity: `LlmRequest.sessionId` threads multi-turn
conversations; the launcher's *New chat* calls
`ILlmService.endSession(oldId)` to clear SESSION-scoped grants and
wake any parked gates. Audit is surfaced via
`ILlmService.getRecentAuditCalls(limit)` or `dumpsys llm`.

## Manifest contract

An MCP-providing app declares both an Android `<service>` (the binder
endpoint) and an `<mcp-server>` (the metadata the registry parses):

```xml
<application>
  <service
      android:name=".MyMcpService"
      android:permission="android.permission.BIND_LLM_MCP_SERVICE"
      android:exported="true" />

  <mcp-server
      android:name=".MyMcpService"
      android:description="@string/desc"
      android:permission="android.permission.BIND_LLM_MCP_SERVICE">
    <tool android:name="my_tool" android:description="@string/tool_desc">
      <input android:name="arg" android:description="@string/arg_desc" />
    </tool>
    <resource android:name="thing"
              android:mcpUri="content://com.example/things"
              android:mimeType="application/json" />
  </mcp-server>
</application>
```

`BIND_LLM_MCP_SERVICE` is declared in framework with
`protectionLevel="signature|privileged"` — only platform-signed or
privileged (`/system/{,_ext}/priv-app/`) apps can host MCP services.

## Repository inventory

Two repos are GitHub forks of AOSP (`platform_frameworks_base`, `aaosp_platform_build`) rebased on `android-15.0.0_r1`. The rest are **new repos** created for AAOSP — they sit at AOSP tree paths but have no upstream ancestor on GitHub. The `sepolicy` and `cuttlefish` repos are **orphan-root snapshots** — their build-VM checkouts are repo-manifest shallow clones, and pushing with AOSP ancestry would require unshallowing hundreds of MB of upstream history for a handful-of-line delta. Each hosts an `AAOSP_OVERLAY.md` at its root documenting the AOSP base commit + the exact files AAOSP touched.

| Repo | AOSP tree path | Branch | Relation | Key changes |
|------|---------------|--------|----------|-------------|
| [`platform_frameworks_base`](https://github.com/rufolangus/platform_frameworks_base) | `frameworks/base` | `aaosp-v15` | fork of `aosp-mirror/platform_frameworks_base` | `LlmManagerService` (runChain agentic loop, ConsentGate, audit wiring, built-in `launch_app` synthesis, `dumpsys llm`), `HitlConsentStore` (consent + audit SQLite), `LlmSessionStore` (schema-only), `McpRegistry`, `McpManifestParser`, `McpPackageHandler`, `ParsingPackageUtils` (`<mcp-server>` skip), `ManifestFixer.cpp` (aapt2 whitelist), `BIND_LLM_MCP_SERVICE` permission, `proguard.flags` (`-keep` JNI callbacks), `ILlmService.aidl` (submit/cancel + v0.5: `confirmToolCall`, `revokeToolGrant`, `getRecentAuditCalls`, `endSession`), `LlmRequest` (v0.5: `sessionId`, `maxToolCalls`), `McpToolCallInfo` (v0.5: `iterationIndex`, `STATUS_PERMISSION_REQUIRED`), `McpServerInfo`/`McpToolInfo`/`McpResourceInfo`/`McpInputInfo` parcelables, `attrs_manifest_mcp.xml` (`mcpRequiresConfirmation`), `public-staging.xml` (promote `mcpRequiresConfirmation`/`mcpRequired` to public), `lint-baseline.txt` (FlaggedApi suppression for promoted attrs), `core/api/current.txt` (stub surface for the promoted attrs) |
| [`aaosp_platform_build`](https://github.com/rufolangus/aaosp_platform_build) | `build/make` | `aaosp` | fork of `aosp-mirror/platform_build` | `target/product/handheld_system_ext.mk`: bakes `libllm_jni.so` into `/system/lib64`, Qwen GGUF into `/product/etc/llm`, `default-permissions-aaosp.xml` into `/system_ext/etc/default-permissions`; `PRODUCT_PACKAGES += AgenticLauncher ContactsMcp CalendarMcp` + privapp xml install |
| [`platform_external_llamacpp`](https://github.com/rufolangus/platform_external_llamacpp) | `external/llama.cpp` | `main` | **new repo — build-glue only** (not a fork; no llama.cpp sources checked in — `src/` is pulled at build time via `scripts/sync_upstream.sh`, baseline b4547) | `Android.bp` for `libllama` + `libllm_jni` (installs to `/system/lib64`); `jni/llm_jni.cpp`; `scripts/sync_upstream.sh`; `scripts/download_model.sh` |
| [`platform_packages_apps_AgenticLauncher`](https://github.com/rufolangus/platform_packages_apps_AgenticLauncher) | `packages/apps/AgenticLauncher` | `main` | **new repo** | Compose chat surface; binds `ILlmService`; reads `McpRegistry` via `McpPackageHandler.getRegistry()`; `privapp-permissions-agenticlauncher.xml` allowlists `SUBMIT_LLM_REQUEST`/`QUERY_ALL_PACKAGES`. v0.5: `ConsentPromptCard` (4-button HITL), `PermissionRequiredCard` (app-details fallback), `ChatMessageBubble` + `messages` list (accumulating history), `handleBuiltinLaunchApp` (fuzzy-match + `startActivity` for the framework's `launch_app`), `PendingConsent`/`PendingPermission` UI state, `confirmToolCall` + `endSession` reflection passthrough, session continuity via `activeSessionId` |
| [`platform_packages_apps_ContactsMcp`](https://github.com/rufolangus/platform_packages_apps_ContactsMcp) | `packages/apps/ContactsMcp` | `main` | **new repo** | First reference MCP provider. v0.5: `add_contact`, `update_contact` write tools (`mcpRequiresConfirmation="true"`), `PermissionRequestActivity` (translucent host for `requestPermissions`), `WRITE_CONTACTS` uses-permission declared but **not** default-granted — first invocation triggers either the in-app permission dialog or the launcher's *Open settings* CTA |
| [`platform_packages_apps_CalendarMcp`](https://github.com/rufolangus/platform_packages_apps_CalendarMcp) | `packages/apps/CalendarMcp` | `main` | **new repo** | Second reference MCP provider, proving multi-MCP + cross-MCP chaining. Tools: `list_events`, `find_free_time`, `create_event` (with `mcpRequiresConfirmation`). Natural-language time parser (`"tomorrow 7pm"`). Same two-layer consent + runtime-perm dance as ContactsMcp. |
| [`aaosp_system_sepolicy`](https://github.com/rufolangus/aaosp_system_sepolicy) | `system/sepolicy` | `aaosp` | **new repo, orphan-root snapshot** — build VM shallow clone prevented ancestry push | `private/service_contexts` + `prebuilts/api/202404/private/service_contexts`: register `llm` service; `build/soong/service_fuzzer_bindings.go`: `EXCEPTION_NO_FUZZER` for `llm` |
| [`aaosp_device_google_cuttlefish`](https://github.com/rufolangus/aaosp_device_google_cuttlefish) | `device/google/cuttlefish` | `aaosp` | **new repo, orphan-root snapshot** — same shallow-clone topology | `shared/device.mk` + `vsoc_x86_64/phone/aosp_cf.mk`: relaxed artifact path requirements for AAOSP system image boot |

## Build & run

```bash
# One-time
source build/envsetup.sh
lunch aosp_cf_x86_64_phone-trunk_staging-userdebug

# Build (full image — incremental systemimage alone is a trap)
m -j32

# Boot
launch_cvd --daemon \
  --gpu_mode=guest_swiftshader --start_webrtc=true \
  --cpus=8 --memory_mb=8192 \
  --extra_kernel_cmdline='androidboot.selinux=permissive'

# Verify (no side-load needed — libllm_jni.so ships in /system/lib64,
# GGUF ships in /product/etc/llm/)
adb shell service list | grep llm
adb shell dumpsys llm
adb shell logcat -d -s LlmManagerService LlmJNI McpManifestParser
```

For incremental iteration on `system_server` code without a full
system-image rebuild, see [`CHANGELOG.md`](./CHANGELOG.md) v0.5 for
the `adb disable-verity` → `remount` → push-`services.jar` sequence
that works on Cuttlefish.

## Adding a new MCP-providing app

1. Drop the APK in `packages/apps/YourMcpApp/` with the two-tag manifest
   pattern shown above.
2. Add to `build/make/target/product/handheld_system_ext.mk`:
   `PRODUCT_PACKAGES += YourMcpApp`.
3. Sign as a platform/system_ext app (placed under `/system_ext/priv-app/`).
4. On boot, `LlmManagerService.discoverMcpServices()` will pick it up at
   `PHASE_BOOT_COMPLETED` and register tools in `McpRegistry`.

## What's shipped / what's next

- **Shipped milestones** — see [`CHANGELOG.md`](./CHANGELOG.md).
- **Planned work + known gaps** — see [`ROADMAP.md`](./ROADMAP.md).
