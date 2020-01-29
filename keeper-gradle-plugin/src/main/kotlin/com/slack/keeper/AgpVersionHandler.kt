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
// TODO Can't use the newer `com.android.Version` until AGP 3.6.0+
@file:Suppress("deprecation")
package com.slack.keeper

import com.android.build.api.transform.Transform
import com.android.build.gradle.internal.pipeline.TransformTask
import com.android.builder.model.Version.ANDROID_GRADLE_PLUGIN_VERSION
import com.google.auto.service.AutoService
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.withType
import org.gradle.util.VersionNumber
import java.lang.reflect.Field
import java.util.Locale
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/** A handler API for working with different AGP versions. */
interface AgpVersionHandler {

  companion object {
    fun getInstance(): AgpVersionHandler = lazyInstance

    private val lazyInstance by lazy {
      createHandler()
    }

    /** Creates a new patcher for the given environment. */
    private fun createHandler(): AgpVersionHandler {
      /** Known patchers, listed in order of preference. */
      // TODO(zsweers) properly use a ServiceLoader for these?
      val handlers = listOf(Agp35xPatcher(), Agp36xPatcher())

      val agpVersion = VersionNumber.parse(ANDROID_GRADLE_PLUGIN_VERSION)
      val baseVersion = VersionNumber.parse(ANDROID_GRADLE_PLUGIN_VERSION).baseVersion
      return handlers.filter { baseVersion >= it.minVersion }
          .maxBy(AgpVersionHandler::minVersion)
          ?: error(
              "$TAG No applicable proguard task patchers found for this version of AGP ($agpVersion). Please file a bug report with your AGP version")
    }
  }

  /** Minimum AGP version for this patcher. */
  val minVersion: VersionNumber

  /** Returns the expected minifier name (i.e. 'R8' or 'Proguard') for the given [project]. */
  fun expectedMinifier(project: Project): String {
    // On 3.4+, R8 is the default. We support 3.5+, so we'll only look to see if it's explicitly disabled
    return if (project.findProperty("android.enableR8")?.toString()?.toBoolean() == false) {
      "Proguard"
    } else {
      "R8"
    }
  }

  /** Returns the interpolated task name for given [minifier] (i.e. 'R8' or 'Proguard') and variant. */
  fun interpolatedTaskName(minifier: String, variant: String): String

  /** Patches the provided [prop] into the target available proguard task. */
  fun applyGeneratedRules(
      project: Project,
      extension: KeeperExtension,
      prop: Provider<Directory>
  )
}

@AutoService(AgpVersionHandler::class)
class Agp35xPatcher : AgpVersionHandler {
  private val proguardConfigurable: Class<out Transform> by lazy {
    @Suppress("UNCHECKED_CAST")
    Class.forName(
        "com.android.build.gradle.internal.transforms.ProguardConfigurable") as Class<out Transform>
  }

  private val configurationFilesField: Field by lazy {
    proguardConfigurable.getDeclaredField("configurationFiles").apply {
      isAccessible = true
    }
  }

  override val minVersion: VersionNumber = VersionNumber.parse("3.5.0")

  override fun interpolatedTaskName(minifier: String, variant: String): String {
    return "transformClassesAndResourcesWith${minifier}For${variant}"
  }

  override fun applyGeneratedRules(project: Project, extension: KeeperExtension,
      prop: Provider<Directory>) {
    val targetName = interpolatedTaskName(expectedMinifier(project), extension.appVariant.capitalize(Locale.US))
    project.tasks.withType<TransformTask>().configureEach {
      if (name == targetName && proguardConfigurable.isInstance(transform)) {
        project.logger.debug("$TAG: Patching task '$name' with inferred androidTest proguard rules")
        (configurationFilesField.get(proguardConfigurable.cast(transform)) as ConfigurableFileCollection)
            .from(prop)
      }
    }
  }
}

@AutoService(AgpVersionHandler::class)
class Agp36xPatcher : AgpVersionHandler {
  private val proguardConfigurableTask: Class<out Task> by lazy {
    @Suppress("UNCHECKED_CAST")
    Class.forName("com.android.build.gradle.internal.tasks.ProguardConfigurableTask") as Class<out Task>
  }

  private val configurationFilesProperty: KProperty1<in Task, ConfigurableFileCollection> by lazy {
    @Suppress("UNCHECKED_CAST")
    proguardConfigurableTask.kotlin.memberProperties.first { it.name == "configurationFiles" } as KProperty1<in Task, ConfigurableFileCollection>
  }

  override val minVersion: VersionNumber = VersionNumber.parse("3.6.0")

  override fun interpolatedTaskName(minifier: String, variant: String): String {
    return "minify${variant}With${minifier}"
  }

  override fun applyGeneratedRules(project: Project, extension: KeeperExtension,
      prop: Provider<Directory>) {
    val targetName = interpolatedTaskName(expectedMinifier(project), extension.appVariant.capitalize(Locale.US))
    project.tasks.withType(proguardConfigurableTask).configureEach {
      // Names are minify{variant}WithProguard
      if (name == targetName) {
        println("$TAG: Patching task '$name' with inferred androidTest proguard rules")
        (configurationFilesProperty.get(this)).from(prop)
      }
    }
  }
}