import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    // Declared here with apply false so the publish plugin's shared build service
    // is loaded by one classloader for the whole build. Without this, applying it
    // to sibling modules makes publishAndReleaseToMavenCentral fail.
    alias(libs.plugins.vanniktech.publish).apply(false)
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
    // Applied (not deferred) at the root so `dokkaGenerate` aggregates every
    // library module into one API site at build/dokka/html.
    alias(libs.plugins.dokka)
}

dependencies {
    dokka(project(":imagekodec"))
    dokka(project(":imagekodec-compose"))
    dokka(project(":imagekodec-coil"))
}

dokka {
    moduleName.set("ImageKodec")
}

// Keep the build on JDK 21 without requiring that runtime in consuming apps.
configure(listOf(project(":imagekodec"), project(":imagekodec-compose"), project(":imagekodec-coil"))) {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            targets.withType<KotlinJvmTarget>().configureEach {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                    freeCompilerArgs.add("-Xjdk-release=11")
                }
                attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 11)
            }
        }
        tasks.withType<JavaCompile>().matching { it.name.startsWith("compileJvm") }.configureEach {
            sourceCompatibility = "11"
            targetCompatibility = "11"
            options.release.set(11)
        }
        // CI runs this on a Mac, where each Apple target compiles against the SDK of Xcode, as it
        // does for a release. No built-in task names the Apple targets and leaves the others out.
        val appleTargets = extensions.getByType<KotlinMultiplatformExtension>().targets
            .withType<KotlinNativeTarget>().matching { it.konanTarget.family.isAppleFamily }
        tasks.register("compileAppleTargets") {
            description = "Compiles the main source of every Apple target."
            group = "build"
            dependsOn(provider { appleTargets.map { it.compilations.getByName("main").compileTaskProvider } })
        }
        tasks.register<Test>("jvmJava11Test") {
            description = "Runs the JVM test suite on the minimum supported Java runtime."
            group = "verification"
            dependsOn("jvmTestClasses")
            val normalTest = tasks.named<Test>("jvmTest")
            testClassesDirs = normalTest.get().testClassesDirs
            classpath = normalTest.get().classpath
            javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(11))
            })
        }
    }
}

// Shared Kite theme. Sources live in ../_kite-docs; ./_kite-docs/sync.sh copies
// them here, so this repo still builds standalone from a fresh clone.
//
// This has to be applied to every project that has Dokka, not just the root:
// under aggregation the root only renders the "all modules" landing page, and
// each module renders its own pages from its own configuration. Configuring
// only the root leaves every actual API page on the stock theme.
allprojects {
    plugins.withId("org.jetbrains.dokka") {
        extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
            pluginsConfiguration.html {
                customStyleSheets.from(
                    rootProject.layout.projectDirectory.file("docs/api-theme/kite.css"),
                )
                templatesDir.set(
                    rootProject.layout.projectDirectory.dir("dokka-templates"),
                )
                footerMessage.set("Apache-2.0 · ImageKodec is part of the Kite family.")
            }

            // A module with a Module.md gets its description onto the aggregated
            // "all modules" landing page, which is otherwise a bare list of names.
            dokkaSourceSets.configureEach {
                val moduleDoc = layout.projectDirectory.file("Module.md")
                if (moduleDoc.asFile.exists()) {
                    includes.from(moduleDoc)
                }
            }

        }
    }
}
