🥅 Keeper
========

A Gradle plugin that infers Proguard/R8 keep rules for androidTest sources.

Keeper into Proguard/R8 to add extra keep rules based on what androidTest classes use from the
target app's sources. This is necessary because the Android Gradle Plugin (AGP) does not currently
factor in androidTest usages of target app sources when running the minification step, which can
result in runtime errors if APIs used by tests are removed.

This is a workaround until AGP supports this: https://issuetracker.google.com/issues/126429384.

**Note:** This plugin uses private APIs from AGP and could break between releases. We currently
support AGP 3.5.3, and will support 3.6 once it is released.

## Installation

Apply keeper _after_ the `android {}` block in your application's build.gradle file and configure
via the `keeper {}` extension.

```gradle
buildscript {
  repositories {
    mavenCentral()
    // ...
  }

  dependencies {
    // ...
    classpath "com.slack.keeper:keeper:x.y.z"
  }

}

android {
  // ...
}

apply plugin: "com.slack.keeper"
keeper {
  /**
   * The name of the androidTest variant to infer for. This should include the "AndroidTest"
   * suffix. Required.
   */
  androidTestVariant = "yourAndroidTestVariantHere" // Required

  /** The name of the application variant to infer for. Required. */
  appVariant = "yourReleaseVariantHere"

  /** R8 version. Must be a tag. Optional, default defined below. */
  r8Version = "1.6.53"

  /**
   * Optional custom jvm arguments to pass into the R8 `PrintUses` execution. Useful if you want
   * to enable debugging in R8. Optional, default is empty.
   *
   * Example: `listOf("-Xdebug", "-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=y")`
   */
  jvmArgs = []
}
```

### Dynamic Configuration

If you want to dynamically configure this, one approach we use internally is to define a `buildVariant`
gradle property and use its value to gate application of this plugin and which variant to use.

```gradle
if (project.hasProperty("buildVariant")) {
    String buildVariant = project.getProperty("buildVariant")
    apply plugin: 'com.slack.keeper'

    keeper {
        androidTestVariant = "${buildVariant}AndroidTest"
        appVariant = buildVariant
    }
}
```

Snapshots of the development version are available in [Sonatype's `snapshots` repository][snapshots].

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