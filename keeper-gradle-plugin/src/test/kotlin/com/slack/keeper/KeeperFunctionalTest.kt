/*
 * Copyright (C) 2020 Slack Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.slack.keeper

import com.google.common.truth.Truth.assertThat
import com.squareup.javapoet.ClassName
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.intellij.lang.annotations.Language
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import java.io.File
import java.util.zip.ZipFile
import javax.lang.model.element.Modifier.STATIC
import com.squareup.kotlinpoet.ClassName as KpClassName

/**
 * Testing gradle plugins is finicky. If you get errors when running from the IDE, try following
 * these instructions: https://stackoverflow.com/a/44692954/3323598
 *
 * Alternatively - run `./gradlew pluginUnderTestMetadata` once from the command line to generate
 * the metadata file and then try again from the IDE.
 *
 * Doubly alternatively - only run tests from the command line. GradleRunner doesn't have great IDE
 * integration and it's not worth the trouble for a small test suite like this if it's not
 * cooperating.
 *
 * To debug the Gradle plugin's code itself, uncomment the `.withDebug()` line in [runGradle]
 * function. Normal debugging in the test code doesn't require this.
 *
 * ---
 *
 * The basic test project structure is roughly this:
 *
 * ```
 * projectDir
 *   - build.gradle
 *   - proguardConfigOutput.pro
 *   - src
 *     - androidTest
 *       - AndroidManifest.xml
 *       - java/com/slack/keeper/example
 *         - ApplicationUsedClass.java
 *         - SampleApplication.java
 *         - TestOnlyClass.java
 *         - TestOnlyKotlinClass.kt
 *         - UnusedClass.java
 *     - main
 *       - AndroidManifest.xml
 *       - java/com/slack/keeper/example
 *         - TestOnlyClassCaller.java
 *         - TestOnlyKotlinClassCaller.kt
 * ```
 */
@RunWith(Parameterized::class)
class KeeperFunctionalTest(private val minifierType: MinifierType) {

  companion object {
    @JvmStatic
    @Parameters(name = "{0}")
    fun data(): List<Array<*>> {
      return listOf(*MinifierType.values().map { arrayOf(it) }.toTypedArray())
    }
  }

  /**
   * Represents a minifier type.
   *
   * @property taskName The representation in a gradle task name.
   * @property expectedRules The expected generated rules outputted by `-printconfiguration`.
   * @property gradleArgs The requisite gradle invocation parameters to enable this minifier.
   */
  enum class MinifierType(
      val taskName: String,
      val expectedRules: String,
      vararg val gradleArgs: String
  ) {
    R8("R8", EXPECTED_GENERATED_RULES, "-Pandroid.enableR8=true"),
    PROGUARD("Proguard", EXPECTED_PROGUARD_CONFIG, "-Pandroid.enableR8=false")
  }

  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

  /**
   * Basic smoke test. This covers the standard flow and touches on the following:
   * - Variant configuration
   * - Packaging of intermediate jars with compiled sources (including Kotlin sources)
   * - Generation of inferred rules
   * - Inclusion of generated rules in the final proguard configuration
   */
  @Test
  fun standard() {
    val (projectDir, proguardConfigOutput) = prepareProject(temporaryFolder)

    val result = runGradle(projectDir, "assembleReleaseAndroidTest")
    assertThat(result.resultOf("jarReleaseAndroidTestClassesForKeeper")).isEqualTo(
        TaskOutcome.SUCCESS)
    assertThat(result.resultOf("jarReleaseClassesForKeeper")).isEqualTo(
        TaskOutcome.SUCCESS)
    assertThat(result.resultOf("inferReleaseAndroidTestUsageForKeeper")).isEqualTo(TaskOutcome.SUCCESS)

    // Ensure the expected parameterized minifiers ran
    val agpVersion = AgpVersionHandler.getInstance()
    assertThat(result.resultOf(agpVersion.interpolatedTaskName(minifierType.taskName, "Release"))).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.resultOf(agpVersion.interpolatedTaskName(minifierType.taskName, "ReleaseAndroidTest"))).isEqualTo(TaskOutcome.SUCCESS)

    // Assert we correctly packaged app classes
    val appJar = projectDir.generatedChild("app.jar")
    val appClasses = ZipFile(appJar).readClasses()
    assertThat(appClasses).containsAtLeastElementsIn(EXPECTED_APP_CLASSES)
    assertThat(appClasses).containsNoneIn(EXPECTED_ANDROID_TEST_CLASSES)

