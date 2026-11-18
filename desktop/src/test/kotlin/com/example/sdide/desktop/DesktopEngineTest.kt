package com.example.sdide.desktop

import com.example.sdide.data.CurriculumExamples
import com.example.sdide.engine.DiagnosticSeverity
import com.example.sdide.engine.SdIdeEngine
import com.example.sdide.engine.SdVmCompiler
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class DesktopEngineTest {
  @Test
  fun runsSharedSdProjectWithTheDesktopEngine() {
    val sample = CurriculumExamples.samples.first()
    val result = SdIdeEngine.runProgram(sample.entryFile, sample.files)

    assertTrue(result.success)
    assertTrue(result.output.contains("Xin chao"))
  }

  @Test
  fun skipsCTranspilationWhenTheCallerOnlyNeedsLanguageDiagnostics() {
    val sample = CurriculumExamples.samples.first()
    val withCSource = SdIdeEngine.compileAndCheck(sample.entryFile, sample.files)
    val withoutCSource = SdIdeEngine.compileAndCheck(
      sample.entryFile,
      sample.files,
      includeCSource = false,
    )

    assertNotNull(withCSource.cSourceCode)
    assertNull(withoutCSource.cSourceCode)
    assertEquals(withCSource.diagnostics, withoutCSource.diagnostics)
    assertEquals(withCSource.unit, withoutCSource.unit)
  }

  @Test
  fun normalExecutionDoesNotGenerateUnusedCSource() {
    val sample = CurriculumExamples.samples.first()

    val result = SdIdeEngine.runProgram(
      sample.entryFile,
      sample.files,
      includeCSource = false,
    )

    assertTrue(result.success)
    assertTrue(result.output.contains("Xin chao"))
    assertNull(result.cSourceCode)
  }

  @Test
  fun integerForAndWhileConditionsPreserveProgramResults() {
    val source = """
      imp*ioprt.ed
      sta(main){
          iid: sta(int total) => edt: sta(total = 0) ed
          iid: sta(for) => cdn,chck:
          dbl sta(int i = 0 ' i <= 10000 ' i++){
              iid: sta(total = total + 1) ed
          }
          dbl ed
          iid: sta(int i) => edt: sta(i = 0) ed
          iid: sta(int limit) => edt: sta(limit = 2) ed
          iid: sta(while) => cdn,edt:
          dbl sta(i < limit){
              iid: sta(total = total + 1) ed
              iid: sta(i = i + 1) ed
          }
          dbl ed
          iid: sta(pri) => "/d{total}" ed
          iid: ref* 0 ed
      }
    """.trimIndent()

    val compilation = SdIdeEngine.compileAndCheck("main.sd", mapOf("main.sd" to source))
    assertNotNull(SdVmCompiler.compile(compilation.unit!!, compilation.externalPrograms))
    val result = SdIdeEngine.runProgram("main.sd", mapOf("main.sd" to source))

    assertTrue(result.diagnostics.joinToString { it.format() }, result.success)
    assertEquals("10003\n", result.output)
  }

  @Test
  fun virtualMachineRunsSdppAndFallsBackForUnsupportedPrograms() {
    val sdppSource = """
      ssta {
        imp*ioprt.$
        iptedt.int value = 7 $
        ids.pri: "/d{value}" $
        ref*0 $:
      } ##
    """.trimIndent()
    val sdppCompilation = SdIdeEngine.compileAndCheck("main.sdpp", mapOf("main.sdpp" to sdppSource))
    assertNotNull(SdVmCompiler.compile(sdppCompilation.unit!!, sdppCompilation.externalPrograms))

    val sdppResult = SdIdeEngine.runProgram("main.sdpp", mapOf("main.sdpp" to sdppSource))
    assertTrue(sdppResult.diagnostics.joinToString { it.format() }, sdppResult.success)
    assertEquals("7\n", sdppResult.output)

    val unsupportedSource = """
      imp*ioprt.ed
      iid: sta(fn) => rtn:
      sta square(int value) -> int {
          iid: ref* value * value ed
      }
      fn ed
      sta(main){
          iid: sta(pri) => "/d{square(7)}" ed
          iid: ref* 0 ed
      }
    """.trimIndent()
    val unsupportedCompilation = SdIdeEngine.compileAndCheck("main.sd", mapOf("main.sd" to unsupportedSource))
    assertNull(SdVmCompiler.compile(unsupportedCompilation.unit!!, unsupportedCompilation.externalPrograms))

    val fallbackResult = SdIdeEngine.runProgram("main.sd", mapOf("main.sd" to unsupportedSource))
    assertTrue(fallbackResult.diagnostics.joinToString { it.format() }, fallbackResult.success)
    assertEquals("49\n", fallbackResult.output)
  }

  @Test
  fun virtualMachineRunsBranchesAndContinueInLoop() {
    val sample = CurriculumExamples.samples.first { it.id == "ch5_loops" }
    val compilation = SdIdeEngine.compileAndCheck(sample.entryFile, sample.files)
    assertFalse(compilation.diagnostics.joinToString { it.format() }, compilation.hasErrors)
    assertNotNull(SdVmCompiler.compile(compilation.unit!!, compilation.externalPrograms))

    val result = SdIdeEngine.runProgram(sample.entryFile, sample.files)

    assertTrue(result.diagnostics.joinToString { it.format() }, result.success)
    assertTrue(result.output.contains("Tong cuoi = 30"))
  }

  @Test
  fun virtualMachineReportsRuntimeDivisionErrors() {
    val source = """
      imp*ioprt.ed
      sta(main){
          iid: sta(int value) => edt: sta(value = 1 / 0) ed
          iid: ref* 0 ed
      }
    """.trimIndent()

    val result = SdIdeEngine.runProgram("main.sd", mapOf("main.sd" to source))

    assertFalse(result.success)
    assertTrue(result.diagnostics.any { it.code == "E401" && it.severity == DiagnosticSeverity.ERROR })
  }

  @Test
  fun nativeCompilationReportsMissingCompilerAndKeepsGeneratedCSource() {
    val directory = Files.createTempDirectory("sdide-native-test").toFile()
    try {
      val error = assertThrows(SdNativeCompileException::class.java) {
        SdNativeCompiler.compile(
          cSource = "int main(void) { return 0; }\n",
          executable = directory.resolve("program.exe"),
          compilerCandidates = emptyList(),
        )
      }

      assertTrue(error.message.orEmpty().contains("GCC/Clang"))
      assertTrue(directory.resolve("program.c").readText().contains("int main"))
    } finally {
      Files.deleteIfExists(directory.resolve("program.c").toPath())
      Files.deleteIfExists(directory.toPath())
    }
  }

  @Test
  fun nativeCompilerRejectsUnsupportedOptimizationSettings() {
    val directory = Files.createTempDirectory("sdide-native-options-test").toFile()
    try {
      val error = assertThrows(SdNativeCompileException::class.java) {
        SdNativeCompiler.compile(
          cSource = "int main(void) { return 0; }\n",
          executable = directory.resolve("program.exe"),
          compilerCandidates = emptyList(),
          optimizationFlag = "-O9",
        )
      }

      assertTrue(error.message.orEmpty().contains("Mức tối ưu không được hỗ trợ"))
      assertFalse(directory.resolve("program.c").exists())
    } finally {
      Files.deleteIfExists(directory.toPath())
    }
  }

  @Test
  fun nativeCompilerBuildsAndRunsTranspiledProgramWhenToolchainExists() {
    val directory = Files.createTempDirectory("sdide-native-run-test").toFile()
    try {
      val source = """
        imp*ioprt.ed
        sta(main){
            iid: sta(int total) => edt: sta(total = 0) ed
            iid: sta(for) => cdn,chck:
            dbl sta(int i = 1 ' i <= 3 ' i++){
                iid: sta(total = total + i) ed
            }
            dbl ed
            iid: sta(int i) => edt: sta(i = 0) ed
            iid: sta(int limit) => edt: sta(limit = 2) ed
            iid: sta(while) => cdn,edt:
            dbl sta(i < limit){
                iid: sta(total = total + 1) ed
                iid: sta(i = i + 1) ed
            }
            dbl ed
            iid: sta(pri) => "/d{total}" ed
            iid: ref* 0 ed
        }
      """.trimIndent()
      val compilation = SdIdeEngine.compileAndCheck("main.sd", mapOf("main.sd" to source))
      assertTrue(compilation.diagnostics.joinToString { it.format() }, !compilation.hasErrors)

      val executable = directory.resolve(if (System.getProperty("os.name").startsWith("Windows", true)) "program.exe" else "program")
      val native = try {
        SdNativeCompiler.compile(compilation.cSourceCode!!, executable)
      } catch (e: SdNativeCompileException) {
        if (e.message.orEmpty().contains("Không tìm thấy GCC/Clang")) {
          Assume.assumeNoException(e)
        }
        throw e
      }
      val process = ProcessBuilder(native.executable.absolutePath)
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().use { it.readText() }

      assertEquals(0, process.waitFor())
      assertEquals("8\n", output)

      val sdppSource = """
        ssta {
          imp*ioprt.$
          ids.pri: "native-sdpp" $
          ref*0 $:
        } ##
      """.trimIndent()
      val sdppCompilation = SdIdeEngine.compileAndCheck("main.sdpp", mapOf("main.sdpp" to sdppSource))
      assertTrue(sdppCompilation.diagnostics.joinToString { it.format() }, !sdppCompilation.hasErrors)
      val sdppExecutable = directory.resolve(if (System.getProperty("os.name").startsWith("Windows", true)) "sdpp.exe" else "sdpp")
      val sdppNative = try {
        SdNativeCompiler.compile(sdppCompilation.cSourceCode!!, sdppExecutable)
      } catch (e: SdNativeCompileException) {
        throw AssertionError("SD++ AOT failed: ${e.message}", e)
      }
      val sdppProcess = ProcessBuilder(sdppNative.executable.absolutePath)
        .redirectErrorStream(true)
        .start()
      val sdppOutput = sdppProcess.inputStream.bufferedReader().use { it.readText() }

      assertEquals(0, sdppProcess.waitFor())
      assertEquals("native-sdpp\n", sdppOutput)
    } finally {
      directory.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
      Files.deleteIfExists(directory.toPath())
    }
  }
}
