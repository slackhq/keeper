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
import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask
import com.android.build.gradle.internal.tasks.ProguardConfigurableTask
import com.android.build.gradle.internal.tasks.R8Task
import com.android.builder.model.BuildType
import com.android.builder.model.ProductFlavor
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.util.Locale

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
 * - Finally - the generated file is wired in to Proguard/R8 via private task APIs and setting
 *   their `configurationFiles` to include our generated one.
 *
 * Appropriate task dependencies (via inputs/outputs, not `dependsOn`) are set up, so this is automatically run as part
 * of the target app variant's full minified APK.
 *
 * The tasks themselves take roughly ~20 seconds total extra work in the Slack android app, with the infer and app jar
 * tasks each taking around 8-10 seconds and the androidTest jar taking around 2 seconds.
 */
public class KeeperPlugin : Plugin<Project> {

  internal companion object {
    const val INTERMEDIATES_DIR = "intermediates/keeper"
    const val PRINTUSES_DEFAULT_VERSION = "1.6.53"
    const val TRACE_REFERENCES_DEFAULT_VERSION = "3.0.9-dev"
    const val CONFIGURATION_NAME = "keeperR8"
    private val MIN_GRADLE_VERSION = GradleVersion.version("6.0")

    fun interpolateTaskName(appVariant: String, minifierTool: String): String {
      return "minify${appVariant.capitalize(Locale.US)}With$minifierTool"
    }
  }

  override fun apply(project: Project) {
    val gradleVersion = GradleVersion.version(project.gradle.gradleVersion)
    check(gradleVersion >= MIN_GRADLE_VERSION) {
      "Keeper requires Gradle 6.0 or later."
    }
    project.pluginManager.withPlugin("com.android.application") {
      val appExtension = project.extensions.getByType<AppExtension>()
      val extension = project.extensions.create<KeeperExtension>("keeper")
      project.configureKeepRulesGeneration(appExtension, extension)
      project.configureL8Rules(appExtension, extension)
    }
  }

  private fun Project.configureL8Rules(
      appExtension: AppExtension,
      extension: KeeperExtension
  ) {
    afterEvaluate {
      if (extension.enableL8RuleSharing.getOrElse(false)) {
        val r8Enabled = !hasProperty("android.enableR8") ||
            property("android.enableR8")?.toString()?.toBoolean() != false
        if (r8Enabled) {
          appExtension.onApplicableVariants(project, extension) { testVariant, appVariant ->
            val appR8Task = "minify${appVariant.name.capitalize(Locale.US)}WithR8"
            val androidTestL8Task = "l8DexDesugarLib${testVariant.name.capitalize(Locale.US)}"
            val inputFiles = tasks
                .named<R8Task>(appR8Task)
                .flatMap { it.projectOutputKeepRules }

            tasks
                .named<L8DexDesugarLibTask>(androidTestL8Task)
                .configure {
                  val taskName = name
                  keepRulesFiles.from(inputFiles)
                  keepRulesConfigurations.set(listOf("-dontobfuscate"))
                  val diagnosticOutputDir = layout.buildDirectory.dir(
                      "$INTERMEDIATES_DIR/l8-diagnostics/$taskName")
                  outputs.dir(diagnosticOutputDir)
                      .withPropertyName("diagnosticsDir")

                  if (extension.emitDebugInformation.getOrElse(false)) {
                    doFirst {
                      val mergedFilesContent = keepRulesFiles.files.asSequence()
                          .flatMap { it.walkTopDown() }
                          .filterNot { it.isDirectory }
                          .joinToString("\n") {
                            "# Source: ${it.absolutePath}\n${it.readText()}"
                          }
                      val configurations = keepRulesConfigurations.orNull.orEmpty().joinToString(
                          "\n", prefix = "# Source: extra configurations\n")
                      diagnosticOutputDir.get().file("mergedL8Rules.pro")
                          .asFile
                          .writeText("$mergedFilesContent\n$configurations")
                    }
                  }
                }
          }
        } else {
          error("enableL8RuleSharing only works if R8 is enabled!")
        }
      }
    }
  }

