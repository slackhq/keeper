package com.slack.keeper

import com.android.zipflinger.ZipArchive
import com.android.zipflinger.ZipSource
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNot
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Returns a sequence of pairs representing the class files and their relative names for use in a
 * zip entry.
 */
internal fun File.classesSequence(): Sequence<Pair<String, File>> {
  val prefix = absolutePath
  return walkTopDown()
      .filter { it.extension == "class" }
      .filterNot { "META-INF" in it.name }
      .map { it.absolutePath.removePrefix(prefix).removePrefix("/") to it }
}

/**
 * Extracts classes from the target [jar] into this archive.
 */
internal suspend fun ZipArchive.extractClassesFrom(jar: File, lock: ReentrantLock) {
  val jarSource = ZipSource(jar)
  val namesToRemove = mutableSetOf<String>()
  jarSource.entries()
      .entries
      .asFlow()
      .filterNot { "META-INF" in it.key }
      .collect { (name, entry) ->
        if (!entry.isDirectory && entry.name.endsWith(".class")) {
          val entryName = name.removePrefix(".")
          namesToRemove += entryName
          jarSource.select(entryName, name)
        }
      }
  lock.withLock {
    namesToRemove.forEach { delete(it) }
    add(jarSource)
  }
}