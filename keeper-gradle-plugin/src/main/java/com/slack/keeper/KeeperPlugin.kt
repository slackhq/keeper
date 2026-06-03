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
@file:Suppress("UnstableApiUsage", "DEPRECATION")

package com.slack.keeper

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.AndroidTest
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.Component
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType
import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask
import com.android.build.gradle.internal.tasks.R8Task
import java.io.File
import java.io.IOException
import java.util.Locale
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal const val TAG = "Keeper"
internal const val KEEPER_TASK_GROUP = "keeper"

/**
 * A simple Gradle plugin that hooks into Proguard/R8 to add extra keep rules based on what
 * androidTest classes use from the target app's sources. This is necessary because AGP does not
 * factor in androidTest usages of target app sources when running the minification step, which can
 * result in runtime errors if APIs used by tests are removed.
 *
 * This is a workaround until AGP supports this: https://issuetracker.google.com/issues/126429384.
 *
 * This is optionally configurable via the [`keeper`][KeeperExtension] extension. For example:
 * ```kotlin
 * keeper {
 *   automaticR8RepoManagement = false
 *   r8JvmArgs = ["-Xdebug", "-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=y"]
 * }
 * ```
 *
 * The general logic flow:
 * - Create a custom `r8` configuration for the R8 dependency.
 * - Register two jar tasks. One for all the classes in its target `testedVariant` and one for all
 *   the classes in the androidTest variant itself. This will use their variant-provided
 *   [JavaCompile] tasks and [KotlinCompile] tasks if available.
 * - Register a [`infer${androidTestVariant}UsageForKeeper`][InferAndroidTestKeepRules] task that
 *   plugs the two aforementioned jars into R8's `TraceReferences` CLI and outputs the inferred
 *   proguard rules into a new intermediate .pro file.
 * - Finally - the generated file is wired in to Proguard/R8 via private task APIs and setting their
 *   `configurationFiles` to include our generated one.
 *
 * Appropriate task dependencies (via inputs/outputs, not `dependsOn`) are set up, so this is
 * automatically run as part of the target app variant's full minified APK.
 *
 * The tasks themselves take roughly ~20 seconds total extra work in the Slack android app, with the
 * infer and app jar tasks each taking around 8-10 seconds and the androidTest jar taking around 2
 * seconds.
 */
public class KeeperPlugin : Plugin<Project> {

  internal companion object {
    const val INTERMEDIATES_DIR = "intermediates/keeper"
    const val TRACE_REFERENCES_DEFAULT_VERSION = "8.2.38"
    const val CONFIGURATION_NAME = "keeperR8"
    private val MIN_GRADLE_VERSION = GradleVersion.version("8.0")

    fun interpolateR8TaskName(variantName: String): String {
      return "minify${variantName.capitalize(Locale.US)}WithR8"
    }

    fun interpolateL8TaskName(variantName: String): String {
      return "l8DexDesugarLib${variantName.capitalize(Locale.US)}"
    }
  }

  override fun apply(project: Project) {
    val gradleVersion = GradleVersion.version(project.gradle.gradleVersion)
    check(gradleVersion >= MIN_GRADLE_VERSION) {
      "Keeper requires Gradle ${MIN_GRADLE_VERSION.version} or later."
    }
    project.pluginManager.withPlugin("com.android.application") {
      val appExtension = project.extensions.getByType(ApplicationExtension::class.java)
      val appComponentsExtension =
        project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
      val extension = project.extensions.create("keeper", KeeperExtension::class.java)
      project.configureKeepRulesGeneration(appExtension, appComponentsExtension, extension)
      configureL8(project, appExtension, appComponentsExtension, extension)
    }
  }

