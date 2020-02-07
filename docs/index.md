🥅 Keeper
========

A Gradle plugin that infers Proguard/R8 keep rules for androidTest sources.

Keeper hooks into Proguard/R8 to add extra keep rules based on what androidTest classes use from the
target app's sources. This is necessary because the Android Gradle Plugin (AGP) does not currently
factor in androidTest usages of target app sources when running the minification step, which can
result in runtime errors if APIs used by tests are removed.

This is (really) useful _only_ if you run your instrumentation tests against your minified release
builds! If you don't run these tests against minified builds, then you don't need this plugin. The
build type that you test against is controlled by the `testBuildType` flag,  which is set to
`debug` by default.

This is a workaround until AGP supports this: https://issuetracker.google.com/issues/126429384.

**Note:** Keeper uses private APIs from AGP and could break between releases. It is currently
tested against AGP versions 3.5.3 and 3.6.0-rc02 (or whatever `ci_agp_version` env
vars are described [here](https://github.com/slackhq/keeper/blob/master/.github/workflows/ci.yml).

## Installation

Keeper is distributed via Maven Central. Apply the keeper Gradle plugin in your application's
build.gradle. Keeper requires Gradle 6.0 or higher.

```groovy
buildscript {
  dependencies {
    // ...
    classpath "com.slack.keeper:keeper:x.y.z"
  }
}

apply plugin: "com.android.application" // <- Keeper only works with com.android.application!
apply plugin: "com.slack.keeper"
```

Optional configuration options can be found on the [Configuration page](configuration.md).

Snapshots of the development version are available in [Sonatype's `snapshots` repository][snapshots].

## Under the hood

The general logic flow:

* Create a custom `r8` configuration for the R8 dependency.
* Register two jar tasks per `androidTest` variant. One for all the classes in its target `testedVariant`
  and one for all the classes in the androidTest variant itself. This will use their variant-provided
  `JavaCompile` tasks and `KotlinCompile` tasks if available.
* Register a [`infer${androidTestVariant}UsageForKeeper`](https://github.com/slackhq/keeper/blob/master/keeper-gradle-plugin/src/main/kotlin/com/slack/keeper/InferAndroidTestKeepRules.kt)
  task that plugs the two aforementioned jars into R8's `PrintUses` CLI and outputs the inferred
  proguard rules into a new intermediate `.pro` file.
* Finally - the generated file is wired in to Proguard/R8 via private task APIs and setting their
  `configurationFiles` to include our generated one.

Appropriate task dependencies (via inputs/outputs, not `dependsOn`) are set up, so this is
automatically run as part of the target app variant's full minified APK.

The tasks themselves take roughly ~20 seconds total extra work in our Slack android app, with the
infer and app jar tasks each taking around 8-10 seconds and the androidTest jar taking around 2 seconds.

License
-------

    Copyright (C) 2020 Slack Technologies, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 [snapshots]: https://oss.sonatype.org/content/repositories/snapshots/
