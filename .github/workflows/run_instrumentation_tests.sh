#!/bin/bash

AGP_VERSION=$1
ENABLE_TRACEREFS=$2

function printthreads {
  echo "Thread dump"
  jps|grep -E 'KotlinCompileDaemon|GradleDaemon'| awk '{print $1}'| xargs jstack
  exit 1
}

trap printthreads SIGINT

echo "Install coreutils" # For gtimeout
brew install coreutils

# We only run the sample with R8 as proguard infinite loops if we have java 8 libraries on the classpath 🙃
echo "Building APK"
./gradlew :sample:minifyExternalStagingWithR8 --stacktrace -PkeeperTest.agpVersion="${AGP_VERSION}" -PkeeperTest.enableTraceReferences="${ENABLE_TRACEREFS}"
# Reclaim memory because Actions OOMs sometimes with having both an emulator and heavy gradle builds going on
./gradlew --stop || jps|grep -E 'KotlinCompileDaemon|GradleDaemon'| awk '{print $1}'| xargs kill -9 || true
# Now proceed, with much of the build being cached up to this point
echo "Running instrumentation tests"
gtimeout --signal=SIGINT 10m ./gradlew connectedExternalStagingAndroidTest --stacktrace -PkeeperTest.agpVersion="${AGP_VERSION}" -PkeeperTest.enableTraceReferences="${ENABLE_TRACEREFS}"
