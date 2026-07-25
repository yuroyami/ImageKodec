import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.plugin)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/** Host-OS Skiko native runtime — needed by jvmTest to rasterize an ImageBitmap headlessly. */
fun currentOsSkikoRuntime(): Provider<MinimalExternalModuleDependency> {
    val os = OperatingSystem.current()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.isMacOsX && arch.contains("aarch64") -> libs.skiko.awt.runtime.macos.arm64
        os.isMacOsX -> libs.skiko.awt.runtime.macos.x64
        os.isLinux && arch.contains("aarch64") -> libs.skiko.awt.runtime.linux.arm64
        os.isLinux -> libs.skiko.awt.runtime.linux.x64
        os.isWindows && arch.contains("aarch64") -> libs.skiko.awt.runtime.windows.arm64
        os.isWindows -> libs.skiko.awt.runtime.windows.x64
        else -> error("Unsupported OS for Skiko: $os $arch")
    }
}

/*
 * :kiteimage-compose is the optional Compose Multiplatform binding for KiteImage.
 *
 * The :kiteimage core stays pure-Kotlin-stdlib so CLI / server consumers don't
 * pull in any UI dependencies. Anything Compose-specific lives here: the
 * KiteImage() composable and the KiteBitmap → ImageBitmap conversion.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    // Public API tracking. `updateLegacyAbi` refreshes api/*.api, `checkLegacyAbi`
    // fails the build when the committed dump and the code disagree: with
    // explicitApi() on, that turns an accidental signature change into a review
    // conversation instead of a broken release.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    // Custom dependsOn edges (skikoMain below) suppress the default hierarchy
    // template silently — force it so iosMain/macosMain still exist and leaves
    // stay wired to them.
    applyDefaultHierarchyTemplate()

    android {
        namespace = "io.github.yuroyami.kiteimage.compose"
        // 37, not 36: Compose Multiplatform 1.12.x pulls androidx.compose
        // 1.12.0-beta02, whose AAR metadata requires consumers to compile
        // against API 37. minSdk is unaffected.
        compileSdk = 37
        minSdk = 21
    }

    jvm()

    // Every target Compose Multiplatform itself ships for.
    // (No iosX64 / macosX64: CMP 1.11 publishes no Intel-Apple variants.)
    listOf(
        iosSimulatorArm64(),
        iosArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KiteImageCompose"
            isStatic = false
        }
    }
    macosArm64()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js(IR) {
        browser()
        binaries.library()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.kiteimage)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation(currentOsSkikoRuntime())
        }

        // One Skiko conversion implementation shared by every non-Android target.
        val skikoMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(skikoMain)
        iosMain.get().dependsOn(skikoMain)
        macosMain.get().dependsOn(skikoMain)
        jsMain.get().dependsOn(skikoMain)
        wasmJsMain.get().dependsOn(skikoMain)
    }
}
