@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `java-gradle-plugin`
  `maven-publish`
  id("com.gradle.plugin-publish") version "0.10.1"
  id("org.jetbrains.kotlin.jvm") version "1.3.71"
  `kotlin-dsl`
  groovy
  signing
  //id("com.bnorm.power.kotlin-power-assert") version "0.3.0"
}

repositories {
  jcenter()
  google()
  maven { url = uri("https://dl.bintray.com/kotlin/kotlin-eap") }
}

version = "0.31.1"
group = "com.autonomousapps"

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
  withJavadocJar()
  withSourcesJar()
}

tasks.withType<KotlinCompile>().configureEach {
  kotlinOptions {
    jvmTarget = "1.8"
  }
}

//tasks.withType<KotlinCompile>().matching {
//    // compileTestKotlin, compileFunctionalTestKotlin, ...
//    // useIR leads to compilation failures for non-standard test source sets :'(
//    //it.name.endsWith("TestKotlin")
//    it.name == "compileTestKotlin"
//}.configureEach {
//    kotlinOptions {
//        // For use with the "com.bnorm.power.kotlin-power-assert" plugin, enabling power asserts in tests
//        // https://github.com/bnorm/kotlin-power-assert
//        useIR = true
//    }
//}

// Add a source set for the functional test suite. This must come _above_ the `dependencies` block.
val functionalTestSourceSet = sourceSets.create("functionalTest") {
  compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
  runtimeClasspath += output + compileClasspath
}
val functionalTestImplementation = configurations.getByName("functionalTestImplementation")
    .extendsFrom(configurations.getByName("testImplementation"))

val funcTestRuntime: Configuration by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
}

val compileFunctionalTestKotlin = tasks.named("compileFunctionalTestKotlin")
tasks.named<AbstractCompile>("compileFunctionalTestGroovy") {
  dependsOn(compileFunctionalTestKotlin)
  classpath += files(compileFunctionalTestKotlin.get().outputs.files)
}

// Add a source set for the smoke test suite. This must come _above_ the `dependencies` block.
val smokeTestSourceSet = sourceSets.create("smokeTest") {
  compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
  runtimeClasspath += output + compileClasspath
}
configurations.getByName("smokeTestImplementation")
    .extendsFrom(functionalTestImplementation)

// Permits testing against different versions of AGP
// 3.5.3
// 3.6.0-rc01
// 4.0.0-alpha09. Min Gradle version is 6.1-rc-1
val agpVersion: String = System.getProperty("funcTest.agpVersion", "3.5.3")

val asmVersion = "7.2.0.1"

val antlrVersion by extra("4.8")
val internalAntlrVersion by extra("$antlrVersion.0")

dependencies {
  implementation(platform("org.jetbrains.kotlin:kotlin-bom"))

  implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
  implementation("com.squareup.moshi:moshi:1.8.0") {
    because("For writing reports in JSON format")
  }
  implementation("com.squareup.moshi:moshi-kotlin:1.8.0") {
    because("For writing reports based on Kotlin classes")
  }
  implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.6.10") {
    because("For writing HTML reports")
  }
  implementation("org.jetbrains.kotlin:kotlin-reflect") {
    because("For Kotlin ABI analysis")
  }
  implementation("org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.1.0") {
    because("For Kotlin ABI analysis")
  }
  implementation(files("libs/asm-$asmVersion.jar"))
  implementation(files("libs/antlr-$internalAntlrVersion.jar"))

  compileOnly("com.android.tools.build:gradle:3.6.1") { //4.0.0-beta03
    because("Auto-wiring into Android projects")
  }
  compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin") {
    because("Auto-wiring into Kotlin projects")
  }

  testImplementation("org.spockframework:spock-core:1.3-groovy-2.5") {
    exclude(module = "groovy-all")
    because("For Spock tests")
  }
  testImplementation("org.jetbrains.kotlin:kotlin-test")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
  testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0") {
    because("Writing manual stubs for Configuration seems stupid")
  }

  functionalTestImplementation("org.spockframework:spock-core:1.3-groovy-2.5") {
    exclude(module = "groovy-all")
    because("For Spock tests")
  }
  functionalTestImplementation("commons-io:commons-io:2.6") {
    because("For FileUtils.deleteDirectory()")
  }
  funcTestRuntime("com.android.tools.build:gradle:$agpVersion")
  funcTestRuntime("org.jetbrains.kotlin:kotlin-gradle-plugin")
}

tasks.jar {
  // Bundle shaded jars into final artifact
  from(zipTree("libs/asm-$asmVersion.jar"))
  from(zipTree("libs/antlr-$internalAntlrVersion.jar"))
}

gradlePlugin {
  plugins {
    create("dependencyAnalysisPlugin") {
      id = "com.autonomousapps.dependency-analysis"
      implementationClass = "com.autonomousapps.DependencyAnalysisPlugin"
    }
  }
}

// For publishing to the Gradle Plugin Portal
// https://plugins.gradle.org/docs/publish-plugin
pluginBundle {
  website = "https://github.com/autonomousapps/dependency-analysis-android-gradle-plugin"
  vcsUrl = "https://github.com/autonomousapps/dependency-analysis-android-gradle-plugin"

  description = "A plugin to report mis-used dependencies in your Android project"

  (plugins) {
    "dependencyAnalysisPlugin" {
      displayName = "Android Dependency Analysis Gradle Plugin"
      tags = listOf("android", "dependencies")
    }
  }

  mavenCoordinates {
    groupId = project.group.toString()
    artifactId = "dependency-analysis-gradle-plugin"
  }
}

