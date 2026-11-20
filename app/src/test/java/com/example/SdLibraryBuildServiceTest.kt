package com.example.sdide.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SdLibraryBuildServiceTest {
  @Test
  fun validatesProjectModulesAndCreatesInstallablePackage() {
    val library = SdLibraryInfo(
      name = "helpers",
      version = "1.0.0",
      description = "Project helpers",
      entryFile = "helpers.sd",
      sourceFiles = linkedMapOf(
        "helpers.sd" to """
          iid: sta(fn) => rtn:
          sta double(int value) -> int {
              iid: ref* value * 2 ed
          }
          fn ed
        """.trimIndent(),
      ),
    )

    val result = SdLibraryPackage.decode(SdLibraryBuildService.build(library))

    assertEquals(library, result)
  }

  @Test
  fun rejectsInvalidModuleSourceAndBuiltInModuleCollisions() {
    val invalidSource = SdLibraryInfo(
      name = "helpers",
      version = "1.0.0",
      description = "",
      entryFile = "helpers.sd",
      sourceFiles = mapOf("helpers.sd" to "invalid source"),
    )
    val builtInCollision = invalidSource.copy(
      name = "other",
      entryFile = "math.sd",
      sourceFiles = mapOf(
        "math.sd" to """
          iid: sta(fn) => rtn:
          sta double(int value) -> int {
              iid: ref* value * 2 ed
          }
          fn ed
        """.trimIndent(),
      ),
    )

    assertThrows(IllegalArgumentException::class.java) {
      SdLibraryBuildService.build(invalidSource)
    }
    assertThrows(IllegalArgumentException::class.java) {
      SdLibraryBuildService.build(builtInCollision)
    }
  }
}
