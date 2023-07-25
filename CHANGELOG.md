Changelog
=========

0.15.0
------

_2023-07-25_

- **Enhancement**: Support AGP 8.1.0. AGP 8.1.0 is the minimum required version now due to breaking API changes.
- Update Gradle to `8.2.1`.

0.14.0
------

_2023-04-15_

Happy new year!

- **Enhancement**: Support AGP 8.0. AGP 7.x and lower are no longer supported.
- **Enhancement**: Use new AGP APIs for retrieving all compiled classes, better supporting configuration cache.
- **Enhancement**: Better support configuration cache in Gradle 8.x in general.
- **Enhancement**: Add new `keeper.disableTestProguardFiles` flag gradle property to optionally disable merging in test proguard files.
- **Enhancement**: `InferAndroidTestKeepRules` no longer extends `JavaExec`.
- **Enhancement**: `PrintUses` support is removed entirely.
- **Fix**: Use `artifactFiles` instead of `resolvedArtifacts` for resolving proguard files.

0.13.0
------

_2022-12-09_

- **Enhancement:** Use Gradle 7.5's new `ArtifactCollection` APIs to for better task input wiring. This allows us to remove the previous hack where we used the target `Configuration` as an internal property. This also improves configuration caching support. Gradle 7.5 is now the minimum required Gradle version.
- **Enhancement:** Use AGP's new `Component.runtimeConfiguration` API. AGP 7.3 is now the minimum required AGP version.
- **Enhancement:** Removed KGP and kotlin-dsl dependencies. Keeper inappropriately included implementation dependencies on these two and no longer does. This is a breaking change for users who were using these dependencies in their build scripts and you should add them separately if you're using them.
- **Removed:** `PrintUses` is no longer supported as this API has been removed from R8 and now only `TraceReferences` is used.
- **Removed**: Removed deprecated `enableL8RuleSharing` and `traceReferences.enabled` properties in the Keeper gradle extension.

0.12.0
------

_2022-02-02_

**New:** Update to AGP 7.1 APIs

Keeper now requires AGP 7.1.0 or higher and has been reworked to use AGP's new extension APIs. As
such, the `variantFilter` API has now been removed.

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

**Other updates**

- Remove shaded ZipFlinger APIs, ZipFlinger is now used directly
- Update Kotlin to `1.6.10`

0.11.2
------

_2022-01-27_

* **Fix:** Fix a configuration caching issue when L8 rule sharing is enabled.

0.11.1
------

_2021-08-31_

* **Fix:** Keeper's intermediate jar creation tasks previously accepted dependencies inputs based on content only. This caused build caching issues across multiple machines with Gradle remote cache enabled because it would then attempt to use the previous build's absolute path to those dependency jars and subsequently fail. This is now fixed by reworking this logic internally a bit and using absolute path sensitivity for that input. This should ensure that caching still works like before across the same machine/paths but will properly be out of date when the absolute paths change across different machines.

0.11.0
------

_2021-07-29_

* Minimum supported AGP version is now `7.0.0`. It may work on older versions but YMMV.
* Update embedded ZipFlinger to `7.0.0` and update to its new `Path` constructors.
* Update to Kotlin `1.5.21`

0.10.0
------

_2021-06-09_

