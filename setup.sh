#!/bin/bash
# LiquidBounce build bootstrap — idempotent, survives fresh VM (all state under /workspace)
set -e
JDK_DIR=$(ls -d /workspace/toolchain/jdk-25* 2>/dev/null | head -1)
if [ -z "$JDK_DIR" ]; then
  URL=$(curl -s 'https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=aarch64&image_type=jdk&os=linux&vendor=eclipse' \
        | python3 -c 'import json,sys;print(json.load(sys.stdin)[0]["binary"]["package"]["link"])')
  mkdir -p /workspace/toolchain
  curl -L --max-time 320 "$URL" -o /tmp/jdk25.tar.gz
  tar -xzf /tmp/jdk25.tar.gz -C /workspace/toolchain
  JDK_DIR=$(ls -d /workspace/toolchain/jdk-25* | head -1)
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME=/workspace/.gradle
cd /workspace/LiquidBounce
./gradlew build -x test --console=plain --no-parallel --no-configure-on-demand \
  --no-configuration-cache -Dorg.gradle.java.installations.paths="$JAVA_HOME" "$@"
