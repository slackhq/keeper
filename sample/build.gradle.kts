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
import com.android.build.gradle.internal.tasks.R8Task
import com.google.common.truth.Truth.assertThat
import com.slack.keeper.InferAndroidTestKeepRules
import com.slack.keeper.optInToKeeper

buildscript {
  dependencies {
    // Truth has nice string comparison APIs and error messages
    classpath(libs.truth)
  }
}

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("com.slack.keeper")
}

android {
  compileSdk = 33
  namespace = "com.slack.keeper.sample"

  defaultConfig {
    applicationId = "com.slack.keeper.example"
    minSdk = 21
    targetSdk = 33
    versionCode = 1
    versionName = "1"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testApplicationId = "com.slack.keeper.sample.androidTest"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    isCoreLibraryDesugaringEnabled = true
  }

  // I know it looks like this shouldn't be necessary in the modern age of Kotlin Android
  // development. But I assure you, it most certainly is. If you try to remove it, remember to check
  // here if you see TestOnlyClass missing from proguard rules, as it's called from a Java file that
  // is, somehow, protected by this block.
  sourceSets {
    maybeCreate("main").java.srcDirs("src/main/kotlin")
    maybeCreate("androidTest").java.srcDirs("src/androidTest/kotlin")
  }

  buildTypes {
    debug { matchingFallbacks += listOf("release") }
    release {
      isMinifyEnabled = true
      signingConfig = signingConfigs.getByName("debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard.pro")
      testProguardFile("test-rules.pro")
      matchingFallbacks += listOf("release")
    }
    create("staging") {
      initWith(getByName("release"))
      isDebuggable = true
    }
  }

  flavorDimensionList.add("environment")
  productFlavors {
    create("internal") {
      dimension = "environment"
      applicationIdSuffix = ".internal"
      versionNameSuffix = "-internal"
    }

    create("external") { dimension = "environment" }
  }

  testBuildType = "staging"
}

// Example: Only enable on "externalStaging"
androidComponents {
  beforeVariants { variantBuilder ->
    if (variantBuilder.name == "externalStaging") {
      variantBuilder.optInToKeeper()
    }
  }
}

keeper {
  // Example: emit extra debug information during Keeper's execution.
  emitDebugInformation.set(true)

  // Example: automatic R8 repo management (more below)
  automaticR8RepoManagement.set(false)

  // Uncomment this line to debug the R8 from a remote instance.
  // r8JvmArgs.addAll(Arrays.asList("-Xdebug",
  // "-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=y"))

  traceReferences {
    // Don't fail the build if missing definitions are found.
    arguments.set(listOf("--map-diagnostics:MissingDefinitionsDiagnostic", "error", "info"))
  }
}

// To speed up testing, we can also eagerly check that the generated rules match what we expect.
// This is _solely_ for CI use with Keeper!
val isCi = providers.environmentVariable("CI").orElse("false").get() == "true"

if (isCi) {
  // TODO create a dependent lifecycle task
  tasks.withType<InferAndroidTestKeepRules>().configureEach {
    doLast {
      println("Checking expected rules")
      val outputRules = outputProguardRules.asFile.get().readText().trim()
      val expectedRules = file("expectedRules.pro").readText().trim()
      if (outputRules != expectedRules) {
        System.err.println(
          "Rules don't match expected, output rules are below. Compare them with 'expectedRules.pro'"
        )
        assertThat(outputRules).isEqualTo(expectedRules)
      }
    }
  }

  tasks.register("validateL8") {
    dependsOn("l8DexDesugarLibExternalStaging")
    doFirst {
      println("Checking expected input rules from diagnostics output")
      val diagnosticFilePath =
        "build/intermediates/keeper/l8-diagnostics/l8DexDesugarLibExternalStaging/patchedL8Rules.pro"
      val diagnostics = file(diagnosticFilePath).readText()
      if ("-keep class j\$.time.Instant" !in diagnostics) {
        throw IllegalStateException(
          "L8 diagnostic rules include the main variant's R8-generated rules, see $diagnosticFilePath"
        )
      }
    }
  }

  tasks
    .withType<R8Task>()
    .matching { it.name == "minifyExternalStagingWithR8" }
    .configureEach {
      doLast {
        println("Checking expected configuration contains embedded library rules from androidTest")
        val output = getProguardConfigurationOutput()
        val allConfigurations = output.get().readText()
        logger.lifecycle("Verifying R8 configuration contents")
        if ("-keep class slack.test.only.Android { *; }" !in allConfigurations) {
          throw IllegalStateException(
            "R8 configuration doesn't contain embedded library rules from androidTest. Full contents:\n$allConfigurations"
          )
        }
        if ("-keep class slack.test.only.Embedded { *; }" !in allConfigurations) {
          throw IllegalStateException(
            "R8 configuration doesn't contain embedded library rules from androidTest. Full contents:\n$allConfigurations"
          )
        }
      }
    }
}

dependencies {
  implementation(project(":sample-libraries:a"))

  coreLibraryDesugaring(libs.desugarJdkLibs)

  androidTestImplementation(project(":sample-libraries:c"))
  androidTestImplementation(libs.okio)
  androidTestImplementation(libs.androidx.annotation)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestUtil(libs.androidx.test.orchestrator)
  androidTestImplementation(libs.androidx.test.truth)
  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.truth)
  androidTestImplementation(project(":sample-libraries:test-only-android"))
  androidTestImplementation(project(":sample-libraries:test-only-jvm"))
}
