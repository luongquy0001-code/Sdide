plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

version = "1.0.0"

dependencies {
  implementation(kotlin("stdlib"))
  testImplementation(libs.junit)
}

kotlin {
  jvmToolchain(17)
  sourceSets.main {
    kotlin.srcDir("../app/src/main/java/com/example/sdide/engine")
    kotlin.srcDir("../app/src/main/java/com/example/sdide/data")
    kotlin.include("**/*.kt")
    kotlin.exclude("ProjectRepository.kt")
  }
  sourceSets.test {
    kotlin.srcDir("../app/src/test/java")
    kotlin.include(
      "**/DesktopEngineTest.kt",
      "**/SdEngineComprehensiveTest.kt",
      "**/SdBytecodeCompilerTest.kt",
      "**/SdLibraryPackageTest.kt",
      "**/SdLibraryBuildServiceTest.kt",
      "**/DesktopLibraryRepositoryTest.kt",
    )
  }
}

application {
  mainClass.set("com.example.sdide.desktop.DesktopMainKt")
}

tasks.jar {
  manifest {
    attributes["Main-Class"] = application.mainClass.get()
  }
}

tasks.register<Exec>("packageWindows") {
  dependsOn(tasks.named("installDist"))

  val appDirectory = layout.buildDirectory.dir("install/desktop/lib").get().asFile
  val outputDirectory = layout.buildDirectory.dir("installer").get().asFile
  val applicationJar = tasks.named<Jar>("jar").get().archiveFileName.get()

  doFirst {
    outputDirectory.mkdirs()
  }

  executable = "jpackage"
  args(
    "--type", "exe",
    "--name", "Sd-IDE",
    "--app-version", project.version.toString(),
    "--vendor", "Sd IDE",
    "--input", appDirectory.absolutePath,
    "--main-jar", applicationJar,
    "--main-class", application.mainClass.get(),
    "--dest", outputDirectory.absolutePath,
    "--win-per-user-install",
    "--win-menu",
    "--win-shortcut",
    "--win-dir-chooser",
    "--win-menu-group", "Sd IDE",
  )
}
