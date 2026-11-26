import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.e8.navhud"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.e8.navhud"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    val keystorePath = localProps.getProperty("keystore.path")
        ?: System.getenv("NAVHUD_KEYSTORE_PATH")
    val keystoreFile = keystorePath?.takeIf { it.isNotBlank() }?.let(::file)?.takeIf { it.exists() }
    val keystorePass = localProps.getProperty("keystore.password")
        ?: System.getenv("NAVHUD_KEYSTORE_PASSWORD")
    val keystoreAlias = localProps.getProperty("keystore.alias")
        ?: System.getenv("NAVHUD_KEY_ALIAS")
    val keystoreKeyPass = localProps.getProperty("keystore.keyPassword")
        ?: System.getenv("NAVHUD_KEY_PASSWORD")
        ?: keystorePass

    signingConfigs {
        if (keystoreFile != null && !keystorePass.isNullOrBlank() && !keystoreAlias.isNullOrBlank()) {
            create("platform") {
                storeFile = keystoreFile
                storeType = localProps.getProperty("keystore.type") ?: "PKCS12"
                storePassword = keystorePass
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPass
            }
        }
    }

    val platformSigning = signingConfigs.findByName("platform")

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (platformSigning != null) {
                signingConfig = platformSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            if (platformSigning != null) {
                signingConfig = platformSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    compileOnly(project(":car-stubs"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
