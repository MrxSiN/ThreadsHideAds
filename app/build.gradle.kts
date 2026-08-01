plugins {
    id("com.android.application")
}

val appVersion = "1.1.2"

val envKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val envKeystoreAlias = System.getenv("ANDROID_KEYSTORE_ALIAS")
val envKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val envKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

android {
    namespace = "my.github.MrxSiN.threadshideads"
    compileSdk = 36

    val releaseSigningConfig = if (
        !envKeystorePath.isNullOrBlank() &&
        !envKeystoreAlias.isNullOrBlank() &&
        !envKeystorePassword.isNullOrBlank() &&
        !envKeyPassword.isNullOrBlank() &&
        file(envKeystorePath).isFile
    ) {
        signingConfigs.create("release") {
            storeFile = file(envKeystorePath)
            storePassword = envKeystorePassword
            keyAlias = envKeystoreAlias
            keyPassword = envKeyPassword
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
