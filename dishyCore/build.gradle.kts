import com.google.protobuf.gradle.id

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
sourceSets.main {
    java.srcDirs("build/generated/source/proto/main/java", "build/generated/source/proto/main/grpc",
        "build/generated/source/proto/main/grpckt")
}
tasks.named("compileKotlin") { dependsOn("generateProto") }

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobufVersion.get()}" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpcVersion.get()}" }
        id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.grpcKotlinVersion.get()}:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { named("java") { option("lite") } }
            task.plugins { id("grpc") { option("lite") }; id("grpckt") }
        }
    }
}

dependencies {
    api(libs.grpc.okhttp)
    api(libs.grpc.protobuf.lite)
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    api(libs.protobuf.kotlin.lite)
    api(libs.kotlinx.coroutines.core)
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
}
