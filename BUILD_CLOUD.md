# Cloud Build Guide

AAOSP requires significant hardware. Here's how to build in the cloud.

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

### SSH in and set up

```bash
gcloud compute ssh aaosp-builder --zone=us-central1-a

# Install AOSP dependencies
sudo apt-get update
sudo apt-get install -y git-core gnupg flex bison build-essential \
  zip curl zlib1g-dev libc6-dev-i386 x11proto-core-dev \
  libx11-dev lib32z1-dev libgl1-mesa-dev libxml2-utils xsltproc \
  unzip fontconfig python3 openjdk-17-jdk

# Install repo
mkdir -p ~/bin
curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
chmod a+x ~/bin/repo
export PATH=~/bin:$PATH

# Sync and build (see README.md)
```

### Cost management

```bash
# Stop the VM when not building (~$0.02/hr stopped for disk)
gcloud compute instances stop aaosp-builder --zone=us-central1-a

# Start it back up when needed
gcloud compute instances start aaosp-builder --zone=us-central1-a
```

## AWS Alternative

```bash
# c5.9xlarge: 36 vCPUs, 72GB RAM
aws ec2 run-instances \
  --image-id ami-0c55b159cbfafe1f0 \
  --instance-type c5.9xlarge \
  --block-device-mappings '[{"DeviceName":"/dev/sda1","Ebs":{"VolumeSize":500,"VolumeType":"gp3"}}]' \
  --key-name your-key
```
