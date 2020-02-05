#!/bin/bash

CI_AGP_VERSION=$1

function printthreads {
  echo "Thread dump"
  jps|grep -E 'KotlinCompileDaemon|GradleDaemon'| awk '{print $1}'| xargs jstack
}

trap printthreads SIGINT

timeout --signal=SIGINT 5m ./gradlew connectedExternalStagingAndroidTest -Pandroid.enableR8=false --stacktrace -PkeeperTest.agpVersion=${CI_AGP_VERSION}
./gradlew connectedExternalStagingAndroidTest -Pandroid.enableR8=true --stacktrace -PkeeperTest.agpVersion=${CI_AGP_VERSION}
