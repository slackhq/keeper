#!/bin/bash

CI_AGP_VERSION=$1

function printthreads {
  echo "Thread dump"
  jps|grep -E 'KotlinCompileDaemon|GradleDaemon'| awk '{print $1}'| xargs jstack
}

trap printthreads SIGINT

echo "Running with Proguard!"
timeout --signal=SIGINT 5m ./gradlew connectedExternalStagingAndroidTest -Pandroid.enableR8=false --stacktrace -PkeeperTest.agpVersion=${CI_AGP_VERSION}
echo "Running with R8!"
./gradlew connectedExternalStagingAndroidTest -Pandroid.enableR8=true --stacktrace -PkeeperTest.agpVersion=${CI_AGP_VERSION}
