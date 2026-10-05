plugins {
    `java-library`
}

description = "Diameter base protocol (RFC 6733) and Credit-Control (RFC 4006) codec"

dependencies {
    api("io.netty:netty-buffer")
    api("io.netty:netty-codec")
    api("io.netty:netty-handler")
    api("io.netty:netty-transport-classes-epoll")
    api("io.netty:netty-transport-classes-kqueue")
    api("org.slf4j:slf4j-api")

    // Native transports: epoll on Linux (Docker / ECS Fargate), kqueue on macOS. NIO is the fallback.
    for (classifier in listOf("linux-x86_64", "linux-aarch_64")) {
        runtimeOnly("io.netty:netty-transport-native-epoll::$classifier")
    }
    for (classifier in listOf("osx-x86_64", "osx-aarch_64")) {
        runtimeOnly("io.netty:netty-transport-native-kqueue::$classifier")
    }
}
