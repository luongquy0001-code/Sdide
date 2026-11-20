package com.example.sdide.desktop

import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCodingAgentTest {
  @Test
  fun explainsStaticDiagnosticsLocally() {
    val source = """
      sta(main){
          iid: sta(int result) => edt: sta(result = missing) ed
          iid: ref* 0 ed
      }
    """.trimIndent()

    val report = OfflineCodingAgent.review("main.sd", mapOf("main.sd" to source))

    assertTrue(report.hasErrors)
    assertTrue(report.findings.any {
      it.diagnostic.code == "E301" && it.guidance.contains("phạm vi khai báo")
    })
  }
}