    // Assert we correctly packaged androidTest classes
    val androidTestJar = projectDir.generatedChild("androidTest.jar")
    val androidTestClasses = ZipFile(androidTestJar).readClasses()
    assertThat(androidTestClasses).containsAtLeastElementsIn(EXPECTED_ANDROID_TEST_CLASSES)
    assertThat(androidTestClasses).containsNoneIn(EXPECTED_APP_CLASSES)

    // Assert we correctly generated rules
    val generatedRules = projectDir.generatedChild("inferredUsage.pro")
    assertThat(generatedRules.readText().trim()).isEqualTo(EXPECTED_GENERATED_RULES)

    // Finally - verify our rules were included in the final minification execution.
    // Have to compare slightly different strings because proguard's format is a little different
    assertThat(proguardConfigOutput.readText().trim()).contains(minifierType.expectedRules)
  }

  // TODO test cases
  //  Transitive androidTest deps using transitive android deps (i.e. like IdlingResource)
  //  multidex (zip64 use in jars)
  //  Error cases
  //    missing variants - but shouldn't be necessary after https://github.com/slackhq/keeper/issues/2
  //    ordering - missing android jar message
  //  Custom r8 versions

  private fun runGradle(projectDir: File, vararg args: String): BuildResult {
    return GradleRunner.create()
        .forwardStdOutput(System.out.writer())
        .forwardStdError(System.err.writer())
        .withProjectDir(projectDir)
        .withArguments("--stacktrace", "-x", "lint", *minifierType.gradleArgs, *args)
        .withPluginClasspath()
//        .withDebug(true)
        .build()
  }

  private fun BuildResult.resultOf(name: String): TaskOutcome {
    return task(name.prefixIfNot(":"))?.outcome
        ?: error("Could not find task '$name', which is usually an indication that it didn't run. See GradleRunner's printed task graph for more details.")
  }
}

private fun String.prefixIfNot(prefix: String) =
    if (this.startsWith(prefix)) this else "$prefix$this"

@Language("PROGUARD")
private val EXPECTED_GENERATED_RULES = """
  -keep class com.slack.keeper.sample.TestOnlyClass {
    public static void testOnlyMethod();
  }
  -keep class com.slack.keeper.sample.TestOnlyKotlinClass {
    public void testOnlyMethod();
    com.slack.keeper.sample.TestOnlyKotlinClass INSTANCE;
  }
""".trimIndent()

@Language("PROGUARD")
private val EXPECTED_PROGUARD_CONFIG = """
    -keep class com.slack.keeper.sample.TestOnlyClass {
        public static void testOnlyMethod();
    }
    
    -keep class com.slack.keeper.sample.TestOnlyKotlinClass {
        com.slack.keeper.sample.TestOnlyKotlinClass INSTANCE;
        public void testOnlyMethod();
    }
""".trimIndent()

@Language("PROGUARD")
private val TEST_PROGUARD_RULES = """
  # Basically don't do anything to androidTest code
  -dontskipnonpubliclibraryclassmembers
  -dontoptimize
  -dontobfuscate
  -dontshrink
  -ignorewarnings
  -dontnote **
""".trimIndent()

@Language("groovy")
private val BUILD_GRADLE_CONTENT = """
  buildscript {
    repositories {
      google()
      mavenCentral()
      jcenter()
    }

    dependencies {
      // Note: this version doesn't really matter, the plugin's version will override it in the test
      classpath "com.android.tools.build:gradle:3.5.3"
      classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:1.3.61"
    }
  }

  plugins {
    id 'com.slack.keeper' apply false
  }

  apply plugin: 'com.android.application'
  apply plugin: 'org.jetbrains.kotlin.android'
  apply plugin: 'com.slack.keeper'

  android {
    compileSdkVersion 29

    defaultConfig {
      applicationId "com.slack.keeper.sample"
      minSdkVersion 21
      targetSdkVersion 29
    }

    buildTypes {
      release {
        minifyEnabled = true
        signingConfig = buildTypes.debug.signingConfig
        proguardFiles getDefaultProguardFile('proguard-android.txt'), 'printconfiguration.pro'
        testProguardFiles('proguard-test-rules.pro')
      }
    }

    testBuildType = "release"
  }

  repositories {
    google()
    mavenCentral()
    jcenter()
  }
  
  dependencies {
    //noinspection DifferentStdlibGradleVersion
    implementation "org.jetbrains.kotlin:kotlin-stdlib:1.3.61"
  }
""".trimIndent()

