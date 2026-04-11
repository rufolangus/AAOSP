# Cloud Build Guide

AAOSP targets **Android 15 (API 35)**. Building requires significant hardware — cloud is recommended.

## GCP (Recommended)

Google maintains Cuttlefish and AOSP — GCP has the best support.

### Create a GCP project

```bash
gcloud projects create aaosp-build --name="AAOSP Build"
gcloud config set project aaosp-build
gcloud billing projects link aaosp-build --billing-account=YOUR_BILLING_ACCOUNT

# Enable Compute Engine
gcloud services enable compute.googleapis.com

# Set a budget alert ($50 cap)
gcloud services enable billingbudgets.googleapis.com
gcloud billing budgets create \
  --billing-account=YOUR_BILLING_ACCOUNT \
  --display-name="AAOSP Build Budget" \
  --budget-amount=50 \
  --threshold-rule=percent=50 \
  --threshold-rule=percent=90 \
  --threshold-rule=percent=100
```

### Create a build VM

```bash
# us-central1-a may be exhausted — try us-east1-b or another zone
gcloud compute instances create aaosp-builder \
  --zone=us-east1-b \
  --machine-type=n2-standard-32 \
  --boot-disk-size=500GB \
  --boot-disk-type=pd-ssd \
  --image-family=ubuntu-2204-lts \
  --image-project=ubuntu-os-cloud
```

Estimated cost: ~$1.50/hr running, ~$0.04/hr stopped (disk only).

### SSH in and set up

```bash
gcloud compute ssh aaosp-builder --zone=us-east1-b

# Install AOSP build dependencies (Android 15 requires JDK 17+)
sudo apt-get update
sudo apt-get install -y git-core gnupg flex bison build-essential \
  zip curl zlib1g-dev libc6-dev-i386 x11proto-core-dev \
  libx11-dev lib32z1-dev libgl1-mesa-dev libxml2-utils xsltproc \
  unzip fontconfig python3 python3-pip openjdk-17-jdk

# Install repo
mkdir -p ~/bin
curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
chmod a+x ~/bin/repo
echo 'export PATH=~/bin:$PATH' >> ~/.bashrc
export PATH=~/bin:$PATH
```

### Sync AAOSP

```bash
mkdir ~/aaosp && cd ~/aaosp

# Initialize AOSP (Android 15, API 35)
repo init -u https://android.googlesource.com/platform/manifest \
  -b android-15.0.0_r1 --depth=1

# Add AAOSP local manifests (pulls our forks automatically)
git clone https://github.com/rufolangus/AAOSP.git .repo/local_manifests_src
mkdir -p .repo/local_manifests
cp .repo/local_manifests_src/local_manifests/*.xml .repo/local_manifests/

# Sync (~100GB download, takes 1-2 hours on a 32-core VM)
repo sync -c -j$(nproc) --no-tags --optimized-fetch

# IMPORTANT: Shallow clone may miss VNDK v32 prebuilt — sync it separately
repo sync prebuilts/vndk/v32 -j4
```

### Prepare llama.cpp and model

```bash
# Pull llama.cpp source into external/llama.cpp/src/
cd external/llama.cpp
bash scripts/sync_upstream.sh

# Download the default model (Qwen 2.5 3B, ~2.1 GB)
bash scripts/download_model.sh
cd ~/aaosp
```

### Build

> **Note:** The `frameworks/base` fork and all AAOSP integration patches
> (Context.java, SystemServer.java, etc.) are already included via the
> local manifest. No manual patching needed.

```bash
source build/envsetup.sh

# Android 15 uses product-release-variant format
lunch aosp_cf_x86_64_phone-trunk_staging-userdebug

# Build (1.5-2 hours on n2-standard-32)
m -j$(nproc)
```

### Run on Cuttlefish

```bash
# Launch the virtual device
launch_cvd

# Push the model
adb shell mkdir -p /data/local/llm
adb push out/target/product/vsoc_x86_64/data/local/llm/*.gguf /data/local/llm/
adb shell restorecon -R /data/local/llm/

# Watch the LLM service start
adb logcat -s LlmManagerService:* McpRegistry:* LlmJNI:*
```

### View the screen (WebRTC)

Cuttlefish runs headless. To see the Android screen from your local machine:

```bash
# Terminal 1: SSH tunnel
gcloud compute ssh aaosp-builder --zone=us-east1-b \
  -- -L 8443:localhost:8443

# Then open in your browser:
# https://localhost:8443
```

This gives you a full Android screen with touch input — perfect for demo videos (screen record the browser tab).

### Follow the build

```bash
# From your local machine — tail the build log:
gcloud compute ssh aaosp-builder --zone=us-east1-b \
  -- "tail -f ~/build.log"

# Quick status check:
gcloud compute ssh aaosp-builder --zone=us-east1-b \
  -- "tail -3 ~/build.log"
```

### Cost management

```bash
# STOP the VM when not building (saves ~$1.50/hr, ~$36/day)
gcloud compute instances stop aaosp-builder --zone=us-east1-b

# Start it back up when needed
gcloud compute instances start aaosp-builder --zone=us-east1-b

# Nuclear option — delete everything (stops all charges)
gcloud compute instances delete aaosp-builder --zone=us-east1-b
```

A few builds + demo video: ~$15-20 total.
The danger: forgetting to stop the VM ($36/day running).

## Troubleshooting

### "VNDK version 32 not found"
Shallow clone missed the prebuilt. Fix:
```bash
repo sync prebuilts/vndk/v32 -j4
```

### "Invalid lunch combo"
Android 15 changed the format from `product-variant` to `product-release-variant`:
```bash
# Old (won't work):
lunch aosp_cf_x86_64_phone-userdebug

# Correct:
lunch aosp_cf_x86_64_phone-trunk_staging-userdebug
```

### "multiple rules generate ... ggml-cpu.o"
Duplicate source files in llama.cpp Android.bp. Already fixed in the repo.

### Zone exhausted
GCP zones run out of capacity. Try a different zone:
```bash
--zone=us-east1-b    # or us-west1-a, europe-west1-b, etc.
```

## AWS Alternative

```bash
# c5.9xlarge: 36 vCPUs, 72GB RAM (~$1.53/hr on-demand)
aws ec2 run-instances \
  --image-id ami-0c55b159cbfafe1f0 \
  --instance-type c5.9xlarge \
  --block-device-mappings '[{"DeviceName":"/dev/sda1","Ebs":{"VolumeSize":500,"VolumeType":"gp3"}}]' \
  --key-name your-key
```

Same setup steps after SSH — install deps, repo init, sync, build.