  /**
   * Configures L8 support via rule sharing and clearing androidTest dex file generation by patching
   * the respective app and test [L8DexDesugarLibTask] tasks.
   *
   * By default, L8 will generate separate rules for test app and androidTest app L8 rules. This can
   * cause problems in minified tests for a couple reasons though! This tries to resolve these via
   * two steps.
   *
   * Issue 1: L8 will try to minify the backported APIs otherwise and can result in conflicting
   * class names between the app and test APKs. This is a little confusing because L8 treats
   * "minified" as "obfuscated" and tries to match. Since we don't care about obfuscating here, we
   * can just disable it.
   *
   * Issue 2: L8 packages `j$` classes into androidTest but doesn't match what's in the target app.
   * This causes confusion when invoking code in the target app from the androidTest classloader and
   * it then can't find some expected `j$` classes. To solve this, we feed the the test app's
   * generated `j$` rules in as inputs to the app L8 task's input rules.
   *
   * More details can be found here: https://issuetracker.google.com/issues/158018485
   *
   * Issue 3: In order for this to work, there needs to only be _one_ dex file generated and it
   * _must_ be the one in the app. This way we avoid classpath conflicts and the one in the app is
   * the source of truth. To force this, we simply clear all the generated output dex files from the
   * androidTest [L8DexDesugarLibTask] task.
   */
  private fun configureL8(
    project: Project,
    appExtension: ApplicationExtension,
    appComponentsExtension: ApplicationAndroidComponentsExtension,
    extension: KeeperExtension,
  ) {
    appComponentsExtension.onApplicableVariants(project, verifyMinification = false) {
      testVariant,
      appVariant ->
      // TODO ideally move to components entirely https://issuetracker.google.com/issues/199411020
      if (appExtension.compileOptions.isCoreLibraryDesugaringEnabled) {

        // To support this, we need to:
        // - Get the androidTest L8DexDesugarLibTask
        // - Pipe its output keep rules into an intermediate mapped provider
        // - Pipe those rules into app L8DexDesugarLibTask's keepRulesConfigurations

        // namedLazy nesting here is unfortunate but necessary because these L8 tasks don't
        // exist yet during this callback. https://issuetracker.google.com/issues/199509581
        project.namedLazy<L8DexDesugarLibTask>(interpolateL8TaskName(testVariant.name)) { testL8Task
          ->
          project.namedLazy<L8DexDesugarLibTask>(interpolateL8TaskName(appVariant.name)) { appL8Task
            ->
            appL8Task.configure {
              keepRulesConfigurations.addAll(
                testL8Task.flatMap { it.keepRules }.map { it.asFile.readLines() }
              )

              // Diagnostics
              if (extension.emitDebugInformation.getOrElse(false)) {
                val taskName = name
                val diagnosticOutputDir =
                  project.layout.buildDirectory
                    .dir("$INTERMEDIATES_DIR/l8-diagnostics/$taskName")
                    .get()
                    .asFile
                doLast {
                  // We can't actually declare this because AGP's NonIncrementalTask will clear it
                  // during the task action
                  // outputs.dir(diagnosticOutputDir)
                  //     .withPropertyName("diagnosticsDir")
                  val outputFile = keepRules.asFile.get()
                  val mergedFilesContent =
                    "# Source: ${outputFile.absolutePath}\n${outputFile.readText()}"

                  val configurations =
                    keepRulesConfigurations.orNull
                      .orEmpty()
                      .joinToString("\n", prefix = "# Source: extra configurations\n")

                  File(diagnosticOutputDir, "patchedL8Rules.pro")
                    .apply {
                      if (exists()) {
                        delete()
                      }
                      parentFile.mkdirs()
                      createNewFile()
                    }
                    .writeText("$mergedFilesContent\n$configurations")
                }
              }
            }
          }
        }

        // Now clear the outputs from androidTest's L8 task to end with
        project.namedLazy<L8DexDesugarLibTask>(interpolateL8TaskName(testVariant.name)) {
          it.configure { doLast { clearDir(desugarLibDex.asFile.get()) } }
        }
      }
    }
  }

