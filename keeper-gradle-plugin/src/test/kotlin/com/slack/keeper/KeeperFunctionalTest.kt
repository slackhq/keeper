/*
 * Copyright (C) 2020 Slack Technologies, LLC
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
import com.slack.keeper.KeeperPlugin.Companion.interpolateR8TaskName
import com.squareup.javapoet.ClassName
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.BuildTask
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
internal class KeeperFunctionalTest(private val minifierType: MinifierType) {

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
   * @property keeperExtraConfig Extra [KeeperExtension] configuration.
   */
  enum class MinifierType(
      val taskName: String,
      val expectedRules: Map<String, List<String>?>,
      val keeperExtraConfig: KeeperExtraConfig = KeeperExtraConfig.NONE
  ) {
    R8_PRINT_USES("R8", EXPECTED_PRINT_RULES_CONFIG),
    R8_TRACE_REFERENCES("R8", EXPECTED_TRACE_REFERENCES_CONFIG,
      keeperExtraConfig = KeeperExtraConfig.TRACE_REFERENCES_ENABLED)
  }

  @Rule
  @JvmField
  val temporaryFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

  /**
   * Basic smoke test. This covers the standard flow and touches on the following:
   * - Variant configuration
   * - Packaging of intermediate jars with compiled sources (including Kotlin sources)
   * - Generation of inferred rules
   * - Inclusion of generated rules in the final proguard configuration
   */
  @Test
  fun standard() {
    val (projectDir, proguardConfigOutput) = prepareProject(temporaryFolder,
        buildGradleFile("staging", keeperExtraConfig = minifierType.keeperExtraConfig))

    val result = projectDir.runAsWiredStaging()

    // Ensure the expected parameterized minifiers ran
    assertThat(result.resultOf(interpolateR8TaskName("ExternalStaging")))
        .isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.resultOf(interpolateR8TaskName("ExternalStagingAndroidTest")))
        .isEqualTo(TaskOutcome.SUCCESS)

    // Assert we correctly packaged app classes
    val appJar = projectDir.generatedChild("externalStaging/classes.jar")
    val appClasses = ZipFile(appJar).readClasses()
    assertThat(appClasses).containsAtLeastElementsIn(EXPECTED_APP_CLASSES)
    assertThat(appClasses).containsNoneIn(EXPECTED_ANDROID_TEST_CLASSES)

    // Assert we correctly packaged androidTest classes
    val androidTestJar = projectDir.generatedChild("externalStagingAndroidTest/classes.jar")
    val androidTestClasses = ZipFile(androidTestJar).readClasses()
    assertThat(androidTestClasses).containsAtLeastElementsIn(EXPECTED_ANDROID_TEST_CLASSES)
    assertThat(androidTestClasses).containsNoneIn(EXPECTED_APP_CLASSES)

    // Assert we correctly generated rules
    val generatedRules = projectDir.generatedChild(
        "externalStagingAndroidTest/inferredKeepRules.pro")
    assertThat(generatedRules.readText().trim()).isEqualTo(
      minifierType.expectedRules.map { indentRules(it.key, it.value) }.joinToString("\n")
    )

    // Finally - verify our rules were included in the final minification execution.
    // Have to compare slightly different strings because proguard's format is a little different
    assertThat(proguardConfigOutput.readText().trim().replace("    ", "  ")).let { assertion ->
      minifierType.expectedRules.forEach {
        assertion.contains(indentRules(it.key, it.value))
      }
    }
  }

  // Asserts that our extension marker properly opts variants in. In our fixture project, the
  // "externalRelease" build variant will be ignored, while tasks will be generated for the
  // "internalRelease" variant.
  @Test
  fun extensionMarker() {
    val (projectDir, _) = prepareProject(temporaryFolder, buildGradleFile("release",
        androidExtraConfig = AndroidExtraConfig.ONLY_INTERNAL_RELEASE))

    val result = runGradle(projectDir, "assembleExternalRelease", "assembleInternalRelease", "-x",
        "lintVitalExternalRelease", "-x", "lintVitalInternalRelease")
    assertThat(result.findTask("jarExternalReleaseAndroidTestClassesForKeeper")).isNull()
    assertThat(result.findTask("jarExternalReleaseClassesForKeeper")).isNull()
    assertThat(result.findTask("inferExternalReleaseAndroidTestKeepRulesForKeeper")).isNull()

    assertThat(result.resultOf("jarInternalReleaseAndroidTestClassesForKeeper")).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.resultOf("jarInternalReleaseClassesForKeeper")).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.resultOf("inferInternalReleaseAndroidTestKeepRulesForKeeper")).isEqualTo(TaskOutcome.SUCCESS)
  }

  // Asserts that if Keeper was configured to create keep rules for a variant that isn't minified,
  // an error will be emitted, and the tasks won't be created.
  @Test
  fun extensionMarkerWarning() {
    // internalDebug variant isn't minified, but the variant is opted into Keeper.
    val (projectDir, _) = prepareProject(temporaryFolder, buildGradleFile("debug",
        androidExtraConfig = AndroidExtraConfig.ONLY_INTERNAL_DEBUG))

    val result = runGradle(projectDir, "assembleInternalDebug")

    // Keeper doesn't create the tasks.
    assertThat(result.findTask("jarInternalDebugAndroidTestClassesForKeeper")).isNull()
    assertThat(result.findTask("jarInternalDebugClassesForKeeper")).isNull()
    assertThat(result.findTask("inferInternalDebugAndroidTestKeepRulesForKeeper")).isNull()
    assertThat(result.output).contains(
        "Keeper is configured to generate keep rules for the \"internalDebug\" build variant")
  }

  // Ensures that manual R8 repo management works
  @Test
  fun manualR8RepoManagement() {
    val (projectDir, _) = prepareProject(temporaryFolder, buildGradleFile("staging", false))
    projectDir.runAsWiredStaging()
  }

  @Test
  fun duplicateClassesWarning() {
    val buildFile = buildGradleFile(
        testBuildType = "staging",
        emitDebugInformation = true,
        extraDependencies = mapOf(
            "implementation" to "\"org.threeten:threetenbp:1.4.0:no-tzdb\"",
            "androidTestImplementation" to "\"org.threeten:threetenbp:1.4.0\""
        )
    )
    val (projectDir, _) = prepareProject(temporaryFolder, buildFile)
    projectDir.runSingleTask("jarExternalStagingAndroidTestClassesForKeeper")

    // Check that we emitted a duplicate classes file
    val duplicateClasses = projectDir.generatedChild(
        "externalStagingAndroidTest/diagnostics/duplicateClasses.txt")
    assertThat(duplicateClasses.readText().trim()).isNotEmpty()
  }

  // TODO test cases
  //  Transitive androidTest deps using transitive android deps (i.e. like IdlingResource)
  //  multidex (zip64 use in jars)

  private fun File.runSingleTask(name: String): BuildResult {
    val result = runGradle(this, name, "-x", "lintVitalExternalStaging")
    assertThat(result.resultOf(name)).isEqualTo(
        TaskOutcome.SUCCESS)
    return result
  }

  private fun File.runAsWiredStaging(): BuildResult {
    val result = runSingleTask("assembleExternalStagingAndroidTest")
    assertThat(result.resultOf("jarExternalStagingAndroidTestClassesForKeeper")).isEqualTo(
        TaskOutcome.SUCCESS)
    assertThat(result.resultOf("jarExternalStagingClassesForKeeper")).isEqualTo(
        TaskOutcome.SUCCESS)
    assertThat(result.resultOf("inferExternalStagingAndroidTestKeepRulesForKeeper")).isEqualTo(
        TaskOutcome.SUCCESS)
    return result
  }

  private fun runGradle(projectDir: File, vararg args: String): BuildResult {
    val extraArgs = args.toMutableList()
    extraArgs += "--stacktrace"
    return GradleRunner.create()
        .forwardStdOutput(System.out.writer())
        .forwardStdError(System.err.writer())
        .withProjectDir(projectDir)
        // TODO eventually test with configuration caching enabled
        // https://docs.gradle.org/nightly/userguide/configuration_cache.html#testkit
        .withArguments(extraArgs)
        .withPluginClasspath()
        .withDebug(true) // Tests run in-process and way faster with this enabled
        .build()
  }

  private fun BuildResult.findTask(name: String): BuildTask? {
    return task(name.prefixIfNot(":"))
  }

  private fun BuildResult.resultOf(name: String): TaskOutcome {
    return findTask(name)?.outcome
        ?: error(
            "Could not find task '$name', which is usually an indication that it didn't run. See GradleRunner's printed task graph for more details.")
  }
}

