#!/bin/bash

CI_AGP_VERSION=$1

./gradlew connectedCheck -Pandroid.enableR8=false --stacktrace -PkeeperTest.agpVersion=${CI_AGP_VERSION}
./gradlew connectedCheck -Pandroid.enableR8=true --stacktrace -PkeeperTest.agpVersion=${CI_AGP_VERSION}
