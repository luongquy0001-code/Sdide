package com.example.sdide.engine

data class CompilationOutcome(
  val unit: SdCompilationUnit?,
  val externalPrograms: Map<String, ProgramNode>,
  val diagnostics: List<SdDiagnostic>,
  val cSourceCode: String?,
) {
  val hasErrors: Boolean get() = diagnostics.any { it.severity == DiagnosticSeverity.ERROR }
}

data class IdeRunResult(
  val success: Boolean,
  val output: String,
  val exitCode: Long,
  val diagnostics: List<SdDiagnostic>,
  val finalSnapshot: DebugSnapshot?,
  val cSourceCode: String?,
  val drawCommands: List<DrawRectCmd> = emptyList(),
  val terminatedByUser: Boolean = false,
)

object SdIdeEngine {

  fun compileAndCheck(
    mainFileName: String,
    projectFiles: Map<String, String>,
    includeCSource: Boolean = true,
  ): CompilationOutcome {
    val source = projectFiles[mainFileName] ?: ""
    val warnings = mutableListOf<SdDiagnostic>()
    return try {
      val lexRes = SdLexer(source, mainFileName).tokenize()
      warnings.addAll(lexRes.warnings)

      val parser = SdParser(
        tokens = lexRes.tokens,
        fileName = mainFileName,
        isSdAll = mainFileName.endsWith(".sdall", ignoreCase = true),
        isExplicitSdppExt = mainFileName.endsWith(".sdpp", ignoreCase = true),
        externalFiles = projectFiles,
        initialWarnings = warnings,
      )
      val unit = parser.parseCompilationUnit()

      val analyzer = SdSemanticAnalyzer(mainFileName, parser.importedUnits)
      val semWarnings = analyzer.analyze(unit)
      warnings.addAll(semWarnings)

      val cCode = if (includeCSource) SdCToTranspiler().transpile(unit) else null
      CompilationOutcome(
        unit = unit,
        externalPrograms = parser.importedUnits,
        diagnostics = warnings.distinct(),
        cSourceCode = cCode,
      )
    } catch (e: SdCompileException) {
      CompilationOutcome(
        unit = null,
        externalPrograms = emptyMap(),
        diagnostics = (warnings + e.diagnostic).distinct(),
        cSourceCode = null,
      )
    }
  }

  fun runProgram(
    mainFileName: String,
    projectFiles: Map<String, String>,
    stdinProvider: StdinProvider = QueueStdinProvider(emptyList()),
    fileBridge: WorkspaceFileBridge = InMemoryFileBridge(),
    debugController: DebugController? = null,
    includeCSource: Boolean = true,
  ): IdeRunResult {
    val compiled = compileAndCheck(mainFileName, projectFiles, includeCSource)
    if (compiled.unit == null || compiled.hasErrors) {
      return IdeRunResult(
        success = false,
        output = "",
        exitCode = -1,
        diagnostics = compiled.diagnostics,
        finalSnapshot = null,
        cSourceCode = compiled.cSourceCode,
      )
    }

    val allDiags = compiled.diagnostics.toMutableList()
    val canUseVm = debugController == null ||
      (!debugController.stepMode && debugController.stepOverDepth == null && debugController.breakpoints.isEmpty())
    val vmProgram = if (canUseVm) SdVmCompiler.compile(compiled.unit, compiled.externalPrograms) else null
    if (vmProgram != null) {
      val vm = SdVirtualMachine(debugController, mainFileName)
      return try {
        val outcome = vm.execute(vmProgram)
        allDiags.addAll(outcome.warnings)
        IdeRunResult(
          success = true,
          output = outcome.output,
          exitCode = outcome.exitCode,
          diagnostics = allDiags.distinct(),
          finalSnapshot = outcome.finalSnapshot,
          cSourceCode = compiled.cSourceCode,
          drawCommands = outcome.drawCommands,
          terminatedByUser = outcome.terminatedByUser,
        )
      } catch (e: SdRuntimeException) {
        allDiags.add(e.diagnostic)
        IdeRunResult(
          success = false,
          output = vm.currentOutput(),
          exitCode = -1,
          diagnostics = allDiags.distinct(),
          finalSnapshot = null,
          cSourceCode = compiled.cSourceCode,
        )
      }
    }

    val interp = SdInterpreter(
      externalPrograms = compiled.externalPrograms,
      stdinProvider = stdinProvider,
      fileBridge = fileBridge,
      debugController = debugController,
      mainFileName = mainFileName,
    )
    return try {
      val outcome = interp.execute(compiled.unit)
      allDiags.addAll(outcome.warnings)
      IdeRunResult(
        success = true,
        output = outcome.output,
        exitCode = outcome.exitCode,
        diagnostics = allDiags.distinct(),
        finalSnapshot = outcome.finalSnapshot,
        cSourceCode = compiled.cSourceCode,
        drawCommands = outcome.drawCommands,
        terminatedByUser = outcome.terminatedByUser,
      )
    } catch (e: SdRuntimeException) {
      allDiags.add(e.diagnostic)
      IdeRunResult(
        success = false,
        output = interp.currentOutput(),
        exitCode = -1,
        diagnostics = allDiags.distinct(),
        finalSnapshot = null,
        cSourceCode = compiled.cSourceCode,
      )
    } catch (e: SdCompileException) {
      allDiags.add(e.diagnostic)
      IdeRunResult(
        success = false,
        output = interp.currentOutput(),
        exitCode = -1,
        diagnostics = allDiags.distinct(),
        finalSnapshot = null,
        cSourceCode = compiled.cSourceCode,
      )
    }
  }
}
