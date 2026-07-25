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

/** Host-OS Skiko native runtime — needed by jvmTest so Coil's fallback SkiaImageDecoder works headlessly. */
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
 * :kiteimage-coil — the Coil interop. Division of labor: Coil owns the pipes
 * (network fetch, disk + memory cache, request lifecycle), KiteImage owns the
 * pixels (decoding + animation). Two pieces:
 *
 *  - KiteImageDecoder: a coil3 Decoder that claims the formats we decode and
 *    declines the rest, so Coil's platform decoders keep handling JPEG/SVG/…
 *  - KiteAsyncImage(): an AsyncImage-alike whose frame loop is ours — Coil's
 *    own compose painter has no animation driver off Android, so we render.
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

    applyDefaultHierarchyTemplate()

    android {
        namespace = "io.github.yuroyami.kiteimage.coil"
        // 37, not 36: Compose Multiplatform 1.12.x pulls androidx.compose
        // 1.12.0-beta02, whose AAR metadata requires consumers to compile
        // against API 37. minSdk is unaffected.
        compileSdk = 37
        minSdk = 21
    }

    jvm()

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KiteImageCoil"
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
            api(libs.coil.core)                          // ImageLoader/Decoder/Image in our API
            implementation(projects.kiteimageCompose)    // KiteBitmap.toImageBitmap for the frame loop
            implementation(libs.coil.singleton)          // SingletonImageLoader default param
            implementation(libs.coil.compose.core)       // LocalPlatformContext + Image.asPainter
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
