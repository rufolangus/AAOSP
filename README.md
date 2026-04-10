# AAOSP - Agentic AOSP

Android with a native LLM System Service, MCP-aware apps, and an agentic launcher.

## Architecture

```
+-------------------------------------+
|         Agentic Launcher            |
|   (renders server-driven UI JSON)   |
+----------------+--------------------+
                 | Binder
+----------------v--------------------+
|        LLM System Service           |
|  +----------+  +-----------------+  |
|  | llama.cpp|  | MCP Tool Cache  |  |
|  | runtime  |  | (from manifests)|  |
|  +----------+  +-----------------+  |
+------+------------+----------+------+
       | Binder     | Binder   | Binder
+------v---+  +-----v----+  +-v---------+
| App A    |  | App B    |  | App C     |
| <mcp>    |  | <mcp>    |  | <mcp>     |
| tools:{} |  | tools:{} |  | tools:{}  |
+----------+  +----------+  +-----------+
```

## Core Concepts

### LLM System Service
A new Android system service (`com.android.server.llm`) that runs a local LLM
via llama.cpp. It acts as an MCP client, discovering and invoking tools exposed
by installed apps.

### MCP in AndroidManifest
Apps declare MCP server capabilities in their `AndroidManifest.xml`. On install,
`PackageManagerService` parses these declarations and caches them in the MCP
Tool Registry.

```xml
<application>
    <mcp-server>
        <tool android:name="send_message"
              android:description="Send a message to a contact">
            <input android:name="recipient" android:type="string" android:required="true" />
            <input android:name="body" android:type="string" android:required="true" />
        </tool>
        <resource android:name="contacts"
                  android:uri="content://com.example.app/contacts"
                  android:mimeType="application/json" />
    </mcp-server>
</application>
```

### Binder IPC
The LLM System Service communicates with apps via Android's standard Binder IPC.
Apps implement an AIDL interface to handle tool invocations from the service.

### Agentic Launcher
A new launcher that receives structured UI JSON from the LLM System Service and
renders it as interactive cards, lists, and actions. Replaces the traditional
app-grid-and-widgets paradigm with an agent-driven experience.

## Repos

| Repo | Description |
|------|-------------|
| [AAOSP](https://github.com/rufolangus/AAOSP) | This repo. Umbrella project, local manifests, build config. |
| [platform_frameworks_base](https://github.com/rufolangus/platform_frameworks_base) | Fork of AOSP frameworks/base. LLM System Service, manifest schema, PMS changes, AIDL. |
| [platform_packages_apps_AgenticLauncher](https://github.com/rufolangus/platform_packages_apps_AgenticLauncher) | The agentic launcher app. |
| [platform_external_llamacpp](https://github.com/rufolangus/platform_external_llamacpp) | llama.cpp packaged for the AOSP build system. |

## Setup

### Prerequisites
- AOSP build environment (cloud VM recommended: 32+ cores, 64GB+ RAM, 500GB+ SSD)
- `repo` tool installed

### Sync

```bash
# Initialize AOSP repo (e.g., Android 14)
repo init -u https://android.googlesource.com/platform/manifest -b android-14.0.0_r1

# Add AAOSP local manifests
git clone https://github.com/rufolangus/AAOSP.git .repo/local_manifests_src
cp .repo/local_manifests_src/local_manifests/*.xml .repo/local_manifests/

# Sync
repo sync -c -j$(nproc)
```

### Build

```bash
source build/envsetup.sh
lunch aosp_cf_x86_64_phone-userdebug   # Cuttlefish target
m -j$(nproc)
```

### Run (Cuttlefish)

```bash
launch_cvd
```

## License

Apache 2.0, same as AOSP.
