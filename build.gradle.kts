import org.asciidoctor.gradle.jvm.AsciidoctorTask

/*
 * Copyright 2022 Intershop Communications AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {

    // project plugins
    groovy

    kotlin("jvm") version "1.9.21"

    // test coverage
    jacoco

    // ide plugin
    idea

    // plugin for documentation
    id("org.asciidoctor.jvm.convert") version "3.3.2"

    // documentation
    id("org.jetbrains.dokka") version "1.9.10"

    // plugin for publishing to Gradle Portal
    id("com.gradle.plugin-publish") version "1.2.1"

    id("com.dorongold.task-tree") version "2.1.1"
}

// release configuration
group = "com.intershop.gradle.jaxb"
description = "Gradle JAXB code generation plugin"
// apply gradle property 'projectVersion' to project.version, default to 'LOCAL'
val projectVersion : String? by project
version = projectVersion ?: "LOCAL"

val sonatypeUsername: String? by project
val sonatypePassword: String? by project

repositories {
    mavenCentral()
    mavenLocal()
}

val pluginUrl = "https://github.com/IntershopCommunicationsAG/${project.name}"
gradlePlugin {
    website = pluginUrl
    vcsUrl = pluginUrl

    plugins {
        create(project.name) {
            id = "com.intershop.gradle.jaxb"
            implementationClass = "com.intershop.gradle.jaxb.JaxbPlugin"
            displayName = project.name
            description = project.description
            tags = listOf("intershop", "jaxb", "build", "code", "generator")
        }
    }
}

java {
    withJavadocJar()
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(11)
    }
}

// set correct project status
if (project.version.toString().endsWith("-SNAPSHOT")) {
    status = "snapshot"
}

val buildDir = layout.buildDirectory.asFile.get()
tasks {
    withType<Test>().configureEach {
        testLogging {
            showStandardStreams = true
            showStackTraces = true
            events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        }

        this.javaLauncher.set( project.javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        })

        systemProperty("intershop.gradle.versions","8.5")

        if(project.hasProperty("repoURL")
                && project.hasProperty("repoUser")
                && project.hasProperty("repoPasswd")) {
            systemProperty("repo_url_config", project.property("repoURL").toString())
            systemProperty("repo_user_config", project.property("repoUser").toString())
            systemProperty("repo_passwd_config", project.property("repoPasswd").toString())
        }

        useJUnitPlatform()
    }

    register<Copy>("copyAsciiDoc") {
        includeEmptyDirs = false

        val outputDir = file("$buildDir/tmp/asciidoctorSrc")
        val inputFiles = fileTree(rootDir) {
            include("**/*.asciidoc")
            exclude("build/**")
        }

        inputs.files.plus( inputFiles )
        outputs.dir( outputDir )

        doFirst {
            outputDir.mkdir()
        }

        from(inputFiles)
        into(outputDir)
    }

    withType<AsciidoctorTask> {
        dependsOn("copyAsciiDoc")

        setSourceDir(file("$buildDir/tmp/asciidoctorSrc"))
        sources(delegateClosureOf<PatternSet> {
            include("README.asciidoc")
        })

        outputOptions {
            setBackends(listOf("html5", "docbook"))
        }

        options = mapOf( "doctype" to "article",
                "ruby"    to "erubis")
        attributes = mapOf(
                "latestRevision"        to  project.version,
                "toc"                   to "left",
                "toclevels"             to "2",
                "source-highlighter"    to "coderay",
                "icons"                 to "font",
                "setanchors"            to "true",
                "idprefix"              to "asciidoc",
                "idseparator"           to "-",
                "docinfo1"              to "true")
    }

    withType<JacocoReport> {
        reports {
            xml.required.set(true)
            html.required.set(true)

            html.outputLocation.set( File(buildDir, "jacocoHtml") )
        }

        val jacocoTestReport by tasks
        jacocoTestReport.dependsOn("test")
    }

    jar.configure {
        dependsOn(asciidoctor)
    }

    dokkaJavadoc.configure {
        outputDirectory.set(buildDir.resolve("dokka"))
    }

    afterEvaluate {
        getByName<Jar>("javadocJar") {
            dependsOn(dokkaJavadoc)
            from(dokkaJavadoc)
        }
    }
}

publishing {
    publications {
        create("intershopMvn", MavenPublication::class.java) {

            from(components["java"])

            artifact(File(buildDir, "docs/asciidoc/html5/README.html")) {
                classifier = "reference"
            }

            artifact(File(buildDir, "docs/asciidoc/docbook/README.xml")) {
                classifier = "docbook"
            }

            pom {
                name.set(project.name)
                description.set(project.description)
                url.set(pluginUrl)
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                organization {
                    name.set("Intershop Communications AG")
                    url.set("http://intershop.com")
                }
                developers {
                    developer {
                        id.set("m-raab")
                        name.set("M. Raab")
                        email.set("mraab@intershop.de")
                    }
                }
                scm {
                    connection.set("git@github.com:IntershopCommunicationsAG/${project.name}.git")
                    developerConnection.set("git@github.com:IntershopCommunicationsAG/${project.name}.git")
                    url.set(pluginUrl)
                }
            }
        }
    }
    repositories {
        maven {
            val isSnapshot = project.version.toString().endsWith("-SNAPSHOT")
            val subrepo = if (isSnapshot) "snapshots" else "releases"
            val nexusRoot = project.property("nexusRoot").toString()
            url = uri("$nexusRoot/repository/maven-$subrepo")

            credentials {
                username = project.property("mavenUser").toString()
                password = project.property("mavenPassword").toString()
            }
        }
    }
}

dependencies {
    implementation(gradleApi())
    implementation(gradleKotlinDsl())

    testImplementation("com.intershop.gradle.test:test-gradle-plugin:5.0.1")
    testImplementation(gradleTestKit())
}