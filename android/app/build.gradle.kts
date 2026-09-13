import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firma: keystore.properties en android/ (local, fuera de git) o variables
// de entorno KEYSTORE_PATH/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD (CI).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)

// Servidor DSU nativo (crate `dsu-android` → libpepomote_dsu.so): el mismo
// código Rust que sirve DSU en el PC, aquí dentro de la app para que Dolphin
// —en OTRO dispositivo— lea de este móvil. Se compila con cargo-ndk:
//   cargo install cargo-ndk && rustup target add aarch64-linux-android …
// Sin la toolchain de Rust el resto de la app sigue compilando: la tarea
// avisa y se salta, y solo falla al ejecutar el modo «Dolphin en Android».
// ABIs de Android (cargo-ndk traduce cada una a su triple de Rust).
// arm64-v8a es el móvil de hoy; armeabi-v7a, los viejos; x86_64, el emulador.
val rustAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val isWindows = System.getProperty("os.name").startsWith("Windows")
val cargoExe = if (isWindows) "cargo.exe" else "cargo"

fun cargoOnPath(): Boolean =
    System.getenv("PATH")?.split(File.pathSeparator)?.any { File(it, cargoExe).exists() } ?: false

val cargoNdk = tasks.register<Exec>("cargoNdkBuild") {
    group = "build"
    description = "Compila el servidor DSU nativo en app/src/main/jniLibs"
    workingDir = rootProject.file("../dsu-android")
    commandLine(
        buildList {
            add(cargoExe)
            add("ndk")
            rustAbis.forEach { addAll(listOf("-t", it)) }
            // minSdk 26: la API contra la que se enlaza el .so
            addAll(listOf("--platform", "26"))
            addAll(listOf("-o", file("src/main/jniLibs").absolutePath))
            addAll(listOf("build", "--release"))
        }
    )

    // Sin la toolchain de Rust el resto de la app sigue compilando: se avisa
    // y se salta. Solo falla al ENTRAR en el modo «Dolphin en Android».
    onlyIf {
        val found = cargoOnPath()
        if (!found) logger.warn("cargo no está en el PATH: sin servidor DSU nativo (ver docs/DOLPHIN-ANDROID.md)")
        found
    }
}

tasks.named("preBuild") { dependsOn(cargoNdk) }

android {
    namespace = "dev.pepotech.pepomote"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.pepotech.pepomote"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.5.0"
    }

    val releaseSigning = signingValue("storeFile", "KEYSTORE_PATH")?.let { store ->
        signingConfigs.create("release") {
            storeFile = rootProject.file(store)
            storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = releaseSigning
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
    }
    sourceSets {
        getByName("test") {
            resources.srcDir("../../protocol/vectors")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
