plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.lab.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.lab.vpn"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // LocalSocksProxy's relay path logs via android.util.Log; on JVM unit
        // tests default-value stubbing keeps the live relay exercisable.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // tun2socks engine (gomobile AAR, see app/libs/README.md).
    implementation(files("libs/tun2socks.aar"))

    // JVM unit tests for the SOCKS5 handshake parsing (LocalSocksProxyTest).
    testImplementation("junit:junit:4.13.2")
}
