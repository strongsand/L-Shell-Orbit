// =========================================================================
// PROJETO: L-Shell Orbit Android
// ARQUIVO: build.gradle.kts (Projeto Raiz)
// VERSÃO: v1.0 (2026-09-20)
//
// CHANGELOG:
// - [v1.0 | 2026-09-20]: Configuração raiz do projeto com plugins Gradle modernos.
//   Adicionado: Android Application, Kotlin Android, Kotlin Compose Compiler,
//   Protobuf Gradle Plugin e repositórios MavenCentral/Google.
// - Motivo: Ponto de entrada de build para o ecossistema Android nativo.
// =========================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.protobuf) apply false
}

