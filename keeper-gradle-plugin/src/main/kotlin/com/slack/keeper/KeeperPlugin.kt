/*
 * Copyright (C) 2020 Slack Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UnstableApiUsage", "DEPRECATION")

package com.slack.keeper

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.attributes.Attribute
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.repositories
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.util.Locale
import java.util.Locale.US

internal const val TAG = "Keeper"
private const val NAME_ANDROID_TEST_JAR = "androidTest"
private const val NAME_APP_JAR = "app"
internal const val KEEPER_TASK_GROUP = "keeper"

/**
 * A simple Gradle plugin that hooks into Proguard/R8 to add extra keep rules based on what androidTest classes use from
 * the target app's sources. This is necessary because AGP does not factor in androidTest usages of target app sources
 * when running the minification step, which can result in runtime errors if APIs used by tests are removed.
 *
 * This is a workaround until AGP supports this: https://issuetracker.google.com/issues/126429384.
 *
 * This is configurable via the [`keeper`][KeeperExtension] extension. For example:
 * ```
 * keeper {
 *   androidTestVariant = "internalDebugAndroidTest"
 *   appVariant = "externalStaging"
 * }
 * ```
 *
 * The general logic flow:
 * - Create a custom `r8` configuration for the R8 dependency.
 * - Register three jar tasks. One for all the classes in the app variant, one for all the classes in the androidTest
 *   variant, and one copy of the compiled android.jar for classpath linking. This will use their variant-provided
 *   [JavaCompile] tasks and [KotlinCompile] tasks if available.
 * - Register a [`inferAndroidTestUsage`][InferAndroidTestKeepRules] task that plugs the two aforementioned jars into
 *   R8's `PrintUses` CLI and outputs the inferred proguard rules into a new intermediate .pro file.
 * - Finally - the generated file is wired in to Proguard/R8 via private task APIs (wired with [AgpVersionHandler])
 *   and setting their `configurationFiles` to include our generated one.
 *
 * Appropriate task dependencies (via inputs/outputs, not `dependsOn`) are set up, so this is automatically run as part
 * of the target app variant's full minified APK.
 *
 * The tasks themselves take roughly ~20 seconds total extra work in the Slack android app, with the infer and app jar
 * tasks each taking around 8-10 seconds and the androidTest jar taking around 2 seconds.
 */
class KeeperPlugin : Plugin<Project> {

  companion object {
    internal const val INTERMEDIATES_DIR = "intermediates/keeper"
    internal const val DEFAULT_R8_VERSION = "1.6.53"
  }

  override fun apply(project: Project) {
    with(project) {
      pluginManager.withPlugin("com.android.application") {
        val extension = project.extensions.create<KeeperExtension>("keeper")

        // Set up r8 configuration
        val r8Configuration = configurations.maybeCreate("r8")

        // This is the maven repo where r8 releases are hosted
        repositories {
          maven("https://storage.googleapis.com/r8-releases/raw")
        }

        val appExtension = project.extensions.getByType<AppExtension>()
        val intermediateAndroidTestJar = createIntermediateAndroidTestJar(extension, appExtension)
        val intermediateAppJar = createIntermediateAppJar(extension, appExtension)

        val compileSdkVersion = appExtension.compileSdkVersion ?: error("No compileSdkVersion found. Make sure to apply the keeper plugin after the android block in build.gradle.")
        val androidJar =
            File("${appExtension.sdkDirectory}/platforms/${compileSdkVersion}/android.jar").also {
              check(it.exists()) {
                "No android.jar found! Expected to find it at: $it"
              }
            }

        val inferAndroidTestUsageProvider = tasks.register(
            "inferAndroidTestUsage",
            InferAndroidTestKeepRules(
                intermediateAndroidTestJar,
                intermediateAppJar,
                androidJar,
                extension.r8JvmArgs,
                r8Configuration
            )
        )

        // Have to run the proguard task configuration afterEvaluate because the transform property isn't set until later
        afterEvaluate {
          val r8Version = extension.r8Version.getOrElse(DEFAULT_R8_VERSION)
          logger.debug("$TAG: Using R8 version '$r8Version'")
          dependencies.add(r8Configuration.name, "com.android.tools:r8:$r8Version")

          val prop = project.layout.dir(
              inferAndroidTestUsageProvider.flatMap { it.outputProguardRules.asFile })
          AgpVersionHandler.getInstance()
              .also { logger.debug("$TAG Using ${it.minVersion} patcher") }
              .applyGeneratedRules(project, extension, prop)
        }
      }
    }
  }

