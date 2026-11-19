package com.example

import com.example.sdide.engine.SdBackendGenerationException
import com.example.sdide.engine.SdIdeEngine
import com.example.sdide.engine.SdSourceOptimizer
import com.example.sdide.engine.SdTargetCodeGenerator
import com.example.sdide.engine.SdTargetLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdCodeGeneratorTest {
  private val source = """
    imp*ioprt.ed
    sta(main){
        iid: sta(int total) => edt: sta(total = 0) ed
        iid: sta(for) => cdn,chck:
        dbl sta(int i = 0 ' i < 3 ' i++){
            iid: sta(total = total + i) ed
        }
        dbl ed
        iid: sta(pri) => "Total = /d{total}" ed
        iid: ref* 0 ed
    }
  """.trimIndent()

  @Test
  fun generatesPortableBackendsForSupportedSdSubset() {
    val compilation = SdIdeEngine.compileAndCheck("main.sd", mapOf("main.sd" to source), includeCSource = false)
    assertFalse(compilation.diagnostics.joinToString { it.format() }, compilation.hasErrors)
    val unit = requireNotNull(compilation.unit)

    val javascript = SdTargetCodeGenerator.generate(unit, SdTargetLanguage.JAVASCRIPT)
    val python = SdTargetCodeGenerator.generate(unit, SdTargetLanguage.PYTHON)
    val kotlin = SdTargetCodeGenerator.generate(unit, SdTargetLanguage.KOTLIN)
    val c = SdTargetCodeGenerator.generate(unit, SdTargetLanguage.C99)

    assertTrue(javascript, javascript.contains("function sdDiv"))
    assertTrue(javascript, javascript.contains("for (let i = 0; (i < 3); i++)"))
    assertTrue(python, python.contains("def main():"))
    assertTrue(python, python.contains("while (i < 3):"))
    assertTrue(kotlin, kotlin.contains("fun main()"))
    assertTrue(kotlin, kotlin.contains("while ((i < 3L))"))
    assertTrue(c, c.contains("#include <stdint.h>"))
  }

  @Test
  fun unsupportedBackendFeaturesFailExplicitly() {
    val pointerSource = """
      ssta {
          imp*mem.$
          iptedt.ptr p = sta.alloc(1) $
          ref*0 $:
      } ##
    """.trimIndent()
    val compilation = SdIdeEngine.compileAndCheck(
      "pointer.sdpp",
      mapOf("pointer.sdpp" to pointerSource),
      includeCSource = false,
    )
    assertFalse(compilation.diagnostics.joinToString { it.format() }, compilation.hasErrors)

    val error = try {
      SdTargetCodeGenerator.generate(requireNotNull(compilation.unit), SdTargetLanguage.PYTHON)
      null
    } catch (e: SdBackendGenerationException) {
      e
    }
    assertTrue(error?.message.orEmpty().contains("chưa hỗ trợ"))
  }

  @Test
  fun optimizerOnlyRemovesIdentityArithmeticFromDeclaredIntegers() {
    val original = """
      iid: sta(int count) => edt: sta(count = 0) ed
      iid: sta(count = count + 0) ed
      iid: sta(int ratio) => edt: sta(ratio = 1) ed
      iid: sta(ratio = ratio * 1) ed
      iid: sta(float scale) => edt: sta(scale = 1) ed
      iid: sta(scale = scale * 1) ed
      iid: sta(unknown = unknown + 0) ed
    """.trimIndent()

    val result = SdSourceOptimizer.optimize(original)
    assertEquals(2, result.changes.size)
    assertTrue(result.changed)
    assertEquals(original.lines().size, result.optimizedSource.lines().size)
    assertEquals("", result.optimizedSource.lines()[1])
    assertEquals("", result.optimizedSource.lines()[3])
    assertEquals(original.lines()[5], result.optimizedSource.lines()[5])
    assertEquals(original.lines()[6], result.optimizedSource.lines()[6])
  }
}
