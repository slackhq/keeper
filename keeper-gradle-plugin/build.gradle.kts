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

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    kotlin("jvm") version "1.3.72"
    kotlin("kapt") version "1.3.72"
    id("com.vanniktech.maven.publish") version "0.11.1"
    id("com.github.johnrengelman.shadow") version "5.2.0"
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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs = listOf("-progressive")
        jvmTarget = "1.8"
    }
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
    useLegacyMode = false
    nexus {
        groupId = "com.slack"
    }
}

val defaultAgpVersion = "3.6.3"
val agpVersion = findProperty("keeperTest.agpVersion")?.toString() ?: defaultAgpVersion

// See https://github.com/slackhq/keeper/pull/11#issuecomment-579544375 for context
val releaseMode = hasProperty("keeper.releaseMode")
val shade: Configuration = configurations.maybeCreate("compileShaded")
configurations.getByName("compileOnly").extendsFrom(shade)
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.3.72")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:1.3.72")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.3.72")

    // We want a newer version of ZipFlinger for Zip64 support but don't want to incur that cost on
    // consumers, so we shade it.
    shade("com.android:zipflinger:4.1.0-alpha09") {
        // ZipFlinger depends on com.android.tools:common which has breaking changes for AGP.
        exclude(group = "com.android.tools")
    }

    if (releaseMode) {
        compileOnly("com.android.tools.build:gradle:$defaultAgpVersion")
    } else {
        implementation("com.android.tools.build:gradle:$agpVersion")
    }

    compileOnly("com.google.auto.service:auto-service-annotations:1.0-rc6")
    kapt("com.google.auto.service:auto-service:1.0-rc6")

    testImplementation("com.squareup:javapoet:1.12.1")
    testImplementation("com.squareup:kotlinpoet:1.5.0")
    testImplementation("com.google.truth:truth:1.0.1")
    testImplementation("junit:junit:4.13")
}

tasks.jar.configure { enabled = false }

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
        exclude(
            // Exclude all the stuff that comes from its transitive guava dependency
            "org/**",
            "com/google/common/**",
            "com/google/errorprone/**",
            "com/google/j2objc/**",
            "javax/**",
            "META-INF/maven/**"
        )
    }
}
artifacts {
    runtime(shadowJar)
    archives(shadowJar)
}
