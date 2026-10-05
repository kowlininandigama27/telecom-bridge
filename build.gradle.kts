// Shared conventions for every module. All third-party versions (Netty, SLF4J,
// Logback, Jackson, JUnit) are aligned through the Spring Boot BOM so the
// simulator and codec run on exactly the same Netty as the gateway.
plugins {
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "com.telecombridge"
    version = "1.0.0"
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    dependencies {
        "implementation"(platform(rootProject.libs.spring.boot.bom))
        "testImplementation"(platform(rootProject.libs.spring.boot.bom))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-serial,-processing"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Fail fast on any ByteBuf leak in codec / client / simulator tests.
        systemProperty("io.netty.leakDetection.level", "paranoid")
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
