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

package com.slack.keeper

import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import java.io.File

/**
 * Generates proguard keep rules from the generated [androidTestJar] and [appJar] tasks,
 * where the generates rules are based on what classes from [appJar] are used by
 * [androidTestJar].
 *
 * This uses R8's [PrintUses](https://r8.googlesource.com/r8/+/master/src/main/java/com/android/tools/r8/PrintUses.java)
 * CLI to perform the analysis.
 *
 * This task's output is finally used as an input into the app variant's proguard transform task.
 */
// TODO(zsweers) Run this in the background once Gradle supports it: https://github.com/gradle/gradle/issues/1367
@CacheableTask
abstract class InferAndroidTestKeepRules : JavaExec() {

  @get:Classpath
  abstract val androidTestJar: RegularFileProperty

  @get:Classpath
  abstract val appJar: RegularFileProperty

  @get:Classpath
  abstract val classpathJar: RegularFileProperty

  /**
   * Optional custom jvm arguments to pass into the exec. Useful if you want to enable debugging in R8.
   *
   * Example: `listOf("-Xdebug", "-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=y")`
   */
  @get:Input
  abstract val jvmArgsProperty: ListProperty<String>

  @get:OutputFile
  abstract val outputProguardRules: RegularFileProperty

  override fun exec() {
    // If you want to debug this, uncomment the below line and attach a remote debugger from a cloned R8 repo project.
    if (jvmArgsProperty.isPresent) {
      val inputJvmArgs = jvmArgsProperty.get()
      if (inputJvmArgs.isNotEmpty()) {
        logger.lifecycle(
            "Starting infer exec with jvmArgs ${inputJvmArgs.joinToString(", ", prefix = "[",
                postfix = "]")}. If debugging, attach the debugger now."
        )
        jvmArgs = inputJvmArgs
      }
    }

    standardOutput = outputProguardRules.asFile.get().outputStream().buffered()
    args = listOf(
        "--keeprules",
        classpathJar.get().asFile.absolutePath,
        appJar.get().asFile.absolutePath,
        androidTestJar.get().asFile.absolutePath
    )
    super.exec()
  }

  companion object {
    @Suppress("UNCHECKED_CAST")
    operator fun invoke(
        androidTestJarProvider: TaskProvider<out Jar>,
        releaseClassesJarProvider: TaskProvider<out Jar>,
        androidJar: File,
        extensionJvmArgs: ListProperty<String>,
        r8Configuration: Configuration
    ): InferAndroidTestKeepRules.() -> Unit = {
      group = KEEPER_TASK_GROUP
      androidTestJar.set(androidTestJarProvider.map { it.archiveFile.get() })
      appJar.set(releaseClassesJarProvider.map { it.archiveFile.get() })
      classpathJar.set(androidJar)
      jvmArgsProperty.set(extensionJvmArgs)
      workingDir = project.projectDir
      outputProguardRules.set(
          project.layout.buildDirectory.file("${KeeperPlugin.INTERMEDIATES_DIR}/inferredUsage.pro"))
      classpath(r8Configuration)
      main = "com.android.tools.r8.PrintUses"

      // Enable more descriptive precondition checks in the CLI
      enableAssertions = true
    }
  }
}