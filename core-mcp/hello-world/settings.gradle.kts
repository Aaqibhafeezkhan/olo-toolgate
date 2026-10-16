pluginManagement {
    repositories {
        maven { url = uri(providers.gradleProperty("toolgateSdkRepository").get()) }
        gradlePluginPortal()
    }
}
rootProject.name = "hello-world"
