# Android 15 Compatibility Notes

AAOSP targets **Android 15 (API 35)** / `android-15.0.0_r1`.

## Verified Compatible

- **SystemServiceRegistry** — same registration pattern as Android 14
- **Context constants** — same pattern for adding `LLM_SERVICE`
- **Binder/bindService** — `BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE` unchanged. system_server is exempt from background restrictions.
- **SQLiteOpenHelper** — no deprecations, WAL + foreign keys work the same
- **Jetpack Compose** — all needed libraries present in `prebuilts/sdk/current/androidx/`
- **SELinux** — same `service_contexts` and `.te` file patterns

## Changes from Android 14

### 1. AIDL Permission Enforcement (new in Android 15)

Android 15 introduced stricter AIDL permission enforcement. Options:

**Option A: Add `@EnforcePermission` annotations to `ILlmService.aidl`:**
```aidl
@EnforcePermission("android.permission.SUBMIT_LLM_REQUEST")
String submit(in LlmRequest request, ILlmResponseCallback callback);
```

**Option B: Add to the build exemption list** (simpler for prototyping):
Add the AIDL to `enforce_permissions_exceptions` in the build config.

We do manual `enforceCallingOrSelfPermission()` checks in the Java implementation, so the actual enforcement is there — this is about build-time validation.

### 2. Compose Prebuilt Naming

The artifact `lifecycle-viewmodel-compose` was renamed to `lifecycle-viewmodel-compose-android` in Android 15's prebuilts. Already updated in the launcher's `Android.bp`.

### 3. ParsingPackageUtils Refactor

Android 15 split some helpers into `FrameworkParsingPackageUtils.java`. The core `parseBaseApplication()` method still exists in `ParsingPackageUtils.java` but the signature may have changed. **Verify at build time:**

```bash
grep -n "parseBaseApplication" frameworks/base/core/java/android/content/pm/parsing/ParsingPackageUtils.java
```

The `<mcp-server>` case insertion documented in `McpPackageHandler.java` may need adjustment to match the new method structure.

### 4. SELinux Prebuilt API

Android 15 requires service_contexts entries in both:
- `system/sepolicy/private/service_contexts`
- `system/sepolicy/prebuilts/api/<version>/private/service_contexts`

Add the `llm` service entry to both locations.
