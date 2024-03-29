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
@file:Suppress("UnstableApiUsage")

package com.slack.keeper

import com.android.zipflinger.BytesSource
import com.android.zipflinger.ZipArchive
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
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

public abstract class KeeperJarTask : DefaultTask() {

  @get:Input public abstract val emitDebugInfo: Property<Boolean>

  @get:OutputDirectory public abstract val diagnosticsOutputDir: DirectoryProperty

  @get:Classpath @get:InputFiles public abstract val allDirectories: ListProperty<Directory>

  /**
   * This needs to use [InputFiles] and [PathSensitivity.ABSOLUTE] because the path to the jars
   * really does matter here. Using [Classpath] is an error, as it looks only at content and not
   * name or path, and we really do need to know the actual path to the artifact, even if its
   * contents haven't changed.
   */
  @get:PathSensitive(PathSensitivity.ABSOLUTE)
  @get:InputFiles
  public abstract val allJars: ListProperty<RegularFile>

  protected fun jarFilesSequence(): Sequence<File> {
    return allJars
      .get()
      .asSequence()
      .map { it.asFile }
      .distinct()
      .sortedBy { it.invariantSeparatorsPath }
  }

  protected fun compiledClassesSequence(): Sequence<Pair<String, File>> {
    // Take the compiled classes
    return allDirectories.get().asSequence().map { it.asFile }.flatMap { it.classesSequence() }
  }

  protected fun diagnostic(fileName: String, body: () -> String): File? {
    return if (emitDebugInfo.get()) {
      diagnosticsOutputDir.get().file("$fileName.txt").asFile.apply { writeText(body()) }
    } else {
      null
    }
  }
}

/**
 * A simple cacheable task that creates a jar from given [allDirectories] and [allJars]. Normally
 * these aren't intended to be cacheable, but in our case it's fine since the resulting jar is an
 * input of a task and not just a transient operation of another plugin.
 *
 * This uses `ZipFlinger` under the hood to run the copy operation performantly.
 */
@CacheableTask
public abstract class VariantClasspathJar : KeeperJarTask() {

  init {
    group = KEEPER_TASK_GROUP
    description = "Creates a fat jar of all the target app classes and its dependencies"
  }

  @get:OutputFile public abstract val archiveFile: RegularFileProperty

  @get:OutputFile public abstract val appJarsFile: RegularFileProperty

  @TaskAction
  public fun createJar() {
    val appJars = mutableSetOf<String>()
    val appClasses = mutableSetOf<String>()
    ZipArchive(archiveFile.asFile.get().toPath()).use { archive ->
      // The runtime classpath (i.e. from dependencies)
      jarFilesSequence().forEach { jar ->
        appJars.add(jar.canonicalPath)
        archive.extractClassesFrom(jar) { appClasses += it }
      }

      // Take the compiled classes
      compiledClassesSequence().forEach { (name, file) ->
        appClasses.add(name)
        archive.delete(name)
        archive.add(BytesSource(file.toPath(), name, Deflater.NO_COMPRESSION))
      }
    }

    appJarsFile.get().asFile.writeText(appJars.sorted().joinToString("\n"))

    diagnostic("classes") { appClasses.sorted().joinToString("\n") }
  }
}

/**
 * A [KeeperJarTask] task that sources from both the androidTest compiled sources _and_ its distinct
 * dependencies (as compared to the [appJarsFile]). R8's `TraceReferences` requires no class overlap
 * between the two jars it's comparing, so at copy-time this will compute the unique androidTest
 * dependencies. We need to have them because there may be APIs that _they_ use that are used in the
 * target app runtime, and we want R8 to account for those usages as well.
 */
@CacheableTask
public abstract class AndroidTestVariantClasspathJar : KeeperJarTask() {

  private companion object {
    val LOG = AndroidTestVariantClasspathJar::class.simpleName!!
  }

  init {
    group = KEEPER_TASK_GROUP
    description =
      "Creates a fat jar of all the test app classes and its distinct dependencies from the target app dependencies"
  }

  // Only care about the contents
  @get:PathSensitive(NONE) @get:InputFile public abstract val appJarsFile: RegularFileProperty

  @get:OutputFile public abstract val archiveFile: RegularFileProperty

  @TaskAction
  public fun createJar() {
    logger.debug("$LOG: Diffing androidTest jars and app jars")
    val appJars = appJarsFile.get().asFile.useLines { it.toSet() }

    val androidTestClasspath = jarFilesSequence().toList()
    diagnostic("jars") { androidTestClasspath.joinToString("\n") { it.canonicalPath } }

    val distinctAndroidTestClasspath =
      androidTestClasspath.toMutableSet().apply { removeAll { it.canonicalPath in appJars } }

    diagnostic("distinctJars") {
      distinctAndroidTestClasspath.joinToString("\n") { it.canonicalPath }
    }

    val androidTestClasses = mutableSetOf<String>()
    ZipArchive(archiveFile.asFile.get().toPath()).use { archive ->
      // The runtime classpath (i.e. from dependencies)
      distinctAndroidTestClasspath
        .filter { it.exists() && it.extension == "jar" }
        .forEach { jar -> archive.extractClassesFrom(jar) { androidTestClasses += it } }

      // Take the compiled classes
      compiledClassesSequence().forEach { (name, file) ->
        androidTestClasses.add(name)
        archive.delete(name)
        archive.add(BytesSource(file.toPath(), name, Deflater.NO_COMPRESSION))
      }
    }

    diagnostic("androidTestClasses") { androidTestClasses.sorted().joinToString("\n") }

    // See https://issuetracker.google.com/issues/157583077 for why we do this
    if (emitDebugInfo.get()) {
      val duplicateClasses =
        appJars
          .asSequence()
          .flatMap { jar -> ZipFile(File(jar)).use { it.entries().toList() }.asSequence() }
          .map { it.name }
          .distinct()
          .filterTo(LinkedHashSet()) { it in androidTestClasses }

      // https://github.com/slackhq/keeper/issues/82
      duplicateClasses.remove("module-info.class")

      if (duplicateClasses.isNotEmpty()) {
        val output = diagnostic("duplicateClasses") { duplicateClasses.sorted().joinToString("\n") }
        logger.warn(
          "Duplicate classes found in androidTest APK and app APK! This" +
            " can cause obscure runtime errors during tests due to the app" +
            " classes being optimized while the androidTest copies of them that are actually used" +
            " at runtime are not. This usually happens when two different dependencies " +
            "contribute the same classes and the app configuration only depends on one of them " +
            "while the androidTest configuration depends on only on the other. " +
            "The list of all duplicate classes can be found at file://$output"
        )
      }
    }
  }
}
