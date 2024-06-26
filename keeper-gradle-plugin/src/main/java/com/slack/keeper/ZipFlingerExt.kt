/*
 * Copyright (C) 2022. Slack Technologies, LLC
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

import com.android.zipflinger.ZipArchive
import com.android.zipflinger.ZipSource
import java.io.File
import java.nio.file.Path

/**
 * Returns a sequence of pairs representing the class files and their relative names for use in a
 * zip entry.
 */
internal fun File.classesSequence(): Sequence<Pair<String, File>> {
  val prefix = absolutePath
  return walkTopDown()
    .filter { it.extension == "class" }
    .filterNot { "META-INF" in it.name }
    .sortedBy { it.invariantSeparatorsPath }
    .map {
      // zip specification "4.4.17.1 file name: (Variable)" items:
      it.absolutePath
        // "The name of the file, with optional relative path.
        //  The path stored MUST NOT contain a drive or
        //  device letter, or a leading slash"
        .removePrefix(prefix)
        .removePrefix(File.separator)
        // "All slashes MUST be forward slashes '/' as opposed
        // to backwards slashes '\' for compatibility"
        .replace(File.separator, "/") to it
    }
}

/** Extracts classes from the target [jar] into this archive. */
internal fun ZipArchive.extractClassesFrom(jar: File, callback: (String) -> Unit) {
  val jarSource = newZipSource(jar)
  jarSource
    .entries()
    .filterNot { "META-INF" in it.key }
    .forEach { (name, entry) ->
      if (!entry.isDirectory && entry.name.endsWith(".class")) {
        val entryName = name.removePrefix(".")
        callback(entryName)
        delete(entryName)
        jarSource.select(entryName, name)
      }
    }
  add(jarSource)
}

private fun newZipSource(jar: File): ZipSource {
  return try {
    // AGP 4.1/4.2
    ZipSource::class.java.getDeclaredConstructor(File::class.java).newInstance(jar)
  } catch (e: NoSuchMethodException) {
    // AGP/ZipFlinger 7+
    ZipSource::class.java.getDeclaredConstructor(Path::class.java).newInstance(jar.toPath())
  }
}
