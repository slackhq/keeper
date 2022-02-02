#!/bin/bash

if [[ "$1" = "--snapshot" ]]; then snapshot=true; fi
if [[ "$1" = "--local" ]]; then local=true; fi

if ! [[ ${local} ]]; then
  ./gradlew -p keeper-gradle-plugin clean publish --no-daemon --no-parallel --no-configuration-cache -Pkeeper.releaseMode=true
  if ! [[ ${snapshot} ]]; then
    ./gradlew -p keeper-gradle-plugin closeAndReleaseRepository
  fi
else
  ./gradlew -p keeper-gradle-plugin clean install --no-daemon --no-parallel --no-configuration-cache -Pkeeper.releaseMode=true
fi
