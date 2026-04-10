# Cloud Build Guide

AAOSP targets **Android 15 (API 35)**. Building requires significant hardware — cloud is recommended.

## GCP (Recommended)

Google maintains Cuttlefish and AOSP — GCP has the best support.

### Create a build VM

```bash
gcloud compute instances create aaosp-builder \
  --zone=us-central1-a \
  --machine-type=n2-standard-32 \
  --boot-disk-size=500GB \
  --boot-disk-type=pd-ssd \
  --image-family=ubuntu-2204-lts \
  --image-project=ubuntu-os-cloud
```

Estimated cost: ~$1.50/hr running, ~$0.04/hr stopped (disk only).

### SSH in and set up

```bash
gcloud compute ssh aaosp-builder --zone=us-central1-a

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
  -b android-15.0.0_r1

# Add AAOSP local manifests (pulls our forks automatically)
git clone https://github.com/rufolangus/AAOSP.git .repo/local_manifests_src
mkdir -p .repo/local_manifests
cp .repo/local_manifests_src/local_manifests/*.xml .repo/local_manifests/

# Sync (~100GB download, takes 1-2 hours)
repo sync -c -j$(nproc) --no-tags
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

### Apply AAOSP patches

These one-liner patches wire the AAOSP components into existing AOSP code:

```bash
# See docs/ in the AAOSP umbrella repo for exact insertion points.
# The 5 patches are:
#   1. Context.java: add LLM_SERVICE constant
#   2. AndroidManifest.xml: declare SUBMIT_LLM_REQUEST + BIND_LLM_MCP_SERVICE
#   3. SystemServiceRegistry.java: register LlmManager
#   4. ParsingPackageUtils.java: handle <mcp-server> tag
#   5. SystemServer.java: start LlmManagerService
```

### Build

```bash
source build/envsetup.sh
lunch aosp_cf_x86_64_phone-userdebug   # Cuttlefish target
m -j$(nproc)                            # ~1-2 hours first build
```

### Run on Cuttlefish

```bash
# Launch the virtual device
launch_cvd

# Push the model (if not baked into the image)
adb shell mkdir -p /data/local/llm
adb push out/target/product/vsoc_x86_64/data/local/llm/*.gguf /data/local/llm/
adb shell restorecon -R /data/local/llm/

# Watch the LLM service start
adb logcat -s LlmManagerService:* McpRegistry:* LlmJNI:*
```

### Cost management

```bash
# STOP the VM when not building (saves ~$1.50/hr)
gcloud compute instances stop aaosp-builder --zone=us-central1-a

# Start it back up when needed
gcloud compute instances start aaosp-builder --zone=us-central1-a
```

A full build cycle (sync + build + test) costs roughly $3-5 on GCP.

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
