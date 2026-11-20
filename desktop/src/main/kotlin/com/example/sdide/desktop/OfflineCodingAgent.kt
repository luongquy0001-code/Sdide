package com.example.sdide.desktop

import com.example.sdide.engine.DiagnosticSeverity
import com.example.sdide.engine.SdDiagnostic
import com.example.sdide.engine.SdIdeEngine
import com.example.sdide.engine.SdSourceOptimizer

data class OfflineAgentFinding(
  val diagnostic: SdDiagnostic,
  val guidance: String,
)

data class OfflineAgentReport(
  val findings: List<OfflineAgentFinding>,
  val safeOptimizationCount: Int,
) {
  val hasErrors: Boolean get() = findings.any { it.diagnostic.severity == DiagnosticSeverity.ERROR }
}

object OfflineCodingAgent {
  fun review(mainFileName: String, projectFiles: Map<String, String>): OfflineAgentReport {
    val compilation = SdIdeEngine.compileAndCheck(mainFileName, projectFiles, includeCSource = false)
    val findings = compilation.diagnostics.map { diagnostic ->
      OfflineAgentFinding(diagnostic, guidanceFor(diagnostic.code))
    }
    val optimizationCount = SdSourceOptimizer.optimize(projectFiles[mainFileName].orEmpty()).changes.size
    return OfflineAgentReport(findings, optimizationCount)
  }

  private fun guidanceFor(code: String): String = when (code) {
    "E301" -> "Kiểm tra tên biến/hàm, phạm vi khai báo và tên module sau imp*."
    "E401" -> "Bảo đảm mẫu số khác 0; kiểm tra cả các nhánh điều kiện có thể chạy."
    "E402" -> "Kiểm tra điều kiện dừng đệ quy và các vòng gọi hàm lặp lại."
    "E407" -> "Giảm cấp phát đồng thời, giải phóng con trỏ không còn dùng và kiểm tra rò rỉ."
    "E215" -> "Đặt module được nhập trong dự án hoặc cài gói .sdlib cung cấp module đó."
    "W201" -> "Kiểm tra điều kiện thoát và bảo đảm thân vòng lặp cập nhật dữ liệu điều kiện."
    "W302" -> "Rà soát vòng đời con trỏ; gọi sta.free khi không còn cần vùng nhớ."
    "W301" -> "Nếu mã không cần thiết, cân nhắc xóa; nếu cần, kiểm tra đường gọi."
    else -> "Đọc gợi ý của bộ phân tích phía trên và kiểm tra ngữ cảnh quanh dòng được báo."
  }
}
