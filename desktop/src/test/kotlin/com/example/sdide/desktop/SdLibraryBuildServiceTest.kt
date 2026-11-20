package com.example.sdide.desktop

import com.example.sdide.data.SdLibraryInfo
import com.example.sdide.data.SdLibraryPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SdLibraryBuildServiceTest {
  @Test
  fun validatesImportedModulesBeforeWritingPackage() {
    val library = SdLibraryInfo(
      name = "mathplus",
      version = "1.0.0",
      description = "Math helpers",
      entryFile = "mathplus.sd",
      sourceFiles = mapOf(
        "mathplus.sd" to """
          iid: sta(fn) => rtn:
          sta square(int value) -> int{
              iid: ref* value * value ed
          }
          fn ed
        """.trimIndent(),
      ),
    )

    val built = SdLibraryPackage.decode(SdLibraryBuildService.build(library))

    assertEquals(library, built)
  }

  @Test
  fun rejectsInvalidModuleSource() {
    val library = SdLibraryInfo(
      name = "broken",
      version = "1.0.0",
      description = "",
      entryFile = "broken.sd",
      sourceFiles = mapOf("broken.sd" to "not a valid Sd module"),
    )

    assertThrows(IllegalArgumentException::class.java) {
      SdLibraryBuildService.build(library)
    }
  }
}