  private fun Project.configureKeepRulesGeneration(
    appExtension: ApplicationExtension,
    appComponentsExtension: ApplicationAndroidComponentsExtension,
    extension: KeeperExtension,
  ) {
    // Set up r8 configuration
    val r8Configuration =
      configurations.create(CONFIGURATION_NAME) {
        description = "R8 dependencies for Keeper. This is used solely for the TraceReferences CLI"
        isVisible = false
        isCanBeConsumed = false
        isCanBeResolved = true
        defaultDependencies {
          logger.debug("keeper r8 default version: $TRACE_REFERENCES_DEFAULT_VERSION")
          add(project.dependencies.create("com.android.tools:r8:$TRACE_REFERENCES_DEFAULT_VERSION"))
        }
      }

    val androidJarRegularFileProvider =
      layout.file(
        provider {
          resolveAndroidEmbeddedJar(
            appExtension,
            appComponentsExtension,
            "android.jar",
            checkIfExisting = true,
          )
        }
      )
    val androidTestJarRegularFileProvider =
      layout.file(
        provider {
          resolveAndroidEmbeddedJar(
            appExtension,
            appComponentsExtension,
            "optional/android.test.base.jar",
            checkIfExisting = false,
          )
        }
      )

    appComponentsExtension.onApplicableVariants(project, verifyMinification = true) {
      testVariant,
      appVariant ->
      val intermediateAppJar =
        createIntermediateAppJar(
          appVariant = appVariant,
          emitDebugInfo = extension.emitDebugInformation,
        )
      val intermediateAndroidTestJar =
        createIntermediateAndroidTestJar(
          emitDebugInfo = extension.emitDebugInformation,
          testVariant = testVariant,
          appJarsProvider = intermediateAppJar.flatMap { it.appJarsFile },
        )
      val inferAndroidTestUsageProvider =
        tasks.register(
          "infer${testVariant.name.capitalize(Locale.US)}KeepRulesForKeeper",
          InferAndroidTestKeepRules::class.java,
          InferAndroidTestKeepRules(
            variantName = testVariant.name,
            androidTestJarProvider = intermediateAndroidTestJar,
            releaseClassesJarProvider = intermediateAppJar,
            androidJar = androidJarRegularFileProvider,
            androidTestJar = androidTestJarRegularFileProvider,
            automaticallyAddR8Repo = extension.automaticR8RepoManagement,
            enableAssertions = extension.enableAssertions,
            extensionJvmArgs = extension.r8JvmArgs,
            traceReferencesArgs = extension.traceReferences.arguments,
            r8Configuration = r8Configuration,
          ),
        )

      val prop = layout.dir(inferAndroidTestUsageProvider.flatMap { it.outputProguardRules.asFile })

      configureR8Task(appVariant.name) {
        logger.debug("$TAG: Patching task '$name' with inferred androidTest proguard rules")
        configurationFiles.from(prop)
        configurationFiles.from(
          project.provider { testVariant.runtimeConfiguration.proguardFiles() }
        )
        configurationFiles.from(testVariant.proguardFiles)
      }
    }
  }

  private fun resolveAndroidEmbeddedJar(
    appExtension: ApplicationExtension,
    appComponentsExtension: ApplicationAndroidComponentsExtension,
    path: String,
    checkIfExisting: Boolean,
  ): File {
    val compileSdkVersion = appExtension.compileSdk ?: error("No compileSdkVersion found")
    val file =
      File(
        "${appComponentsExtension.sdkComponents.sdkDirectory.get().asFile}/platforms/android-$compileSdkVersion/$path"
      )
    check(!checkIfExisting || file.exists()) {
      "No $path found! Expected to find it at: ${file.absolutePath}"
    }
    return file
  }

  private fun ApplicationAndroidComponentsExtension.onApplicableVariants(
    project: Project,
    verifyMinification: Boolean,
    body: (AndroidTest, ApplicationVariant) -> Unit,
  ) {
    onVariants { appVariant ->
      val buildType = appVariant.buildType ?: return@onVariants
      // Look for our marker extension
      appVariant.getExtension(KeeperVariantMarker::class.java) ?: return@onVariants
      appVariant.androidTest?.let { testVariant ->
        if (verifyMinification && !appVariant.isMinifyEnabled) {
          project.logger.error(
            """
            Keeper is configured to generate keep rules for the "${appVariant.name}" build variant, but the variant doesn't
            have minification enabled, so the keep rules will have no effect. To fix this warning, either avoid applying
            the Keeper plugin when android.testBuildType = $buildType or enable minification on this variant.
            """
              .trimIndent()
          )
          return@let
        }

        body(testVariant, appVariant)
      }
    }
  }

  private fun Project.configureR8Task(appVariant: String, action: R8Task.() -> Unit) {
    val targetName = interpolateR8TaskName(appVariant)

    tasks.withType(R8Task::class.java).matching { it.name == targetName }.configureEach(action)
  }

