#!/bin/bash

CI_AGP_VERSION=$1

echo "Running with Proguard!"
./gradlew connectedExternalStagingAndroidTest -Pandroid.enableR8=false --stacktrace -PkeeperTest.agpVersion=${CI_AGP_VERSION}
echo "Running with R8!"
./gradlew connectedExternalStagingAndroidTest -Pandroid.enableR8=true --stacktrace -PkeeperTest.agpVersion=${CI_AGP_VERSION}
