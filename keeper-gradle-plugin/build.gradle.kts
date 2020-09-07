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
import com.github.jengelman.gradle.plugins.shadow.tasks.ConfigureShadowRelocation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `kotlin-dsl`
  `java-gradle-plugin`
  kotlin("jvm") version "1.4.0"
  kotlin("kapt") version "1.4.0"
  id("com.vanniktech.maven.publish") version "0.12.0"
  id("com.github.johnrengelman.shadow") version "6.0.0"
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
val shade: Configuration = configurations.maybeCreate("compileShaded")
configurations.getByName("compileOnly").extendsFrom(shade)
dependencies {
  implementation("org.jetbrains.kotlin:kotlin-stdlib:1.4.0")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:1.4.0")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.4.0")

  // We want a newer version of ZipFlinger for Zip64 support but don't want to incur that cost on
  // consumers, so we shade it.
  shade("com.android:zipflinger:4.1.0-rc02") {
    // ZipFlinger depends on com.android.tools:common and guava, but neither are actually used
    // com.android.tools:annotations are used, but we can exclude them too since they're just
    // annotations and not needed at runtime.
    exclude(group = "com.android.tools")
    exclude(group = "com.google.guava")
  }

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

tasks.register<ConfigureShadowRelocation>("relocateShadowJar") {
  target = tasks.shadowJar.get()
}

val shadowJar = tasks.shadowJar.apply {
  configure {
    dependsOn(tasks.getByName("relocateShadowJar"))
    minimize()
    archiveClassifier.set("")
    configurations = listOf(shade)
    relocate("com.android.zipflinger", "com.slack.keeper.internal.zipflinger")
  }
}
artifacts {
  runtime(shadowJar)
  archives(shadowJar)
}

// Shadow plugin doesn't natively support gradle metadata, so we have to tell the maven plugin where
// to get a jar now.
afterEvaluate {
  configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
      if (name == "pluginMaven") {
        // This is to properly wire the shadow jar's gradle metadata and pom information
        setArtifacts(artifacts.matching { it.classifier != "" })
        artifact(shadowJar)
      }
    }
  }
}
