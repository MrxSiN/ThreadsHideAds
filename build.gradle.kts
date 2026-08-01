plugins {
    id("com.android.application")
}

val appVersion = "1.1.2"

val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val keystoreAlias = System.getenv("ANDROID_KEYSTORE_ALIAS")
val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val keyPassword = System.getenv("ANDROID_KEY_PASSWORD")

android {
    namespace = "my.github.MrxSiN.threadshideads"
    compileSdk = 36

    val releaseSigningConfig = if (
        !keystorePath.isNullOrBlank() &&
        !keystoreAlias.isNullOrBlank() &&
        !keystorePassword.isNullOrBlank() &&
        !keyPassword.isNullOrBlank() &&
        file(keystorePath).isFile
    ) {
        signingConfigs.create("release") {
            storeFile = file(requireNotNull(keystorePath))
            storePassword = requireNotNull(keystorePassword)
            keyAlias = requireNotNull(keystoreAlias)
            this.keyPassword = requireNotNull(keyPassword)
        }
    } else {
        null
    }

    defaultConfig {
        applicationId = "my.github.MrxSiN.threadshideads"
        minSdk = 23
        targetSdk = 36
        versionCode = 3
        versionName = appVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            releaseSigningConfig?.let { signingConfig = it }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("ThreadsHideAds-v$appVersion.apk")
        }
    }
}

dependencies {
    implementation("org.luckypray:dexkit:2.2.0")
    compileOnly("de.robv.android.xposed:api:82")
}
