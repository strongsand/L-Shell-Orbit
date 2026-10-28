plugins { `java-library`; alias(libs.plugins.kotlin.jvm) }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies { testImplementation("junit:junit:4.13.2") }
