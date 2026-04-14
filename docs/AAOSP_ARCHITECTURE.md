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

All repos branch from AOSP `android-15.0.0_r1` unless noted. Repos marked
**(fork)** have a GitHub fork; the rest are committed locally pending fork
creation.

| Repo | Branch | Key changes |
|------|--------|-------------|
| `frameworks/base` | `aaosp-v15` | `LlmManagerService` (runChain agentic loop, ConsentGate, audit wiring, built-in `launch_app` synthesis, `dumpsys llm`), `HitlConsentStore` (consent + audit SQLite), `LlmSessionStore` (schema-only), `McpRegistry`, `McpManifestParser`, `McpPackageHandler`, `ParsingPackageUtils` (`<mcp-server>` skip), `ManifestFixer.cpp` (aapt2 whitelist), `BIND_LLM_MCP_SERVICE` permission, `proguard.flags` (`-keep` JNI callbacks), `ILlmService.aidl` (submit/cancel + v0.5: `confirmToolCall`, `revokeToolGrant`, `getRecentAuditCalls`, `endSession`), `LlmRequest` (v0.5: `sessionId`, `maxToolCalls`), `McpToolCallInfo` (v0.5: `iterationIndex`, `STATUS_PERMISSION_REQUIRED`), `McpServerInfo`/`McpToolInfo`/`McpResourceInfo`/`McpInputInfo` parcelables, `attrs_manifest_mcp.xml` (`mcpRequiresConfirmation`), `public-staging.xml` (promote `mcpRequiresConfirmation`/`mcpRequired` to public), `lint-baseline.txt` (FlaggedApi suppression for promoted attrs), `core/api/current.txt` (stub surface for the promoted attrs) |
| `external/llama.cpp` **(fork)** | `main` | `Android.bp` for `libllama` + `libllm_jni`; `jni/llm_jni.cpp`; `ggml-cpu-impl.c` rename to dodge dup-basename; b4547 baseline |
| `packages/apps/AgenticLauncher` **(fork)** | `main` | Compose-based chat surface; binds `ILlmService`; reads `McpRegistry` via `McpPackageHandler.getRegistry()`; `privapp-permissions-agenticlauncher.xml` allowlists `SUBMIT_LLM_REQUEST`/`QUERY_ALL_PACKAGES` |
| `packages/apps/AgenticLauncher` **(fork)** | `main` | v0.5: `ConsentPromptCard` (4-button HITL), `PermissionRequiredCard` (app-details fallback), `ChatMessageBubble` + `messages` list (conversation history that accumulates instead of overwriting), `handleBuiltinLaunchApp` (fuzzy-match + `startActivity` for the framework's `launch_app`), `PendingConsent`/`PendingPermission` UI state, `confirmToolCall` + `endSession` reflection passthrough, session continuity via `activeSessionId` |
| `packages/apps/ContactsMcp` | n/a (in-tree) | First reference MCP provider. v0.5: `add_contact`, `update_contact` write tools (`mcpRequiresConfirmation="true"`), `PermissionRequestActivity` (translucent host for `requestPermissions`), `WRITE_CONTACTS` uses-permission declared but **not** default-granted — first invocation triggers either the in-app permission dialog or the launcher's *Open settings* CTA |
| `packages/apps/CalendarMcp` | n/a (in-tree) | Second reference MCP provider, proving multi-MCP + cross-MCP chaining works. Tools: `list_events`, `find_free_time`, `create_event` (with `mcpRequiresConfirmation`). Natural-language time parser (`"tomorrow 7pm"`). Same two-layer consent + runtime-perm dance as ContactsMcp. |
| `system/sepolicy` | aaosp branch | `private/service_contexts` adds `llm` service entry; `service_fuzzer_bindings.go` exception |
| `device/google/cuttlefish` | aaosp branch | `vsoc_x86_64/phone/aosp_cf.mk`: relaxed artifact path requirements; product partition tweaks |
| `build/make` | aaosp branch | `target/product/handheld_system_ext.mk`: `PRODUCT_PACKAGES += AgenticLauncher ContactsMcp`; copies privapp xml |

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
