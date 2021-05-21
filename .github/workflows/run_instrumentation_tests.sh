#!/bin/bash

AGP_VERSION=$1
ENABLE_TRACEREFS=$2

# We only run the sample with R8 as proguard infinite loops if we have java 8 libraries on the classpath 🙃
echo "Building APK and verifying L8"
./gradlew :sample:minifyExternalStagingWithR8 --stacktrace -PkeeperTest.agpVersion="${AGP_VERSION}" -PkeeperTest.enableTraceReferences="${ENABLE_TRACEREFS}" -Pkeeper.verifyL8=true
# Reclaim memory because Actions OOMs sometimes with having both an emulator and heavy gradle builds going on
./gradlew --stop || jps|grep -E 'KotlinCompileDaemon|GradleDaemon'| awk '{print $1}'| xargs kill -9 || true
# Now proceed, with much of the build being cached up to this point
echo "Running instrumentation tests"
./gradlew connectedExternalStagingAndroidTest --stacktrace -PkeeperTest.agpVersion="${AGP_VERSION}" -PkeeperTest.enableTraceReferences="${ENABLE_TRACEREFS}"
