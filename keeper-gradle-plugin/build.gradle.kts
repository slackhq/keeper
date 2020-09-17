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
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `kotlin-dsl`
  `java-gradle-plugin`
  kotlin("jvm") version "1.4.10"
  kotlin("kapt") version "1.4.10"
  id("com.vanniktech.maven.publish") version "0.12.0"
}

buildscript {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    jcenter()
  }
}

repositories {
  google()
  gradlePluginPortal()
  mavenCentral()
  jcenter()
}

tasks.withType<KotlinCompile>().configureEach {
  kotlinOptions {
    freeCompilerArgs = listOf("-progressive")
    jvmTarget = "1.8"
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

kotlinDslPluginOptions {
  experimentalWarning.set(false)
}

mavenPublish {
  nexus {
    stagingProfile = "com.slack"
  }
}

val defaultAgpVersion = "4.0.0"
val agpVersion = findProperty("keeperTest.agpVersion")?.toString() ?: defaultAgpVersion

// See https://github.com/slackhq/keeper/pull/11#issuecomment-579544375 for context
val releaseMode = hasProperty("keeper.releaseMode")
dependencies {
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:1.4.10")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.4.10")

  if (releaseMode) {
    compileOnly("com.android.tools.build:gradle:$defaultAgpVersion")
  } else {
    implementation("com.android.tools.build:gradle:$agpVersion")
  }

  compileOnly("com.google.auto.service:auto-service-annotations:1.0-rc7")
  kapt("com.google.auto.service:auto-service:1.0-rc7")

  testImplementation("com.squareup:javapoet:1.13.0")
  testImplementation("com.squareup:kotlinpoet:1.6.0")
  testImplementation("com.google.truth:truth:1.0.1")
  testImplementation("junit:junit:4.13")
}
