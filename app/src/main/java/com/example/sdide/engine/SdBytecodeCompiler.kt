package com.example.sdide.engine

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class SdBytecodeCompileException(
  val diagnostics: List<SdDiagnostic>,
) : IllegalArgumentException(
  diagnostics.joinToString("\n") { it.format() }.ifBlank { "Không thể biên dịch tệp Sd." },
)

object SdBytecodeCompiler {
  private const val FORMAT_VERSION = 1

  fun compile(mainFileName: String, projectFiles: Map<String, String>): ByteArray {
    val compilation = SdIdeEngine.compileAndCheck(mainFileName, projectFiles, includeCSource = false)
    if (compilation.unit == null || compilation.hasErrors) {
      throw SdBytecodeCompileException(compilation.diagnostics)
    }

    val source = projectFiles[mainFileName]
      ?: throw SdBytecodeCompileException(compilation.diagnostics)
    val tokens = SdLexer(source, mainFileName).tokenize().tokens
    val isSdpp = mainFileName.endsWith(".sdpp", ignoreCase = true)
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { data ->
      val magic = if (isSdpp) "SPP1" else "SDB1"
      data.write(magic.toByteArray(Charsets.US_ASCII))
      data.writeShort(FORMAT_VERSION)
      data.writeInt(tokens.size)
      for (token in tokens) {
        val lexeme = token.lexeme.toByteArray(Charsets.UTF_8)
        data.writeByte(token.type.ordinal)
        data.writeByte(if (token.precededByNewline) 1 else 0)
        data.writeInt(token.line)
        data.writeInt(token.col)
        data.writeInt(lexeme.size)
        data.write(lexeme)
      }
    }
    return output.toByteArray()
  }
}
