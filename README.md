# AAOSP — Agentic AOSP

**Android's natural evolution for the agentic era.**

MCP (Model Context Protocol) is the standard. Every major AI platform — Claude, GPT, Gemini — speaks it. Every tool, every integration, every workflow is converging on MCP as the universal interface between AI and software.

Android has always been a platform built on protocols. Intents, Content Providers, Broadcast Receivers — these were the right abstractions for the app era. MCP is the right abstraction for the agentic era.

AAOSP makes MCP a first-class citizen in Android. Apps declare tools. The OS runs the model. The user just talks.

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

Any app can become an MCP server. Add a declaration to your manifest, implement one AIDL interface, and the LLM can use your app's capabilities.

### 1. Declare tools in your manifest

```xml
<application>
    <mcp-server
        android:name=".MyMcpService"
        android:description="Messaging capabilities"
        android:permission="android.permission.BIND_LLM_MCP_SERVICE"
        android:mcpVersion="2024-11-05">

        <tool
            android:name="send_message"
            android:description="Send a message to a contact"
            android:mcpRequiresConfirmation="true">
            <input android:name="recipient"
                   android:mcpType="string"
                   android:mcpRequired="true"
                   android:description="Contact name or number" />
            <input android:name="body"
                   android:mcpType="string"
                   android:mcpRequired="true"
                   android:description="Message content" />
        </tool>

        <resource
            android:name="recent_messages"
            android:mcpUri="content://com.example/messages/recent"
            android:description="Recent messages"
            android:mcpMimeType="application/json" />
    </mcp-server>
</application>
```

### 2. Implement the AIDL interface

```java
public class MyMcpService extends Service {
    private final IMcpToolProvider.Stub mBinder =
            new IMcpToolProvider.Stub() {
        @Override
        public String invokeTool(String toolName, String argsJson) {
            JSONObject args = new JSONObject(argsJson);
            switch (toolName) {
                case "send_message":
                    String to = args.getString("recipient");
                    String body = args.getString("body");
                    // ... send the message ...
                    return "{\"status\": \"sent\"}";
                default:
                    return "{\"error\": \"unknown tool\"}";
            }
        }

        @Override
        public String readResource(String name) { /* ... */ }

        @Override
        public String listResources(String pattern) { /* ... */ }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
```

### 3. That's it

The OS handles everything else:
- **Discovery**: PackageManager indexes your tools at install time
- **Routing**: The LLM decides when to call your tool based on user intent
- **Execution**: The system service binds to your service via Binder and invokes the tool
- **Safety**: `mcpRequiresConfirmation="true"` gates destructive actions behind user approval
- **Reliability**: The system tracks success rates and deprioritizes unreliable tools

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

## Project Structure

| Repo | What | Branch |
|---|---|---|
| **[AAOSP](https://github.com/rufolangus/AAOSP)** | Umbrella: manifests, SELinux, test apps, docs | `main` |
| **[platform_frameworks_base](https://github.com/rufolangus/platform_frameworks_base)** | LLM Service, MCP schema, AIDL, SDK manager, session store | `aaosp` |
| **[platform_packages_apps_AgenticLauncher](https://github.com/rufolangus/platform_packages_apps_AgenticLauncher)** | Compose launcher with server-driven UI and history | `main` |
| **[platform_external_llamacpp](https://github.com/rufolangus/platform_external_llamacpp)** | llama.cpp build integration, JNI bridge, model scripts | `main` |

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

# Sync (pulls AAOSP forks automatically)
repo sync -c -j$(nproc)
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
lunch aosp_cf_x86_64_phone-userdebug   # Cuttlefish (cloud/emulator)
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

| Component | Status |
|---|---|
| MCP manifest schema (`<mcp-server>`, `<tool>`, `<input>`, `<resource>`) | Done |
| PackageManager integration (parse + cache on install) | Done |
| LLM System Service (inference, tool calling, session management) | Done |
| JNI bridge to llama.cpp (modern sampler API) | Done |
| Qwen 2.5 tiered model config | Done |
| SDK manager (`LlmManager`, `LlmRequest`) | Done |
| AIDL interfaces (`ILlmService`, `IMcpToolProvider`, callbacks) | Done |
| Agentic Launcher (Compose, server-driven UI, history) | Done |
| Session persistence (SQLite, tool reliability stats) | Done |
| SELinux policies | Done |
| Permission model (`SUBMIT_LLM_REQUEST` / `BIND_LLM_MCP_SERVICE`) | Done |
| Test MCP app (ContactsMcp) | Done |
| **Cloud build + Cuttlefish boot** | **Next** |

## Contributing

This is early-stage. The first build hasn't happened yet. If you're interested in agentic Android, the highest-impact contributions right now:

- **MCP apps**: Add `<mcp-server>` declarations to AOSP built-in apps (Messaging, Calendar, Settings, Clock, Camera)
- **Cloud build**: Help get the first successful build on Cuttlefish
- **Model evaluation**: Test different Qwen 2.5 quantizations for tool-calling accuracy vs. speed
- **Launcher UX**: The server-driven UI schema needs more element types (charts, images, forms)

## License

Apache 2.0, same as AOSP.