private fun String.prefixIfNot(prefix: String) =
    if (this.startsWith(prefix)) this else "$prefix$this"

@Language("PROGUARD")
private val EXPECTED_TRACE_REFERENCES_CONFIG: Map<String, List<String>?> = mapOf(
  "-keep class com.slack.keeper.sample.TestOnlyClass" to listOf(
    "public static void testOnlyMethod();"
  ),
  "-keep class com.slack.keeper.sample.TestOnlyKotlinClass" to listOf(
    "public void testOnlyMethod();",
    "com.slack.keeper.sample.TestOnlyKotlinClass INSTANCE;"
  )
)

@Language("PROGUARD")
private val EXPECTED_PRINT_RULES_CONFIG = EXPECTED_TRACE_REFERENCES_CONFIG

private fun indentRules(header: String, content: List<String>?) =
  if (content == null) header else
    "$header {\n${content.joinToString("\n") { "  $it" }}\n}"

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

internal enum class KeeperExtraConfig(val groovy: String) {
  NONE(""),
  TRACE_REFERENCES_ENABLED(
      """
      traceReferences {}
      """.trimIndent()
  );
}

internal enum class AndroidExtraConfig(val groovy: String) {
  ONLY_EXTERNAL_STAGING(
      """
      androidComponents {
        beforeVariants(selector().all()) { variantBuilder ->
          if (variantBuilder.name == "externalStaging") {
            variantBuilder.registerExtension(
              com.slack.keeper.KeeperVariantMarker.class,
              com.slack.keeper.KeeperVariantMarker.INSTANCE
            )
          }
        }
      }
      """.trimIndent()
  ),
  ONLY_INTERNAL_RELEASE(
      """
      androidComponents {
        beforeVariants(selector().all()) { variantBuilder ->
          if (variantBuilder.name == "internalRelease") {
            variantBuilder.registerExtension(
              com.slack.keeper.KeeperVariantMarker.class,
              com.slack.keeper.KeeperVariantMarker.INSTANCE
            )
          }
        }
      }
      """.trimIndent()
  ),
  ONLY_INTERNAL_DEBUG(
      """
      androidComponents {
        beforeVariants(selector().all()) { variantBuilder ->
          if (variantBuilder.name == "internalDebug") {
            variantBuilder.registerExtension(
              com.slack.keeper.KeeperVariantMarker.class,
              com.slack.keeper.KeeperVariantMarker.INSTANCE
            )
          }
        }
      }
      """.trimIndent()
  );
}