  private fun Project.configureKeepRulesGeneration(
      appExtension: AppExtension,
      extension: KeeperExtension
  ) {
    // Set up r8 configuration
    val r8Configuration = configurations.create(CONFIGURATION_NAME) {
      description = "R8 dependencies for Keeper. This is used solely for the PrintUses CLI"
      isVisible = false
      isCanBeConsumed = false
      isCanBeResolved = true
      defaultDependencies {
        val version = when (extension.traceReferences.enabled.get()) {
          false -> PRINTUSES_DEFAULT_VERSION
          true -> TRACE_REFERENCES_DEFAULT_VERSION
        }
        logger.debug("keeper r8 default version: $version")
        add(project.dependencies.create("com.android.tools:r8:$version"))
      }
    }

    val androidJarRegularFileProvider = layout.file(provider {
      resolveAndroidEmbeddedJar(appExtension, "android.jar", checkIfExisting = true)
    })
    val androidTestJarRegularFileProvider = layout.file(provider {
      resolveAndroidEmbeddedJar(appExtension, "optional/android.test.base.jar",
          checkIfExisting = false)
    })

    appExtension.testVariants.configureEach {
      val appVariant = testedVariant
      val extensionFilter = extension._variantFilter
      val ignoredVariant = extensionFilter?.let {
        logger.debug(
            "$TAG Resolving ignored status for android variant ${appVariant.name}")
        val filter = VariantFilterImpl(appVariant)
        it.execute(filter)
        logger.debug("$TAG Variant '${appVariant.name}' ignored? ${filter._ignored}")
        filter._ignored
      } ?: !appVariant.buildType.isMinifyEnabled
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
    }

    appExtension.onApplicableVariants(project, extension) { testVariant, appVariant ->
      val intermediateAppJar = createIntermediateAppJar(
          appVariant = appVariant,
          emitDebugInfo = extension.emitDebugInformation
      )
      val intermediateAndroidTestJar = createIntermediateAndroidTestJar(
          emitDebugInfo = extension.emitDebugInformation,
          testVariant = testVariant,
          appJarsProvider = intermediateAppJar.flatMap { it.appJarsFile }
      )
      val inferAndroidTestUsageProvider = tasks.register(
          "infer${testVariant.name.capitalize(Locale.US)}KeepRulesForKeeper",
          InferAndroidTestKeepRules(
              variantName = testVariant.name,
              androidTestJarProvider = intermediateAndroidTestJar,
              releaseClassesJarProvider = intermediateAppJar,
              androidJar = androidJarRegularFileProvider,
              androidTestJar = androidTestJarRegularFileProvider,
              automaticallyAddR8Repo = extension.automaticR8RepoManagement,
              enableAssertions = extension.enableAssertions,
              extensionJvmArgs = extension.r8JvmArgs,
              traceReferencesEnabled = extension.traceReferences.enabled,
              traceReferencesArgs = extension.traceReferences.arguments,
              r8Configuration = r8Configuration
          )
      )

      val prop = layout.dir(
          inferAndroidTestUsageProvider.flatMap { it.outputProguardRules.asFile })
      applyGeneratedRules(appVariant.name, prop)
    }
  }

  private fun resolveAndroidEmbeddedJar(
      appExtension: AppExtension,
      path: String,
      checkIfExisting: Boolean
  ): File {
    val compileSdkVersion = appExtension.compileSdkVersion
        ?: error("No compileSdkVersion found")
    val file = File("${appExtension.sdkDirectory}/platforms/${compileSdkVersion}/${path}")
    check(!checkIfExisting || file.exists()) {
      "No $path found! Expected to find it at: ${file.absolutePath}"
    }
    return file
  }

