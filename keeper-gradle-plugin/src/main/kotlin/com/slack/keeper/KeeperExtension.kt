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

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

/** Configuration for the [InferAndroidTestKeepRules]. */
open class KeeperExtension @Inject constructor(objects: ObjectFactory) {

  /**
   * The name of the androidTest variant to infer for. This should include the "AndroidTest"
   * suffix. Required.
   */
  lateinit var androidTestVariant: String

  /** The name of the application variant to infer for. Required. */
  lateinit var appVariant: String

  /** R8 version. Can be a tag or sha. Default is 1.6.53. */
  val r8Version: Property<String> = objects.property()

  /**
   * Optional custom jvm arguments to pass into the R8 `PrintUses` execution. Useful if you want
   * to enable debugging in R8.
   *
   * Example: `listOf("-Xdebug", "-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=y")`
   */
  val r8JvmArgs: ListProperty<String> = objects.listProperty()
}