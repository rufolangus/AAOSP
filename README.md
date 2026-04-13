# Agentic Android Open Source Project (AAOSP)

**Android's natural evolution for the agentic era.**

MCP (Model Context Protocol) is the standard. Every major AI platform — Claude, GPT, Gemini — speaks it. Every tool, every integration, every workflow is converging on MCP as the universal interface between AI and software.

Android has always been a platform built on protocols. Intents, Content Providers, Broadcast Receivers — these were the right abstractions for the app era. MCP is the right abstraction for the agentic era.

AAOSP makes MCP a first-class citizen in Android. Apps declare tools. The OS runs the model. The user just talks.

[![AAOSP demo — "what's John's number?"](https://cdn.loom.com/sessions/thumbnails/edac9d03682b4413afd2fcc80693275e-0fe3e8877cc50875.gif)](https://www.loom.com/share/edac9d03682b4413afd2fcc80693275e)

> ▶️ **The thumbnail above looks blank — that's a Loom timing quirk, the actual demo is further into the video.** [Click through to watch the full 30-second flow on Loom](https://www.loom.com/share/edac9d03682b4413afd2fcc80693275e). What you'll see: launcher answering "what's John's number?" with Qwen 2.5 0.5B emitting a `<tool_call>` for `search_contacts`, AAOSP dispatching to `ContactsMcp` via `IMcpToolProvider.invokeTool()`, the result round-tripping back through the LLM, and the answer rendering in the chat UI. Verified live on Cuttlefish, 2026-04-12. *(A better-framed recording is in the works.)*

> **⭐ If the demo above made you nod, [star the repo](https://github.com/rufolangus/AAOSP).** It's the cheapest way to signal which AOSP forks are worth tracking and helps the right contributors find this.

## What This Is

An AOSP fork that adds three things:

1. **An LLM that runs as a system service** — like LocationManager or NotificationManager, but for intelligence. Local inference via llama.cpp. No cloud. No API keys. No data leaving the device.

2. **MCP in the manifest** — apps declare their capabilities as MCP tools in `AndroidManifest.xml`, the same way they've always declared intents and permissions. The OS indexes them at install time. The LLM discovers and uses them at runtime.

3. **A launcher that renders, not lists** — instead of an app grid, the launcher shows you what you need. Ask a question, the LLM calls the right tools across your installed apps and renders the answer as interactive UI.

## Why

Every phone has a dozen apps that each do one thing. To send a message, you open the messaging app. To check a calendar event, you open the calendar app. To find a contact, you open the contacts app.

An agent just does it. "Text John I'll be late, and move my 3pm meeting to 4" — one sentence, two apps, zero app switching.

Android already has the pieces. Binder IPC is the fastest inter-process communication on mobile. PackageManager already indexes app capabilities at install time. The manifest already declares what apps can do. AAOSP connects these pieces to an LLM through MCP — the protocol the entire AI industry is standardizing on.

This isn't adding AI to Android. This is Android adapting to the world that already exists.

## Who this is for

The interface is shifting from screens to agents. MCP is the rare case where the protocol is winning before any one vendor has captured the new surface. AAOSP is the substrate that lets each constituency stay first-class:

- **OEMs** ship an LLM-ready OS without betting on their own model.
- **App developers** become reachable by any agent via one manifest block. No SDK.
- **Enterprise software (Square, Toast, Epic, ServiceNow)** expose tools to on-device agents without routing customer data through a third-party model.
- **LLM vendors** compete on model quality, not integration surface area.
- **Users** get one assistant, every app visible to it, data stays local.

## Architecture

```
+----------------------------------------------+
|              Agentic Launcher                 |
|        (Compose, server-driven UI)            |
+-----------------------+----------------------+
                        | Binder (SUBMIT_LLM_REQUEST)
+-----------------------v----------------------+
|            LLM System Service                 |
|  +------------+  +------------------------+  |
|  | Qwen 2.5   |  | MCP Tool Registry      |  |
|  | (llama.cpp)|  | (indexed from manifests)|  |
|  +------------+  +------------------------+  |
|  +------------+  +------------------------+  |
|  | Session    |  | Tool Stats             |  |
|  | Store (DB) |  | (reliability tracking) |  |
|  +------------+  +------------------------+  |
+------+-------------+-------------+-----------+
       | Binder       | Binder      | Binder
       | (BIND_LLM_   | (BIND_LLM_  | (BIND_LLM_
       |  MCP_SERVICE) |  MCP_SERVICE)|  MCP_SERVICE)
+------v------+ +-----v------+ +---v----------+
| Contacts    | | Messaging  | | Calendar     |
| <mcp-server>| | <mcp-server>| | <mcp-server> |
| tools:      | | tools:      | | tools:       |
|  search     | |  send_sms   | |  get_events  |
|  get_contact| |  read_msgs  | |  create_event|
+-------------+ +-------------+ +--------------+
```

## How Apps Become Agentic

Any app can become an MCP server. Add two blocks to your manifest, implement one AIDL interface, done. Full working reference: **[packages/apps/ContactsMcp](https://github.com/rufolangus/platform_packages_apps_ContactsMcp)**.

### 1. Declare tools in your manifest

You need **both** an Android `<service>` (the binder endpoint the OS binds to) **and** an `<mcp-server>` (the metadata the registry parses). The example below is lifted verbatim from the reference `ContactsMcp` app.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.android.contacts.mcp"
    android:versionCode="102"
    android:versionName="1.0.2">

    <uses-permission android:name="android.permission.READ_CONTACTS" />

    <application android:label="@string/app_name">

        <service
            android:name=".ContactsMcpService"
            android:permission="android.permission.BIND_LLM_MCP_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.llm.MCP_SERVICE" />
            </intent-filter>
        </service>

        <mcp-server
            android:name=".ContactsMcpService"
            android:description="@string/mcp_description"
            android:permission="android.permission.BIND_LLM_MCP_SERVICE">

            <tool android:name="search_contacts"
                  android:description="@string/tool_search_desc">
                <input android:name="query"
                       android:description="@string/input_query_desc" />
            </tool>

            <tool android:name="get_contact"
                  android:description="@string/tool_get_desc">
                <input android:name="name"
                       android:description="@string/input_name_desc" />
            </tool>

            <tool android:name="list_favorites"
                  android:description="@string/tool_favorites_desc" />

        </mcp-server>

    </application>
</manifest>
```

> **Gotcha (Android 15):** the `<intent-filter>` on the `<service>` is **required**, even if your service is only bound by component name. PMS silently drops `<service>` declarations without one. Use any no-op action — `android.llm.MCP_SERVICE` is the convention.

### 1b. Full schema

The parser in `frameworks/base/services/core/java/com/android/server/pm/McpManifestParser.java` accepts more than ContactsMcp uses. Here's everything framework-base reads into `McpRegistry` today, shown on a hypothetical `MessagingMcp`:

```xml
<mcp-server
    android:name=".MessagingMcpService"
    android:description="@string/mcp_description"
    android:permission="android.permission.BIND_LLM_MCP_SERVICE"
    android:mcpVersion="2024-11-05">

    <!-- Destructive tool with per-tool permission override and typed,
         required inputs. -->
    <tool android:name="send_message"
          android:description="@string/tool_send_desc"
          android:permission="android.permission.SEND_SMS"
          android:mcpRequiresConfirmation="true">
        <input android:name="recipient"
               android:description="@string/input_recipient_desc"
               android:mcpType="string"
               android:mcpRequired="true" />
        <input android:name="body"
               android:description="@string/input_body_desc"
               android:mcpType="string"
               android:mcpRequired="true" />
        <input android:name="priority"
               android:description="@string/input_priority_desc"
               android:mcpType="string"
               android:mcpEnumValues="low,normal,high" />
    </tool>

    <!-- Read-only tool with a typed-integer input. -->
    <tool android:name="get_recent"
          android:description="@string/tool_recent_desc">
        <input android:name="count"
               android:description="@string/input_count_desc"
               android:mcpType="integer"
               android:mcpRequired="false" />
    </tool>

    <!-- Resource the LLM can read or list via IMcpToolProvider.
         Surfaces as a typed content:// URI the system can hand back. -->
    <resource android:name="recent_messages"
              android:description="@string/resource_recent_desc"
              android:mcpUri="content://com.example.messaging/recent"
              android:mimeType="application/json" />

</mcp-server>
```

### Support matrix (v0.1.0)

| Element / attribute | Parsed into `McpRegistry` | Used in the runtime today |
|---|---|---|
| `<mcp-server>` `name` / `description` / `permission` | ✅ | ✅ (binder gating, prompt injection) |
| `<mcp-server>` `mcpVersion` | ✅ → `McpServerInfo.protocolVersion` | ⚠️ stored, not consumed |
| `<tool>` `name` / `description` | ✅ | ✅ (listed in `<tools>` prompt block) |
| `<tool>` `permission` (per-tool override) | ✅ → `McpToolInfo.permission` | ⚠️ stored, not enforced |
| `<tool>` `mcpRequiresConfirmation` | ✅ → `McpToolInfo.requiresConfirmation` | ⚠️ stored; HITL consent UI is designed, not wired |
| `<input>` `name` / `description` | ✅ | ✅ |
| `<input>` `mcpType` (`string` / `number` / `integer` / `boolean` / `array` / `object`) | ✅ → `McpInputInfo.type` | ⚠️ flattened to `"string"` in the current prompt builder |
| `<input>` `mcpRequired` | ✅ | ✅ (feeds the `required` array of the tool's JSON schema) |
| `<input>` `mcpEnumValues` (comma-separated) | ✅ → `McpInputInfo.enumValues` | ⚠️ stored, not surfaced in the prompt yet |
| `<resource>` `name` / `mcpUri` / `description` / `mimeType` | ✅ → `McpResourceInfo` | ⚠️ stored, not exposed to the LLM (no resources block in the prompt yet) |

### 2. Implement the AIDL interface

```java
package com.android.contacts.mcp;

import android.app.Service;
import android.content.Intent;
import android.llm.IMcpToolProvider;
import android.os.IBinder;
import org.json.JSONObject;

public class ContactsMcpService extends Service {

    private final IMcpToolProvider.Stub mBinder = new IMcpToolProvider.Stub() {
        @Override
        public String invokeTool(String toolName, String argumentsJson) {
            try {
                JSONObject args = new JSONObject(argumentsJson);
                switch (toolName) {
                    case "search_contacts":
                        return searchContacts(args.optString("query"));
                    case "get_contact":
                        return getContact(args.optString("name"));
                    case "list_favorites":
                        return listFavorites();
                    default:
                        return "{\"error\":\"unknown tool: " + toolName + "\"}";
                }
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        }

        @Override
        public String readResource(String resourceName) {
            return "{}"; // not used in this app
        }

        @Override
        public String listResources(String uriPattern) {
            return "[]"; // not used in this app
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    // searchContacts, getContact, listFavorites each return a JSON string.
    // See ContactsMcpService.java for the ContactsContract queries.
}
```

`IMcpToolProvider` lives at `frameworks/base/core/java/android/llm/IMcpToolProvider.aidl` — three methods, all returning `String` (JSON payload is the contract):

```aidl
interface IMcpToolProvider {
    String invokeTool(String toolName, String argumentsJson);
    String readResource(String resourceName);
    String listResources(String uriPattern);
}
```

### 3. That's it

The OS handles everything else (status of each piece as of `v0.1.0`):

- **Discovery** ✅ — `LlmManagerService.discoverMcpServices()` runs at `PHASE_BOOT_COMPLETED`, reads every installed app's manifest, and registers `<mcp-server>` entries into `McpRegistry`.
- **Prompt injection** ✅ — every registered tool is serialized into Qwen's `<tools>…</tools>` chat template block on each inference call.
- **Routing** ✅ — when the LLM emits `<tool_call>{"name":…,"arguments":…}</tool_call>`, the dispatcher looks up the owning package/service from the routing table and binds to it via `IMcpToolProvider`.
- **Execution** ✅ — binder call to `invokeTool()` with a 10s timeout; the JSON result feeds a second LLM pass for natural-language presentation.
- **Consent / confirmation / audit** ⚠️ designed in `docs/`, not yet wired. `mcpRequiresConfirmation="true"` will eventually gate destructive tools behind a user prompt.
- **Reliability stats** ⚠️ scaffolded (`LlmSessionStore`), not yet wired. Future: the system tracks per-tool success rates and deprioritizes unreliable tools.

## The Model

AAOSP ships with **Qwen 2.5**, auto-selected by device capability:

| Device RAM | Model | Disk | Why |
|---|---|---|---|
| 12GB+ | Qwen 2.5 7B Q4_K_M | ~4.4 GB | Best tool-calling accuracy |
| **8GB+** | **Qwen 2.5 3B Q4_K_M** | **~2.0 GB** | **Default — best balance** |
| 4-8GB | Qwen 2.5 1.5B Q4_K_M | ~1.1 GB | Faster, lighter |
| <4GB | Qwen 2.5 0.5B Q8_0 | ~0.5 GB | Basic capability |

Runs entirely on-device via llama.cpp. No network required. Inference on a Snapdragon 8 Gen 3: ~15-20 tokens/sec with the 3B model.

Qwen 2.5 was chosen for its structured output reliability — it consistently generates valid JSON tool calls, which is critical for MCP.

## Permission Model

Apps are tools, not clients. They never talk to the LLM — the LLM talks to them.

| Permission | Level | Who | Purpose |
|---|---|---|---|
| `SUBMIT_LLM_REQUEST` | signature\|privileged | Launcher, SystemUI | Submit prompts, receive responses |
| `BIND_LLM_MCP_SERVICE` | signature | system_server | Bind to app MCP services |
| *(none)* | — | All other apps | Declare `<mcp-server>`, implement AIDL |

Third-party apps cannot submit arbitrary prompts to the LLM. They can only offer tools and wait to be called. This prevents abuse and keeps the user in control through the launcher.

## Human in the Loop

The user is always in control. Three layers of consent, from coarse to fine:

### Layer 1: MCP Server Access (first-use, per-app)

The first time the LLM wants to use any tool from an app, the system shows a consent dialog:

```
┌──────────────────────────────────────┐
│ Allow AI to use Contacts?            │
│                                      │
│ This app's AI tools:                 │
│ * Search contacts                    │
│ * Get contact details                │
│ * List favorites                     │
│                                      │
│ You can change this anytime in       │
│ Settings -> AI -> Tool Access.       │
│                                      │
│     [Don't Allow]      [Allow]       │
└──────────────────────────────────────┘
```

The decision is persisted and revocable in Settings. Same pattern as Android runtime permissions.

### Layer 2: Action Confirmation (per-call, destructive tools)

Tools with `mcpRequiresConfirmation="true"` show what will happen before it happens:

```
┌──────────────────────────────────────┐
│ Send message?                        │
│                                      │
│ To: John Smith                       │
│ Message: "Running late, be there     │
│ in 20 min"                           │
│                                      │
│ [ ] Don't ask again for this action  │
│                                      │
│     [Cancel]          [Send]         │
└──────────────────────────────────────┘
```

The "Don't ask again" checkbox lets the user auto-confirm tools they trust. Auto-confirms are per-tool, per-user, and revocable in Settings. Revoking server access (Layer 1) also clears all auto-confirms for that app.

### Layer 3: Audit Trail (passive, always on)

Every tool call is logged: what tool, what arguments, what result, whether confirmation was required/auto-confirmed, success or failure, latency. Users can review this in Settings under AI activity. Capped at 1000 entries per user.

The flow: **check consent -> prompt if needed -> check confirmation -> prompt or auto-confirm -> execute -> log everything.**

## Project Structure

| Repo | What | Branch |
|---|---|---|
| **[AAOSP](https://github.com/rufolangus/AAOSP)** | Umbrella: `repo` manifests, SELinux, test apps, docs | `main` |
| **[platform_frameworks_base](https://github.com/rufolangus/platform_frameworks_base)** | LLM Service, MCP schema/parser, BIND_LLM_MCP_SERVICE, ParsingPackageUtils + aapt2 patches, AIDL | `aaosp-v15` |
| **[platform_packages_apps_AgenticLauncher](https://github.com/rufolangus/platform_packages_apps_AgenticLauncher)** | Compose launcher, binder client, privapp permission allowlist | `main` |
| **[platform_packages_apps_ContactsMcp](https://github.com/rufolangus/platform_packages_apps_ContactsMcp)** | Reference MCP-providing app (`search_contacts`, `get_contact`, `list_favorites`) | `main` |
| **[platform_external_llamacpp](https://github.com/rufolangus/platform_external_llamacpp)** | llama.cpp Android.bp, `libllm_jni.so` JNI bridge | `main` |
| **[aaosp_platform_build](https://github.com/rufolangus/aaosp_platform_build)** | `PRODUCT_PACKAGES` + privapp xml install for system_ext | `aaosp` |
| **aaosp_system_sepolicy** | `llm` service_contexts + fuzzer exception (fork created, push pending pack-size fix) | `aaosp` |
| **aaosp_device_google_cuttlefish** | Cuttlefish bring-up tweaks (fork created, push pending pack-size fix) | `aaosp` |

For the deep technical view (file-by-file changes, build pitfalls, debugging
gotchas, current state of bring-up), see **[docs/AAOSP_ARCHITECTURE.md](docs/AAOSP_ARCHITECTURE.md)**.

### frameworks/base components

```
core/java/android/llm/              # SDK: LlmManager, LlmRequest, AIDL interfaces
core/java/android/content/pm/mcp/   # MCP parcelables + AIDL
core/res/res/values/                 # attrs_manifest_mcp.xml (manifest schema)
services/core/.../server/llm/        # LlmManagerService, LlmModelConfig, LlmSessionStore
services/core/.../server/pm/         # McpManifestParser, McpRegistry, McpPackageHandler
```

## Build

### Prerequisites
- Cloud VM recommended: 32+ cores, 64GB+ RAM, 500GB+ SSD ([cloud build guide](BUILD_CLOUD.md))
- `repo` tool installed

### Sync

```bash
# Initialize AOSP (Android 15, API 35)
repo init -u https://android.googlesource.com/platform/manifest -b android-15.0.0_r1

# Add AAOSP local manifests
git clone https://github.com/rufolangus/AAOSP.git .repo/local_manifests_src
mkdir -p .repo/local_manifests
cp .repo/local_manifests_src/local_manifests/*.xml .repo/local_manifests/

# Sync (~100GB, 1-2 hours)
repo sync -c -j$(nproc) --no-tags --optimized-fetch

# Shallow clone may miss VNDK v32 — sync it separately
repo sync prebuilts/vndk/v32 -j4
```

### Prepare llama.cpp and model

```bash
# Pull llama.cpp source
cd external/llama.cpp
bash scripts/sync_upstream.sh

# Download Qwen 2.5 (auto-selects tier based on device)
bash scripts/download_model.sh
cd ../..
```

### Build

```bash
source build/envsetup.sh
lunch aosp_cf_x86_64_phone-trunk_staging-userdebug   # Cuttlefish
m -j$(nproc)
```

### Run on Cuttlefish

```bash
launch_cvd

# Push the model to the virtual device
adb shell mkdir -p /data/local/llm
adb push out/target/product/vsoc_x86_64/data/local/llm/*.gguf /data/local/llm/
adb shell restorecon -R /data/local/llm/
```

## Status

Verified end-to-end on Cuttlefish (`aosp_cf_x86_64_phone-trunk_staging-userdebug`):

| Component | Status |
|---|---|
| AOSP build pipeline (`m -j32` → `system.img` → `launch_cvd`) | ✅ Boots |
| `libllm_jni.so` (llama.cpp b4547, x86_64 silvermont) | ✅ Loads in `system_server` |
| Qwen 2.5 0.5B GGUF model load (ctx=2048, vocab=151936) | ✅ Loads |
| `LlmManagerService` binder API (`service llm: found`) | ✅ Live |
| MCP manifest schema (aapt2 whitelist for `<mcp-server>`/`<tool>`/`<input>`/`<resource>`) | ✅ Compiles |
| `ParsingPackageUtils` patch — skip `<mcp-server>` subtree cleanly | ✅ Live |
| `BIND_LLM_MCP_SERVICE` permission `signature\|privileged` | ✅ Live |
| `McpManifestParser` runtime parse from APK | ✅ Registers tools |
| `discoverMcpServices()` at `PHASE_BOOT_COMPLETED` | ✅ `1 package(s), 3 tool(s)` |
| ContactsMcp installs as `/system_ext/priv-app/` with `<service>` registered | ✅ (requires `<intent-filter>` on the service — see Gotchas) |
| Agentic Launcher install | ✅ Installs |
| SELinux `llm` service_contexts | ✅ Live |
| Privapp permission allowlist for `SUBMIT_LLM_REQUEST` | ✅ Live |
| Tool-call loop: prompt injection → Qwen `<tool_call>` → dispatch to `IMcpToolProvider.invokeTool()` → humanized result | ✅ Verified end-to-end with Qwen 2.5 0.5B + ContactsMcp |

Designed and implemented but **not yet wired/verified**:

| Component | Status |
|---|---|
| Human-in-the-loop UI (consent / confirmation / don't-ask-again / audit) | Designed in `docs/`, not yet wired into `LlmManagerService` |
| `LlmSessionStore` SQLite persistence + tool reliability stats | Class scaffolded, not yet wired |
| Session AIDL methods on `ILlmService` | Designed, not yet added |
| Tiered Qwen model auto-select (0.5B / 1.5B / 3B / 7B) | 0.5B verified; tiering logic not yet built |
| Cuttlefish boot stability across rebuilds | Hits dm-verity recovery if `m systemimage` is used without rebuilding `boot.img`/`vbmeta.img` — must `m -j32` |

For the platform debugging trail (Android-15 quirks, Soong cache gotchas,
dm-verity recovery traps), see
**[docs/AAOSP_ARCHITECTURE.md](docs/AAOSP_ARCHITECTURE.md)**.

## Contributing

This is early-stage. The end-to-end agentic loop is verified on Cuttlefish
(see demo above). If you're interested in agentic Android, the highest-impact
contributions right now:

- **MCP apps**: Add `<mcp-server>` declarations to AOSP built-in apps (Messaging, Calendar, Settings, Clock, Camera)
- **MCP discovery debugging**: Why is `<service>` dropped during PMS scan when ContactsMcp is platform-signed and BIND_LLM_MCP_SERVICE is `signature|privileged`? See open issue above.
- **HITL wiring**: The consent / confirmation / audit UI is designed in `docs/` but not yet wired into `LlmManagerService`.
- **Session persistence**: Wire `LlmSessionStore` (SQLite) into the binder API so sessions survive process restart.
- **Tiered model selector**: Auto-pick Qwen 2.5 size (0.5B / 1.5B / 3B / 7B) by `ActivityManager.MemoryInfo.totalMem`.
- **Model evaluation**: Test different Qwen 2.5 quantizations for tool-calling accuracy vs. speed.
- **Launcher UX**: The server-driven UI schema needs more element types (charts, images, forms).
- **Bake LLM into the OS**: `libllm_jni.so` and the Qwen GGUF currently live in `/data/local/llm/` and are pushed via `adb` after boot. They should ship on `/system/lib64/` and `/product/etc/llm/` (or a dedicated read-only partition) so the LLM is available on first boot with no setup. Requires Soong install rules for the `.so`, a `PRODUCT_COPY_FILES` for the GGUF, and SELinux contexts for the model directory.
- **Repo manifest hardening**: Push `system/sepolicy` and `device/google/cuttlefish` forks (currently blocked on github pack-size limit; needs `git gc` or orphan-branch workaround).

## License

Apache 2.0, same as AOSP.
