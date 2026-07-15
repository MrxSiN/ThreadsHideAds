plugins {
    id("com.android.application")
}

val appVersion = "1.1.0"

android {
    namespace = "my.github.MrxSiN.threadshideads"
    compileSdk = 36

    defaultConfig {
        applicationId = "my.github.MrxSiN.threadshideads"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = appVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
