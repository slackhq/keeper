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

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.property
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * A simple cacheable [Jar] task. Normally these aren't intended to be cacheable, but in our case it's fine since
 * the resulting jar is an input of a task and not just a transient operation of another plugin.
 */
@CacheableTask
abstract class VariantClasspathJar : Jar()

/**
 * A [VariantClasspathJar] task that sources from both the androidTest compiled sources _and_ its distinct dependencies
 * (as compared to the [appRuntime]). R8's `PrintUses` requires no class overlap between the two jars it's comparing, so
 * at copy-time this will compute the unique androidTest dependencies. We need to have them because there may be
 * APIs that _they_ use that are used in the target app runtime, and we want R8 to account for those usages as well.
 */
@Suppress("UnstableApiUsage")
@CacheableTask
abstract class AndroidTestVariantClasspathJar @Inject constructor(objects: ObjectFactory) : VariantClasspathJar() {

  private companion object {
    val LOG = AndroidTestVariantClasspathJar::class.simpleName!!
  }

  @get:Classpath
  val androidTestRuntime: ConfigurableFileCollection = objects.fileCollection()

  @get:Classpath
  val appRuntime: ConfigurableFileCollection = objects.fileCollection()

  @get:Input
  val emitDebugInfo: Property<Boolean> = objects.property()

  override fun copy() {
    measureTimeMillis {
      project.logger.debug("$LOG: Diffing androidTest jars and app jars")
      val appJars = appRuntime.mapTo(mutableSetOf()) { it }
      val debug = emitDebugInfo.get()
      if (debug) {
        project.file("${project.buildDir}/${KeeperPlugin.INTERMEDIATES_DIR}/appJars2.txt").apply {
          writeText(appJars.sortedBy { it.path }
              .joinToString("\n") {
                it.path
              })
        }
      }
      val androidTestClasspath = androidTestRuntime.mapTo(mutableSetOf()) { it }
      if (debug) {
        project.file("${project.buildDir}/${KeeperPlugin.INTERMEDIATES_DIR}/androidTestJars2.txt").apply {
          writeText(androidTestClasspath.sortedBy { it.path }
              .joinToString("\n") {
                it.path
              })
        }
      }
      val distinctAndroidTestClasspath = androidTestRuntime.filterNotTo(mutableSetOf(), appJars::contains)
      if (debug) {
        project.file("${project.buildDir}/${KeeperPlugin.INTERMEDIATES_DIR}/distinctJars2.txt").apply {
          writeText(distinctAndroidTestClasspath.sortedBy { it.path }
              .joinToString("\n") {
                it.path
              })
        }
      }
      from(distinctAndroidTestClasspath.filter { it.extension == "jar" }.map(project::zipTree))
    }.also {
      project.logger.debug("$LOG: Diffing completed in ${it}ms")
    }
    super.copy()
  }
}
