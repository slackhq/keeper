#!/bin/bash

CI_AGP_VERSION=$1

function printthreads {
  echo "Thread dump"
  jps|grep -E 'KotlinCompileDaemon|GradleDaemon'| awk '{print $1}'| xargs jstack
  exit 1
}

trap printthreads SIGINT

echo "Install coreutils" # For gtimeout
brew install coreutils

# We only run the sample with R8 as proguard infinite loops if we have java 8 libraries on the classpath 🙃
gtimeout --signal=SIGINT 10m ./gradlew connectedExternalStagingAndroidTest --stacktrace -PkeeperTest.agpVersion="${CI_AGP_VERSION}"
