#!/bin/bash

if [[ "$1" = "--local" ]]; then local=true; fi

if ! [[ ${local} ]]; then
  ./gradlew -p keeper-gradle-plugin publish -x dokkaHtml
else
  ./gradlew -p keeper-gradle-plugin publishToMavenLocal -x dokkaHtml
fi
