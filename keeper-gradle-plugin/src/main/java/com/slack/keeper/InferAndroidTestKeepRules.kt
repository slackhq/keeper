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
package com.slack.keeper

import java.util.Locale
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.artifacts.ResolvableConfiguration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.process.ExecOperations

/**
 * Generates proguard keep rules from the generated [androidTestSourceJar] and [appTargetJar] tasks,
 * where the generates rules are based on what classes from [appTargetJar] are used by
 * [androidTestSourceJar].
 *
 * This uses R8's
 * [TraceReferences](https://r8.googlesource.com/r8/+/refs/heads/main/src/main/java/com/android/tools/r8/tracereferences/TraceReferences.java)
 * CLI to perform the analysis.
 *
 * This task's output is finally used as an input into the app variant's proguard transform task.
 */
@CacheableTask
public abstract class InferAndroidTestKeepRules
@Inject
constructor(private val execOps: ExecOperations) : DefaultTask() {

  init {
    group = KEEPER_TASK_GROUP
    description = "Infers keep rules based on target app APIs used from the androidTest classes"
  }

  @get:Classpath public abstract val androidTestSourceJar: RegularFileProperty

  @get:Classpath public abstract val appTargetJar: RegularFileProperty

  @get:Classpath public abstract val androidJar: RegularFileProperty

  @get:Classpath @get:Optional public abstract val androidTestJar: RegularFileProperty

  /**
   * Optional custom jvm arguments to pass into the exec. Useful if you want to enable debugging in
   * R8.
   *
   * Example: `listOf("-Xdebug", "-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=y")`
   */
  @get:Input public abstract val jvmArgsProperty: ListProperty<String>

  /**
   * Enable more descriptive precondition checks in the CLI. If disabled, errors will be emitted to
   * the generated proguard rules file instead.
   */
  @get:Input public abstract val enableAssertionsProperty: Property<Boolean>

  /** @see TraceReferences.arguments */
  @get:Input public abstract val traceReferencesArgs: ListProperty<String>

  @get:Classpath @get:InputFiles public abstract val r8Program: ConfigurableFileCollection

  @get:OutputFile public abstract val outputProguardRules: RegularFileProperty

  @TaskAction
  internal fun exec() {
    // If you want to debug this, uncomment the below line and attach a remote debugger from a
    // cloned R8 repo project.
    var inputJvmArgs = emptyList<String>()
    if (jvmArgsProperty.isPresent) {
      val args = jvmArgsProperty.get()
      if (args.isNotEmpty()) {
        val argString = args.joinToString(", ", prefix = "[", postfix = "]")
        logger.debug(
          "Starting infer exec with jvmArgs $argString. If debugging, attach the debugger now."
        )
        inputJvmArgs = args
      }
    }

    execOps.javaexec {
      classpath(r8Program)
      jvmArgs(inputJvmArgs)
      args(genTraceReferencesArgs())
      enableAssertions = enableAssertionsProperty.get()
      mainClass.set("com.android.tools.r8.tracereferences.TraceReferences")
    }
  }

  private fun genTraceReferencesArgs(): List<String?> =
    listOf<Pair<String, String?>>(
        "--keep-rules" to "",
        "--lib" to androidJar.get().asFile.absolutePath,
        "--lib" to androidTestJar.get().asFile.takeIf { it.exists() }?.absolutePath,
        "--target" to appTargetJar.get().asFile.absolutePath,
        "--source" to androidTestSourceJar.get().asFile.absolutePath,
        "--output" to outputProguardRules.get().asFile.absolutePath
      )
      .map { if (it.second != null) listOf(it.first, it.second) else listOf() }
      .reduce { acc, any -> acc + any }
      // Add user provided args coming from TraceReferences.arguments after generated ones.
      .plus(traceReferencesArgs.getOrElse(listOf()))

  public companion object {
    @Suppress("LongParameterList")
    public operator fun invoke(
      variantName: String,
      androidTestJarProvider: TaskProvider<out AndroidTestVariantClasspathJar>,
      releaseClassesJarProvider: TaskProvider<out VariantClasspathJar>,
      androidJar: Provider<RegularFile>,
      androidTestJar: Provider<RegularFile>,
      automaticallyAddR8Repo: Property<Boolean>,
      enableAssertions: Property<Boolean>,
      extensionJvmArgs: ListProperty<String>,
      traceReferencesArgs: ListProperty<String>,
      r8Configuration: NamedDomainObjectProvider<ResolvableConfiguration>
    ): InferAndroidTestKeepRules.() -> Unit = {
      if (automaticallyAddR8Repo.get()) {
        // This is the maven repo where r8 tagged releases are hosted. Only the r8 artifact is
        // allowed to be fetched from this.
        // Ideally we would tie the r8Configuration to this, but unfortunately Gradle doesn't
        // support this yet.
        with(project.repositories) {
          // Limit this repo to only the R8 dependency
          maven {
            name = "R8 releases repository for use with Keeper"
            setUrl("https://storage.googleapis.com/r8-releases/raw")
            content { includeModule("com.android.tools", "r8") }
          }
        }
      }

      androidTestSourceJar.set(androidTestJarProvider.flatMap { it.archiveFile })
      appTargetJar.set(releaseClassesJarProvider.flatMap { it.archiveFile })
      this.androidJar.set(androidJar)
      this.androidTestJar.set(androidTestJar)
      jvmArgsProperty.set(extensionJvmArgs)
      this.traceReferencesArgs.set(traceReferencesArgs)
      outputProguardRules.set(
        project.layout.buildDirectory.file(
          "${KeeperPlugin.INTERMEDIATES_DIR}/${
          variantName.capitalize(Locale.US)
          }/inferredKeepRules.pro"
        )
      )
      r8Program.setFrom(r8Configuration)
      enableAssertionsProperty.set(enableAssertions)
    }
  }
}
