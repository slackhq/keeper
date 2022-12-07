/*
 * Copyright (C) 2020. Slack Technologies, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  versionCatalogs {
    if (System.getenv("DEP_OVERRIDES") == "true") {
      val overrides = System.getenv().filterKeys { it.startsWith("DEP_OVERRIDE_") }
      maybeCreate("libs").apply {
        for ((key, value) in overrides) {
          val catalogKey = key.removePrefix("DEP_OVERRIDE_").toLowerCase()
          println("Overriding $catalogKey with $value")
          version(catalogKey, value)
        }
      }
    }
  }
}

rootProject.name = "keeper-root"
include(":sample")
include(":sample-libraries:a")
include(":sample-libraries:b")
include(":sample-libraries:c")
include(":sample-libraries:test-only-android")
include(":sample-libraries:test-only-jvm")

includeBuild("keeper-gradle-plugin") {
  dependencySubstitution {
    substitute(module("com.slack.keeper:keeper")).using(project(":"))
  }
}