private val MAIN_SOURCES = setOf(
    // Class that's accessed from the application but not test sources
    javaFile("com.slack.keeper.sample", "ApplicationUsedClass") {
      methodSpec("applicationCalledMethod") {
        addModifiers(STATIC)
        addComment("This method is called from the application class")
      }
    },
    javaFile("com.slack.keeper.sample", "SampleApplication") {
      superclass(ClassName.get("android.app", "Application"))
      methodSpec("onCreate") {
        addAnnotation(Override::class.java)
        addStatement("\$T.applicationCalledMethod()",
            ClassName.get("com.slack.keeper.sample", "ApplicationUsedClass"))
      }
    },
    // Class that's only accessed from androidTest
    javaFile("com.slack.keeper.sample", "TestOnlyClass") {
      methodSpec("testOnlyMethod") {
        addModifiers(STATIC)
        addComment("This method is only called from androidTest sources!")
      }
    },
    // Class that's only accessed from androidTest
    kotlinFile("com.slack.keeper.sample", "TestOnlyKotlinClass") {
      funSpec("testOnlyMethod") {
        addComment("This method is only called from androidTest sources!")
      }
    },
    // Class that's only accessed from androidTest
    javaFile("com.slack.keeper.sample", "UnusedClass") {
      methodSpec("unusedMethod") {
        addModifiers(STATIC)
        addComment("This class and method are completely unused")
      }
    }
)

private val ANDROID_TEST_SOURCES = setOf(
    // AndroidTest file that uses the TestOnlyClass
    javaFile("com.slack.keeper.sample", "TestOnlyClassCaller") {
      methodSpec("callTestOnlyMethod") {
        addStatement("\$T.testOnlyMethod()",
            ClassName.get("com.slack.keeper.sample", "TestOnlyClass"))
      }
    },
    // AndroidTest file that uses the TestOnlyKotlinClass
    kotlinFile("com.slack.keeper.sample", "TestOnlyKotlinClassCaller") {
      funSpec("callTestOnlyMethod") {
        addStatement("%T.testOnlyMethod()",
            KpClassName("com.slack.keeper.sample", "TestOnlyKotlinClass"))
      }
    }
)

// We include Unit.class here because that allows us to also test that App's transitive dependencies
// are included in the jar and excluded from the androidTest jar (anything present in both is only
// stored in the app jar). Unit is from the Kotlin stdlib.
private val EXPECTED_APP_CLASSES: Set<String> = MAIN_SOURCES.mapToSet {
  "${it.name}.class"
} + "Unit.class"

private val EXPECTED_ANDROID_TEST_CLASSES: Set<String> = ANDROID_TEST_SOURCES.mapToSet {
  "${it.name}.class"
}

private data class ProjectData(val dir: File, val proguardConfigOutput: File)

private fun prepareProject(temporaryFolder: TemporaryFolder): ProjectData {
  // Create fixture
  val projectDir = temporaryFolder.newFolder("testProject")
  projectDir.newFile("build.gradle").apply { writeText(BUILD_GRADLE_CONTENT) }
  projectDir.newFile("proguard-test-rules.pro") { writeText(TEST_PROGUARD_RULES) }
  projectDir.newFile("src/main/AndroidManifest.xml") {
    //language=xml
    writeText("""
      <?xml version="1.0" encoding="utf-8"?>
      <manifest package="com.slack.keeper.sample">
        <application name="com.slack.keeper.sample.SampleApplication" />
      </manifest>
    """.trimIndent())
  }
  projectDir.newFile("src/androidTest/AndroidManifest.xml") {
    //language=xml
    writeText("""
      <?xml version="1.0" encoding="utf-8"?>
      <manifest package="com.slack.keeper.sample" />
    """.trimIndent())
  }

  val mainSources = projectDir.newDir("src/main/java")
  MAIN_SOURCES.forEach {
    mainSources += it
  }

  val androidTestSources = projectDir.newDir("src/androidTest/java")
  ANDROID_TEST_SOURCES.forEach {
    androidTestSources += it
  }

  // To verify we correctly wired the generated rules into the minification task, we add a custom
  // second proguard file that just specifies `-printconfiguration` pointing to an output file
  // that we can read to verify our generated rules were added.
  val proguardConfigOutput = projectDir.newFile("proguardConfigOutput.pro")
  projectDir.newFile("printconfiguration.pro") {
    writeText("""
      -printconfiguration ${proguardConfigOutput.absolutePath}
    """.trimIndent())
  }

  return ProjectData(projectDir, proguardConfigOutput)
}

private fun <T, R> Collection<T>.mapToSet(mapper: (T) -> R): Set<R> {
  return mapTo(mutableSetOf(), mapper)
}