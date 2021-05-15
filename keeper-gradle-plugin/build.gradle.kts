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
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URL

plugins {
  `kotlin-dsl`
  `java-gradle-plugin`
  kotlin("jvm") version "1.5.0"
  kotlin("kapt") version "1.5.0"
  id("org.jetbrains.dokka") version "1.4.32"
  id("com.vanniktech.maven.publish") version "0.15.1"
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
    // Because Gradle's Kotlin handling is stupid
    apiVersion = "1.4"
    languageVersion = "1.4"
//    @Suppress("SuspiciousCollectionReassignment")
//    freeCompilerArgs += listOf("-progressive")
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
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
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

tasks.named<DokkaTask>("dokkaHtml") {
  outputDirectory.set(rootDir.resolve("../docs/0.x"))
  dokkaSourceSets.configureEach {
    skipDeprecated.set(true)
    externalDocumentationLink {
      url.set(URL("https://docs.gradle.org/${gradle.gradleVersion}/javadoc/index.html"))
    }
    externalDocumentationLink {
      packageListUrl.set(URL("https://developer.android.com/reference/tools/gradle-api/4.1/package-list"))
      url.set(URL("https://developer.android.com/reference/tools/gradle-api/4.1/classes"))
    }
  }
}

val defaultAgpVersion = "7.0.0-alpha14"
val agpVersion = findProperty("keeperTest.agpVersion")?.toString() ?: defaultAgpVersion

// See https://github.com/slackhq/keeper/pull/11#issuecomment-579544375 for context
val releaseMode = hasProperty("keeper.releaseMode")
dependencies {
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:1.5.0")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.5.0")
  implementation("com.android:zipflinger:4.2.1")

  if (releaseMode) {
    compileOnly("com.android.tools.build:gradle:$defaultAgpVersion")
  } else {
    implementation("com.android.tools.build:gradle:$agpVersion")
  }

  compileOnly("com.google.auto.service:auto-service-annotations:1.0")
  kapt("com.google.auto.service:auto-service:1.0")

  testImplementation("com.squareup:javapoet:1.13.0")
  testImplementation("com.squareup:kotlinpoet:1.8.0")
  testImplementation("com.google.truth:truth:1.1.2")
  testImplementation("junit:junit:4.13.2")
}
