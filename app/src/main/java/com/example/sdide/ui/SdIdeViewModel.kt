package com.example.sdide.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sdide.data.IdePreferences
import com.example.sdide.data.ProjectRepository
import com.example.sdide.data.SampleProject
import com.example.sdide.data.SdLibraryBuildService
import com.example.sdide.data.SdLibraryInfo
import com.example.sdide.data.SdLibraryPackage
import com.example.sdide.engine.DebugCommand
import com.example.sdide.engine.DebugController
import com.example.sdide.engine.DebugSnapshot
import com.example.sdide.engine.DiagnosticSeverity
import com.example.sdide.engine.DrawRectCmd
import com.example.sdide.engine.SdBytecodeCompileException
import com.example.sdide.engine.SdBytecodeCompiler
import com.example.sdide.engine.SdDiagnostic
import com.example.sdide.engine.SdIdeEngine
import com.example.sdide.engine.SdBackendGenerationException
import com.example.sdide.engine.SdOptimizationResult
import com.example.sdide.engine.SdSourceOptimizer
import com.example.sdide.engine.SdTargetCodeGenerator
import com.example.sdide.engine.SdTargetLanguage
import com.example.sdide.engine.StdinProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

enum class WorkbenchTab {
  CONSOLE, PROBLEMS, DEBUGGER, STDIN, DRAW
}

private const val LIVE_OUTPUT_BATCH_CHARS = 16 * 1024
private const val LIVE_OUTPUT_BATCH_INTERVAL_NANOS = 100_000_000L

data class IdeUiState(
  val projectName: String = "Sd IDE",
  val entryFile: String = "xin_chao.sd",
  val activeFile: String = "xin_chao.sd",
  val files: Map<String, String> = emptyMap(),
  val editorValue: TextFieldValue = TextFieldValue(""),
  val canUndo: Boolean = false,
  val canRedo: Boolean = false,
  val diagnostics: List<SdDiagnostic> = emptyList(),
  val breakpoints: Set<Int> = emptySet(),
  val stdinText: String = "",
  val isRunning: Boolean = false,
  val isPausedInDebug: Boolean = false,
  val debugLine: Int? = null,
  val waitingForStdinPrompt: String? = null,
  val consoleOutput: String = "Sẵn sàng. Nhấn Chạy (▶) hoặc Gỡ lỗi (🐞) để thực thi mã Sd / Sd++.\n",
  val lastExitCode: Long? = null,
  val lastRunSucceeded: Boolean? = null,
  val debugSnapshot: DebugSnapshot? = null,
  val cSourceCode: String? = null,
  val drawCommands: List<DrawRectCmd> = emptyList(),
  val activeTab: WorkbenchTab = WorkbenchTab.CONSOLE,
  val scrollToLineRequest: Pair<Int, Long>? = null,
  val preferences: IdePreferences = IdePreferences(),
  val showCViewer: Boolean = false,
  val showCodeTools: Boolean = false,
  val targetLanguage: SdTargetLanguage = SdTargetLanguage.C99,
  val generatedSource: String? = null,
  val codeGenerationError: String? = null,
  val isGeneratingCode: Boolean = false,
  val optimizationPreview: SdOptimizationResult? = null,
  val showCurriculumSheet: Boolean = false,
  val showStorageManager: Boolean = false,
  val dataFiles: List<String> = emptyList(),
  val storagePath: String = "",
  val fileMessage: String? = null,
  val showLibraryManager: Boolean = false,
  val installedLibraries: List<SdLibraryInfo> = emptyList(),
)

class SdIdeViewModel(application: Application) : AndroidViewModel(application) {
  private val repo = ProjectRepository(application)
  @Volatile private var installedLibrarySourceFiles: Map<String, String> = emptyMap()

  private val _uiState = MutableStateFlow(IdeUiState())
  val uiState: StateFlow<IdeUiState> = _uiState.asStateFlow()

  private val undoStacks = HashMap<String, ArrayDeque<TextFieldValue>>()
  private val redoStacks = HashMap<String, ArrayDeque<TextFieldValue>>()
  private var liveCheckJob: Job? = null
  private var codeGenerationJob: Job? = null
  private val liveCheckGeneration = AtomicLong()
  private val codeGenerationGeneration = AtomicLong()
  private val liveCheckLock = Any()
  private var runJob: Job? = null
  private var activeDebugController: DebugController? = null
  private var activeInteractiveStdinQueue: LinkedBlockingQueue<String>? = null