* Core Library Desugaring is now fully supported. See its
[section](https://slackhq.github.io/keeper/#core-library-desugaring-l8-support) in on the project
site for more details!
* The `keeper.enableL8RuleSharing` Gradle API is now deprecated and does nothing. L8 support is
automatically enabled if `android.compileOptions.coreLibraryDesugaringEnabled` is true in AGP.

0.9.0
-----

_2021-05-20_

* **New:** Support for Gradle 7.0.
  * If you read diagnostic outputs, note that these are now always written to a unique directory per task.
* **New:** Experimental support for AGP 7.0.0 (tested up to beta01).
* **New:** Support for JDK 11 (possibly higher, but we test only JDK 11).
* We no longer ship a copy of ZipFlinger and now just use it directly from AGP.
* AGP 4.1.x is no longer supported.
  * It may work, but we no longer test against it. New minimum supported version is AGP 4.2.0.
* Proguard is no longer supported.
  * It may work, but we no longer test against it. Only R8 is officially supported now.

0.8.0
-----

_2021-01-04_

* **New:** Experimental support for the new upcoming `TraceReferences` tool from R8, which is the
successor for the current `PrintUses` CLI that Keeper uses. See [configuration instructions](https://slackhq.github.io/keeper/configuration/#tracereferences) for more details.
* Updated to Kotlin 1.4.21.

Special thanks to [@pgreze](https://github.com/pgreze) for contributing the TraceReferences support in this release!

0.7.0
-----

_2020-09-25_

* **New:** `enableL8RuleSharing` feature for sharing generated proguard rules between androidtest and main APK assembly. See [configuration instructions](https://slackhq.github.io/keeper/configuration/#l8-rule-sharing) for more details.
* Make the default behavior to ignore minifyEnabled = false variants ([#61](https://github.com/slackhq/keeper/pull/61))
* Built against Kotlin 1.4, using its new `explicitApi()` mode
* ZipFlinger is now manually copied in due to shadow jar packaging issues. This version is current with AGP 4.1.0-rc03

Special thanks to [@colintheshots](https://github.com/colintheshots) for contributing to this release.

!!! warning "Heads up!"
    We're considering dropping support for Proguard. Please let us know if you need this.

0.6.0
-----

_2020-06-09_

* Keeper now supports Gradle [Configuration Caching](https://docs.gradle.org/6.5-rc-1/userguide/configuration_cache.html)
  * Note: At the time of writing, Gradle's `JavaExec` task (which Keeper uses) does not support it yet. This is expected to be resolved in Gradle 6.6.
* Keeper requires AGP 4.0.0 (minimum) now.
* Diagnostics improvements
  * Enabling extra debug information now also triggers printing of text files with a list of all seen classes in respective jars, for easy checking.
  * Diagnostics are now under build/intermediates/keeper/diagnostics.
  * Note that "app jars" are not under the new diagnostics directory, but rather in the main keeper directory. These are now used as a task input internally.
* Some minor under the hood enhancements to avoid processing app classpath twice.

0.5.0
-----

_2020-05-28_

* Keeper now supports AGP 4.0 and 4.1!
* New `enableAssertions` property to control assertions behavior when running R8's `PrintUses` CLI.
This can be useful when debugging errors, as R8 will print those errors out to the generated proguard
file instead if you disable this (the default is enabled).

```kotlin
keeper {
 enableAssertions.set(false)
}
```

0.4.3
-----

_2020-05-17_

* ZipFlinger updated to 4.1.0-alpha09, which allows us to support Zip64. To avoid conflicts with AGP,
we now shade ZipFlinger in directly.
* Kotlin 1.3.72

**Note: versions 0.4.0-0.4.2 had packaging issues, please skip to 0.4.3.**

0.3.2
-----

_2020-05-6_

* Fix support for Gradle 6.4. We assumed that the new `exclusiveContent` API on maven repositories
would be fixed in this version, but it had another regression.

0.3.1
-----

_2020-03-28_

**Edit:** Release failed to upload, we're working on a fix. In the meantime continue to use 0.3.0
and the regular non-`plugins {}` approach.

Keeper can now be consumed via regular gradle `plugins {}` block.

```kotlin
plugins {
  id("com.slack.keeper") version "0.3.1"
}
```

Note that we still publish to Maven Central, so you would need to add it to the repositories list
in `settings.gradle`.

```gradle
pluginsManagement {
  repositories {
    mavenCentral() // woo-hoo!
    gradlePluginPortal() // there by default
  }
}
```

0.3.0
-----

_2020-03-26_

* Keeper now uses Zipflinger for packaging, which should give a nice speed boost in creating
intermediate jars.

Perf comparisons on the slack app:

| Task | Before | After |
|-|--------|-------|
| inferExternalStagingAndroidTestKeepRulesForKeeper | 14.126s | 11.138s |
| jarExternalStagingClassesForKeeper | 10.364s | 6.347s |
| jarExternalStagingAndroidTestClassesForKeeper | 4.504s | 2.609s |

* Keeper now only supports AGP 3.6 or higher. If you still need AGP 3.5.x support, please continue
using Keeper 0.2.0 or lower.
* Update Kotlin to 1.3.71.

0.2.0
-----

_2020-02-12_

### New Variant Filter API [#14](https://github.com/slackhq/keeper/pull/14)
You can specify a variantFilter on the keeper extension to dynamically configure which variants Keeper
operates on (similar to the Android Gradle Plugin's VariantFilter API).

```groovy
keeper {
  variantFilter {
    if (name == "variantThatShouldTotallyBeIgnored") {
      setIgnore(true)
    }
  }
}
```

### R8 Repository Management Opt-Out [#17](https://github.com/slackhq/keeper/pull/17)
If you don't want Keeper to automatically manage adding R8's maven repo, you can set disable it via
`automaticR8RepoManagement`. Note that you'll need to manually add your own repo that the `keeperR8`
configuration can resolve from.

```groovy
keeper {
  automaticR8RepoManagement = false
}

// Example demo of how to configure your own R8 repo
repositories {
  maven {
    url = uri("https://storage.googleapis.com/r8-releases/raw")
    content {
      includeModule("com.android.tools", "r8")
    }
  }
}
```

### Preliminary support for AGP 3.6

We've tested up to 3.6-rc03, let us know if you see any issues!

0.1.0
-----

_2020-01-29_

Initial release!
