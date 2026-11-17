package com.example

import com.example.sdide.engine.SdBytecodeCompileException
import com.example.sdide.engine.SdBytecodeCompiler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class SdBytecodeCompilerTest {
  private val sdSource = """
    imp*ioprt.ed
    sta(main){
      iid: sta(pri) => "ok" ed
      iid: ref* 0 ed
    }
  """.trimIndent()
  private val sdppSource = """
    ssta {
      imp*ioprt.$
      ids.pri: "ok" $
      ref*0 $:
    } ##
  """.trimIndent()

  @Test
  fun emitsStableSdAndSdppBytecodeHeaders() {
    val sd = SdBytecodeCompiler.compile("main.sd", mapOf("main.sd" to sdSource))
    val sdpp = SdBytecodeCompiler.compile("main.sdpp", mapOf("main.sdpp" to sdppSource))

    assertEquals("SDB1", String(sd, 0, 4, Charsets.US_ASCII))
    assertEquals("SPP1", String(sdpp, 0, 4, Charsets.US_ASCII))
    assertEquals(1, ByteBuffer.wrap(sd, 4, 2).short.toInt())
    assertTrue(ByteBuffer.wrap(sd, 6, 4).int > 0)
    assertArrayEquals(sd, SdBytecodeCompiler.compile("main.sd", mapOf("main.sd" to sdSource)))
  }

  @Test
  fun rejectsInvalidSourceInsteadOfExportingBytecode() {
    val error = assertThrows(SdBytecodeCompileException::class.java) {
      SdBytecodeCompiler.compile("main.sd", mapOf("main.sd" to "not valid Sd"))
    }

    assertTrue(error.diagnostics.isNotEmpty())
  }
}