  init {
    val ws = repo.loadOrSeedWorkspace()
    val installedLibraries = repo.installedSdLibraries()
    installedLibrarySourceFiles = installedLibraries.flatMap { it.sourceFiles.entries }
      .associateTo(LinkedHashMap()) { it.key to it.value }
    val initialText = ws.files[ws.entryFile] ?: ""
    _uiState.update {
      it.copy(
        projectName = ws.projectName,
        entryFile = ws.entryFile,
        activeFile = ws.entryFile,
        files = ws.files,
        editorValue = TextFieldValue(initialText),
        dataFiles = repo.listDataFiles(),
        storagePath = repo.storageDirectoryPath(),
        installedLibraries = installedLibraries,
      )
    }
    runStaticCheck()

    viewModelScope.launch {
      repo.preferencesFlow.collect { prefs ->
        _uiState.update { it.copy(preferences = prefs) }
      }
    }
  }

  fun onEditorValueChange(newValue: TextFieldValue) {
    val current = _uiState.value
    val file = current.activeFile
    val oldText = current.editorValue.text

    if (newValue.text != oldText) {
      codeGenerationGeneration.incrementAndGet()
      codeGenerationJob?.cancel()
      val undo = undoStacks.getOrPut(file) { ArrayDeque() }
      undo.addLast(current.editorValue)
      if (undo.size > 60) undo.removeFirst()
      redoStacks.getOrPut(file) { ArrayDeque() }.clear()

      val updatedFiles = current.files.toMutableMap()
      updatedFiles[file] = newValue.text
      _uiState.update {
        it.copy(
          files = updatedFiles,
          editorValue = newValue,
          canUndo = undo.isNotEmpty(),
          canRedo = false,
          generatedSource = null,
          codeGenerationError = null,
          isGeneratingCode = false,
        )
      }
      repo.saveFile(file, newValue.text)
      scheduleLiveCheck()
    } else {
      _uiState.update { it.copy(editorValue = newValue) }
    }
  }

  fun undo() {
    val file = _uiState.value.activeFile
    val undo = undoStacks[file] ?: return
    if (undo.isEmpty()) return
    val redo = redoStacks.getOrPut(file) { ArrayDeque() }
    redo.addLast(_uiState.value.editorValue)
    val prev = undo.removeLast()
    applyHistoryValue(file, prev, undo.isNotEmpty(), redo.isNotEmpty())
  }

  fun redo() {
    val file = _uiState.value.activeFile
    val redo = redoStacks[file] ?: return
    if (redo.isEmpty()) return
    val undo = undoStacks.getOrPut(file) { ArrayDeque() }
    undo.addLast(_uiState.value.editorValue)
    val next = redo.removeLast()
    applyHistoryValue(file, next, undo.isNotEmpty(), redo.isNotEmpty())
  }

  private fun applyHistoryValue(file: String, tfv: TextFieldValue, canUndo: Boolean, canRedo: Boolean) {
    val updatedFiles = _uiState.value.files.toMutableMap()
    updatedFiles[file] = tfv.text
    _uiState.update {
      it.copy(
        files = updatedFiles,
        editorValue = tfv,
        canUndo = canUndo,
        canRedo = canRedo,
      )
    }
    repo.saveFile(file, tfv.text)
    scheduleLiveCheck()
  }

  fun insertSnippet(snippet: SymbolSnippet) {
    onEditorValueChange(insertSymbolIntoTextField(_uiState.value.editorValue, snippet))
  }

  fun switchFile(fileName: String) {
    val st = _uiState.value
    if (fileName !in st.files) return
    val content = st.files[fileName] ?: ""
    val canUndo = undoStacks[fileName]?.isNotEmpty() == true
    val canRedo = redoStacks[fileName]?.isNotEmpty() == true
    _uiState.update {
      it.copy(
        activeFile = fileName,
        editorValue = TextFieldValue(content),
        canUndo = canUndo,
        canRedo = canRedo,
      )
    }
  }

