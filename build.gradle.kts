plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = "com.danny29404711"
version = "1.0.6-26.1.2"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    // Paper / Minecraft 26.1.2. The + uses the latest available Paper build for 26.1.2.
    // If you want a fully pinned build, replace + with the exact stable build number from papermc.io/downloads.
    paperweight.paperDevBundle("26.1.2.build.+")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
}
