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
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.agp.library) apply false
  // Version just here to make gradle happy. It's always substituted as an included build
  id("com.slack.keeper") version "0.14.0" apply false
  alias(libs.plugins.spotless)
}

subprojects {
  pluginManager.withPlugin("java") {
    configure<JavaPluginExtension> { toolchain { languageVersion.set(JavaLanguageVersion.of(20)) } }

    tasks.withType<JavaCompile>().configureEach { options.release.set(11) }
  }

  plugins.withType<KotlinBasePlugin>().configureEach {
    project.tasks.withType<KotlinCompile>().configureEach {
      compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-progressive")
      }
    }
  }
}

configurations
  .matching { it.name.startsWith("spotless") }
  .configureEach {
    resolutionStrategy {
      // Guava's new gradle metadata is a dumpster fire https://github.com/google/guava/issues/6612
      force("com.google.guava:guava:32.0.1-jre")
    }
  }

spotless {
  format("misc") {
    target("**/*.md", ".gitignore")
    trimTrailingWhitespace()
    endWithNewline()
  }
  val ktfmtVersion = libs.versions.ktfmt.get()
  kotlin {
    target("**/*.kt")
    targetExclude("**/.gradle/**", "**/build/**")
    ktfmt(ktfmtVersion).googleStyle()
    trimTrailingWhitespace()
    endWithNewline()
    licenseHeaderFile(rootProject.file("spotless/copyright.kt"))
    targetExclude("**/copyright.kt")
  }
  kotlinGradle {
    target("**/*.kts", "./*.kts")
    ktfmt(ktfmtVersion).googleStyle()
    trimTrailingWhitespace()
    endWithNewline()
    licenseHeaderFile(
      "spotless/copyright.kt",
      "(import|plugins|buildscript|dependencies|dependencyResolutionManagement|pluginManagement|rootProject)"
    )
  }
  java {
    target("**/*.java")
    googleJavaFormat(libs.versions.gjf.get()).reflowLongStrings()
    trimTrailingWhitespace()
    endWithNewline()
    licenseHeaderFile(rootProject.file("spotless/copyright.java"))
    targetExclude("**/copyright.java")
  }
}
