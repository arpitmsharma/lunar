plugins {
    kotlin("jvm") version "1.9.22" apply false
    kotlin("plugin.serialization") version "1.9.22" apply false
    id("io.ktor.plugin") version "2.3.7" apply false
}

group = "com.lunar"
version = "1.0.0"

subprojects {
    repositories {
        mavenCentral()
    }
}
