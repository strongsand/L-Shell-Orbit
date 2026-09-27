// =========================================================================
// PROJETO: L-Shell Orbit (L-Shell Orbit Android)
// ARQUIVO: settings.gradle.kts
// VERSÃO: v1.2 (2026-09-20)
//
// CHANGELOG:
// - [v1.2 | 2026-09-20]: Sincronizado rootProject.name para 'L-Shell Orbit' conforme
//   definido no Android Studio pelo usuário.
// - [v1.0 | 2026-09-20]: Resolução de repositórios Google, MavenCentral e módulo :app.
// =========================================================================

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "L-Shell Orbit"
include(":app")