// For publishing to other repositories
publishing {
  publications {
    afterEvaluate {
      named<MavenPublication>("dependencyAnalysisPluginPluginMarkerMaven") {
        pom {
          name.set("Dependency Analysis Gradle Plugin")
          description.set("Analyzes dependency usage in Android and Java/Kotlin projects")
          url.set("https://github.com/autonomousapps/dependency-analysis-android-gradle-plugin")
          inceptionYear.set("2019")
          licenses {
            license {
              name.set("The Apache License, Version 2.0")
              url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
          }
          developers {
            developer {
              id.set("autonomousapps")
              name.set("Tony Robalik")
            }
          }
          scm {
            connection.set("scm:git:git://github.com/autonomousapps/dependency-analysis-android-gradle-plugin.git")
            developerConnection.set("scm:git:ssh://github.com/autonomousapps/dependency-analysis-android-gradle-plugin.git")
            url.set("https://github.com/autonomousapps/dependency-analysis-android-gradle-plugin")
          }
        }
      }
    }

    create<MavenPublication>("plugin") {
      from(components["java"])

      versionMapping {
        usage("java-api") {
          fromResolutionOf("runtimeClasspath")
        }
        usage("java-runtime") {
          fromResolutionResult()
        }
      }

      pom {
        name.set("Dependency Analysis Gradle Plugin")
        description.set("Analyzes dependency usage in Android and Java/Kotlin projects")
        url.set("https://github.com/autonomousapps/dependency-analysis-android-gradle-plugin")
        inceptionYear.set("2019")
        licenses {
          license {
            name.set("The Apache License, Version 2.0")
            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
          }
        }
        developers {
          developer {
            id.set("autonomousapps")
            name.set("Tony Robalik")
          }
        }
        scm {
          connection.set("scm:git:git://github.com/autonomousapps/dependency-analysis-android-gradle-plugin.git")
          developerConnection.set("scm:git:ssh://github.com/autonomousapps/dependency-analysis-android-gradle-plugin.git")
          url.set("https://github.com/autonomousapps/dependency-analysis-android-gradle-plugin")
        }
      }
    }
    repositories {
      val sonatypeUsername = project.properties["sonatypeUsername"]?.toString()
      val sonatypePassword = project.properties["sonatypePassword"]?.toString()
      if (sonatypeUsername != null && sonatypePassword != null) {
        maven {
          name = "sonatype"
          val releasesRepoUrl = "https://oss.sonatype.org/service/local/staging/deploy/maven2"
          val snapshotsRepoUrl = "https://oss.sonatype.org/content/repositories/snapshots"
          url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)

          credentials {
            username = sonatypeUsername
            password = sonatypePassword
          }
        }
      }
    }
  }
}

afterEvaluate {
  signing {
    sign(publishing.publications["plugin"], publishing.publications["dependencyAnalysisPluginPluginMarkerMaven"])
  }
}

gradlePlugin.testSourceSets(functionalTestSourceSet, smokeTestSourceSet)

// Add a task to run the functional tests
val functionalTest by tasks.registering(Test::class) {
  mustRunAfter(tasks.named("test"))

  description = "Runs the functional tests."
  group = "verification"

  testClassesDirs = functionalTestSourceSet.output.classesDirs
  classpath = functionalTestSourceSet.runtimeClasspath

  // Workaround for https://github.com/gradle/gradle/issues/4506#issuecomment-570815277
  systemProperty("org.gradle.testkit.dir", file("${buildDir}/tmp/test-kit"))
  systemProperty("com.autonomousapps.agpversion", agpVersion)

  beforeTest(closureOf<TestDescriptor> {
    logger.lifecycle("Running test: $this")
  })
}

val smokeTestVersionKey = "com.autonomousapps.version"
val smokeTestVersion: String = System.getProperty(smokeTestVersionKey, latestRelease())

val smokeTest by tasks.registering(Test::class) {
  mustRunAfter(tasks.named("test"), functionalTest)

  description = "Runs the smoke tests."
  group = "verification"

  testClassesDirs = smokeTestSourceSet.output.classesDirs
  classpath = smokeTestSourceSet.runtimeClasspath

  systemProperty(smokeTestVersionKey, smokeTestVersion)

  beforeTest(closureOf<TestDescriptor> {
    logger.lifecycle("Running test: $this")
  })
}

/**
 * Algorithm:
 * 1. If version is !SNAPSHOT, latestRelease is identical, but with -SNAPSHOT suffix.
 * 2. If version is SNAPSHOT, latestRelease is non-SNAPSHOT, and patch should be -1 by comparison.
 */
fun latestRelease(): String {
  val v = version as String
  return if (!v.endsWith("SNAPSHOT")) {
    "$v-SNAPSHOT"
  } else {
    val regex = """(\d+).(\d+).(\d+)-SNAPSHOT""".toRegex()
    val groups = regex.find(v)!!.groupValues
    val major = groups[1].toInt()
    val minor = groups[2].toInt()
    val patch = groups[3].toInt()

    "$major.$minor.${patch - 1}"
  }
}

tasks.withType<PluginUnderTestMetadata>().configureEach {
  pluginClasspath.from(funcTestRuntime)
}

val check = tasks.named("check")
check.configure {
  // Run the functional tests as part of `check`
  // Do NOT add smokeTest here. It would be too slow.
  dependsOn(functionalTest)
}

tasks.named("publishPlugins") {
  // Note that publishing non-snapshots requires a successful smokeTest
  if (!(project.version as String).endsWith("SNAPSHOT")) {
    dependsOn(check, smokeTest)
  }
}

tasks.withType<GroovyCompile>().configureEach {
  options.isIncremental = true
}
