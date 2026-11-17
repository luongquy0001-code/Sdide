package com.example.sdide.desktop

import com.example.sdide.data.CurriculumExamples
import com.example.sdide.engine.SdIdeEngine
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEngineTest {
  @Test
  fun runsSharedSdProjectWithTheDesktopEngine() {
    val sample = CurriculumExamples.samples.first()
    val result = SdIdeEngine.runProgram(sample.entryFile, sample.files)

    assertTrue(result.success)
    assertTrue(result.output.contains("Xin chao"))
  }
}
