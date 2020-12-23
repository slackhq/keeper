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
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

/** Configuration for the [InferAndroidTestKeepRules]. */
public open class KeeperExtension @Inject constructor(objects: ObjectFactory) {
  @Suppress("PropertyName")
  internal var _variantFilter: Action<VariantFilter>? = null

  /**
   * Applies a variant filter for Android. Note that the variant tested is the _app_ variant, not
   * the test variant.
   *
   * @param action the configure action for the [VariantFilter]
   */
  public fun variantFilter(action: Action<VariantFilter>) {
    this._variantFilter = action
  }

  /**
   * Controls whether or not to automatically add the R8 repository for dependencies. Default is
   * true. Disable if you want to define your own repo for fetching the R8 dependency.
   */
  @Suppress("UnstableApiUsage")
  public val automaticR8RepoManagement: Property<Boolean> = objects.property<Boolean>().convention(true)

  /**
   * Optional custom jvm arguments to pass into the R8 `PrintUses` execution. Useful if you want
   * to enable debugging in R8.
   *
   * Example: `listOf("-Xdebug", "-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=y")`
   */
  public val r8JvmArgs: ListProperty<String> = objects.listProperty()

  /** Emit extra debug information, useful for bug reporting. */
  @Suppress("UnstableApiUsage")
  public val emitDebugInformation: Property<Boolean> = objects.property<Boolean>().convention(false)

  /** Controls whether or not to enable assertions in the JavaExec run of R8. Default is true. */
  @Suppress("UnstableApiUsage")
  public val enableAssertions: Property<Boolean> = objects.property<Boolean>().convention(true)

  /**
   * Enables L8 rule sharing. By default, L8 will generate separate rules for test app and
   * androidTest app L8 rules. This can cause problems in minified tests for a couple reasons
   * though! This tries to resolve these via two steps.
   *
   * Issue 1: L8 will try to obfuscate this otherwise and can result in conflicting class names
   * between the app and test APKs. This is a little confusing because L8 treats "minified" as
   * "obfuscated" and tries to match. Since we don't care about obfuscating here, we can just
   * disable it.
   *
   * Issue 2: L8 packages `j$` classes into androidTest but doesn't match what's in the target app.
   * This causes confusion when invoking code in the target app from the androidTest classloader
   * and it then can't find some expected `j$` classes. To solve this, we feed the the app's
   * generated `j$` rules in as inputs to the androidTest L8 task's input rules.
   *
   * More details can be found here: https://issuetracker.google.com/issues/158018485
   */
  public val enableL8RuleSharing: Property<Boolean> = objects.property<Boolean>().convention(false)

  internal val traceReferences: TraceReferences = objects.newInstance()

  public fun traceReferences(action: Action<TraceReferences>) {
    traceReferences.enabled.set(true)
    action.execute(traceReferences)
  }
}

public interface VariantFilter {
  /**
   * Indicate whether or not to ignore this particular variant. Default is false.
   */
  public fun setIgnore(ignore: Boolean)

  /**
   * Returns the Build Type.
   */
  public val buildType: BuildType

  /**
   * Returns the list of flavors, or an empty list.
   */
  public val flavors: List<ProductFlavor>

  /**
   * Returns the unique variant name.
   */
  public val name: String
}

public abstract class TraceReferences @Inject constructor(objects: ObjectFactory) {
  /**
   * Controls whether or not to use the new experimental TraceReferences entry-point.
   * Default is false but it's automatically enabled if the traceReferences block was used.
   */
  internal val enabled: Property<Boolean> = objects.property<Boolean>().convention(false)

  /**
   * Optional arguments during the trace-references invocation,
   * only considered if [isTraceReferencesEnabled] is true.
   *
   * Default value: `listOf("--map-diagnostics:MissingDefinitionsDiagnostic", "error", "info")`
   */
  public val arguments: ListProperty<String> = objects.listProperty<String>()
          .convention(listOf("--map-diagnostics:MissingDefinitionsDiagnostic", "error", "info"))
}
