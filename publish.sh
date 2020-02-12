#!/bin/bash

if [[ "$1" = "--snapshot" ]]; then snapshot=true; fi

cd keeper-gradle-plugin
./gradlew clean uploadArchives --no-daemon --no-parallel -Pkeeper.releaseMode=true

if ! [[ ${snapshot} ]]; then
  ./gradlew closeAndReleaseRepository
fi
cd ..
