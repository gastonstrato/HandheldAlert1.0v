plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// En GitHub Actions viene el número de build (siempre creciente) y la clave
// de firma: el workflow la decodifica del secreto SIGNING_KEYSTORE_BASE64 a
// un archivo temporal (SIGNING_KEYSTORE_PATH) y la contraseña viene del
// secreto SIGNING_PASSWORD. La clave nunca está en el repo. Sin ellos
// (compilación local) se usa versionCode fijo y la clave debug de siempre.
val ciBuildNumber: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val signingKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotBlank() }
val useFixedSigning = signingKeystorePath != null && signingPassword != null

android {
    namespace = "com.gaston.handheldalert"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gaston.handheldalert"
        minSdk = 26
        targetSdk = 34
        // El actualizador compara este número con el "-buildN" de la última
        // release, que es el mismo número de build de GitHub.
        versionCode = ciBuildNumber ?: 1
        versionName = "1.5.0"
    }

    signingConfigs {
        // Clave fija: Android solo deja instalar una versión encima de otra
        // si están firmadas con la misma clave. La clave debug que genera
        // cada runner de GitHub es distinta en cada build, por eso antes
        // había que desinstalar para actualizar.
        if (useFixedSigning) {
            create("fixed") {
                storeFile = file(signingKeystorePath!!)
                storeType = "PKCS12"
                storePassword = signingPassword
                keyAlias = "handheldalert"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        debug {
            if (useFixedSigning) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")
}
