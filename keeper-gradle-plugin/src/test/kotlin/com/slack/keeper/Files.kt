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

import java.io.File
import java.util.zip.ZipFile

internal fun ZipFile.readClasses(): Set<String> {
  val classes = mutableSetOf<String>()
  return use {
    val entries = it.entries()
    while (entries.hasMoreElements()) {
      val next = entries.nextElement()
      if (!next.isDirectory && next.name.endsWith(".class")) {
        classes += next.name.substringAfterLast("/")
      }
    }
    classes
  }
}

internal fun File.newFile(path: String, block: (File.() -> Unit)? = null): File {
  return File(this, path).apply {
    parentFile.mkdirs()
    block?.invoke(this)
  }
}

internal fun File.newDir(path: String): File {
  return File(this, path).apply { mkdirs() }
}

internal fun File.generatedChild(path: String) = child("build", "intermediates", "keeper", path)
internal fun File.child(vararg path: String) = File(this, path.toList().joinToString(File.separator))