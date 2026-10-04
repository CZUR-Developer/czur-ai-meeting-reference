plugins {
    `java-library`
}

version = "0.4.0"

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
