// =========================================================================
// PROJETO: L-Shell Orbit (L-Shell Orbit Android)
// ARQUIVO: app/build.gradle.kts
// VERSÃO: v1.4 (2026-09-20)
//
// CHANGELOG:
// - [v1.4 | 2026-09-20]: Adicionado explicitamente os diretórios de código gerado
//   pelo protoc aos sourceSets do Kotlin/Java. Isso força o compilador Kotlin a
//   encontrar o pacote 'io.github.strongsand.lshell.proto' imediatamente sem falhas
//   de indexação no Android Studio / Gradle 8+.
// - [v1.3 | 2026-09-20]: Ajuste no plugin grpckt.
// =========================================================================

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.strongsand.lshell"
    // Public framework Live Update (36) and MetricStyle (37) APIs; target remains unchanged.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.strongsand.lshell"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.2.0-beta.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(project(":dishyCore"))
    implementation(project(":beaconProtocol"))
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    testImplementation("junit:junit:4.13.2")
    // Real JSON parsing on the JVM; Android's org.json stubs cannot test catalog assets.
    testImplementation("org.json:json:20240303")
    // AndroidX & Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose & Material 3 Expressive
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Jetpack Glance (Widgets da Tela Inicial)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    constraints {
        implementation("androidx.datastore:datastore-preferences:${libs.versions.datastore.get()}") {
            because("Preserve the existing transitive Glance version without a direct app dependency")
        }
    }

    // Background & Persistência Local
    implementation(libs.androidx.work.runtime.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // SGP4/SDP4 orbital propagation for the AR satellite layer (MIT).
    implementation("uk.me.g4dpz:predict4java:1.2.2") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }

    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // gRPC & Protobuf
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.kotlin.lite)
}

