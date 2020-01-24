/*
 * Copyright (C) 2020 Slack Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.slack.keeper

import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.jvm.tasks.Jar
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * A simple cacheable [Jar] task. Normally these aren't intended to be cacheable, but in our case it's fine since this
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
  val androidTestRuntime = objects.fileCollection()

  @get:Classpath
  val appRuntime = objects.fileCollection()

  override fun copy() {
    measureTimeMillis {
      project.logger.debug("$LOG: Diffing androidTest jars and app jars")
      val appJars = appRuntime.filter { it.extension == "jar" }.mapTo(mutableSetOf()) { it.nameWithoutExtension }
      val distinctAndroidTestJars = androidTestRuntime.filter {
        it.extension == "jar" && it.nameWithoutExtension !in appJars
      }
      from(distinctAndroidTestJars.map { project.zipTree(it) })
    }.also {
      project.logger.debug("$LOG: Diffing completed in ${it}ms")
    }
    super.copy()
  }
}
