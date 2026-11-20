package com.example.sdide.desktop

import com.example.sdide.data.SdLibraryInfo
import com.example.sdide.data.SdLibraryPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class DesktopLibraryRepositoryTest {
  @Test
  fun installsAndLoadsSdLibrarySources() {
    val directory = Files.createTempDirectory("sdide-desktop-libraries").toFile()
    try {
      val library = sampleLibrary()
      val repository = DesktopLibraryRepository(directory)

      repository.install(SdLibraryPackage.encode(library))

      assertEquals(listOf(library), repository.installedLibraries())
      assertEquals(library.sourceFiles, repository.sourceFiles())
      repository.uninstall(library.name)
      assertEquals(emptyList<SdLibraryInfo>(), repository.installedLibraries())
    } finally {
      directory.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
      Files.deleteIfExists(directory.toPath())
    }
  }

  @Test
  fun rejectsBuiltInModuleCollisions() {
    val directory = Files.createTempDirectory("sdide-desktop-libraries").toFile()
    try {
      val library = sampleLibrary().copy(
        name = "myio",
        entryFile = "ioprt.sd",
        sourceFiles = mapOf("ioprt.sd" to "module"),
      )

      assertThrows(IllegalArgumentException::class.java) {
        DesktopLibraryRepository(directory).install(SdLibraryPackage.encode(library))
      }
    } finally {
      directory.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
      Files.deleteIfExists(directory.toPath())
    }
  }

  private fun sampleLibrary() = SdLibraryInfo(
    name = "collections",
    version = "1.0.0",
    description = "Collection helpers",
    entryFile = "collections.sd",
    sourceFiles = mapOf("collections.sd" to "module"),
  )
}
