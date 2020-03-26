Changelog
=========

0.3.0
-----

_2020-03-26_

* Keeper now uses Zipflinger for packaging, which should give a nice speed boost in creating
intermediate jars.
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
