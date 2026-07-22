enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "KiteImage-KMP"

// :kiteimage — the pure-Kotlin image codec core (stdlib only). Format sniffing,
// decoders (BMP, PNG, GIF, …), the ARGB pixel-buffer type, and the
// malformed-input guards all live here and port cleanly to every KMP target.
include(":kiteimage")

// :kiteimage-compose — the optional Compose Multiplatform binding: the
// KiteImage() composable (auto-detects animated vs static input) and the
// KiteBitmap → ImageBitmap conversion. Keeps Compose off core consumers.
include(":kiteimage-compose")

// :kiteimage-coil — Coil interop: KiteImageDecoder plugs our codecs into Coil's
// pipeline (network, disk/memory cache, lifecycle stay Coil's), and
// KiteAsyncImage() renders the result with our frame loop so animation works on
// every target, not just Android.
include(":kiteimage-coil")

// :sample — desktop-first gallery app: every decodable format through the
// KiteImage() composable. ./gradlew :sample:run
include(":sample")
