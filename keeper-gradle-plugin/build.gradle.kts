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
import java.net.URI
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version libs.versions.kotlin.get()
  `java-gradle-plugin`
  id("org.jetbrains.dokka") version "1.9.0"
  alias(libs.plugins.mavenPublish)
  alias(libs.plugins.binaryCompatibilityValidator)
  id("org.jetbrains.kotlin.plugin.sam.with.receiver") version libs.versions.kotlin.get()
}

// Reimplement kotlin-dsl's application of this function for nice DSLs
samWithReceiver { annotation("org.gradle.api.HasImplicitReceiver") }

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17) // Match AGP's requirement
    // Because Gradle's Kotlin handling is stupid, this falls out of date quickly
    apiVersion.set(KotlinVersion.KOTLIN_1_9)
    languageVersion.set(KotlinVersion.KOTLIN_1_9)
    //   freeCompilerArgs.add(listOf("-progressive"))
    // We use class SAM conversions because lambdas compiled into invokedynamic are not
    // Serializable, which causes accidental headaches with Gradle configuration caching. It's
    // easier for us to just use the previous anonymous classes behavior
    freeCompilerArgs.add("-Xsam-conversions=class")
  }
}

tasks.withType<Test>().configureEach {
  beforeTest(closureOf<TestDescriptor> { logger.lifecycle("Running test: $this") })
  // Required to test configuration cache in tests when using withDebug()
  // https://github.com/gradle/gradle/issues/22765#issuecomment-1339427241
  jvmArgs(
    "--add-opens",
    "java.base/java.util=ALL-UNNAMED",
    "--add-opens",
    "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens",
    "java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens",
    "java.base/java.net=ALL-UNNAMED",
  )
}

sourceSets {
  getByName("test").resources.srcDirs(project.layout.buildDirectory.dir("pluginUnderTestMetadata"))
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(20)) } }

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

gradlePlugin {
  plugins {
    plugins.create("keeper") {
      id = "com.slack.keeper"
      implementationClass = "com.slack.keeper.KeeperPlugin"
    }
  }
}

kotlin { explicitApi() }

tasks.withType<DokkaTask>().configureEach {
  outputDirectory.set(rootDir.resolve("../docs/0.x"))
  dokkaSourceSets.configureEach {
    skipDeprecated.set(true)
    suppressInheritedMembers.set(true)
    externalDocumentationLink {
      url.set(
        URI("https://docs.gradle.org/${gradle.gradleVersion}/javadoc/allpackages-index.html")
          .toURL()
      )
    }
    externalDocumentationLink {
      packageListUrl.set(
        URI("https://developer.android.com/reference/tools/gradle-api/7.3/package-list").toURL()
      )
      url.set(URI("https://developer.android.com/reference/tools/gradle-api/7.3/classes").toURL())
    }
  }
}

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
  signAllPublications()
}

// Fix missing implicit task dependency in Gradle's test kit
tasks.named("processTestResources") { dependsOn("pluginUnderTestMetadata") }

// TODO how can we lazily chain this to other configurations?
val addTestPlugin = configurations.dependencyScope("addTestPlugin").get()

configurations { testImplementation.configure { extendsFrom(addTestPlugin) } }

tasks.pluginUnderTestMetadata {
  // make sure the test can access plugins for coordination.
  pluginClasspath.from(addTestPlugin)
}

dependencies {
  compileOnly(libs.kgp.api)
  compileOnly(libs.kgp)
  compileOnly(libs.zipflinger)
  compileOnly(libs.agp)

  addTestPlugin.invoke(libs.agp)
  addTestPlugin(libs.kgp)
  addTestPlugin(libs.kgp.api)
  testImplementation(libs.javapoet)
  testImplementation(libs.kotlinpoet)
  testImplementation(libs.truth)
  testImplementation(libs.junit)
}
