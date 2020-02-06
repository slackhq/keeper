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

import com.android.builder.model.BuildType
import com.android.builder.model.ProductFlavor
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

/** Configuration for the [InferAndroidTestKeepRules]. */
open class KeeperExtension @Inject constructor(objects: ObjectFactory) {
  @Suppress("PropertyName")
  internal var _variantFilter: Action<VariantFilter>? = null

  /**
   * Applies a variant filter for Android. Note that the variant tested is the _app_ variant, not
   * the test variant.
   *
   * @param action the configure action for the [VariantFilter]
   */
  fun variantFilter(action: Action<VariantFilter>) {
    this._variantFilter = action
  }

  /**
   * Controls whether or not to automatically add the R8 repository for dependencies. Default is
   * true. Disable if you want to define your own repo for fetching the R8 dependency.
   */
  @Suppress("UnstableApiUsage")
  val automaticR8RepoManagement: Property<Boolean> = objects.property<Boolean>().convention(true)

  /**
   * Optional custom jvm arguments to pass into the R8 `PrintUses` execution. Useful if you want
   * to enable debugging in R8.
   *
   * Example: `listOf("-Xdebug", "-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=y")`
   */
  val r8JvmArgs: ListProperty<String> = objects.listProperty()

  /** Emit extra debug information, useful for bug reporting. */
  @Suppress("UnstableApiUsage")
  var emitDebugInformation: Property<Boolean> = objects.property<Boolean>().convention(false)
}


interface VariantFilter {
  /**
   * Indicate whether or not to ignore this particular variant. Default is false.
   */
  fun setIgnore(ignore: Boolean)

  /**
   * Returns the Build Type.
   */
  val buildType: BuildType

  /**
   * Returns the list of flavors, or an empty list.
   */
  val flavors: List<ProductFlavor>

  /**
   * Returns the unique variant name.
   */
  val name: String
}