  /**
   * Creates an intermediate androidTest.jar consisting of all the classes compiled for the
   * androidTest source set. This output is used in the inferAndroidTestUsage task.
   */
  private fun Project.createIntermediateAndroidTestJar(
    emitDebugInfo: Provider<Boolean>,
    testVariant: AndroidTest,
    appJarsProvider: Provider<RegularFile>,
  ): TaskProvider<out AndroidTestVariantClasspathJar> {
    val taskProvider =
      tasks.register(
        "jar${testVariant.name.capitalize(Locale.US)}ClassesForKeeper",
        AndroidTestVariantClasspathJar::class.java,
      ) {
        this.emitDebugInfo.value(emitDebugInfo)
        this.appJarsFile.set(appJarsProvider)

        val outputDir = layout.buildDirectory.dir("$INTERMEDIATES_DIR/${testVariant.name}")
        val diagnosticsDir =
          layout.buildDirectory.dir("$INTERMEDIATES_DIR/${testVariant.name}/diagnostics")
        this.diagnosticsOutputDir.set(diagnosticsDir)
        archiveFile.set(outputDir.map { it.file("classes.jar") })
      }

    wireClassesAndJarsFor(testVariant, taskProvider)
    return taskProvider
  }

  private fun wireClassesAndJarsFor(
    component: Component,
    taskProvider: TaskProvider<out KeeperJarTask>,
  ) {
    component.artifacts
      .forScope(ScopedArtifacts.Scope.ALL)
      .use(taskProvider)
      .toGet(ScopedArtifact.CLASSES, KeeperJarTask::allJars, KeeperJarTask::allDirectories)
  }

  /**
   * Creates an intermediate app.jar consisting of all the classes compiled for the target app
   * variant. This output is used in the inferAndroidTestUsage task.
   */
  private fun Project.createIntermediateAppJar(
    appVariant: ApplicationVariant,
    emitDebugInfo: Provider<Boolean>,
  ): TaskProvider<out VariantClasspathJar> {
    val taskProvider =
      tasks.register(
        "jar${appVariant.name.capitalize(Locale.US)}ClassesForKeeper",
        VariantClasspathJar::class.java,
      ) {
        this.emitDebugInfo.set(emitDebugInfo)

        val outputDir = layout.buildDirectory.dir("$INTERMEDIATES_DIR/${appVariant.name}")
        val diagnosticsDir =
          layout.buildDirectory.dir("$INTERMEDIATES_DIR/${appVariant.name}/diagnostics")
        diagnosticsOutputDir.set(diagnosticsDir)
        archiveFile.set(outputDir.map { it.file("classes.jar") })
        appJarsFile.set(outputDir.map { it.file("jars.txt") })
      }
    wireClassesAndJarsFor(appVariant, taskProvider)
    return taskProvider
  }
}

private fun Configuration.proguardFiles(): FileCollection {
  return artifactView(ArtifactType.FILTERED_PROGUARD_RULES)
}

private fun Configuration.artifactView(artifactType: ArtifactType): FileCollection {
  return incoming
    .artifactView { attributes { attribute(AndroidArtifacts.ARTIFACT_TYPE, artifactType.type) } }
    .artifacts
    .artifactFiles
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

/**
 * Similar to [TaskContainer.named], but waits until the task is registered if it doesn't exist,
 * yet. If the task is never registered, then this method will throw an error after the
 * configuration phase.
 */
private inline fun <reified T : Task> Project.namedLazy(
  targetName: String,
  crossinline action: (TaskProvider<T>) -> Unit,
) {
  try {
    action(tasks.named(targetName, T::class.java))
    return
  } catch (ignored: UnknownTaskException) {}

  var didRun = false

  tasks.withType(T::class.java) {
    if (name == targetName) {
      action(tasks.named(name, T::class.java))
      didRun = true
    }
  }

  afterEvaluate {
    if (!didRun) {
      throw GradleException("Didn't find task $name with type ${T::class}.")
    }
  }
}

private fun clearDir(path: File) {
  if (!path.isDirectory) {
    if (path.exists()) {
      path.deleteRecursively()
    }
    if (!path.mkdirs()) {
      throw IOException(String.format("Could not create empty folder %s", path))
    }
    return
  }

  path.listFiles()?.forEach(File::deleteRecursively)
}
