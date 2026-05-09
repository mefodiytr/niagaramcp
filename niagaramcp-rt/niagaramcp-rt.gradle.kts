/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */

import com.tridium.gradle.plugins.bajadoc.task.Bajadoc
import com.tridium.gradle.plugins.module.util.ModulePart.RuntimeProfile.*

plugins {
  id("com.tridium.niagara-module")
  id("com.tridium.niagara-signing")
  id("com.tridium.bajadoc")
  id("com.tridium.niagara-jacoco")
  id("com.tridium.niagara-annotation-processors")
  id("com.tridium.convention.niagara-home-repositories")
}

// Override vendor inherited from the root build (which sets defaultVendor("bc"))
// so the assembled module.xml ships with vendor="niagaramcp".
moduleManifest {
  moduleName.set("niagaramcp")
  runtimeProfile.set(rt)
  vendor.set("niagaramcp")
}

dependencies {
  nre(":nre")
  api(":baja")
  api(":control-rt")
  api(":web-rt")
  api(":history-rt")

  // javax.servlet API (lives in $niagara_home/bin/ext as a flat-file Maven repo).
  compileOnly("javax.servlet:javax.servlet-api:3.1.0")

  moduleTestImplementation(":test-wb")
}

// Make WEB-INF/web.xml and sample-knowledge.yaml end up at the root of the jar.
sourceSets {
  main {
    resources {
      srcDir("src")
      include("WEB-INF/**")
      include("sample-knowledge.yaml")
    }
  }
}

tasks.named<Bajadoc>("bajadoc") {
  includePackage("com.niagaramcp.server")
  includePackage("com.niagaramcp.server.tools")
  includePackage("com.niagaramcp.json")
}
