pluginManagement {
    repositories {
        google()
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

rootProject.name = "CoverDisplay"

include(":shared")   // data models + Data Layer protocol used by both apps
include(":mobile")   // phone companion: reads what's playing, feeds the watch
include(":wear")     // Galaxy Watch app: the Cover Flow UI
