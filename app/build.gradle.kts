plugins {
    id("com.android.application")
}

android {
    namespace = "my.github.MrxSiN.threadshideads"
    compileSdk = 36

    defaultConfig {
        applicationId = "my.github.MrxSiN.threadshideads"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

dependencies {
    implementation("org.luckypray:dexkit:2.2.0")
    compileOnly("de.robv.android.xposed:api:82")
}
