plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.iammaplezeng.voicecall.keepalive"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.iammaplezeng.voicecall.keepalive"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/**"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
