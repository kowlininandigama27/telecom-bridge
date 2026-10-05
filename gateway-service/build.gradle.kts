plugins {
    id("org.springframework.boot")
}

description = "REST-to-Diameter (Ro/Gy) charging gateway"

dependencies {
    implementation(project(":diameter-core"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    annotationProcessor(platform(rootProject.libs.spring.boot.bom))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(project(":diameter-simulator"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
}

tasks.bootJar {
    archiveFileName.set("gateway-service.jar")
}

tasks.jar {
    enabled = false
}