  fun createNewFile(rawName: String) {
    val clean = rawName.trim()
    if (clean.isEmpty()) return
    val finalName = if (clean.endsWith(".sd") || clean.endsWith(".sdpp") || clean.endsWith(".sdall")) {
      clean
    } else {
      "$clean.sd"
    }
    val st = _uiState.value
    if (finalName in st.files) {
      switchFile(finalName)
      return
    }
    val template = when {
      finalName.endsWith(".sdpp") -> "ssta {\n    imp*ioprt.$\n    ids.pri: \"Xin chao Sd++!\" $\n    ref*0 $:\n} ##\n"
      finalName.endsWith(".sdall") -> "start ssta {\n    imp*ioprt.$\n    ids.pri: \"Ngan 1\" $\n} ##\n;\non imp*ioprt.ed\nsta(main){\n    iid: sta(pri) => \"Ngan 2\" ed\n    iid: ref* 0 ed\n}\n"
      else -> "\\\\ Thu vien / tep moi: $finalName \\\\\n"
    }
    val updated = st.files.toMutableMap()
    updated[finalName] = template
    repo.saveFile(finalName, template)
    _uiState.update {
      it.copy(
        files = updated,
        activeFile = finalName,
        editorValue = TextFieldValue(template),
        canUndo = false,
        canRedo = false,
      )
    }
    scheduleLiveCheck()
  }

  fun deleteFile(fileName: String) {
    val st = _uiState.value
    if (st.files.size <= 1 || fileName !in st.files) return
    val updated = st.files.toMutableMap()
    updated.remove(fileName)
    repo.deleteFile(fileName)
    val nextActive = if (st.activeFile == fileName) updated.keys.first() else st.activeFile
    val nextEntry = if (st.entryFile == fileName) updated.keys.first() else st.entryFile
    if (nextEntry != st.entryFile) repo.setEntryFile(nextEntry)
    _uiState.update {
      it.copy(
        files = updated,
        activeFile = nextActive,
        entryFile = nextEntry,
        editorValue = TextFieldValue(updated[nextActive] ?: ""),
      )
    }
    runStaticCheck()
  }

  fun setEntryFile(fileName: String) {
    if (fileName !in _uiState.value.files) return
    repo.setEntryFile(fileName)
    _uiState.update { it.copy(entryFile = fileName) }
    runStaticCheck()
  }

  fun loadSampleProject(sample: SampleProject) {
    stopExecution()
    undoStacks.clear()
    redoStacks.clear()
    val ws = repo.saveSampleAsWorkspace(sample)
    val content = ws.files[ws.entryFile] ?: ""
    _uiState.update {
      it.copy(
        projectName = ws.projectName,
        entryFile = ws.entryFile,
        activeFile = ws.entryFile,
        files = ws.files,
        editorValue = TextFieldValue(content),
        canUndo = false,
        canRedo = false,
        breakpoints = emptySet(),
        stdinText = sample.defaultStdin,
        consoleOutput = "Đã tải mẫu: ${sample.title} (${sample.chapter}). Nhấn ▶ Chạy để xem kết quả.\n",
        lastExitCode = null,
        lastRunSucceeded = null,
        debugSnapshot = null,
        drawCommands = emptyList(),
        showCurriculumSheet = false,
      )
    }
    runStaticCheck()
  }

  fun toggleBreakpoint(line: Int) {
    _uiState.update { st ->
      val next = if (line in st.breakpoints) st.breakpoints - line else st.breakpoints + line
      activeDebugController?.breakpoints = next
      st.copy(breakpoints = next)
    }
  }

  fun updateStdinText(text: String) {
    _uiState.update { it.copy(stdinText = text) }
  }

  fun selectTab(tab: WorkbenchTab) {
    _uiState.update { it.copy(activeTab = tab) }
  }

  fun setCurriculumSheetVisible(visible: Boolean) {
    _uiState.update { it.copy(showCurriculumSheet = visible) }
  }

  fun setCViewerVisible(visible: Boolean) {
    _uiState.update { it.copy(showCViewer = visible) }
    if (visible) runStaticCheck()
  }

  fun setCodeToolsVisible(visible: Boolean) {
    if (!visible) {
      codeGenerationGeneration.incrementAndGet()
      codeGenerationJob?.cancel()
    }
    _uiState.update {
      it.copy(
        showCodeTools = visible,
        optimizationPreview = if (visible) it.optimizationPreview else null,
        isGeneratingCode = if (visible) it.isGeneratingCode else false,
      )
    }
  }

  fun selectTargetLanguage(target: SdTargetLanguage) {
    if (_uiState.value.targetLanguage == target) return
    codeGenerationGeneration.incrementAndGet()
    codeGenerationJob?.cancel()
    _uiState.update {
      it.copy(
        targetLanguage = target,
        generatedSource = null,
        codeGenerationError = null,
        isGeneratingCode = false,
      )
    }
  }

