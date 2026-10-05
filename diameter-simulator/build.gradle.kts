plugins {
    application
}

description = "Standalone Diameter Credit-Control (OCS) simulator listening on TCP 3868"

dependencies {
    implementation(project(":diameter-core"))
    implementation("io.netty:netty-transport")
    runtimeOnly("ch.qos.logback:logback-classic")
}

application {
    mainClass.set("com.telecombridge.simulator.DiameterSimulator")
    applicationDefaultJvmArgs = listOf("-XX:+UseZGC", "-Xms128m", "-Xmx256m")
}
