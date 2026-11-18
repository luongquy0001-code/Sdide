package com.example

import com.example.sdide.engine.SdIdeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdVmExecutionTest {
  @Test
  fun runsTenThousandIterationLoopWithoutGeneratingCSource() {
    val source = """
      imp*ioprt.ed
      sta(main){
          iid: sta(int total) => edt: sta(total = 0) ed
          iid: sta(for) => cdn,chck:
          dbl sta(int i = 0 ' i <= 10000 ' i++){
              iid: sta(total = total + 1) ed
          }
          dbl ed
          iid: sta(pri) => "/d{total}" ed
          iid: ref* 0 ed
      }
    """.trimIndent()

    val result = SdIdeEngine.runProgram(
      mainFileName = "main.sd",
      projectFiles = mapOf("main.sd" to source),
      includeCSource = false,
    )

    assertTrue(result.diagnostics.joinToString { it.format() }, result.success)
    assertEquals("10001\n", result.output)
    assertNull(result.cSourceCode)
    assertFalse(result.diagnostics.any { it.severity.name == "ERROR" })
  }

  @Test
  fun fallbackInterpreterHandlesTenThousandIterationLoop() {
    val source = """
      imp*ioprt.ed
      sta(main){
          iid: sta(float marker) => edt: sta(marker = 0.5) ed
          iid: sta(int total) => edt: sta(total = 0) ed
          iid: sta(for) => cdn,chck:
          dbl sta(int i = 0 ' i <= 10000 ' i++){
              iid: sta(total = total + 1) ed
          }
          dbl ed
          iid: sta(pri) => "/d{total}" ed
          iid: ref* 0 ed
      }
    """.trimIndent()

    val result = SdIdeEngine.runProgram(
      mainFileName = "main.sd",
      projectFiles = mapOf("main.sd" to source),
      includeCSource = false,
    )

    assertTrue(result.diagnostics.joinToString { it.format() }, result.success)
    assertEquals("10001\n", result.output)
    assertNull(result.cSourceCode)
  }
}
