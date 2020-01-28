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

internal val AGP_VERSION get() = VersionNumber.parse(ANDROID_GRADLE_PLUGIN_VERSION)

interface ProguardTaskPatcher {

  companion object {
    /** Known patchers, listed in order of preference. */
    private val PATCHERS = listOf(Agp35xPatcher(), Agp36xPatcher())

    /** Creates a new patcher for the given environment. */
    fun create(project: Project): ProguardTaskPatcher {
      project.logger.debug("$TAG AGP version is $AGP_VERSION")
      val baseVersion = AGP_VERSION.baseVersion
      return PATCHERS.filter { baseVersion >= it.minVersion }
          .maxBy(ProguardTaskPatcher::minVersion)
          ?: error(
              "$TAG No applicable proguard task patchers found for this version of AGP ($AGP_VERSION). Please file a bug report with your AGP version")
    }
  }

  /** Minimum AGP version for this patcher. */
  val minVersion: VersionNumber

  /** Patches the provided [prop] into the target available proguard task. */
  fun applyGeneratedRules(
      project: Project,
      extension: KeeperExtension,
      prop: Provider<Directory>
  )
}

@AutoService(ProguardTaskPatcher::class)
class Agp35xPatcher : ProguardTaskPatcher {
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

  override fun applyGeneratedRules(project: Project, extension: KeeperExtension,
      prop: Provider<Directory>) {
    project.tasks.withType<TransformTask>().configureEach {
      if (name.endsWith(extension.appVariant, ignoreCase = true) &&
          proguardConfigurable.isInstance(transform)) {
        project.logger.debug("$TAG: Patching task '$name' with inferred androidTest proguard rules")
        (configurationFilesField.get(proguardConfigurable.cast(transform)) as ConfigurableFileCollection)
            .from(prop)
      }
    }
  }
}

@AutoService(ProguardTaskPatcher::class)
class Agp36xPatcher : ProguardTaskPatcher {
  private val proguardConfigurableTask: Class<out Task> by lazy {
    @Suppress("UNCHECKED_CAST")
    Class.forName("com.android.build.gradle.internal.tasks.ProguardConfigurableTask") as Class<out Task>
  }

  private val configurationFilesProperty: KProperty1<in Task, ConfigurableFileCollection> by lazy {
    @Suppress("UNCHECKED_CAST")
    proguardConfigurableTask.kotlin.memberProperties.first { it.name == "configurationFiles" } as KProperty1<in Task, ConfigurableFileCollection>
  }

  override val minVersion: VersionNumber = VersionNumber.parse("3.6.0")

  override fun applyGeneratedRules(project: Project, extension: KeeperExtension,
      prop: Provider<Directory>) {
    val targetNamePrefix = "minify${extension.appVariant.capitalize(Locale.US)}With"
    project.tasks.withType(proguardConfigurableTask).configureEach {
      // Names are minify{variant}WithProguard
      if (name.startsWith(targetNamePrefix)) {
        project.logger.debug("$TAG: Patching task '$name' with inferred androidTest proguard rules")
        (configurationFilesProperty.get(this)).from(prop)
      }
    }
  }
}