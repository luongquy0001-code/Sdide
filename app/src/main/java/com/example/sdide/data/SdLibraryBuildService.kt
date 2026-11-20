package com.example.sdide.data

import com.example.sdide.engine.DiagnosticSeverity
import com.example.sdide.engine.SdIdeEngine

object SdLibraryBuildService {
  fun build(library: SdLibraryInfo): ByteArray {
    val sourceFiles = LinkedHashMap(library.sourceFiles)
    var checkFile = "__sdlib_build_check__.sd"
    while (checkFile in sourceFiles) checkFile = "_$checkFile"

    val imports = library.sourceFiles.keys.sorted().joinToString("\n") { fileName ->
      val moduleName = fileName.substringBeforeLast('.')
      val extension = if (fileName.endsWith(".sdpp", ignoreCase = true)) ".$" else ".ed"
      "imp*$moduleName$extension"
    }
    sourceFiles[checkFile] = """
      $imports
      sta(main){
          iid: ref* 0 ed
      }
    """.trimIndent()

    val compilation = SdIdeEngine.compileAndCheck(
      checkFile,
      sourceFiles,
      includeCSource = false,
    )
    val errors = compilation.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }
    require(compilation.unit != null && errors.isEmpty()) {
      errors.joinToString("\n") { it.format() }.ifBlank { "Không thể kiểm tra mã nguồn thư viện." }
    }
    require(SdLibraryCatalog.builtIns.none { it.name == library.name }) {
      "Tên '${library.name}' đã được dùng bởi thư viện tích hợp sẵn."
    }
    val builtInModule = library.sourceFiles.keys.firstOrNull { fileName ->
      fileName.substringBeforeLast('.').lowercase() in SdLibraryCatalog.builtIns.map { it.name }
    }
    require(builtInModule == null) {
      "Module '${builtInModule?.substringBeforeLast('.')}' trùng với thư viện tích hợp sẵn."
    }
    return SdLibraryPackage.encode(library)
  }
}