  fun generateTargetCode(target: SdTargetLanguage) {
    codeGenerationJob?.cancel()
    val generation = codeGenerationGeneration.incrementAndGet()
    val snapshot = _uiState.value
    _uiState.update {
      it.copy(
        targetLanguage = target,
        generatedSource = null,
        codeGenerationError = null,
        isGeneratingCode = true,
      )
    }
    val projectFiles = compilationFiles(snapshot.files)
    codeGenerationJob = viewModelScope.launch(Dispatchers.Default) {
      try {
        val outcome = SdIdeEngine.compileAndCheck(snapshot.entryFile, projectFiles, includeCSource = false)
        val output = when {
          outcome.hasErrors -> {
            _uiState.update { current ->
              if (codeGenerationGeneration.get() == generation &&
                current.entryFile == snapshot.entryFile && current.files == snapshot.files
              ) {
                current.copy(codeGenerationError = outputError(outcome))
              } else current
            }
            null
          }
          outcome.unit == null -> {
            _uiState.update { current ->
              if (codeGenerationGeneration.get() == generation &&
                current.entryFile == snapshot.entryFile && current.files == snapshot.files
              ) {
                current.copy(codeGenerationError = "Không thể phân tích dự án để sinh mã.")
              } else current
            }
            null
          }
          else -> try {
            SdTargetCodeGenerator.generate(outcome.unit, target, outcome.externalPrograms)
          } catch (e: SdBackendGenerationException) {
            _uiState.update { current ->
              if (codeGenerationGeneration.get() == generation &&
                current.entryFile == snapshot.entryFile && current.files == snapshot.files
              ) {
                current.copy(codeGenerationError = e.message ?: "Backend từ chối tính năng chưa hỗ trợ.")
              } else current
            }
            null
          }
        }
        if (output != null) {
          _uiState.update { current ->
            if (codeGenerationGeneration.get() == generation &&
              current.entryFile == snapshot.entryFile && current.files == snapshot.files
            ) {
              current.copy(generatedSource = output, codeGenerationError = null)
            } else current
          }
        }
      } finally {
        if (codeGenerationGeneration.get() == generation) {
          _uiState.update { it.copy(isGeneratingCode = false) }
        }
      }
    }
  }

