// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

plugins {
  id("java")
  id("org.jetbrains.intellij") version "1.17.4"


}

group = "com.mapledoum"
version = "2.0.5"

repositories {
  mavenCentral()
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
}

// See https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {

  version.set("2023.3.7")
  plugins.set(listOf("com.intellij.java"))

}

tasks {
  buildSearchableOptions {
    enabled = false
  }

  patchPluginXml {
    version.set("${project.version}")
    sinceBuild.set("233")
    untilBuild.set("242.*")
  }
  signPlugin {
    certificateChain.set(providers.fileContents(layout.projectDirectory.file("sign-code-plugin/chain.crt")).asText)
    privateKey.set(providers.fileContents(layout.projectDirectory.file("sign-code-plugin/private.pem")).asText)
    password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
  }
  publishPlugin {
    token.set(providers.environmentVariable("PUBLISH_TOKEN"))
  }

}