  /**
   * Creates an intermediate androidTest.jar consisting of all the classes compiled for the androidTest source set.
   * This output is used in the inferAndroidTestUsage task.
   */
  private fun Project.createIntermediateAndroidTestJar(
      extension: KeeperExtension,
      appExtension: AppExtension
  ): TaskProvider<out Jar> {
    return tasks.register<AndroidTestVariantClasspathJar>(
        "jarAndroidTestClassesForAndroidTestKeepRules") {
      group = KEEPER_TASK_GROUP
      val outputDir = project.layout.buildDirectory.dir(INTERMEDIATES_DIR)
      archiveBaseName.set(NAME_ANDROID_TEST_JAR)

      appExtension.applicationVariants.configureEach {
        if (name == extension.appVariant) {
          appRuntime.from(runtimeConfiguration.incoming.artifactView {
            attributes {
              attribute(Attribute.of("artifactType", String::class.java), "android-classes")
            }
          }.files)
        }
      }

      appExtension.testVariants.configureEach {
        if (name == extension.androidTestVariant) {
          from(project.layout.dir(javaCompileProvider.map { it.destinationDir }))
          androidTestRuntime.from(runtimeConfiguration.incoming.artifactView {
            attributes {
              attribute(Attribute.of("artifactType", String::class.java), "android-classes")
            }
          }.files)
        }
      }

      tasks.providerWithNameOrNull<KotlinCompile>(
          "compile${extension.androidTestVariant.capitalize(US)}Kotlin")
          ?.let { kotlinCompileTask ->
            from(project.layout.dir(kotlinCompileTask.map { it.destinationDir })) {
              include("**/*.class")
            }
          }
      destinationDirectory.set(outputDir)

      // Because we may have more than 65535 classes. Dex method limit's distant cousin.
      isZip64 = true
    }
  }

  /**
   * Creates an intermediate app.jar consisting of all the classes compiled for the target app variant. This
   * output is used in the inferAndroidTestUsage task.
   */
  private fun Project.createIntermediateAppJar(
      extension: KeeperExtension,
      appExtension: AppExtension
  ): TaskProvider<out Jar> {
    return tasks.register<VariantClasspathJar>("jarAppClassesForAndroidTestKeepRules") {
      group = KEEPER_TASK_GROUP
      val outputDir = project.layout.buildDirectory.dir(INTERMEDIATES_DIR)
      archiveBaseName.set(NAME_APP_JAR)
      appExtension.applicationVariants.configureEach {
        if (name == extension.appVariant) {
          from(project.layout.dir(javaCompileProvider.map { it.destinationDir }))
          from(javaCompileProvider.map { javaCompileTask ->
            javaCompileTask.classpath.filter { it.name.endsWith("jar") }.map { zipTree(it) }
          })
        }
      }

      tasks.providerWithNameOrNull<KotlinCompile>(
          "compile${extension.appVariant.capitalize(US)}Kotlin")
          ?.let { kotlinCompileTask ->
            from(project.layout.dir(kotlinCompileTask.map { it.destinationDir })) {
              include("**/*.class")
            }
          }
      destinationDirectory.set(outputDir)

      // Because we have more than 65535 classes. Dex method limit's distant cousin.
      isZip64 = true
    }
  }
}

private inline fun <reified T : Task> TaskContainer.providerWithNameOrNull(
    name: String): TaskProvider<T>? {
  return try {
    named<T>(name)
  } catch (e: UnknownTaskException) {
    null
  }
}

/** Copy of the stdlib version until it's stable. */
internal fun String.capitalize(locale: Locale): String {
  if (isNotEmpty()) {
    val firstChar = this[0]
    if (firstChar.isLowerCase()) {
      return buildString {
        val titleChar = firstChar.toTitleCase()
        if (titleChar != firstChar.toUpperCase()) {
          append(titleChar)
        } else {
          append(this@capitalize.substring(0, 1).toUpperCase(locale))
        }
        append(this@capitalize.substring(1))
      }
    }
  }
  return this
}
