#!/bin/bash

adb uninstall com.slack.keeper.sample || true
adb uninstall com.slack.keeper.sample.androidTest || true

echo "Building APK and verifying L8"
./gradlew :sample:minifyExternalStagingWithR8 --stacktrace -Pkeeper.verifyL8=true

# Reclaim memory because Actions OOMs sometimes with having both an emulator and heavy gradle builds going on
./gradlew --stop || jps|grep -E 'KotlinCompileDaemon|GradleDaemon'| awk '{print $1}'| xargs kill -9 || true

# Now proceed, with much of the build being cached up to this point
echo "Running instrumentation tests"
./gradlew connectedExternalStagingAndroidTest --stacktrace

adb uninstall com.slack.keeper.sample || true
adb uninstall com.slack.keeper.sample.androidTest || true