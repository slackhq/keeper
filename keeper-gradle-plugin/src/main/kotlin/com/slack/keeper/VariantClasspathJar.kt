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
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.property
import java.util.zip.Deflater
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * A simple cacheable task that creates a jar from a given [classpath]. Normally these aren't
 * intended to be cacheable, but in our case it's fine since the resulting jar is an input of a
 * task and not just a transient operation of another plugin.
 *
 * This uses `ZipFlinger` under the hood to run the copy operation performantly.
 */
@Suppress("UnstableApiUsage")
@CacheableTask
abstract class VariantClasspathJar @Inject constructor(objects: ObjectFactory) : DefaultTask() {
  /**
   * This is the "official" input for wiring task dependencies correctly, but is otherwise
   * unused. See [configuration].
   */
  @get:Classpath
  val artifactFiles = objects.fileCollection()

  /**
   * This is what the task actually uses as its input.
   */
  @get:Internal
  lateinit var configuration: Configuration

  @get:OutputFile
  val archiveFile: RegularFileProperty = objects.fileProperty()

  @Suppress("UnstableApiUsage")
  @get:Classpath
  val classpath: ConfigurableFileCollection = objects.fileCollection()

  fun from(vararg paths: Any) {
    classpath.from(*paths)
  }

  @TaskAction
  fun createJar() {
    from(configuration.artifactView().files.filter { it.extension == "jar" }.map(project::zipTree))
    ZipArchive(archiveFile.asFile.get()).use { archive ->
      // The runtime classpath (i.e. from dependencies)
      configuration.artifactView().files.filter { it.extension == "jar" }.forEach {
        archive.extractClassesFrom(it)
      }

      // Take the compiled classpath
      classpath.asSequence()
          .flatMap { it.classesSequence() }
          .forEach { (name, file) ->
            archive.add(BytesSource(file, name, Deflater.NO_COMPRESSION))
          }
    }
  }
}

/**
 * A [Jar] task that sources from both the androidTest compiled sources _and_ its distinct dependencies
 * (as compared to the [appConfiguration]). R8's `PrintUses` requires no class overlap between the two jars it's comparing, so
 * at copy-time this will compute the unique androidTest dependencies. We need to have them because there may be
 * APIs that _they_ use that are used in the target app runtime, and we want R8 to account for those usages as well.
 */
@CacheableTask
abstract class AndroidTestVariantClasspathJar @Inject constructor(objects: ObjectFactory) : Jar() {

  private companion object {
    val LOG = AndroidTestVariantClasspathJar::class.simpleName!!
  }

  /**
   * This is the "official" input for wiring task dependencies correctly, but is otherwise
   * unused. See [appConfiguration].
   */
  @get:Classpath
  val appArtifactFiles = objects.fileCollection()

  /**
   * This is what the task actually uses as its input.
   */
  @get:Internal
  lateinit var appConfiguration: Configuration

  /**
   * This is the "official" input for wiring task dependencies correctly, but is otherwise
   * unused. See [androidTestArtifactFiles].
   */
  @get:Classpath
  val androidTestArtifactFiles = objects.fileCollection()

  /** This is what the task actually uses as its input. */
  @get:Internal
  lateinit var androidTestConfiguration: Configuration

  @get:Input
  val emitDebugInfo: Property<Boolean> = objects.property()

  override fun copy() {
    measureTimeMillis {
      project.logger.debug("$LOG: Diffing androidTest jars and app jars")
      val appJars = appConfiguration.artifactView().files.filterTo(LinkedHashSet()) { it.extension == "jar" }
      diagnostic("${archiveFile.get().asFile.nameWithoutExtension}AppJars") {
        appJars.sortedBy { it.path }
            .joinToString("\n") {
              it.path
            }
      }
      val androidTestClasspath = androidTestConfiguration.artifactView().files.filterTo(LinkedHashSet()) { it.extension == "jar" }
      diagnostic("${archiveFile.get().asFile.nameWithoutExtension}Jars") {
        androidTestClasspath.sortedBy { it.path }
            .joinToString("\n") {
              it.path
            }
      }
      val distinctAndroidTestClasspath = androidTestClasspath.toMutableSet().apply {
        removeAll(appJars)
      }
      diagnostic("${archiveFile.get().asFile.nameWithoutExtension}DistinctJars2") {
        distinctAndroidTestClasspath.sortedBy { it.path }
            .joinToString("\n") {
              it.path
            }
      }
      from(distinctAndroidTestClasspath.filter { it.extension == "jar" }.map(project::zipTree))
    }.also {
      project.logger.debug("$LOG: Diffing completed in ${it}ms")
    }
    super.copy()
  }

  private fun diagnostic(fileName: String, body: () -> String) {
    if (emitDebugInfo.get()) {
      project.file("${project.buildDir}/${KeeperPlugin.INTERMEDIATES_DIR}/${fileName}.txt").apply {
        writeText(body())
      }
    }
  }
}
