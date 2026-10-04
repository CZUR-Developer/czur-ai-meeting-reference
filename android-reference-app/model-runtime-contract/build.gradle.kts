plugins {
    `java-library`
}

version = "0.3.0"

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
