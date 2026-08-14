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
        // 华为 Scan Kit 只在华为自家仓库分发（不上 Maven Central）
        maven { url = uri("https://developer.huawei.com/repo/") }
    }
}

rootProject.name = "ScanDemo"
include(":app")
