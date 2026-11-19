package com.example

import com.example.sdide.data.CurriculumExamples
import com.example.sdide.engine.DiagnosticSeverity
import com.example.sdide.engine.QueueStdinProvider
import com.example.sdide.engine.SdIdeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdEngineComprehensiveTest {

  private fun runSingle(
    code: String,
    fileName: String = "test.sd",
    stdin: List<String> = emptyList(),
  ) = SdIdeEngine.runProgram(
    mainFileName = fileName,
    projectFiles = mapOf(fileName to code),
    stdinProvider = QueueStdinProvider(stdin),
  )

  private fun hasCode(diags: List<com.example.sdide.engine.SdDiagnostic>, code: String): Boolean =
    diags.any { it.code == code }

  @Test
  fun testAllCurriculumSamplesExceptIntentionalBugHunt() {
    for (sample in CurriculumExamples.samples) {
      val res = SdIdeEngine.runProgram(
        mainFileName = sample.entryFile,
        projectFiles = sample.files,
      )
      if (sample.id == "ch9_bughunt") {
        assertFalse("ch9_bughunt should fail with diagnostics", res.success)
        assertTrue(res.diagnostics.any { it.severity == DiagnosticSeverity.ERROR })
      } else {
        assertTrue(
          "Sample '${sample.id}' failed with: ${res.diagnostics.joinToString { it.format() }}",
          res.success,
        )
        assertTrue("Sample '${sample.id}' should produce output", res.output.isNotBlank())
        assertTrue("Sample '${sample.id}' should produce C99 code", !res.cSourceCode.isNullOrBlank())
      }
    }
  }

  @Test
  fun testStringSlashSlashAndE104() {
    val ok = runSingle(
      """
      imp*ioprt.ed
      sta(main){
          iid: sta(pri) => "Ti le 3//4 va//eDong moi" ed
          iid: ref* 0 ed
      }
      """.trimIndent(),
    )
    assertTrue(ok.diagnostics.joinToString { it.format() }, ok.success)
    assertEquals("Ti le 3/4 va\nDong moi\n", ok.output)

    val bad = runSingle(
      """
      imp*ioprt.ed
      sta(main){
          iid: sta(pri) => "Sai //x o day" ed
          iid: ref* 0 ed
      }
      """.trimIndent(),
    )
    assertFalse(bad.success)
    assertTrue(hasCode(bad.diagnostics, "E104"))
  }

  @Test
  fun testArrayPassByReferenceAndStructByValue() {
    val res = runSingle(
      """
      imp*ioprt.ed
      iid: sta(struct) => dta:
      sta Box{ int val }
      strt ed

      iid: sta(void) => unkw:
      sta modify(int arr[2] ' Box b){
          iid: sta(arr[0] = 99) ed
          iid: sta(b.val = 777) ed
      }
      void ed

      sta(main){
          iid: sta(int a[2]) => edt: sta(a = [1 ' 2]) ed
          iid: sta(Box bx) ed
          iid: sta(bx.val = 10) ed
          iid: call modify(a ' bx) ed
          iid: sta(pri) => "/d{a[0]}-/d{bx.val}" ed
          iid: ref* 0 ed
      }
      """.trimIndent(),
    )
    assertTrue(res.diagnostics.joinToString { it.format() }, res.success)
    assertEquals("99-10\n", res.output)
  }

  @Test
  fun testIptInteractiveStdinAndBoleanElse() {
    val res = runSingle(
      code = """
      imp*ioprt.ed
      sta(main){
          iid: sta(int diem) ed
          iid: sta(ipt) => edt: sta(diem) ed
          iid: sta(bolean) => cdn,tf:
          sta(diem >= 8){
              iid: sta(pri) => "Gioi" ed
          }
          sta(else){
              iid: sta(pri) => "Kha" ed
          }
          bln ed
          iid: ref* 0 ed
      }
      """.trimIndent(),
      stdin = listOf("9"),
    )
    assertTrue(res.diagnostics.joinToString { it.format() }, res.success)
    assertEquals("Gioi\n", res.output)
  }

  @Test
  fun testMirrorFirstAssignmentErrorE301() {
    // Ch 14 page 71: dem = dem + 1 on first use in *m must throw E301
    val res = runSingle(
      fileName = "mirror_err.sdpp",
      code = """
      ssta {
          imp*ioprt.$
          *m
              dem = dem + 1
          :&
          ref*0 $:
      } ##
      """.trimIndent(),
    )
    assertFalse(res.success)
    assertTrue(hasCode(res.diagnostics, "E301"))
  }

  @Test
  fun testHeapDoubleFreeE406AndOutOfBoundsE408() {
    val dblFree = runSingle(
      fileName = "heap1.sdpp",
      code = """
      ssta {
          imp*mem.$
          iptedt.ptr p = sta.alloc(2) $
          sta.free(p) $
          sta.free(p) $
          ref*0 $:
      } ##
      """.trimIndent(),
    )
    assertFalse(dblFree.success)
    assertTrue(hasCode(dblFree.diagnostics, "E406"))

    val oob = runSingle(
      fileName = "heap2.sdpp",
      code = """
      ssta {
          imp*mem.$
          iptedt.ptr p = sta.alloc(2) $
          *(p + 5) = 10 $
          sta.free(p) $
          ref*0 $:
      } ##
      """.trimIndent(),
    )
    assertFalse(oob.success)
    assertTrue(hasCode(oob.diagnostics, "E408"))
  }

  @Test
  fun freedHeapBlocksDoNotAccumulateDuringLoops() {
    val result = runSingle(
      fileName = "heap_loop.sdpp",
      code = """
      ssta {
          imp*mem.$
          iid: sta(for) => cdn,chck:
          dbl sta(int i = 0 ' i < 5000 ' i++){
              iptedt.ptr p = sta.alloc(1) $
              sta.free(p) $
          }
          dbl ed
          ref*0 $:
      } ##
      """.trimIndent(),
    )

    assertTrue(result.diagnostics.joinToString { it.format() }, result.success)
    assertEquals(256, result.finalSnapshot?.heapBlocks?.size)
    assertTrue(result.finalSnapshot?.heapBlocks?.all { it.freed } == true)
    assertFalse(hasCode(result.diagnostics, "W302"))
  }

  @Test
  fun dripAndMajorCollectionsReclaimUnreachableHeap() {
    val result = runSingle(
      fileName = "gc_loop.sdpp",
      code = """
      ssta {
          imp*mem.$
          imp*sys.$
          imp*ioprt.$
          iid: sta(for) => cdn,chck:
          dbl sta(int i = 0 ' i < 5000 ' i++){
              iptedt.ptr p = sta.alloc(1) $
          }
          dbl ed
          iptedt.int reclaimed = sys.gc() $
          ids.pri: "/d{reclaimed}" $
          ref*0 $:
      } ##
      """.trimIndent(),
    )

    assertTrue(result.diagnostics.joinToString { it.format() }, result.success)
    assertTrue(result.output.trim().toInt() > 0)
    assertTrue(result.finalSnapshot?.heapBlocks?.all { it.freed } == true)
    assertFalse(hasCode(result.diagnostics, "W302"))
  }

  @Test
  fun simulatedRegistersExpandAcrossBanksAndSlots() {
    val result = runSingle(
      fileName = "registers.sdpp",
      code = """
      ssta {
          imp*ioprt.$
          #{ #sdbin: 0Nx[1], 1Ax[2], 15Nx[3] # }#
          :p > sdppbin: int result = nx{0Nx, 1Ax, 15Nx} $
          ids.pri: "/d{result}" $
          ref*0 $:
      } ##
      """.trimIndent(),
    )

    assertTrue(result.diagnostics.joinToString { it.format() }, result.success)
    assertEquals("1", result.output.trim())
    assertEquals(224, com.example.sdide.engine.SdParser.VALID_REGISTERS.size)
  }

  @Test
  fun garbageCollectorKeepsPointersInActiveLoopScopesAlive() {
    val result = runSingle(
      fileName = "gc_roots.sdpp",
      code = """
      ssta {
          imp*mem.$
          imp*sys.$
          imp*ioprt.$
          iid: sta(for) => cdn,chck:
          dbl sta(int i = 0 ' i < 1 ' i++){
              iptedt.ptr kept = sta.alloc(1) $
              iid: sta(for) => cdn,chck:
              dbl sta(int j = 0 ' j < 128 ' j++){
                  iptedt.ptr temporary = sta.alloc(1) $
              }
              dbl ed
              iptedt.int value = *(kept) $
              iptedt.int reclaimed = sys.gc() $
              ids.pri: "/d{value}" $
              sta.free(kept) $
          }
          dbl ed
          ref*0 $:
      } ##
      """.trimIndent(),
    )

    assertTrue(result.diagnostics.joinToString { it.format() }, result.success)
    assertEquals("0", result.output.trim())
  }

  @Test
  fun excessiveRecursionReturnsDiagnosticInsteadOfOverflowingRuntimeStack() {
    val result = runSingle(
      code = """
      imp*ioprt.ed
      iid: sta(fn) => rtn:
      sta recurse(int depth) -> int {
          iid: ref* recurse(depth + 1) ed
      }
      fn ed
      sta(main){
          iid: sta(pri) => recurse(0) ed
          iid: ref* 0 ed
      }
      """.trimIndent(),
    )

    assertFalse(result.success)
    assertTrue(result.diagnostics.joinToString { it.format() }, hasCode(result.diagnostics, "E402"))
  }

  @Test
  fun testHardHaltE409AndRegisterE220AndE218() {
    val hardHalt = runSingle(
      fileName = "halt.sdpp",
      code = """
      ssta {
          imp*ioprt.$
          $$
          ref*0 $:
      } ##
      """.trimIndent(),
    )
    assertFalse(hardHalt.success)
    assertTrue(hasCode(hardHalt.diagnostics, "E409"))

    val unnullified = runSingle(
      fileName = "reg220.sdpp",
      code = """
      ssta {
          #{ #sdbin: 0Ax[7] # }#
          ref*0 $:
      } ##
      """.trimIndent(),
    )
    assertFalse(unnullified.success)
    assertTrue(hasCode(unnullified.diagnostics, "E220"))

    val borrowErr = runSingle(
      fileName = "reg218.sdpp",
      code = """
      ssta {
          imp*ioprt.$
          iptedt.int x = 5 $
          ids.pri: "/d{*m(x)}" $
          ref*0 $:
      } ##
      """.trimIndent(),
    )
    assertFalse(borrowErr.success)
    assertTrue(hasCode(borrowErr.diagnostics, "E218"))
  }

  @Test
  fun testSdAllStopAndWarningsW208AndW201() {
    val sdall = runSingle(
      fileName = "chain.sdall",
      code = """
      start ssta {
          imp*ioprt.$
          ids.pri: "1" $
      } ##
      ;
      stop ssta {
          imp*ioprt.$
          ids.pri: "2" $
      } ##
      ;
      on ssta {
          imp*ioprt.$
          ids.pri: "3" $
      } ##
      """.trimIndent(),
    )
    assertTrue(sdall.diagnostics.joinToString { it.format() }, sdall.success)
    assertEquals("1\n2\n", sdall.output)
    assertTrue(hasCode(sdall.diagnostics, "W208"))
  }

  @Test
  fun testCircularImportE211() {
    val res = SdIdeEngine.runProgram(
      mainFileName = "a.sd",
      projectFiles = mapOf(
        "a.sd" to "imp*b.ed\nsta(main){\niid: ref* 0 ed\n}",
        "b.sd" to "imp*a.ed\niid: sta(fn) => rtn:\nsta f() -> int{\niid: ref* 1 ed\n}\nfn ed",
      ),
    )
    assertFalse(res.success)
    assertTrue(hasCode(res.diagnostics, "E211"))
  }

  @Test
  fun testImportedSdLibrarySourceResolvesAsModule() {
    val res = SdIdeEngine.runProgram(
      mainFileName = "main.sd",
      projectFiles = mapOf(
        "main.sd" to """
          imp*ioprt.ed
          imp*mathplus.ed
          sta(main){
            iid: sta(pri) => "/d{mathplus.square(7)}" ed
            iid: ref* 0 ed
          }
        """.trimIndent(),
        "mathplus.sd" to """
          iid: sta(fn) => rtn:
          sta square(int value) -> int{
            iid: ref* value * value ed
          }
          fn ed
        """.trimIndent(),
      ),
    )

    assertTrue(res.diagnostics.joinToString { it.format() }, res.success)
    assertEquals("49\n", res.output)
  }
}
