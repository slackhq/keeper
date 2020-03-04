#!/bin/bash

if [[ "$1" = "--snapshot" ]]; then snapshot=true; fi
if [[ "$1" = "--local" ]]; then local=true; fi

cd keeper-gradle-plugin
if ! [[ ${local} ]]; then
  ./gradlew clean uploadArchives --no-daemon --no-parallel -Pkeeper.releaseMode=true
  if ! [[ ${snapshot} ]]; then
    ./gradlew closeAndReleaseRepository
  fi
else
  ./gradlew clean install --no-daemon --no-parallel -Pkeeper.releaseMode=true
fi

cd ..
