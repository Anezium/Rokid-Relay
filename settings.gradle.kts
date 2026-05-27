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
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
    }
}

rootProject.name = "Rokid Relay"
include(":phone")
include(":glasses")

includeBuild("../CxrGlobal") {
    dependencySubstitution {
        substitute(module("com.example.cxrglobal:lib")).using(project(":lib"))
    }
}
