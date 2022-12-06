#!/bin/bash

if [[ "$1" = "--snapshot" ]]; then snapshot=true; fi
if [[ "$1" = "--local" ]]; then local=true; fi

if ! [[ ${local} ]]; then
  ./gradlew -p keeper-gradle-plugin publish
  if ! [[ ${snapshot} ]]; then
    ./gradlew -p keeper-gradle-plugin closeAndReleaseRepository
  fi
else
  ./gradlew -p keeper-gradle-plugin publishToMavenLocal
fi
