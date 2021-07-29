#!/bin/bash

if [[ "$1" = "--snapshot" ]]; then snapshot=true; fi
if [[ "$1" = "--local" ]]; then local=true; fi

if ! [[ ${local} ]]; then
  ./gradlew -p keeper-gradle-plugin clean publishAllPublicationsToMavenCentralRepository --no-daemon --no-parallel -Pkeeper.releaseMode=true
  if ! [[ ${snapshot} ]]; then
    ./gradlew -p keeper-gradle-plugin closeAndReleaseRepository
  fi
else
  ./gradlew -p keeper-gradle-plugin clean install --no-daemon --no-parallel -Pkeeper.releaseMode=true
fi
