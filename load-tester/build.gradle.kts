plugins {
    application
}

description = "Constant-rate (open model) HTTP load generator for POST /api/v1/charge"

dependencies {
    implementation("io.projectreactor.netty:reactor-netty-http")
    // Reactor Netty picks native kqueue/epoll automatically when present.
    for (classifier in listOf("linux-x86_64", "linux-aarch_64")) {
        runtimeOnly("io.netty:netty-transport-native-epoll::$classifier")
    }
    for (classifier in listOf("osx-x86_64", "osx-aarch_64")) {
        runtimeOnly("io.netty:netty-transport-native-kqueue::$classifier")
    }
    runtimeOnly("ch.qos.logback:logback-classic")
}

application {
    mainClass.set("com.telecombridge.loadtest.LoadTest")
    applicationDefaultJvmArgs = listOf("-Xms256m", "-Xmx512m")
}
