#!/usr/bin/env bash
# One-time setup of the build host: JDK 21 + Android SDK in the user's home, no sudo.
set -euo pipefail
mkdir -p ~/opt
if [ ! -x ~/opt/jdk21/bin/java ]; then
  curl -fsSL -o /tmp/jdk.tgz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  rm -rf ~/opt/jdk21 && mkdir ~/opt/jdk21 && tar xzf /tmp/jdk.tgz -C ~/opt/jdk21 --strip-components=1
fi
export JAVA_HOME=~/opt/jdk21 PATH=~/opt/jdk21/bin:$PATH
grep -q DOTMAIL_ENV ~/.bashrc || cat >> ~/.bashrc <<'RC'
# DOTMAIL_ENV
export JAVA_HOME=$HOME/opt/jdk21
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
RC
mkdir -p ~/.gradle
cat > ~/.gradle/gradle.properties <<'GP'
org.gradle.jvmargs=-Xmx8g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8
kotlin.daemon.jvmargs=-Xmx4g
org.gradle.workers.max=10
org.gradle.parallel=true
GP
bash ~/install-android-sdk.sh
echo "REMOTE_SETUP_DONE"
