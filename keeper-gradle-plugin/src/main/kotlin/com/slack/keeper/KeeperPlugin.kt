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
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import com.android.builder.model.BuildType
import com.android.builder.model.ProductFlavor
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.artifacts.ArtifactView
import org.gradle.api.artifacts.Configuration
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.util.Locale
import java.util.Locale.US

internal const val TAG = "Keeper"
internal const val KEEPER_TASK_GROUP = "keeper"

/**
 * A simple Gradle plugin that hooks into Proguard/R8 to add extra keep rules based on what androidTest classes use from
 * the target app's sources. This is necessary because AGP does not factor in androidTest usages of target app sources
 * when running the minification step, which can result in runtime errors if APIs used by tests are removed.
 *
 * This is a workaround until AGP supports this: https://issuetracker.google.com/issues/126429384.
 *
 * This is optionally configurable via the [`keeper`][KeeperExtension] extension. For example:
 * ```
 * keeper {
 *   automaticR8RepoManagement = false
 *   r8JvmArgs = ["-Xdebug", "-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=y"]
 * }
 * ```
 *
 * The general logic flow:
 * - Create a custom `r8` configuration for the R8 dependency.
 * - Register two jar tasks. One for all the classes in its target `testedVariant` and one for all
 *   the classes in the androidTest variant itself. This will use their variant-provided [JavaCompile]
 *   tasks and [KotlinCompile] tasks if available.
 * - Register a [`infer${androidTestVariant}UsageForKeeper`][InferAndroidTestKeepRules] task that
 *   plugs the two aforementioned jars into R8's `PrintUses` CLI and outputs the inferred proguard
 *   rules into a new intermediate .pro file.
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
    internal const val CONFIGURATION_NAME = "keeperR8"
    private val MIN_GRADLE_VERSION = GradleVersion.version("6.0")
  }

  override fun apply(project: Project) {
    val gradleVersion = GradleVersion.version(project.gradle.gradleVersion)
    check(gradleVersion >= MIN_GRADLE_VERSION) {
      "Keeper requires Gradle 6.0 or later."
    }
    with(project) {
      pluginManager.withPlugin("com.android.application") {
        val extension = project.extensions.create<KeeperExtension>("keeper")
        val agpHandler = AgpVersionHandler.getInstance()
            .also { logger.debug("$TAG Using ${it.minVersion} patcher") }

        // Set up r8 configuration
        val r8Configuration = configurations.create(CONFIGURATION_NAME) {
          description = "R8 dependencies for Keeper. This is used solely for the PrintUses CLI"
          isVisible = false
          isCanBeConsumed = false
          isCanBeResolved = true
          defaultDependencies {
            add(project.dependencies.create("com.android.tools:r8:$DEFAULT_R8_VERSION"))
          }
        }

        val appExtension = project.extensions.getByType<AppExtension>()

        val androidJarFileProvider = project.provider {
          val compileSdkVersion = appExtension.compileSdkVersion
              ?: error("No compileSdkVersion found")
          File("${appExtension.sdkDirectory}/platforms/${compileSdkVersion}/android.jar").also {
            check(it.exists()) {
              "No android.jar found! Expected to find it at: $it"
            }
          }
        }
        val androidJarRegularFileProvider = project.layout.file(androidJarFileProvider)

        appExtension.testVariants.configureEach {
          val appVariant = testedVariant
          val extensionFilter = extension._variantFilter
          val ignoredVariant = extensionFilter?.let {
            project.logger.debug(
                "$TAG Resolving ignored status for android variant ${appVariant.name}")
            val filter = VariantFilterImpl(appVariant)
            it.execute(filter)
            project.logger.debug("$TAG Variant '${appVariant.name}' ignored? ${filter._ignored}")
            filter._ignored
          } ?: false
          if (ignoredVariant) {
            return@configureEach
          }
          if (!appVariant.buildType.isMinifyEnabled) {
            logger.error("""
                Keeper is configured to generate keep rules for the "${appVariant.name}" build variant, but the variant doesn't 
                have minification enabled, so the keep rules will have no effect. To fix this warning, either avoid applying 
                the Keeper plugin when android.testBuildType = ${appVariant.buildType.name}, or use the variant filter feature 
                of the DSL to exclude "${appVariant.name}" from keeper:
                  keeper {
                    variantFilter {
                      setIgnore(name != <the variant to test>)
                    }
                  }
                """.trimIndent())
            return@configureEach
          }

          val intermediateAndroidTestJar = createIntermediateAndroidTestJar(
              agpHandler,
              extension.emitDebugInformation,
              this,
              appVariant
          )
          val intermediateAppJar = createIntermediateAppJar(agpHandler, appVariant)
          val inferAndroidTestUsageProvider = tasks.register(
              "infer${name.capitalize(US)}KeepRulesForKeeper",
              InferAndroidTestKeepRules(
                  variantName = name,
                  androidTestJarProvider = intermediateAndroidTestJar,
                  releaseClassesJarProvider = intermediateAppJar,
                  androidJar = androidJarRegularFileProvider,
                  automaticallyAddR8Repo = extension.automaticR8RepoManagement,
                  extensionJvmArgs = extension.r8JvmArgs,
                  r8Configuration = r8Configuration
              )
          )

          val prop = project.layout.dir(
              inferAndroidTestUsageProvider.flatMap { it.outputProguardRules.asFile })
          agpHandler.applyGeneratedRules(project, appVariant.name, prop)
        }
      }
    }
  }

  /**
   * Creates an intermediate androidTest.jar consisting of all the classes compiled for the androidTest source set.
   * This output is used in the inferAndroidTestUsage task.
   */
  private fun Project.createIntermediateAndroidTestJar(
      agpHandler: AgpVersionHandler,
      emitDebugInfo: Property<Boolean>,
      testVariant: TestVariant,
      appVariant: BaseVariant
  ): TaskProvider<out AndroidTestVariantClasspathJar> {
    return tasks.register<AndroidTestVariantClasspathJar>(
        "jar${testVariant.name.capitalize(US)}ClassesForKeeper") {
      group = KEEPER_TASK_GROUP
      this.emitDebugInfo.value(emitDebugInfo)

      with(appVariant) {
        appArtifactFiles.from(compileConfiguration.artifactView(agpHandler).artifacts.artifactFiles)
        appConfiguration.set(runtimeConfiguration)
      }

      with(testVariant) {
        from(project.layout.dir(javaCompileProvider.map { it.destinationDir }))
        androidTestArtifactFiles.from(compileConfiguration.artifactView(agpHandler).artifacts.artifactFiles)
        androidTestConfiguration.set(runtimeConfiguration)
        tasks.providerWithNameOrNull<KotlinCompile>(
            "compile${name.capitalize(US)}Kotlin")
            ?.let { kotlinCompileTask ->
              from(project.layout.dir(kotlinCompileTask.map { it.destinationDir }))
            }
      }

      archiveFile.set(project.layout.buildDirectory.dir(INTERMEDIATES_DIR).map {
        it.file("${testVariant.name}.jar")
      })
    }
  }

  /**
   * Creates an intermediate app.jar consisting of all the classes compiled for the target app variant. This
   * output is used in the inferAndroidTestUsage task.
   */
  private fun Project.createIntermediateAppJar(
      agpHandler: AgpVersionHandler,
      appVariant: BaseVariant
  ): TaskProvider<out VariantClasspathJar> {
    return tasks.register<VariantClasspathJar>(
        "jar${appVariant.name.capitalize(US)}ClassesForKeeper") {
      group = KEEPER_TASK_GROUP
      with(appVariant) {
        from(project.layout.dir(javaCompileProvider.map { it.destinationDir }))
        artifactFiles.from(compileConfiguration.artifactView(agpHandler).artifacts.artifactFiles)
        configuration.set(runtimeConfiguration)

        tasks.providerWithNameOrNull<KotlinCompile>(
            "compile${name.capitalize(US)}Kotlin")
            ?.let { kotlinCompileTask ->
              from(project.layout.dir(kotlinCompileTask.map { it.destinationDir }))
            }
      }

      archiveFile.set(project.layout.buildDirectory.dir(INTERMEDIATES_DIR).map {
        it.file("${appVariant.name}.jar")
      })
    }
  }
}

internal fun Configuration.artifactView(agpHandler: AgpVersionHandler): ArtifactView {
  return incoming.artifactView {
    attributes {
      attribute(AndroidArtifacts.ARTIFACT_TYPE, agpHandler.artifactTypeValue)
    }
  }
}

private inline fun <reified T : Task> TaskContainer.providerWithNameOrNull(
    name: String
): TaskProvider<T>? {
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

private class VariantFilterImpl(variant: BaseVariant) : VariantFilter {
  @Suppress("PropertyName")
  var _ignored: Boolean = true

  override fun setIgnore(ignore: Boolean) {
    _ignored = ignore
  }

  override val buildType: BuildType = variant.buildType
  override val flavors: List<ProductFlavor> = variant.productFlavors
  override val name: String = variant.name
}
