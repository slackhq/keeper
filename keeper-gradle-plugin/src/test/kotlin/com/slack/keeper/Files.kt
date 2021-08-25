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

import com.slack.keeper.SourceFile.JavaSourceFile
import com.slack.keeper.SourceFile.KotlinSourceFile
import com.squareup.javapoet.JavaFile
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
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

internal fun File.generatedChild(path: String) = child("build", "intermediates", "keeper", *path.split("/").toTypedArray())
internal fun File.child(vararg path: String) = File(this,
    path.toList().joinToString(File.separator)).apply {
  check(exists()) {
    "Child doesn't exist! Expected $this. Other files in this dir: ${parentFile.listFiles()}"
  }
}

internal sealed class SourceFile(val name: String) {
  abstract fun writeTo(file: File)

  data class JavaSourceFile(val javaFile: JavaFile) : SourceFile(javaFile.typeSpec.name) {
    override fun writeTo(file: File) = javaFile.writeTo(file)
  }

  data class KotlinSourceFile(
      val fileSpec: FileSpec
  ) : SourceFile(fileSpec.members.filterIsInstance<TypeSpec>().first().name!!) {
    override fun writeTo(file: File) = fileSpec.writeTo(file)
  }
}

internal operator fun File.plusAssign(sourceFile: SourceFile) {
  sourceFile.writeTo(this)
}

internal fun JavaFile.asSourceFile(): SourceFile = JavaSourceFile(this)
internal fun FileSpec.asSourceFile(): SourceFile = KotlinSourceFile(this)