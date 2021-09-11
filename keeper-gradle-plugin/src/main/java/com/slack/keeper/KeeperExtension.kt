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

package com.slack.keeper

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import javax.inject.Inject
import kotlin.DeprecationLevel.ERROR

/** Configuration for the [InferAndroidTestKeepRules]. */
public abstract class KeeperExtension @Inject constructor(objects: ObjectFactory) {
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

  @Deprecated(
    message = "Core Library Desugaring (L8) is automatically configured, this does nothing now " +
      "and will eventually be removed.",
    level = ERROR
  )
  public val enableL8RuleSharing: Property<Boolean> = objects.property<Boolean>().convention(false)

  internal val traceReferences: TraceReferences = objects.newInstance()

  /**
   * Allows to enable the new experimental TraceReferences entry-point,
   * and optionally specify additional arguments.
   * @see TraceReferences.arguments
   */
  public fun traceReferences(action: Action<TraceReferences>) {
    traceReferences.enabled.set(true)
    action.execute(traceReferences)
  }
}

public abstract class TraceReferences @Inject constructor(objects: ObjectFactory) {
  /**
   * Controls whether or not to use the new experimental TraceReferences entry-point.
   * Default is false but it's automatically enabled if the traceReferences block was invoked.
   */
  internal val enabled: Property<Boolean> = objects.property<Boolean>().convention(false)

  /**
   * Optional arguments during the trace-references invocation,
   * only considered when [enabled] is true.
   *
   * Default value: `listOf("--map-diagnostics:MissingDefinitionsDiagnostic", "error", "info")`
   * which is coming from [this discussion](https://issuetracker.google.com/issues/173435379)
   * with the R8 team.
   */
  public val arguments: ListProperty<String> = objects.listProperty<String>()
          .convention(listOf("--map-diagnostics:MissingDefinitionsDiagnostic", "error", "info"))
}