  private fun outputError(outcome: com.example.sdide.engine.CompilationOutcome): String =
    outcome.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }
      .joinToString("\n") { it.format() }
      .ifEmpty { "Không thể phân tích dự án để sinh mã." }

  fun previewSourceOptimization() {
    val source = _uiState.value.editorValue.text
    _uiState.update { it.copy(optimizationPreview = SdSourceOptimizer.optimize(source)) }
  }

  fun dismissSourceOptimizationPreview() {
    _uiState.update { it.copy(optimizationPreview = null) }
  }

  fun applySourceOptimization() {
    val result = _uiState.value.optimizationPreview ?: return
    if (!result.changed) return
    val oldValue = _uiState.value.editorValue
    val cursor = oldValue.selection.start.coerceIn(0, result.optimizedSource.length)
    onEditorValueChange(
      TextFieldValue(result.optimizedSource, selection = TextRange(cursor)),
    )
    _uiState.update { it.copy(optimizationPreview = null) }
  }

  fun setStorageManagerVisible(visible: Boolean) {
    if (visible) refreshStorageFiles()
    _uiState.update { it.copy(showStorageManager = visible) }
  }

  fun setLibraryManagerVisible(visible: Boolean) {
    if (visible) refreshInstalledLibraries()
    _uiState.update { it.copy(showLibraryManager = visible) }
  }

  fun installSdLibrary(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val name = queryDisplayName(uri)
        require(name.endsWith(".sdlib", ignoreCase = true)) {
          "Hãy chọn gói thư viện có đuôi .sdlib."
        }
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)
          ?.use(SdLibraryPackage::readPackageBytes)
          ?: throw IOException("Không thể mở gói thư viện đã chọn.")
        val library = repo.installSdLibrary(bytes)
        refreshInstalledLibraries()
        runStaticCheck()
        _uiState.update { it.copy(fileMessage = "Đã cài ${library.name} ${library.version}") }
      } catch (e: IOException) {
        reportFileError(e)
      } catch (e: SecurityException) {
        reportFileError(e)
      } catch (e: IllegalArgumentException) {
        reportFileError(e)
      } catch (e: IllegalStateException) {
        reportFileError(e)
      }
    }
  }

  fun uninstallSdLibrary(name: String) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        repo.uninstallSdLibrary(name)
        refreshInstalledLibraries()
        runStaticCheck()
        _uiState.update { it.copy(fileMessage = "Đã gỡ thư viện $name") }
      } catch (e: IOException) {
        reportFileError(e)
      } catch (e: IllegalArgumentException) {
        reportFileError(e)
      } catch (e: IllegalStateException) {
        reportFileError(e)
      }
    }
  }

  fun exportSdLibrary(library: SdLibraryInfo, uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val bytes = SdLibraryBuildService.build(library)
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
          ?.use { it.write(bytes) }
          ?: throw IOException("Không thể ghi gói thư viện vào vị trí đã chọn.")
        _uiState.update { it.copy(fileMessage = "Đã tạo thư viện ${library.name} ${library.version}") }
      } catch (e: IOException) {
        reportFileError(e)
      } catch (e: SecurityException) {
        reportFileError(e)
      } catch (e: IllegalArgumentException) {
        reportFileError(e)
      } catch (e: IllegalStateException) {
        reportFileError(e)
      }
    }
  }

  private fun refreshInstalledLibraries() {
    val libraries = repo.installedSdLibraries()
    installedLibrarySourceFiles = libraries.flatMap { it.sourceFiles.entries }
      .associateTo(LinkedHashMap()) { it.key to it.value }
    _uiState.update { it.copy(installedLibraries = libraries) }
  }

  fun clearFileMessage() {
    _uiState.update { it.copy(fileMessage = null) }
  }

  fun importSourceFile(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val name = queryDisplayName(uri)
        val contents = getApplication<Application>().contentResolver.openInputStream(uri)
          ?.use { it.readBytes().toString(Charsets.UTF_8) }
          ?: throw IOException("Không thể mở tệp đã chọn.")
        val importedName = repo.importSourceFile(name, contents)
        val current = _uiState.value
        val updatedFiles = current.files.toMutableMap().apply { put(importedName, contents) }
        val nextEntry = if (current.files.isEmpty()) importedName else current.entryFile
        if (nextEntry != current.entryFile) repo.setEntryFile(nextEntry)
        _uiState.update {
          it.copy(
            files = updatedFiles,
            activeFile = importedName,
            entryFile = nextEntry,
            editorValue = TextFieldValue(contents),
            canUndo = false,
            canRedo = false,
            fileMessage = "Đã nhập $importedName",
          )
        }
        runStaticCheck()
      } catch (e: IOException) {
        reportFileError(e)
      } catch (e: SecurityException) {
        reportFileError(e)
      } catch (e: IllegalArgumentException) {
        reportFileError(e)
      } catch (e: IllegalStateException) {
        reportFileError(e)
      }
    }
  }

  fun exportActiveSource(uri: Uri) {
    val st = _uiState.value
    val contents = st.files[st.activeFile] ?: run {
      _uiState.update { it.copy(fileMessage = "Không tìm thấy mã nguồn đang mở.") }
      return
    }
    writeDocument(uri, contents.toByteArray(Charsets.UTF_8), "Đã xuất ${st.activeFile}")
  }

  fun exportActiveBytecode(uri: Uri) {
    val st = _uiState.value
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val bytes = SdBytecodeCompiler.compile(st.entryFile, compilationFiles(st.files))
        writeDocument(uri, bytes, "Đã xuất bytecode cho ${st.entryFile}")
      } catch (e: SdBytecodeCompileException) {
        reportFileError(e)
      } catch (e: IOException) {
        reportFileError(e)
      } catch (e: SecurityException) {
        reportFileError(e)
      } catch (e: IllegalArgumentException) {
        reportFileError(e)
      }
    }
  }

  fun createDataFile(rawName: String) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        repo.writeDataFile(rawName, byteArrayOf())
        refreshStorageFiles()
        _uiState.update { it.copy(fileMessage = "Đã tạo tệp dữ liệu $rawName") }
      } catch (e: IOException) {
        reportFileError(e)
      } catch (e: IllegalArgumentException) {
        reportFileError(e)
      } catch (e: IllegalStateException) {
        reportFileError(e)
      }
    }
  }

  fun importDataFile(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val name = queryDisplayName(uri)
        val contents = getApplication<Application>().contentResolver.openInputStream(uri)
          ?.use { it.readBytes() }
          ?: throw IOException("Không thể mở tệp đã chọn.")
        repo.writeDataFile(name, contents)
        refreshStorageFiles()
        _uiState.update { it.copy(fileMessage = "Đã nhập dữ liệu $name") }
      } catch (e: IOException) {
        reportFileError(e)
      } catch (e: SecurityException) {
        reportFileError(e)
      } catch (e: IllegalArgumentException) {
        reportFileError(e)
      } catch (e: IllegalStateException) {
        reportFileError(e)
      }
    }
  }

  fun exportDataFile(fileName: String, uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        writeDocument(uri, repo.readDataFile(fileName), "Đã xuất dữ liệu $fileName")
      } catch (e: IOException) {
        reportFileError(e)
      } catch (e: SecurityException) {
        reportFileError(e)
      } catch (e: IllegalArgumentException) {
        reportFileError(e)
      }
    }
  }

  fun deleteDataFile(fileName: String) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        repo.deleteDataFile(fileName)
        refreshStorageFiles()
        _uiState.update { it.copy(fileMessage = "Đã xóa tệp dữ liệu $fileName") }
      } catch (e: IOException) {
        reportFileError(e)
      } catch (e: IllegalArgumentException) {
        reportFileError(e)
      } catch (e: IllegalStateException) {
        reportFileError(e)
      }
    }
  }

  private fun refreshStorageFiles() {
    val dataFiles = repo.listDataFiles()
    _uiState.update { it.copy(dataFiles = dataFiles, storagePath = repo.storageDirectoryPath()) }
  }

  private fun queryDisplayName(uri: Uri): String {
    val resolver = getApplication<Application>().contentResolver
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameColumn >= 0) cursor.getString(nameColumn)?.let { return it }
      }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
      ?: throw IOException("Không lấy được tên tệp đã chọn.")
  }

  private fun writeDocument(uri: Uri, contents: ByteArray, successMessage: String) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
          ?.use { it.write(contents) }
          ?: throw IOException("Không thể ghi vào vị trí đã chọn.")
        _uiState.update { it.copy(fileMessage = successMessage) }
      } catch (e: IOException) {
        reportFileError(e)
      } catch (e: SecurityException) {
        reportFileError(e)
      }
    }
  }

  private fun reportFileError(error: Exception) {
    _uiState.update {
      it.copy(fileMessage = "Thao tác tệp thất bại: ${error.message ?: error.javaClass.simpleName}")
    }
  }

  fun toggleTheme() {
    val next = !_uiState.value.preferences.darkTheme
    viewModelScope.launch { repo.setDarkTheme(next) }
  }

  fun adjustFontSize(delta: Int) {
    val next = (_uiState.value.preferences.fontSizeSp + delta).coerceIn(11, 22)
    viewModelScope.launch { repo.setFontSize(next) }
  }

  fun jumpToProblem(diag: SdDiagnostic) {
    val targetFile = diag.fileName
    if (targetFile != null && targetFile in _uiState.value.files && targetFile != _uiState.value.activeFile) {
      switchFile(targetFile)
    }
    val text = _uiState.value.editorValue.text
    val offset = lineColToOffset(text, diag.line, diag.col)
    _uiState.update {
      it.copy(
        editorValue = it.editorValue.copy(selection = TextRange(offset)),
        scrollToLineRequest = diag.line to System.nanoTime(),
      )
    }
  }

  private fun lineColToOffset(text: String, targetLine: Int, targetCol: Int): Int {
    var line = 1
    var col = 1
    for (i in text.indices) {
      if (line == targetLine && col >= targetCol) return i
      if (text[i] == '\n') {
        if (line == targetLine) return i
        line++
        col = 1
      } else {
        col++
      }
    }
    return text.length
  }

  private fun scheduleLiveCheck() {
    liveCheckJob?.cancel()
    liveCheckJob = viewModelScope.launch {
      delay(300)
      runStaticCheck()
    }
  }

  private fun compilationFiles(projectFiles: Map<String, String>): Map<String, String> =
    LinkedHashMap<String, String>().apply {
      putAll(installedLibrarySourceFiles)
      putAll(projectFiles)
    }

  fun runStaticCheck() {
    val generation = liveCheckGeneration.incrementAndGet()
    liveCheckJob?.cancel()
    val st = _uiState.value
    liveCheckJob = viewModelScope.launch(Dispatchers.Default) {
      val res = synchronized(liveCheckLock) {
        if (liveCheckGeneration.get() != generation) {
          null
        } else {
          SdIdeEngine.compileAndCheck(
            st.entryFile,
            compilationFiles(st.files),
            includeCSource = st.showCViewer,
          )
        }
      }
      if (res != null && liveCheckGeneration.get() == generation) {
        _uiState.update { current ->
          if (current.entryFile != st.entryFile || current.files != st.files ||
            current.showCViewer != st.showCViewer
          ) {
            current
          } else {
            current.copy(
              diagnostics = res.diagnostics,
              cSourceCode = res.cSourceCode,
            )
          }
        }
      }
    }
  }

  fun runProgram(
    startInDebugStep: Boolean = false,
    entryFileOverride: String? = null,
    terminalCommand: String? = null,
  ) {
    if (_uiState.value.isRunning) {
      stopExecution()
    }
    val st = _uiState.value
    val entryFile = entryFileOverride?.takeIf { it in st.files } ?: st.entryFile
    val projectFilesSnapshot = compilationFiles(st.files)
    val initialStdinLines = st.stdinText
      .split('\n')
      .map { it.trimEnd('\r') }
      .dropLastWhile { it.isEmpty() }

    val stdinQueue = LinkedBlockingQueue<String>(initialStdinLines.ifEmpty { emptyList() })
    activeInteractiveStdinQueue = stdinQueue

    val commandEcho = terminalCommand?.let { "sd> $it\n" }.orEmpty()
    val liveOutput = StringBuilder(commandEcho)
    var publishedOutputLength = 0
    var lastOutputPublishNanos = System.nanoTime()
    fun publishLiveOutput(force: Boolean = false) {
      val pendingChars = liveOutput.length - publishedOutputLength
      if (pendingChars == 0) return
      val now = System.nanoTime()
      if (!force &&
        pendingChars < LIVE_OUTPUT_BATCH_CHARS &&
        now - lastOutputPublishNanos < LIVE_OUTPUT_BATCH_INTERVAL_NANOS
      ) {
        return
      }

      val snapshot = liveOutput.toString()
      publishedOutputLength = snapshot.length
      lastOutputPublishNanos = now
      _uiState.update { it.copy(consoleOutput = snapshot) }
    }

    val stdinProvider = object : StdinProvider {
      override fun readNextLine(promptHint: String): String? {
        val prefilled = stdinQueue.poll()
        if (prefilled != null) return prefilled
        // Pause and wait interactively from user UI
        publishLiveOutput(force = true)
        _uiState.update {
          it.copy(
            waitingForStdinPrompt = promptHint,
            activeTab = WorkbenchTab.CONSOLE,
          )
        }

        return try {
          val line = stdinQueue.take()
          _uiState.update { it.copy(waitingForStdinPrompt = null) }
          line
        } catch (_: InterruptedException) {
          _uiState.update { it.copy(waitingForStdinPrompt = null) }
          null
        }
      }
    }

    val controller = DebugController(
      initialStepMode = startInDebugStep,
      breakpoints = st.breakpoints,
      onPausedCallback = { snap ->
        _uiState.update {
          it.copy(
            isPausedInDebug = true,
            debugLine = snap.line,
            debugSnapshot = snap,
            activeTab = WorkbenchTab.DEBUGGER,
          )
        }
      },
      onOutputCallback = { chunk ->
        liveOutput.append(chunk)
        publishLiveOutput()
      },
    )
    activeDebugController = controller

    _uiState.update {
      it.copy(
        isRunning = true,
        isPausedInDebug = false,
        debugLine = null,
        waitingForStdinPrompt = null,
        consoleOutput = commandEcho,
        lastExitCode = null,
        lastRunSucceeded = null,
        drawCommands = emptyList(),
        activeTab = if (startInDebugStep) WorkbenchTab.DEBUGGER else WorkbenchTab.CONSOLE,
      )
    }

    runJob = viewModelScope.launch(Dispatchers.IO) {
      val result = SdIdeEngine.runProgram(
        mainFileName = entryFile,
        projectFiles = projectFilesSnapshot,
        stdinProvider = stdinProvider,
        fileBridge = repo.createFileBridge(),
        debugController = controller,
        includeCSource = false,
      )

      val errorLogs = result.diagnostics
        .filter { it.severity == DiagnosticSeverity.ERROR }
        .joinToString("\n") { it.format() }

      val finalConsole = buildString {
        append(commandEcho)
        append(result.output)
        if (errorLogs.isNotEmpty()) {
          if (isNotEmpty() && !endsWith('\n')) append('\n')
          append(errorLogs).append('\n')
        }
        if (result.terminatedByUser) {
          append("[Đã dừng thực thi bởi người dùng]\n")
        }
      }

      val nextTab = when {
        !result.success && result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR } -> WorkbenchTab.PROBLEMS
        result.drawCommands.isNotEmpty() -> WorkbenchTab.DRAW
        else -> _uiState.value.activeTab
      }

      _uiState.update {
        it.copy(
          isRunning = false,
          isPausedInDebug = false,
          debugLine = null,
          waitingForStdinPrompt = null,
          diagnostics = result.diagnostics,
          consoleOutput = finalConsole,
          lastExitCode = if (result.success) result.exitCode else null,
          lastRunSucceeded = result.success,
          debugSnapshot = result.finalSnapshot ?: it.debugSnapshot,
          cSourceCode = result.cSourceCode ?: it.cSourceCode,
          drawCommands = result.drawCommands,
          activeTab = nextTab,
        )
      }
    }
  }

  fun submitInteractiveStdin(inputLine: String) {
    _uiState.update {
      it.copy(consoleOutput = it.consoleOutput + "> $inputLine\n")
    }
    activeInteractiveStdinQueue?.offer(inputLine)
  }

  fun debugStepInto() {
    _uiState.update { it.copy(isPausedInDebug = false) }
    activeDebugController?.sendCommand(DebugCommand.STEP_INTO)
  }

  fun debugStepOver() {
    _uiState.update { it.copy(isPausedInDebug = false) }
    activeDebugController?.sendCommand(DebugCommand.STEP_OVER)
  }

  fun debugContinue() {
    _uiState.update { it.copy(isPausedInDebug = false) }
    activeDebugController?.sendCommand(DebugCommand.CONTINUE)
  }

  fun stopExecution() {
    activeDebugController?.sendCommand(DebugCommand.STOP)
    runJob?.cancel()
    activeInteractiveStdinQueue?.clear()
    _uiState.update {
      it.copy(
        isRunning = false,
        isPausedInDebug = false,
        debugLine = null,
        waitingForStdinPrompt = null,
      )
    }
  }

  fun clearConsole() {
    _uiState.update { it.copy(consoleOutput = "") }
  }

  fun executeTerminalCommand(rawCommand: String) {
    val input = rawCommand.trim()
    if (input.isEmpty()) return
    val parts = input.split(Regex("\\s+"))
    val command = parts.first().lowercase()
    val argument = parts.getOrNull(1)

    when (command) {
      "clear" -> clearConsole()
      "help" -> appendTerminalText(
        input,
        "Lệnh cục bộ: help, clear, files, open <tệp>, run [tệp], check, libs, pwd, stop.\n" +
          "Terminal này điều khiển SD & SDPP; không thực thi lệnh shell của Android.\n",
      )
      "files" -> appendTerminalText(
        input,
        _uiState.value.files.keys.sorted().joinToString(separator = "\n", postfix = "\n").ifEmpty { "(dự án trống)\n" },
      )
      "open" -> {
        val file = argument
        if (file == null || file !in _uiState.value.files) {
          appendTerminalText(input, "Không tìm thấy tệp. Dùng files để xem các tệp trong dự án.\n")
        } else {
          switchFile(file)
          appendTerminalText(input, "Đã mở $file.\n")
        }
      }
      "run" -> {
        if (_uiState.value.isRunning) {
          appendTerminalText(input, "Đang có chương trình chạy; dùng stop trước.\n")
        } else if (argument != null && argument !in _uiState.value.files) {
          appendTerminalText(input, "Không tìm thấy tệp '$argument'. Dùng files để xem các tệp trong dự án.\n")
        } else {
          runProgram(entryFileOverride = argument, terminalCommand = input)
        }
      }
      "check" -> {
        runStaticCheck()
        appendTerminalText(input, "Đã kiểm tra mã nguồn; xem tab Vấn đề để đọc chẩn đoán.\n")
      }
      "libs" -> {
        val libraries = _uiState.value.installedLibraries
          .joinToString(separator = "\n") { "${it.name} ${it.version}" }
        appendTerminalText(input, libraries.ifEmpty { "Chưa cài thư viện ngoài.\n" })
      }
      "pwd" -> appendTerminalText(input, "${repo.storageDirectoryPath()}\n")
      "stop" -> {
        stopExecution()
        appendTerminalText(input, "Đã gửi yêu cầu dừng.\n")
      }
      else -> appendTerminalText(input, "Không có lệnh '$command'. Gõ help để xem danh sách.\n")
    }
  }

  private fun appendTerminalText(command: String, response: String) {
    _uiState.update { state ->
      val combined = "${state.consoleOutput}sd> $command\n$response"
      val bounded = if (combined.length > 400_000) combined.takeLast(400_000) else combined
      state.copy(consoleOutput = bounded)
    }
  }
}
