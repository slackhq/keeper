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
tested against AGP versions 3.5.3, 3.6.0-rc01, and 4.0.0-alpha09 (or whatever `ci_agp_version` env
vars are described [here](https://github.com/slackhq/keeper/blob/master/.github/workflows/ci.yml).

## Installation

Keeper is distributed via Maven Central. Apply the keeper Gradle plugin in your application's
build.gradle.

```groovy
buildscript {
  dependencies {
    classpath "com.slack.keeper:keeper:x.y.z"
  }
}

apply plugin: "com.slack.keeper"
```

There are optional configurations available via the `keeper` extension, mostly just for debugging
purposes or setting a custom R8 version.

```
keeper {
  /**
   * R8 version, only used for PrintUses and does _not_ override the R8 version used for
   * minification. Must be a tag. Default defined below.
   */
  r8Version = "1.6.53"

  /**
   * Optional custom jvm arguments to pass into the R8 `PrintUses` execution. Useful if you want
   * to enable debugging in R8. Default is empty.
   *
   * Example: `listOf("-Xdebug", "-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=y")`
   */
  r8JvmArgs = []
}
```

Snapshots of the development version are available in [Sonatype's `snapshots` repository][snapshots].

### Dynamic Configuration

As mentioned above, Keeper's default behavior with no configuration will enable it for _all_
androidTest variants. This may not be what you want for your actual production builds that you plan
to distribute.

Normally, your app variant's minification task doesn't depend on compilation of its corresponding
`androidTest` variant. This means you can call `assembleRelease` and `assembleAndroidTestRelease`
 won't inherently run. Keeper, however, changes this since it requires the compiled androidTest
 sources in order to correctly infer how they use APIs in the app variant. For a production build,
you likely _do_ want these "test-only" APIs removed if possible though. There are a few patterns to
better control this behavior via Gradle property.

Let's assume an example command to build a production app with custom property `productionBuild`.

```bash
./gradlew :myapp:assembleRelease -PproductionBuild=true
```

#### Use the `testBuildType` option

If you avoid setting your `testBuildType` to your "release" build type in a production build, then
Keeper won't configure your release artifact to depend on test sources since your release variant
would no longer be the `testedVariant` of any `androidTest` variants.

This is the *recommended* solution.

```groovy
android {
  // ...

  if (hasProperty("productionBuild")) {
    testBuildType = "debug"
  } else {
    // You would have had to have been doing something like this anyway if you're using Keeper!
    testBuildType = "release"
  }
}
```

#### Avoid applying the plugin entirely

This is probably the simplest approach, but not as dynamic as controlling the `testBuildType`.

```groovy
if (!hasProperty("productionBuild")) {
  apply plugin: 'com.slack.keeper
}
```

### <your build here>

Everyone's project is different, so you should do whatever works for you! We're open to suggestions
of better ways to support configuration for this, so please do file issues if you have any proposals.

## Under the hood

The general logic flow:
- Create a custom `r8` configuration for the R8 dependency.
- Register two jar tasks per `androidTest` variant. One for all the classes in its target `testedVariant`
  and one for all the classes in the androidTest variant itself. This will use their variant-provided
  `JavaCompile` tasks and `KotlinCompile` tasks if available.
- Register a [`infer${androidTestVariant}UsageForKeeper`](https://github.com/slackhq/keeper/blob/master/keeper-gradle-plugin/src/main/kotlin/com/slack/keeper/InferAndroidTestKeepRules.kt)
  task that plugs the two aforementioned jars into R8's `PrintUses` CLI and outputs the inferred
  proguard rules into a new intermediate `.pro` file.
- Finally - the generated file is wired in to Proguard/R8 via private task APIs and setting their
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