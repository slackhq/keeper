#!/bin/bash

if [[ "$1" = "--local" ]]; then local=true; fi

export KEEPER_RELEASING=true
if ! [[ ${local} ]]; then
  ./gradlew -p keeper-gradle-plugin publish
else
  ./gradlew -p keeper-gradle-plugin publishToMavenLocal
fi
