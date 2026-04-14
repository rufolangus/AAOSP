# AAOSP — architecture & platform notes

Technical companion to the [umbrella README](../README.md).

The README is the front door: vision, demo, manifest contract example, build
instructions, status, and contributing guide. **This doc is the technical truth
of record** — file-by-file changes, critical bring-up fixes, Android-15 debug
trail, and current state of what is live vs. what's wired but not consumed yet.

When in doubt about *what AAOSP is*, read the README. When in doubt about
*how a specific piece was made to work*, read this.

## Demo

[![AAOSP demo — "what's John's number?"](https://cdn.loom.com/sessions/thumbnails/7493fe15dee9463c9626c170e1e44e92-ed91244c8ecd4aeb.gif)](https://www.loom.com/share/7493fe15dee9463c9626c170e1e44e92)

*Launcher answering "what's John's number?" — verified end-to-end on Cuttlefish 2026-04-13 (`v0.3.0`). The card under the answer shows the Contacts app icon + `search_contacts · 240 ms` — the new typed-event attribution surface.*

## Component map (inside `system_server`)

```
 LlmManagerService (binder: android.llm.ILlmService)
  ├── static { System.loadLibrary("llm_jni") }
  │     fallback: System.load("/data/local/llm/libllm_jni.so")
  ├── onBootPhase(PHASE_SYSTEM_SERVICES_READY=500)
  │     └─ loadModel()  → nativeLoadModel() → llama.cpp
  ├── onBootPhase(PHASE_BOOT_COMPLETED=1000)
  │     └─ discoverMcpServices()
  │         ├─ pm.getInstalledPackages(GET_SERVICES | MATCH_DISABLED_COMPONENTS
  │         │                          | MATCH_DIRECT_BOOT_AWARE
  │         │                          | MATCH_DIRECT_BOOT_UNAWARE)
  │         └─ parseManifestMcpServers(pkg)
  │             └─ AssetManager.openXmlResourceParser("AndroidManifest.xml")
  │                 └─ McpManifestParser.parseMcpServer(res, parser, pkgName)
  │                     → registers in McpRegistry (via McpPackageHandler)
  └── submit(LlmRequest, ILlmResponseCallback)
        ├─ buildPrompt() → injects <tools> block from McpRegistry
        ├─ nativeGenerate() → Qwen tokens → NativeTokenCallback (buffers)
        ├─ maybeExecuteToolCall(rawOutput)
        │     └─ parses <tool_call>{name,arguments}</tool_call>
        │     └─ invokeMcpTool(pkgName, svcName, toolName, argsJson)
        │         └─ bindService() → IMcpToolProvider.invokeTool() (10s timeout)
        └─ callback.onComplete(clean text)
```

Key classes, all in `frameworks/base`:
- `services/core/java/com/android/server/llm/LlmManagerService.java` — the orchestrator
- `services/core/java/com/android/server/pm/McpManifestParser.java` — XML → `McpServerInfo`
- `services/core/java/com/android/server/pm/McpRegistry.java` + `McpPackageHandler.java` — system-wide registry (singleton)
- `core/java/android/llm/ILlmService.aidl` + `IMcpToolProvider.aidl` — binder contracts
- `core/java/android/content/pm/mcp/{McpServerInfo,McpToolInfo,McpInputInfo,McpResourceInfo}.java` — parcelables

## Repository inventory

| Path | Repo | Branch | v0.1.0 | Key changes |
|------|------|--------|--------|-------------|
| `frameworks/base` | [rufolangus/platform_frameworks_base](https://github.com/rufolangus/platform_frameworks_base) | `aaosp-v15` | ✅ | `LlmManagerService`, `McpRegistry`, `McpManifestParser`, `McpPackageHandler`, `ParsingPackageUtils` (`<mcp-server>` skip), `ManifestFixer.cpp` (aapt2 whitelist), `BIND_LLM_MCP_SERVICE` permission, `proguard.flags` (`-keep` JNI callbacks), `ILlmService.aidl`, `IMcpToolProvider.aidl`, MCP parcelables |
| `external/llama.cpp` | [rufolangus/platform_external_llamacpp](https://github.com/rufolangus/platform_external_llamacpp) | `main` | ✅ | `Android.bp` for `libllama` + `libllm_jni`; `jni/llm_jni.cpp`; `ggml-cpu-impl.c` rename to dodge dup-basename; b4547 baseline |
| `packages/apps/AgenticLauncher` | [rufolangus/platform_packages_apps_AgenticLauncher](https://github.com/rufolangus/platform_packages_apps_AgenticLauncher) | `main` | ✅ | Compose chat surface; binds `ILlmService`; `privapp-permissions-agenticlauncher.xml` allowlists `SUBMIT_LLM_REQUEST` / `QUERY_ALL_PACKAGES` |
| `packages/apps/ContactsMcp` | [rufolangus/platform_packages_apps_ContactsMcp](https://github.com/rufolangus/platform_packages_apps_ContactsMcp) | `main` | ✅ | Reference MCP provider with three tools (`search_contacts`, `get_contact`, `list_favorites`) |
| `build/make` | [rufolangus/aaosp_platform_build](https://github.com/rufolangus/aaosp_platform_build) | `aaosp` | ✅ | `handheld_system_ext.mk`: `PRODUCT_PACKAGES += AgenticLauncher ContactsMcp`; privapp xml install |
| `system/sepolicy` | [rufolangus/aaosp_system_sepolicy](https://github.com/rufolangus/aaosp_system_sepolicy) | *(empty fork)* | ⚠️ | `llm` in `service_contexts`, fuzzer exception. First-push hit github pack-size limit; needs `git gc --aggressive` retry or orphan-branch snapshot. |
| `device/google/cuttlefish` | [rufolangus/aaosp_device_google_cuttlefish](https://github.com/rufolangus/aaosp_device_google_cuttlefish) | *(empty fork)* | ⚠️ | Relaxed artifact-path requirements + product partition tweaks. Same pack-size issue as sepolicy. |

## Critical bring-up fixes

These took multiple debugging cycles. Recording them so we (and contributors) don't repeat the dance.

### Compile / package layer

1. **R8 stripped JNI callback methods** — added `-keep class com.android.server.llm.LlmManagerService$NativeTokenCallback { *; }` in `frameworks/base/services/proguard.flags`. The class is also `public` with `public` methods so the keep matches.
2. **aapt2 rejected `<mcp-server>`** — patched `tools/aapt2/link/ManifestFixer.cpp` to whitelist `<mcp-server>` and its `<tool>` / `<input>` / `<resource>` children under `<application>`.
3. **PMS dropped `<service>` after `<mcp-server>`** — patched `ParsingPackageUtils.java` so the `<mcp-server>` case calls `XmlUtils.skipCurrentTag(parser)` and returns success, not consuming sibling tags. Applied at **both** dispatch sites in that file (two switch blocks).

### Runtime / binder layer

4. **`BIND_LLM_MCP_SERVICE` was `signature`-only** — privileged system_ext apps can't be platform-signed in a userdebug build; bumped to `signature|privileged`.
5. **MCP discovery ran before PMS finished scanning** — moved `discoverMcpServices()` from `PHASE_SYSTEM_SERVICES_READY` (500) to `PHASE_BOOT_COMPLETED` (1000).
6. **`libllm_jni.so` install** (`v0.2.0`) — installed to `/system/lib64` via a Soong rule in `external/llama.cpp/Android.bp` plus `PRODUCT_PACKAGES += libllm_jni` in `aaosp_platform_build`. The Qwen GGUF lands in `/product/etc/llm/` via `PRODUCT_COPY_FILES`. `LlmManagerService.findModel()` searches `/data/local/llm` (dev override) → `/product/etc/llm` (canonical) → `/system/etc/llm` (legacy). The static-block fallback `System.load("/data/local/llm/libllm_jni.so")` is kept for dev builds where the lib hasn't yet been baked; once you have a v0.2.0+ image it's never hit.

### Android-15 quirks (the most non-obvious)

7. **PMS silently drops `<service>` without an `<intent-filter>`.** A service declared with only `android:name` + `android:permission` + `android:exported` **will not appear** in `pm query-services` or `pkg.services` — no error, no warning. Add a no-op filter:
   ```xml
   <service android:name=".X" android:permission="…" android:exported="true">
     <intent-filter>
       <action android:name="android.llm.MCP_SERVICE" />
     </intent-filter>
   </service>
   ```
8. **`pm.getInstalledPackages(GET_SERVICES)` returns `services=null`** for `system_ext` priv-apps unless you also pass `MATCH_DISABLED_COMPONENTS | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE`. Cost hours of Soong-cache debugging before I figured this out.

### Cuttlefish / build-infra quirks

9. **dm-verity hash mismatch on incremental `m systemimage`** — `boot.img` / `vbmeta.img` don't rebuild and the device boots into recovery. Always full `m -j32`.
10. **`adb reboot` on Cuttlefish doesn't actually restart the VM** — guest kernel goes down, crosvm stays up, init never re-runs. Use `stop_cvd && launch_cvd` for a real cycle.
11. **`launch_cvd` wipes `/data`** — lib + model push must come *after* the final `launch_cvd`, not before any subsequent one. Otherwise the new boot crash-loops on `UnsatisfiedLinkError` in `loadModel()` and RescueParty stalls boot.
12. **Soong incremental cache serves stale `framework.jar` / `framework-res.apk`** — even after source edits. Symptom: identical md5 across rebuilds despite confirmed source changes. Workaround: `rm -rf out/soong/.intermediates/<module>` then rebuild.
13. **`adb install -r -d` of a system app creates a `/data/app/` override** that supersedes `/system_ext/priv-app/`. Sideloaded APKs are signed with the dev/debug key, so any `<service>` gated by a signature-protected permission silently fails to register. Use `make` + relaunch instead of sideloading for system-app testing; `adb uninstall <pkg>` reverts to the system version.

## Adding a new MCP-providing app to the AAOSP build

The README's *How Apps Become Agentic* explains the app-developer contract
(manifest + `IMcpToolProvider`). This section is the **platform-integrator**
view: how to get a new MCP-providing app into the actual image so it ships on
boot and `discoverMcpServices()` can find it.

1. **Drop the app source under `packages/apps/YourMcpApp/`** with an
   `Android.bp` that sets `privileged: true`, `system_ext_specific: true`,
   `platform_apis: true`, and `certificate: "platform"`. See
   `packages/apps/ContactsMcp/Android.bp` as the reference.
2. **Add the package to `PRODUCT_PACKAGES`** in the umbrella's
   `build/make/target/product/handheld_system_ext.mk`:
   ```make
   PRODUCT_PACKAGES += YourMcpApp
   ```
3. **If your app needs runtime permissions** (e.g. `READ_CONTACTS` for a
   contacts-style tool), grant them via `default-permissions-aaosp.xml` in
   `/system_ext/etc/default-permissions/` so the user doesn't see a
   permission prompt on first tool call. (This file doesn't exist yet in
   v0.1.0 — listed in README's Contributing section as open work.)
4. **On next boot**, `LlmManagerService.discoverMcpServices()` picks up the
   new `<mcp-server>` at `PHASE_BOOT_COMPLETED` and registers its tools in
   `McpRegistry`. Verify with:
   ```bash
   adb shell logcat -d -s LlmManagerService | grep 'discovery complete'
   # → MCP discovery complete: N package(s), M tool(s)
   ```

No framework rebuild needed for subsequent MCP app additions — only a new
system image with the app included.

## Current wire-up status (what's live vs. designed)

| Layer | Status | Notes |
|---|---|---|
| LLM load + inference | ✅ | Qwen 2.5 0.5B via JNI to llama.cpp, `service llm: found` |
| MCP discovery | ✅ | `1 package(s), 3 tool(s)` from ContactsMcp, verified |
| `<mcp-server>` manifest parsing | ✅ | `McpManifestParser` reads all documented attrs (see README support matrix) |
| Prompt injection (`<tools>` block) | ✅ | Qwen chat template, schema derived from `McpToolInfo` |
| Tool-call dispatch | ✅ | `maybeExecuteToolCall()` → `IMcpToolProvider.invokeTool()` with 10s timeout |
| Response humanization | ⚠️ 1-pass | Current `aaosp-v15` HEAD humanizes tool-result JSON via `humanizeToolResult()`. A 2-pass agentic loop (`buildContinuationPrompt()` feeding `<tool_response>` back to the LLM) was prototyped on the cloud VM but is **not yet pushed** to github. |
| **`/system` bake-in** (`v0.2.0`) | ✅ | `libllm_jni.so` in `/system/lib64`, Qwen GGUF in `/product/etc/llm`. First boot is fully functional with no `adb push`. |
| **Auto-grant runtime perms** (`v0.2.0`) | ✅ | `default-permissions-aaosp.xml` in `/system_ext/etc/default-permissions/` grants `READ_CONTACTS` to ContactsMcp on first boot — no `pm grant`. |
| **Typed tool-call events** (`v0.3.0`) | ✅ | `McpToolCallInfo` parcelable on `ILlmResponseCallback.onToolCall` / `onToolResult`. Fields: sessionId, toolName, packageName, serviceName, argumentsJson, resultJson, status (STARTED/COMPLETED/FAILED/PERMISSION_REQUIRED), durationMillis. Replaces the loose-string callbacks. |
| **Tool-call attribution UI** (`v0.3.0`) | ✅ | Launcher resolves app icon + label from `packageName` and renders an attribution card alongside each tool call. The user always sees which app the LLM is asking. |
| **`ThinkingCard`** (`v0.3.0`) | ✅ | Spinner + "Thinking…" between submit and first event. Chat surface no longer blanks. |
| **System prompt rewrite** (`v0.3.0`) | ✅ | Single `SYSTEM_PROMPT_HEADER` used by both inference passes. Sections: PRIVACY (local processing), GROUNDING (no fabrication), TOOLS (use them, normalize queries), OUTPUT FORMAT, FEW_SHOT EXAMPLES. Designed for Qwen 2.5 0.5B's specific failure modes. |
| **Inference temperature split** (`v0.3.0`) | ✅ | Tool-call pass uses `min(request.temperature, 0.1)` for deterministic JSON; answer pass uses caller's requested temp (default 0.7). |
| HITL consent / confirmation / audit | ❌ designed only | `mcpRequiresConfirmation` is parsed; UI not wired |
| Per-tool permission enforcement | ❌ | `McpToolInfo.permission` is parsed but not checked at dispatch time |
| `LlmSessionStore` (SQLite, tool reliability stats) | ❌ scaffolded | Class exists, no binder integration |
| Session AIDL methods on `ILlmService` | ❌ | Not yet added |
| Tiered model auto-select (0.5B → 1.5B → 3B → 7B by RAM) | ❌ | 0.5B hardcoded |
| `dumpsys llm` | ❌ | Segfaults; needs proper `dump()` impl |

## See also

- **[README → How Apps Become Agentic](../README.md#how-apps-become-agentic)** — manifest contract, `IMcpToolProvider` interface, support matrix, and "what's live vs. stored".
- **[README → Build](../README.md#build)** — `repo init` / `repo sync` / `lunch` / `m -j32` / `launch_cvd`. Canonical build instructions live there; this doc does not duplicate them.
- **[README → Contributing](../README.md#contributing)** — prioritized open work (HITL wiring, tiered model selector, `<mcp-server>` on built-in apps, real-device port).
