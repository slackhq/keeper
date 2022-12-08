## Basic Configuration

In order to enable Keeper on a particular variant, you must register the `KeeperVariantMarker`
extension in AGP's `VariantBuilder` API like so:

```kotlin
androidComponents {
  beforeVariants { builder ->
    if (shouldRunKeeperOnVariant()) {
      builder.optInToKeeper() // Helpful extension function
    }
  }
}
```

Or in Groovy

```kotlin
androidComponents {
  beforeVariants { builder ->
    if (shouldRunKeeperOnVariant(builder)) {
      builder.registerExtension(KeeperVariantMarker.class, KeeperVariantMarker.INSTANCE)
    }
  }
}
```

Keeper's default behavior with no configuration effectively be a no-op, which isn't what you want!

## Advanced Configuration

Normally, your app variant's minification task doesn't depend on compilation of its corresponding
`androidTest` variant. This means you can call `assembleRelease` and `assembleAndroidTestRelease`
 won't inherently run. Keeper, however, changes this since it requires the compiled androidTest
 sources in order to correctly infer how they use APIs in the app variant. For a production build,
you likely _do_ want these "test-only" APIs removed if possible though. There are a few patterns to
better control this behavior via Gradle property.

### Simplest solution

The simplest solution is to add a new build type that extends `release` but is solely used for these
tests. This way it's identical to release in everything except the name.

```groovy
android {
  buildTypes {
    staging {
      initWith release
    }
  }

  testBuildType = "staging"
}
```

Now Keeper will only wire for the StagingAndroidTest build type and the `assembleRelease` dependency
tree will remain untouched.

This is the **recommended** solution, since builds that need this plugin likely already have custom
logic in place for controlling `testBuildType` and it avoids messing with your normal release build.
 This is what we do internally at Slack as well.

#### Property-based Examples

Let's assume an example command to build a production app with custom property `productionBuild`.

```bash
./gradlew :myapp:assembleRelease -PproductionBuild=true
```

### Use the `testBuildType` option

If you avoid setting your `testBuildType` to your "release" build type in a production build, then
Keeper won't configure your release artifact to depend on test sources since your release variant
would no longer be the `testedVariant` of any `androidTest` variants.

```groovy
android {
  // ...

  if (hasProperty("productionBuild")) {
    testBuildType = "debug"
  } else {
    testBuildType = "release"
  }
}
```

### Avoid applying the plugin entirely

This is probably the simplest approach, but not as dynamic as controlling the `testBuildType`.

```groovy
if (!hasProperty("productionBuild")) {
  apply plugin: "com.slack.keeper"
}
```

### <your build here>

Everyone's project is different, so you should do whatever works for you! We're open to suggestions
of better ways to support configuration for this, so please do file issues if you have any proposals.

## Custom R8 behavior

Keeper uses R8's `TraceReferences` CLI under the hood for rules inference. By default it uses R8 version
`3.2.78`. If you want to customize what version is used, you can specify the dependency via the
`keeperR8` configuration. Note that these must be tags from R8's
[`r8-releases/raw`](https://storage.googleapis.com/r8-releases/raw) maven repo.

```groovy
dependencies {
  keeperR8 "com.android.tools:r8:x.y.z"
}
```

If you don't want Keeper to automatically manage adding R8's maven repo, you can set disable it
via `automaticR8RepoManagement`. Note that you'll need to manually add your own repo that the
`keeperR8` configuration mentioned above can resolve from.

```groovy
keeper {
  automaticR8RepoManagement = false
}
```

If you want to add custom JVM arguments to its invocation (i.e. for debugging), you and set them
via the `keeper` extension.

```groovy
keeper {
  /**
   * Optional custom jvm arguments to pass into the R8 `PrintUses` execution. Useful if you want
   * to enable debugging in R8. Default is empty.
   *
   * Example: `listOf("-Xdebug", "-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=y")`
   */
  r8JvmArgs = []
}
```

## TraceReferences

!!! warning "Heads up!"
    TraceReferences is still experimental. Before reporting issues, please check if it's an issue with Keeper or TraceReferences itself before filing. If it's an issue with TraceReferences, please file on the Android issue tracker instead: https://issuetracker.google.com/issues/new?component=326788

`TraceReferences` is a new tool for generating Proguard rules based on one jar's usage of classes
in another jar. It is the formal successor and long-term replacement for the current `PrintUses` CLI
that Keeper currently uses.

```kotlin
keeper {
  traceReferences {
    // Invoking this enables it!

    // Optional: extra arguments to pass to TraceReferences
    arguments.set(listOf("--map-diagnostics:MissingDefinitionsDiagnostic", "error", "info"))
  }
}
```

Note that this uses the R8 version controlled by the R8 version defined by the `keeperR8` version.
If you want to change it, set the version in `keeperR8` to whichever version you want (must be
higher than `3.0.9-dev`).