@Language("groovy")
private fun buildGradleFile(
    testBuildType: String,
    automaticR8RepoManagement: Boolean = true,
    keeperExtraConfig: KeeperExtraConfig = KeeperExtraConfig.NONE,
    androidExtraConfig: AndroidExtraConfig = AndroidExtraConfig.ONLY_EXTERNAL_STAGING,
    emitDebugInformation: Boolean = false,
    extraDependencies: Map<String, String> = emptyMap()
): String {
  @Suppress("UnnecessaryVariable")
  @Language("groovy")
  val buildScript = """
  buildscript {
    repositories {
      google()
      mavenCentral()
    }

    dependencies {
      // Note: this version doesn't really matter, the plugin's version will override it in the test
      classpath "com.android.tools.build:gradle:7.1.0"
      //noinspection DifferentKotlinGradleVersion
      classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:1.6.10"
    }
  }

  plugins {
    id 'com.slack.keeper' apply false
  }

  apply plugin: 'com.android.application'
  apply plugin: 'org.jetbrains.kotlin.android'
  apply plugin: 'com.slack.keeper'

  android {
    compileSdkVersion 30

    defaultConfig {
      applicationId "com.slack.keeper.sample"
      minSdkVersion 21
      targetSdkVersion 30
    }

    buildTypes {
      release {
        minifyEnabled = true
        signingConfig = buildTypes.debug.signingConfig
        proguardFiles getDefaultProguardFile('proguard-android.txt'), 'testconfiguration.pro'
        testProguardFiles('proguard-test-rules.pro')
      }
      staging {
        initWith release
        matchingFallbacks = ['release']
      }
    }
    
    flavorDimensions "environment"
    productFlavors {
      internal {
        dimension "environment"
        applicationIdSuffix ".internal"
        versionNameSuffix "-internal"
      }

      external {
        dimension "environment"
      }
    }

    testBuildType = "$testBuildType"
  }

  repositories {
    google()
    mavenCentral()
    ${
    if (automaticR8RepoManagement) "" else """
    maven {
      url = uri("https://storage.googleapis.com/r8-releases/raw")
      content {
        includeModule("com.android.tools", "r8")
      }
    }
    """
  }
  }
  
  ${androidExtraConfig.groovy}
  
  keeper {
    emitDebugInformation.set($emitDebugInformation)
    automaticR8RepoManagement.set($automaticR8RepoManagement)
    emitDebugInformation.set($emitDebugInformation)
    ${keeperExtraConfig.groovy}
  }
  
  dependencies {
    ${extraDependencies.entries.joinToString("\n") { "    ${it.key} ${it.value}" }}
  }
""".trimIndent()
  return buildScript
}

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
        addStatement("super.onCreate()")
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
    // Class that's unused
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

private fun prepareProject(temporaryFolder: TemporaryFolder, buildFileText: String): ProjectData {
  // Create fixture
  val projectDir = temporaryFolder.newFolder("testProject")
  projectDir.newFile("build.gradle").apply { writeText(buildFileText) }
  projectDir.newFile("proguard-test-rules.pro") { writeText(TEST_PROGUARD_RULES) }
  projectDir.newFile("src/main/AndroidManifest.xml") {
    writeText("""
      <?xml version="1.0" encoding="utf-8"?>
      <manifest package="com.slack.keeper.sample">
        <application name="com.slack.keeper.sample.SampleApplication" />
      </manifest>
    """.trimIndent())
  }
  projectDir.newFile("src/androidTest/AndroidManifest.xml") {
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
  projectDir.newFile("testconfiguration.pro") {
    writeText("""
      -printconfiguration ${proguardConfigOutput.absolutePath}
      -keep class com.slack.keeper.sample.SampleApplication { *; }
      # Proguard complains about module-info classes in META-INF
      -ignorewarnings
    """.trimIndent())
  }

  return ProjectData(projectDir, proguardConfigOutput)
}

private fun <T, R> Collection<T>.mapToSet(mapper: (T) -> R): Set<R> {
  return mapTo(mutableSetOf(), mapper)
}
