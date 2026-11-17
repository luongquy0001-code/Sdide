package com.example

import com.example.sdide.data.SdLibraryPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SdLibraryPackageTest {
  @Test
  fun readsVersionedLibraryAndItsSdSources() {
    val packageBytes = packageZip(
      "manifest.properties" to """
        format=1
        name=collections
        version=1.2.0
        description=Collection helpers
        entry=collections.sd
      """.trimIndent(),
      "src/collections.sd" to "sta(main){ iid: ref* 0 ed }",
      "src/helpers.sdpp" to "ssta { ref*0 $: } ##",
    )

    val library = SdLibraryPackage.decode(packageBytes)

    assertEquals("collections", library.name)
    assertEquals("collections.sd", library.entryFile)
    assertEquals(setOf("collections.sd", "helpers.sdpp"), library.sourceFiles.keys)
  }

  @Test
  fun rejectsTraversalAndEntryOutsidePackageSources() {
    val packageBytes = packageZip(
      "manifest.properties" to """
        format=1
        name=unsafe
        version=1
        entry=unsafe.sd
      """.trimIndent(),
      "src/unsafe.sd" to "sta(main){ iid: ref* 0 ed }",
      "../escape.sd" to "not allowed",
    )

    val error = assertThrows(IllegalArgumentException::class.java) {
      SdLibraryPackage.decode(packageBytes)
    }

    assertTrue(error.message.orEmpty().contains("Đường dẫn"))
  }

  @Test
  fun rejectsPackagesLargerThanLimit() {
    val error = assertThrows(IllegalArgumentException::class.java) {
      SdLibraryPackage.decode(ByteArray(SdLibraryPackage.MAX_PACKAGE_BYTES + 1))
    }

    assertTrue(error.message.orEmpty().contains("8 MiB"))
  }

  private fun packageZip(vararg files: Pair<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      for ((name, content) in files) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }
    }
    return bytes.toByteArray()
  }
}
