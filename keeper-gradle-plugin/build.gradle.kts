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
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URL

plugins {
  `kotlin-dsl`
  `java-gradle-plugin`
  kotlin("jvm") version "1.6.10"
  id("org.jetbrains.dokka") version "1.6.10"
  id("com.vanniktech.maven.publish") version "0.18.0"
  id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.8.0"
}

buildscript {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

repositories {
  mavenCentral()
  google()
  gradlePluginPortal()
}

tasks.withType<KotlinCompile>().configureEach {
  kotlinOptions {
    jvmTarget = "1.8"
    // Because Gradle's Kotlin handling is stupid, this falls out of date quickly
    apiVersion = "1.5"
    languageVersion = "1.5"
//    @Suppress("SuspiciousCollectionReassignment")
//    freeCompilerArgs += listOf("-progressive")
    // We use class SAM conversions because lambdas compiled into invokedynamic are not
    // Serializable, which causes accidental headaches with Gradle configuration caching. It's
    // easier for us to just use the previous anonymous classes behavior
    @Suppress("SuspiciousCollectionReassignment")
    freeCompilerArgs += "-Xsam-conversions=class"
  }
}

tasks.withType<Test>().configureEach {
  beforeTest(closureOf<TestDescriptor> {
    logger.lifecycle("Running test: $this")
  })
}

sourceSets {
  getByName("test").resources.srcDirs("$buildDir/pluginUnderTestMetadata")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(11))
  }
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(8)
}

gradlePlugin {
  plugins {
    plugins.create("keeper") {
      id = "com.slack.keeper"
      implementationClass = "com.slack.keeper.KeeperPlugin"
    }
  }
}

kotlin {
  explicitApi()
}

tasks.withType<DokkaTask>().configureEach {
  outputDirectory.set(rootDir.resolve("../docs/0.x"))
  dokkaSourceSets.configureEach {
    skipDeprecated.set(true)
    suppressInheritedMembers.set(true)
    externalDocumentationLink {
      url.set(URL("https://docs.gradle.org/${gradle.gradleVersion}/javadoc/index.html"))
    }
    externalDocumentationLink {
      packageListUrl.set(URL("https://developer.android.com/reference/tools/gradle-api/7.0/package-list"))
      url.set(URL("https://developer.android.com/reference/tools/gradle-api/7.0/classes"))
    }
  }
}

val defaultAgpVersion = "7.1.0"
val agpVersion = findProperty("keeperTest.agpVersion")?.toString() ?: defaultAgpVersion

// See https://github.com/slackhq/keeper/pull/11#issuecomment-579544375 for context
val releaseMode = hasProperty("keeper.releaseMode")
dependencies {
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:1.6.10")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.6.10")
  compileOnly("com.android:zipflinger:7.1.0")

  if (releaseMode) {
    compileOnly("com.android.tools.build:gradle:$defaultAgpVersion")
  } else {
    implementation("com.android.tools.build:gradle:$agpVersion")
  }

  testImplementation("com.squareup:javapoet:1.13.0")
  testImplementation("com.squareup:kotlinpoet:1.9.0")
  testImplementation("com.google.truth:truth:1.1.3")
  testImplementation("junit:junit:4.13.2")
}
