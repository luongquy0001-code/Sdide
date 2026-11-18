package com.example.sdide.desktop

import com.example.sdide.data.SdLibraryInfo
import com.example.sdide.data.SdLibraryPackage
import com.example.sdide.engine.DiagnosticSeverity
import com.example.sdide.engine.SdIdeEngine

object SdLibraryBuildService {
  fun build(library: SdLibraryInfo): ByteArray {
    val projectFiles = LinkedHashMap(library.sourceFiles)
    var checkFile = "__sdlib_build_check__.sd"
    while (checkFile in projectFiles) checkFile = "_$checkFile"

    val imports = library.sourceFiles.keys.sorted().joinToString("\n") { fileName ->
      val moduleName = fileName.substringBeforeLast('.')
      val moduleExtension = if (fileName.endsWith(".sdpp")) ".$" else ".ed"
      "imp*$moduleName$moduleExtension"
    }
    projectFiles[checkFile] = """
      $imports
      sta(main){
          iid: ref* 0 ed
      }
    """.trimIndent()

    val compilation = SdIdeEngine.compileAndCheck(
      checkFile,
      projectFiles,
      includeCSource = false,
    )
    val errors = compilation.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }
    require(compilation.unit != null && errors.isEmpty()) {
      errors.joinToString("\n") { it.format() }.ifBlank { "Không thể kiểm tra mã nguồn thư viện." }
    }
    return SdLibraryPackage.encode(library)
  }
}
