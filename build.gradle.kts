tasks.named("lintKotlinMain") {
    enabled = false
}
allprojects {
    repositories {
        mavenCentral()
        google()
        maven(url = "https://jitpack.io")
    }
}
