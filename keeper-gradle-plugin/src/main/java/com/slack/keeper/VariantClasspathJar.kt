/*
 * Copyright (C) 2020 Slack Technologies, LLC
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
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.PathSensitivity.NONE
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipFile

public abstract class BaseKeeperJarTask : DefaultTask() {

  @get:Input
  public abstract val emitDebugInfo: Property<Boolean>

  private lateinit var artifacts: ArtifactCollection

  @get:OutputDirectory
  public abstract val diagnosticsOutputDir: DirectoryProperty

  /**
   * This artifact collection is the result of resolving the compilation classpath.
   */
  public fun setArtifacts(artifacts: ArtifactCollection) {
    this.artifacts = artifacts
  }

  /**
   * This needs to use [InputFiles] and [PathSensitivity.ABSOLUTE] because the path to the
   * jars really does matter here. Using [Classpath] is an error, as it looks only at content and
   * not name or path, and we really do need to know the actual path to the artifact, even if its
   * contents haven't changed.
   */
  @PathSensitive(PathSensitivity.ABSOLUTE)
  @InputFiles
  public fun getArtifactFiles(): FileCollection = artifacts.artifactFiles

  protected fun diagnostic(fileName: String, body: () -> String): File? {
    return if (emitDebugInfo.get()) {
      diagnosticsOutputDir.get().file("${fileName}.txt").asFile.apply {
        writeText(body())
      }
    } else {
      null
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
public abstract class VariantClasspathJar : BaseKeeperJarTask() {

  @get:OutputFile
  public abstract val archiveFile: RegularFileProperty

  @Suppress("UnstableApiUsage")
  @get:Classpath
  public abstract val classpath: ConfigurableFileCollection

  @get:OutputFile
  public abstract val appJarsFile: RegularFileProperty

  public fun from(vararg paths: Any) {
    classpath.from(*paths)
  }

  @TaskAction
  public fun createJar() {
    val appJars = mutableSetOf<String>()
    val appClasses = mutableSetOf<String>()
    ZipArchive(archiveFile.asFile.get().toPath()).use { archive ->
      // The runtime classpath (i.e. from dependencies)
      getArtifactFiles()
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
            archive.add(BytesSource(file.toPath(), name, Deflater.NO_COMPRESSION))
          }
    }

    appJarsFile.get().asFile.writeText(appJars.sorted().joinToString("\n"))

    diagnostic("classes") {
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
public abstract class AndroidTestVariantClasspathJar : BaseKeeperJarTask() {

  private companion object {
    val LOG = AndroidTestVariantClasspathJar::class.simpleName!!
  }

  @get:PathSensitive(NONE) // Only care about the contents
  @get:InputFile
  public abstract val appJarsFile: RegularFileProperty

  @Suppress("UnstableApiUsage")
  @get:Classpath
  public abstract val classpath: ConfigurableFileCollection

  @get:OutputFile
  public abstract val archiveFile: RegularFileProperty

  public fun from(vararg paths: Any) {
    classpath.from(*paths)
  }

  @TaskAction
  public fun createJar() {
    logger.debug("$LOG: Diffing androidTest jars and app jars")
    val appJars = appJarsFile.get().asFile.useLines { it.toSet() }

    val androidTestClasspath = getArtifactFiles()
    diagnostic("jars") {
      androidTestClasspath.sortedBy { it.canonicalPath }
          .joinToString("\n") {
            it.canonicalPath
          }
    }

    val distinctAndroidTestClasspath = androidTestClasspath.toMutableSet().apply {
      removeAll { it.canonicalPath in appJars }
    }

    diagnostic("distinctJars") {
      distinctAndroidTestClasspath.sortedBy { it.canonicalPath }
          .joinToString("\n") {
            it.canonicalPath
          }
    }

    val androidTestClasses = mutableSetOf<String>()
    ZipArchive(archiveFile.asFile.get().toPath()).use { archive ->
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
            archive.add(BytesSource(file.toPath(), name, Deflater.NO_COMPRESSION))
          }
    }

    diagnostic("androidTestClasses") {
      androidTestClasses.sorted().joinToString("\n")
    }

    // See https://issuetracker.google.com/issues/157583077 for why we do this
    if (emitDebugInfo.get()) {
      val duplicateClasses = appJars.asSequence()
          .flatMap { jar -> ZipFile(File(jar)).use { it.entries().toList() }.asSequence() }
          .map { it.name }
          .distinct()
          .filterTo(LinkedHashSet()) { it in androidTestClasses }

      // https://github.com/slackhq/keeper/issues/82
      duplicateClasses.remove("module-info.class")

      if (duplicateClasses.isNotEmpty()) {
        val output = diagnostic("duplicateClasses") {
          duplicateClasses.sorted().joinToString("\n")
        }
        logger.warn("Duplicate classes found in androidTest APK and app APK! This" +
            " can cause obscure runtime errors during tests due to the app" +
            " classes being optimized while the androidTest copies of them that are actually used" +
            " at runtime are not. This usually happens when two different dependencies " +
            "contribute the same classes and the app configuration only depends on one of them " +
            "while the androidTest configuration depends on only on the other. " +
            "The list of all duplicate classes can be found at file://$output")
      }
    }
  }
}
