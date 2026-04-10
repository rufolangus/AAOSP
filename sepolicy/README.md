# AAOSP SELinux Policies

SELinux policy additions for the LLM System Service and MCP subsystem.

## Files

| File | Purpose | Target |
|------|---------|--------|
| `llm_service.te` | Domain rules, file access, service type | `system/sepolicy/private/` |
| `file_contexts.aaosp` | Label model file directories | Append to `system/sepolicy/private/file_contexts` |
| `service_contexts.aaosp` | Label the "llm" binder service | Append to `system/sepolicy/private/service_contexts` |

## What the policy covers

### Model file access
- `/data/local/llm/*.gguf` → `llm_model_data_file` — read + mmap by `system_server` only
- `/system/etc/llm/*.gguf` → `llm_model_system_file` — read + mmap by `system_server` only
- mmap is required — llama.cpp memory-maps models for performance

### Binder service
- `llm` service registered in `service_contexts` as `llm_service` type
- Apps can `find` the service (Java-level permission check via `USE_LLM`)
- `system_server` ↔ app MCP services: covered by existing `binder_call(system_server, appdomain)` rules

### Neverallow
- Only `system_server` can read model files
- Model files cannot be written in `/system`
- Model files cannot be executed

## Integration

### Option 1: Direct patch
```bash
# Copy policy file
cp llm_service.te $AOSP/system/sepolicy/private/

# Append file contexts
cat file_contexts.aaosp >> $AOSP/system/sepolicy/private/file_contexts

# Append service contexts
cat service_contexts.aaosp >> $AOSP/system/sepolicy/private/service_contexts
```

### Option 2: Sepolicy overlay (preferred)
Place in a device-specific or product-specific sepolicy directory and include via `BOARD_SEPOLICY_DIRS` in your `BoardConfig.mk`:

```makefile
# In device/aaosp/sepolicy/BoardConfig.mk
BOARD_SEPOLICY_DIRS += device/aaosp/sepolicy
```

## Post-install: Label model files

After pushing models, relabel:
```bash
adb shell restorecon -R /data/local/llm/
```

## Verification

```bash
# Check service label
adb shell service list | grep llm
adb shell ls -Z /data/local/llm/

# Check for denials
adb shell dmesg | grep "avc.*llm"
adb logcat -s "SELinux"
```
