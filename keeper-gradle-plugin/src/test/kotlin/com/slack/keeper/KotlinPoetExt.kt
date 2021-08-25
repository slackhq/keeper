/*
 * Copyright (C) 2020 Slack Technologies, LLC
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

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeSpec
import java.io.File

internal fun kotlinFile(packageName: String, className: String,
    body: TypeSpec.Builder.() -> Unit): SourceFile {
  return FileSpec.get(
      packageName = packageName,
      typeSpec = TypeSpec.objectBuilder(className)
          .apply(body)
          .build()
  ).asSourceFile()
}

internal fun TypeSpec.Builder.funSpec(name: String, body: FunSpec.Builder.() -> Unit) {
  addFunction(FunSpec.builder(name).apply(body).build())
}

internal operator fun File.plusAssign(fileSpec: FileSpec) {
  fileSpec.writeTo(this)
}