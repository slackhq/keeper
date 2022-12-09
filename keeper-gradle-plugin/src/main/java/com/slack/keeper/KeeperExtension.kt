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
package com.slack.keeper

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/** Configuration for the [InferAndroidTestKeepRules]. */
public abstract class KeeperExtension @Inject constructor(objects: ObjectFactory) {
  /**
   * Controls whether or not to automatically add the R8 repository for dependencies. Default is
   * true. Disable if you want to define your own repo for fetching the R8 dependency.
   */
  public val automaticR8RepoManagement: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * Optional custom jvm arguments to pass into the R8 `TraceReferences` execution. Useful if you want
   * to enable debugging in R8.
   *
   * Example: `listOf("-Xdebug", "-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=y")`
   */
  public val r8JvmArgs: ListProperty<String> = objects.listProperty(String::class.java)

  /** Emit extra debug information, useful for bug reporting. */
  public val emitDebugInformation: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

  /** Controls whether or not to enable assertions in the JavaExec run of R8. Default is true. */
  public val enableAssertions: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  internal val traceReferences: TraceReferences = objects.newInstance(TraceReferences::class.java)

  /**
   * Allows to enable the new experimental TraceReferences entry-point,
   * and optionally specify additional arguments.
   * @see TraceReferences.arguments
   */
  public fun traceReferences(action: Action<TraceReferences>) {
    action.execute(traceReferences)
  }
}

public abstract class TraceReferences @Inject constructor(objects: ObjectFactory) {

  /**
   * Optional arguments during the trace-references invocation.
   *
   * Default value: `listOf("--map-diagnostics:MissingDefinitionsDiagnostic", "error", "info")`
   * which is coming from [this discussion](https://issuetracker.google.com/issues/173435379)
   * with the R8 team.
   */
  public val arguments: ListProperty<String> = objects.listProperty(String::class.java)
    .convention(listOf("--map-diagnostics:MissingDefinitionsDiagnostic", "error", "info"))
}
