plugins {
    `java-library`
}

version = "0.2.0"

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
