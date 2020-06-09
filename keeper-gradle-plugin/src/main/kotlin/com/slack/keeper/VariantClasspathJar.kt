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

@file:Suppress("UnstableApiUsage")

package com.slack.keeper

import com.android.zipflinger.BytesSource
import com.android.zipflinger.ZipArchive
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.NONE
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import java.util.zip.Deflater

abstract class BaseKeeperJarTask : DefaultTask() {

  @get:Input
  abstract val emitDebugInfo: Property<Boolean>

  @get:OutputDirectory
  abstract val diagnosticsOutputDir: DirectoryProperty

  protected fun diagnostic(fileName: String, body: () -> String) {
    if (emitDebugInfo.get()) {
      diagnosticsOutputDir.get().file("${fileName}.txt").asFile.apply {
        writeText(body())
      }
    }
  }
}

/**
 * A simple cacheable task that creates a jar from a given [classpath]. Normally these aren't
 * intended to be cacheable, but in our case it's fine since the resulting jar is an input of a
 * task and not just a transient operation of another plugin.
 *
 * This uses `ZipFlinger` under the hood to run the copy operation performantly.
 */
@Suppress("UnstableApiUsage")
@CacheableTask
abstract class VariantClasspathJar : BaseKeeperJarTask() {

  @get:Classpath
  abstract val artifactFiles: ConfigurableFileCollection

  @get:OutputFile
  abstract val archiveFile: RegularFileProperty

  @Suppress("UnstableApiUsage")
  @get:Classpath
  abstract val classpath: ConfigurableFileCollection

  @get:OutputFile
  abstract val appJarsFile: RegularFileProperty

  fun from(vararg paths: Any) {
    classpath.from(*paths)
  }

  @TaskAction
  fun createJar() {
    val appJars = mutableSetOf<String>()
    val appClasses = mutableSetOf<String>()
    ZipArchive(archiveFile.asFile.get()).use { archive ->
      // The runtime classpath (i.e. from dependencies)
      artifactFiles
          .forEach { jar ->
            appJars.add(jar.canonicalPath)
            archive.extractClassesFrom(jar) {
              appClasses += it
            }
          }

      // Take the compiled classes
      classpath.asSequence()
          .flatMap { it.classesSequence() }
          .forEach { (name, file) ->
            appClasses.add(name)
            archive.delete(name)
            archive.add(BytesSource(file, name, Deflater.NO_COMPRESSION))
          }
    }

    appJarsFile.get().asFile.writeText(appJars.sorted().joinToString("\n"))

    diagnostic("${archiveFile.get().asFile.nameWithoutExtension}Classes") {
      appClasses.sorted()
          .joinToString("\n")
    }
  }
}

/**
 * A [Jar] task that sources from both the androidTest compiled sources _and_ its distinct dependencies
 * (as compared to the [appJarsFile]). R8's `PrintUses` requires no class overlap between the two jars it's comparing, so
 * at copy-time this will compute the unique androidTest dependencies. We need to have them because there may be
 * APIs that _they_ use that are used in the target app runtime, and we want R8 to account for those usages as well.
 */
@CacheableTask
abstract class AndroidTestVariantClasspathJar : BaseKeeperJarTask() {

  private companion object {
    val LOG = AndroidTestVariantClasspathJar::class.simpleName!!
  }

  @get:Classpath
  abstract val androidTestArtifactFiles: ConfigurableFileCollection

  @get:PathSensitive(NONE) // Only care about the contents
  @get:InputFile
  abstract val appJarsFile: RegularFileProperty

  @Suppress("UnstableApiUsage")
  @get:Classpath
  abstract val classpath: ConfigurableFileCollection

  @get:OutputFile
  abstract val archiveFile: RegularFileProperty

  fun from(vararg paths: Any) {
    classpath.from(*paths)
  }

  @TaskAction
  fun createJar() {
    logger.debug("$LOG: Diffing androidTest jars and app jars")
    val appJars = appJarsFile.get().asFile.useLines { it.toSet() }

    val androidTestClasspath = androidTestArtifactFiles.files
    diagnostic("${archiveFile.get().asFile.nameWithoutExtension}Jars") {
      androidTestClasspath.sortedBy { it.canonicalPath }
          .joinToString("\n") {
            it.canonicalPath
          }
    }

    val distinctAndroidTestClasspath = androidTestClasspath.toMutableSet().apply {
      removeAll { it.canonicalPath in appJars }
    }

    diagnostic("${archiveFile.get().asFile.nameWithoutExtension}DistinctJars") {
      distinctAndroidTestClasspath.sortedBy { it.canonicalPath }
          .joinToString("\n") {
            it.canonicalPath
          }
    }

    val androidTestClasses = mutableSetOf<String>()
    ZipArchive(archiveFile.asFile.get()).use { archive ->
      // The runtime classpath (i.e. from dependencies)
      distinctAndroidTestClasspath
          .filter { it.exists() && it.extension == "jar" }
          .forEach { jar ->
            archive.extractClassesFrom(jar) {
              androidTestClasses += it
            }
          }

      // Take the compiled classes
      classpath.asSequence()
          .flatMap { it.classesSequence() }
          .forEach { (name, file) ->
            androidTestClasses += name
            archive.delete(name)
            archive.add(BytesSource(file, name, Deflater.NO_COMPRESSION))
          }
    }

    diagnostic("${archiveFile.get().asFile.nameWithoutExtension}Classes") {
      androidTestClasses.sorted().joinToString("\n")
    }
  }
}
