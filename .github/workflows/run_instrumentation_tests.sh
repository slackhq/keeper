#!/bin/bash

CI_AGP_VERSION=$1

function printthreads {
  echo "Thread dump"
  jps|grep -E 'KotlinCompileDaemon|GradleDaemon'| awk '{print $1}'| xargs jstack
  exit 1
}

trap printthreads SIGINT

echo "Install coreutils"
brew install coreutils

echo "Running with Proguard!"
gtimeout --signal=SIGINT 5m ./gradlew connectedExternalStagingAndroidTest -Pandroid.enableR8=false --stacktrace -PkeeperTest.agpVersion=${CI_AGP_VERSION}
echo "Running with R8!"
gtimeout --signal=SIGINT 5m ./gradlew connectedExternalStagingAndroidTest -Pandroid.enableR8=true --stacktrace -PkeeperTest.agpVersion=${CI_AGP_VERSION}