  private fun AppExtension.onApplicableVariants(
      project: Project,
      extension: KeeperExtension,
      body: (TestVariant, BaseVariant) -> Unit
  ) {
    testVariants.configureEach {
      val testVariant = this
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
        project.logger.error("""
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

      body(testVariant, appVariant)
    }
  }

  private fun Project.applyGeneratedRules(appVariant: String, prop: Provider<Directory>) {
    // R8 is the default, so we'll only look to see if it's explicitly disabled
    val r8Enabled = providers.gradleProperty("android.enableR8")
        .forUseAtConfigurationTime()
        .map {
          @Suppress("PlatformExtensionReceiverOfInline")
          it.toBoolean()
        }
        .getOrElse(true)

    val minifierTool = if (r8Enabled) "R8" else "Proguard"

    val targetName = interpolateTaskName(appVariant, minifierTool)

    tasks.withType<ProguardConfigurableTask>()
        .matching { it.name == targetName }
        .configureEach {
          logger.debug(
              "$TAG: Patching task '$name' with inferred androidTest proguard rules")
          configurationFiles.from(prop)
        }
  }

  /**
   * Creates an intermediate androidTest.jar consisting of all the classes compiled for the androidTest source set.
   * This output is used in the inferAndroidTestUsage task.
   */
  private fun Project.createIntermediateAndroidTestJar(
      emitDebugInfo: Provider<Boolean>,
      testVariant: TestVariant,
      appJarsProvider: Provider<RegularFile>
  ): TaskProvider<out AndroidTestVariantClasspathJar> {
    return tasks.register<AndroidTestVariantClasspathJar>(
        "jar${testVariant.name.capitalize(Locale.US)}ClassesForKeeper") {
      group = KEEPER_TASK_GROUP
      this.emitDebugInfo.value(emitDebugInfo)
      this.appJarsFile.set(appJarsProvider)

      with(testVariant) {
        from(layout.dir(javaCompileProvider.map { it.destinationDir }))
        androidTestArtifactFiles.from(runtimeConfiguration.artifactView())
        tasks.providerWithNameOrNull<KotlinCompile>(
            "compile${name.capitalize(Locale.US)}Kotlin")
            ?.let { kotlinCompileTask ->
              from(layout.dir(kotlinCompileTask.map { it.destinationDir }))
            }
      }

      val outputDir = layout.buildDirectory.dir("$INTERMEDIATES_DIR/${testVariant.name}")
      val diagnosticsDir = layout.buildDirectory.dir(
          "$INTERMEDIATES_DIR/${testVariant.name}/diagnostics")
      this.diagnosticsOutputDir.set(diagnosticsDir)
      archiveFile.set(outputDir.map {
        it.file("androidTestClasses.jar")
      })
    }
  }

  /**
   * Creates an intermediate app.jar consisting of all the classes compiled for the target app variant. This
   * output is used in the inferAndroidTestUsage task.
   */
  private fun Project.createIntermediateAppJar(
      appVariant: BaseVariant,
      emitDebugInfo: Provider<Boolean>
  ): TaskProvider<out VariantClasspathJar> {
    return tasks.register<VariantClasspathJar>(
        "jar${appVariant.name.capitalize(Locale.US)}ClassesForKeeper") {
      group = KEEPER_TASK_GROUP
      this.emitDebugInfo.set(emitDebugInfo)
      with(appVariant) {
        from(layout.dir(javaCompileProvider.map { it.destinationDir }))
        artifactFiles.from(runtimeConfiguration.artifactView())

        tasks.providerWithNameOrNull<KotlinCompile>(
            "compile${name.capitalize(Locale.US)}Kotlin")
            ?.let { kotlinCompileTask ->
              from(layout.dir(kotlinCompileTask.map { it.destinationDir }))
            }
      }

      val outputDir = layout.buildDirectory.dir("$INTERMEDIATES_DIR/${appVariant.name}")
      val diagnosticsDir = layout.buildDirectory.dir(
          "$INTERMEDIATES_DIR/${appVariant.name}/diagnostics")
      diagnosticsOutputDir.set(diagnosticsDir)
      archiveFile.set(outputDir.map { it.file("appClasses.jar") })
      appJarsFile.set(outputDir.map { it.file("appJars.txt") })
    }
  }
}

private fun Configuration.artifactView(): FileCollection {
  return incoming
      .artifactView {
        attributes {
          attribute(AndroidArtifacts.ARTIFACT_TYPE, "android-classes-jar")
        }
      }
      .artifacts
      .artifactFiles
      .filter { it.exists() && it.extension == "jar" }
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
  var _ignored: Boolean = false

  override fun setIgnore(ignore: Boolean) {
    _ignored = ignore
  }

  override val buildType: BuildType = variant.buildType
  override val flavors: List<ProductFlavor> = variant.productFlavors
  override val name: String = variant.name
}
