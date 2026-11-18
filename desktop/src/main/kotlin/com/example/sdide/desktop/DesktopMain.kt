package com.example.sdide.desktop

import com.example.sdide.data.CurriculumExamples
import com.example.sdide.data.SdLibraryCatalog
import com.example.sdide.data.SdLibraryInfo
import com.example.sdide.data.SdLibraryPackage
import com.example.sdide.engine.DebugCommand
import com.example.sdide.engine.DebugController
import com.example.sdide.engine.DebugSnapshot
import com.example.sdide.engine.DiagnosticSeverity
import com.example.sdide.engine.SdBytecodeCompileException
import com.example.sdide.engine.SdBytecodeCompiler
import com.example.sdide.engine.SdIdeEngine
import com.example.sdide.engine.StdinProvider
import com.example.sdide.engine.WorkspaceFileBridge
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.prefs.Preferences
import java.util.concurrent.Executors
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter

fun main() {
  SwingUtilities.invokeLater {
    SdIdeDesktop().isVisible = true
  }
}

private class SdIdeDesktop : JFrame("Sd IDE") {
  private val editor = JTextArea()
  private val output = JTextArea()
  private val filePicker = JComboBox<String>()
  private val status = JLabel(" ")
  private val worker = Executors.newSingleThreadExecutor()
  private val preferences = Preferences.userNodeForPackage(SdIdeDesktop::class.java)
  private val runButton = JButton("Chạy")
  private val debugButton = JButton("Gỡ lỗi")
  private val stopButton = JButton("Dừng")
  private val stepIntoButton = JButton("Vào bước")
  private val stepOverButton = JButton("Qua bước")
  private val continueButton = JButton("Tiếp tục")
  private var projectDirectory = File(System.getProperty("user.home"), "SdIDE")
  private var activeFile: String? = null
  private var loadingFile = false
  private var activeDebugController: DebugController? = null
  private val breakpoints = linkedSetOf<Int>()
  private var editorFontSize = preferences.getInt("fontSize", 14).coerceIn(11, 22)
  private var darkTheme = preferences.getBoolean("darkTheme", true)
  private val themeButton = JButton(if (darkTheme) "Sáng" else "Tối")

  init {
    defaultCloseOperation = DO_NOTHING_ON_CLOSE
    minimumSize = Dimension(820, 560)
    setSize(1100, 760)
    setLocationRelativeTo(null)

    editor.font = Font(Font.MONOSPACED, Font.PLAIN, 14)
    editor.tabSize = 4
    output.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
    output.isEditable = false
    applyEditorPreferences()

    val toolbar = JPanel(GridLayout(3, 1, 0, 2)).apply {
      add(JPanel(FlowLayout(FlowLayout.LEADING)).apply {
        add(button("Mở dự án", ::chooseProject))
        add(button("Mở tệp", ::chooseSourceFile))
        add(button("Nhập mã", ::importSourceFile))
        add(button("Xuất mã", ::exportSourceFile))
        add(button("Tệp mới", ::createSourceFile))
        add(button("Xóa tệp", ::deleteSourceFile))
        add(button("Lưu") { saveCurrentFile() })
        add(filePicker)
      })
      add(JPanel(FlowLayout(FlowLayout.LEADING)).apply {
        add(runButton.apply { addActionListener { runProgram() } })
        add(debugButton.apply { addActionListener { runProgram(startInDebugStep = true) } })
        add(stopButton.apply { isEnabled = false; addActionListener { stopExecution() } })
        add(button("Đặt BP", ::toggleBreakpointAtCaret))
        add(stepIntoButton.apply { isEnabled = false; addActionListener { sendDebugCommand(DebugCommand.STEP_INTO) } })
        add(stepOverButton.apply { isEnabled = false; addActionListener { sendDebugCommand(DebugCommand.STEP_OVER) } })
        add(continueButton.apply { isEnabled = false; addActionListener { sendDebugCommand(DebugCommand.CONTINUE) } })
        add(button("Xuất bytecode", ::exportBytecode))
      })
      add(JPanel(FlowLayout(FlowLayout.LEADING)).apply {
        add(button("Thư viện", ::manageLibraries))
        add(button("Tệp dữ liệu", ::manageDataFiles))
        add(button("Giáo trình", ::showCurriculum))
        add(button("Xem C99", ::showCSource))
        add(button("AOT qua C99", ::compileNative))
        add(button("A−", ::decreaseFontSize))
        add(button("A+", ::increaseFontSize))
        add(themeButton.apply { addActionListener { toggleTheme() } })
      })
    }

    val panes = JSplitPane(
      JSplitPane.VERTICAL_SPLIT,
      JScrollPane(editor),
      JScrollPane(output),
    ).apply {
      resizeWeight = 0.72
      dividerLocation = 500
    }

    contentPane.layout = BorderLayout()
    contentPane.add(toolbar, BorderLayout.NORTH)
    contentPane.add(panes, BorderLayout.CENTER)
    contentPane.add(status, BorderLayout.SOUTH)

    filePicker.addActionListener {
      if (!loadingFile) {
        switchToFile(filePicker.selectedItem as? String ?: return@addActionListener)
      }
    }
    editor.document.addDocumentListener(object : DocumentListener {
      override fun insertUpdate(e: DocumentEvent) = onEditorChanged()
      override fun removeUpdate(e: DocumentEvent) = onEditorChanged()
      override fun changedUpdate(e: DocumentEvent) = onEditorChanged()
    })
    addWindowListener(object : WindowAdapter() {
      override fun windowClosing(e: WindowEvent) {
        saveCurrentFile()
        worker.shutdown()
        dispose()
      }
    })

    loadProject(projectDirectory)
  }

  private fun button(label: String, action: () -> Unit) =
    JButton(label).apply { addActionListener { action() } }

  private fun chooseProject() {
    val chooser = JFileChooser(projectDirectory).apply {
      fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
      dialogTitle = "Chọn thư mục dự án Sd"
    }
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      loadProject(chooser.selectedFile)
    }
  }

  private fun chooseSourceFile() {
    val chooser = JFileChooser(projectDirectory).apply {
      dialogTitle = "Mở tệp mã nguồn Sd"
      fileFilter = FileNameExtensionFilter("Mã nguồn Sd (*.sd, *.sdpp, *.sdall, *.txt)", "sd", "sdpp", "sdall", "txt")
    }
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      loadProject(chooser.selectedFile.parentFile, chooser.selectedFile.name)
    }
  }

  private fun importSourceFile() {
    if (!saveCurrentFile()) return
    val chooser = JFileChooser(projectDirectory).apply {
      dialogTitle = "Nhập mã nguồn vào dự án"
      fileFilter = FileNameExtensionFilter("Mã nguồn Sd (*.sd, *.sdpp, *.sdall, *.txt)", "sd", "sdpp", "sdall", "txt")
    }
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
    val source = chooser.selectedFile
    if (!isSourceFile(source.name)) {
      showError("Chỉ nhập tệp .sd, .sdpp, .sdall hoặc .txt.")
      return
    }
    val target = File(projectDirectory, source.name)
    if (target.exists() &&
      JOptionPane.showConfirmDialog(
        this,
        "Tệp '${source.name}' đã tồn tại. Ghi đè?",
        "Xác nhận nhập mã nguồn",
        JOptionPane.YES_NO_OPTION,
      ) != JOptionPane.YES_OPTION
    ) {
      return
    }
    try {
      Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      loadProject(projectDirectory, source.name)
    } catch (e: IOException) {
      showError("Không thể nhập '${source.name}': ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private fun exportSourceFile() {
    val fileName = activeFile ?: return
    if (!saveCurrentFile()) return
    val chooser = JFileChooser(projectDirectory).apply {
      dialogTitle = "Xuất mã nguồn"
      fileFilter = FileNameExtensionFilter("Mã nguồn Sd", "sd", "sdpp", "sdall", "txt")
      selectedFile = File(projectDirectory, fileName)
    }
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
    try {
      if (!confirmOverwrite(chooser.selectedFile, "Xuất mã nguồn")) return
      val contents = File(projectDirectory, fileName).readBytes()
      writeAtomically(chooser.selectedFile, contents)
      status.text = "Đã xuất mã nguồn: ${chooser.selectedFile.absolutePath}"
    } catch (e: IOException) {
      showError("Không thể xuất mã nguồn: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private fun exportBytecode() {
    val fileName = activeFile ?: return
    if (!saveCurrentFile()) return
    val extension = if (fileName.endsWith(".sdpp", ignoreCase = true)) ".sdppbin" else ".sdbin"
    val baseName = fileName.substringBeforeLast('.', fileName)
    val chooser = JFileChooser(projectDirectory).apply {
      dialogTitle = "Xuất bytecode SdIDE"
      selectedFile = File(projectDirectory, "$baseName$extension")
    }
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
    try {
      if (!confirmOverwrite(chooser.selectedFile, "Xuất bytecode")) return
      val bytes = SdBytecodeCompiler.compile(fileName, compilationSources())
      writeAtomically(chooser.selectedFile, bytes)
      status.text = "Đã xuất bytecode: ${chooser.selectedFile.absolutePath}"
    } catch (e: SdBytecodeCompileException) {
      showError(e.message ?: "Không thể biên dịch bytecode.")
    } catch (e: IOException) {
      showError("Không thể ghi bytecode: ${e.message ?: e.javaClass.simpleName}")
    } catch (e: IllegalArgumentException) {
      showError("Không thể đọc thư viện đã cài: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private fun createSourceFile() {
    val name = JOptionPane.showInputDialog(this, "Tên tệp mới (.sd hoặc .sdpp):")?.trim().orEmpty()
    if (name.isBlank()) return
    val safeName = File(name).name
    if (safeName != name || !isSourceFile(safeName)) {
      showError("Tên tệp phải kết thúc bằng .sd, .sdpp, .sdall hoặc .txt.")
      return
    }
    if (!projectDirectory.isDirectory && !projectDirectory.mkdirs()) {
      showError("Không thể tạo thư mục dự án: ${projectDirectory.absolutePath}")
      return
    }
    val file = File(projectDirectory, safeName)
    if (file.exists()) {
      showError("Tệp '$safeName' đã tồn tại.")
      return
    }
    file.writeText("")
    loadProject(projectDirectory, safeName)
  }

  private fun deleteSourceFile() {
    val fileName = activeFile ?: return
    val sourceFiles = projectDirectory.listFiles()
      ?.filter { it.isFile && isSourceFile(it.name) }
      .orEmpty()
    if (sourceFiles.size <= 1) {
      showError("Dự án phải giữ ít nhất một tệp mã nguồn.")
      return
    }
    if (
      JOptionPane.showConfirmDialog(
        this,
        "Xóa '$fileName' khỏi dự án?",
        "Xóa tệp mã nguồn",
        JOptionPane.YES_NO_OPTION,
      ) != JOptionPane.YES_OPTION
    ) {
      return
    }
    val file = File(projectDirectory, fileName)
    if (!file.delete()) {
      showError("Không thể xóa '$fileName'.")
      return
    }
    loadProject(projectDirectory)
  }

  private fun loadProject(directory: File, preferredFile: String? = null) {
    if (!saveCurrentFile()) return
    if (!directory.exists() && !directory.mkdirs()) {
      showError("Không thể tạo thư mục dự án: ${directory.absolutePath}")
      return
    }
    if (!directory.isDirectory) {
      showError("Đường dẫn dự án không phải thư mục.")
      return
    }

    projectDirectory = directory.canonicalFile
    val sourceFiles = projectDirectory.listFiles()
      ?.filter { it.isFile && isSourceFile(it.name) }
      ?.sortedBy { it.name.lowercase() }
      .orEmpty()

    if (sourceFiles.isEmpty()) {
      val sample = CurriculumExamples.samples.first()
      for ((name, source) in sample.files) File(projectDirectory, name).writeText(source)
      File(projectDirectory, ".sdide_entry").writeText(sample.entryFile)
    }

    val names = projectDirectory.listFiles()
      ?.filter { it.isFile && isSourceFile(it.name) }
      ?.map { it.name }
      ?.sortedBy { it.lowercase() }
      .orEmpty()
    val entry = File(projectDirectory, ".sdide_entry").takeIf { it.isFile }?.readText()?.trim()
    val selected = preferredFile?.takeIf { it in names }
      ?: entry?.takeIf { it in names }
      ?: names.firstOrNull()
    if (selected == null) {
      showError("Thư mục dự án chưa có tệp mã nguồn Sd.")
      return
    }

    loadingFile = true
    filePicker.removeAllItems()
    names.forEach(filePicker::addItem)
    filePicker.selectedItem = selected
    loadingFile = false
    activeFile = null
    switchToFile(selected)
    title = "Sd IDE - ${projectDirectory.name}"
    status.text = "Dự án: ${projectDirectory.absolutePath}"
  }

  private fun switchToFile(fileName: String) {
    if (fileName == activeFile) return
    if (!saveCurrentFile()) return
    val file = File(projectDirectory, fileName)
    if (!file.isFile) {
      showError("Không thể mở tệp: ${file.absolutePath}")
      return
    }
    loadingFile = true
    editor.text = file.readText()
    activeFile = fileName
    loadingFile = false
    try {
      File(projectDirectory, ".sdide_entry").writeText(fileName)
    } catch (e: Exception) {
      showError("Không thể lưu tệp chạy chính: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private fun onEditorChanged() {
    if (loadingFile) return
    val fileName = activeFile ?: return
    try {
      File(projectDirectory, fileName).writeText(editor.text)
      status.text = "Đã tự động lưu $fileName"
    } catch (e: Exception) {
      status.text = "Lỗi lưu tệp: ${e.message ?: e.javaClass.simpleName}"
    }
  }

  private fun saveCurrentFile(): Boolean {
    val fileName = activeFile ?: return true
    try {
      File(projectDirectory, fileName).writeText(editor.text)
      return true
    } catch (e: Exception) {
      showError("Không thể lưu '$fileName': ${e.message ?: e.javaClass.simpleName}")
      return false
    }
  }

  private fun runProgram(startInDebugStep: Boolean = false) {
    if (stopButton.isEnabled) return
    val mainFile = activeFile ?: return
    if (!saveCurrentFile()) return
    val runDirectory = projectDirectory
    val sources = try {
      compilationSources()
    } catch (e: IOException) {
      showError("Không thể đọc thư viện đã cài: ${e.message ?: e.javaClass.simpleName}")
      return
    } catch (e: IllegalArgumentException) {
      showError("Thư viện đã cài không hợp lệ: ${e.message ?: e.javaClass.simpleName}")
      return
    }
    val controller = DebugController(
      initialStepMode = startInDebugStep,
      breakpoints = breakpoints.toSet(),
      onPausedCallback = { snapshot ->
        SwingUtilities.invokeLater { showDebugSnapshot(snapshot) }
      },
    )
    activeDebugController = controller
    updateRunControls(running = true, paused = false)
    output.text = if (startInDebugStep) "Đang gỡ lỗi $mainFile...\n" else "Đang chạy $mainFile...\n"
    worker.submit {
      try {
        val result = SdIdeEngine.runProgram(
          mainFileName = mainFile,
          projectFiles = sources,
          stdinProvider = object : StdinProvider {
            override fun readNextLine(promptHint: String): String? {
              val answer = arrayOfNulls<String>(1)
              SwingUtilities.invokeAndWait {
                answer[0] = JOptionPane.showInputDialog(this@SdIdeDesktop, promptHint, "Nhập dữ liệu", JOptionPane.QUESTION_MESSAGE)
              }
              return answer[0]
            }
          },
          fileBridge = projectFileBridge(runDirectory),
          debugController = controller,
          includeCSource = false,
        )
        val text = buildString {
          append(result.output)
          if (result.output.isNotEmpty() && !result.output.endsWith('\n')) append('\n')
          result.diagnostics.forEach { append(it.format()).append('\n') }
          append(
            when {
              result.terminatedByUser -> "Đã dừng chương trình."
              result.success -> "Hoàn tất (mã ${result.exitCode})."
              else -> "Chương trình thất bại."
            },
          )
        }
        SwingUtilities.invokeLater {
          output.text = text
          status.text = when {
            result.terminatedByUser -> "Đã dừng"
            result.success -> "Chạy thành công"
            else -> "Chạy thất bại — xem chẩn đoán"
          }
          activeDebugController = null
          updateRunControls(running = false, paused = false)
        }
      } catch (e: Exception) {
        SwingUtilities.invokeLater {
          output.text = "Không thể chạy chương trình: ${e.message ?: e.javaClass.simpleName}"
          status.text = "Lỗi thực thi"
          activeDebugController = null
          updateRunControls(running = false, paused = false)
        }
      }
    }
  }

  private fun stopExecution() {
    activeDebugController?.sendCommand(DebugCommand.STOP)
    status.text = "Đang dừng chương trình..."
  }

  private fun sendDebugCommand(command: DebugCommand) {
    activeDebugController?.sendCommand(command)
    updateRunControls(running = true, paused = false)
  }

  private fun updateRunControls(running: Boolean, paused: Boolean) {
    runButton.isEnabled = !running
    debugButton.isEnabled = !running
    stopButton.isEnabled = running
    stepIntoButton.isEnabled = running && paused
    stepOverButton.isEnabled = running && paused
    continueButton.isEnabled = running && paused
  }

  private fun showDebugSnapshot(snapshot: DebugSnapshot) {
    if (snapshot.fileName == null || snapshot.fileName == activeFile) {
      val lineIndex = (snapshot.line - 1).coerceIn(0, editor.lineCount - 1)
      editor.caretPosition = editor.getLineStartOffset(lineIndex)
    }
    val frameText = snapshot.callStack.joinToString("\n") { frame ->
      val locals = frame.locals.joinToString("\n") { "  ${it.type} ${it.name} = ${it.value}" }
      "${frame.name} — dòng ${frame.line}\n$locals"
    }
    val globals = snapshot.globals.joinToString("\n") { "${it.type} ${it.name} = ${it.value}" }
    output.text = buildString {
      appendLine("Tạm dừng tại ${snapshot.fileName ?: activeFile}:${snapshot.line}")
      appendLine(frameText)
      if (globals.isNotBlank()) appendLine("Toàn cục:\n$globals")
      if (snapshot.heapBlocks.isNotEmpty()) {
        appendLine("Heap:")
        snapshot.heapBlocks.forEach { appendLine("  ${it.addressHex} (${it.sizeSlots} ô): ${it.preview}") }
      }
      if (snapshot.registers.isNotEmpty()) {
        appendLine("Thanh ghi:")
        snapshot.registers.forEach { appendLine("  ${it.name} = ${it.value}") }
      }
      appendLine()
      appendLine("Dùng Vào bước, Qua bước, Tiếp tục hoặc Dừng.")
    }
    status.text = "Đang gỡ lỗi — dòng ${snapshot.line}"
    updateRunControls(running = true, paused = true)
  }

  private fun toggleBreakpointAtCaret() {
    val line = try {
      editor.getLineOfOffset(editor.caretPosition) + 1
    } catch (e: javax.swing.text.BadLocationException) {
      showError("Không thể xác định dòng hiện tại: ${e.message ?: e.javaClass.simpleName}")
      return
    }
    if (!breakpoints.add(line)) breakpoints.remove(line)
    activeDebugController?.breakpoints = breakpoints.toSet()
    status.text = if (line in breakpoints) {
      "Đặt breakpoint dòng $line"
    } else {
      "Đã bỏ breakpoint dòng $line"
    }
  }

  private fun compilationSources(): Map<String, String> {
    val sources = LinkedHashMap<String, String>()
    sources.putAll(DesktopLibraryRepository(File(projectDirectory, ".sdide/libraries")).sourceFiles())
    val projectSources = projectDirectory.listFiles()
      ?.filter { it.isFile && isSourceFile(it.name) }
      ?.sortedBy { it.name.lowercase(Locale.ROOT) }
      ?: throw IOException("Không thể đọc tệp trong dự án: ${projectDirectory.absolutePath}")
    projectSources.forEach { sources[it.name] = it.readText(Charsets.UTF_8) }
    return sources
  }

  private fun writeAtomically(target: File, bytes: ByteArray) {
    val parent = target.absoluteFile.parentFile
      ?: throw IOException("Không xác định được thư mục đầu ra.")
    if (!parent.isDirectory && !parent.mkdirs()) {
      throw IOException("Không thể tạo thư mục đầu ra: ${parent.absolutePath}")
    }
    val temporary = Files.createTempFile(parent.toPath(), ".sdide-", ".tmp")
    try {
      Files.write(temporary, bytes)
      try {
        Files.move(
          temporary,
          target.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun confirmOverwrite(target: File, title: String): Boolean =
    !target.exists() || JOptionPane.showConfirmDialog(
      this,
      "Tệp ${target.absolutePath} đã tồn tại. Ghi đè?",
      title,
      JOptionPane.YES_NO_OPTION,
    ) == JOptionPane.YES_OPTION

  private fun manageLibraries() {
    val repository = DesktopLibraryRepository(File(projectDirectory, ".sdide/libraries"))
    val installed = try {
      repository.installedLibraries()
    } catch (e: IOException) {
      showError("Không thể đọc danh sách thư viện: ${e.message ?: e.javaClass.simpleName}")
      return
    } catch (e: IllegalArgumentException) {
      showError("Gói thư viện đã cài không hợp lệ: ${e.message ?: e.javaClass.simpleName}")
      return
    }
    val installedByLabel = installed.associateBy { "${it.name} ${it.version}" }
    val builtInLabels = SdLibraryCatalog.builtIns.associateBy { "${it.name} — ${it.title}" }
    val options = listOf("Cài gói .sdlib…", "Đóng gói dự án hiện tại…") +
      installedByLabel.keys.map { "Gỡ: $it" } +
      builtInLabels.keys.map { "Tích hợp sẵn: $it" }
    val selected = JOptionPane.showInputDialog(
      this,
      buildString {
        appendLine("Thư viện cài đặt: ${installed.size}")
        appendLine("Thư viện tích hợp sẵn: ${SdLibraryCatalog.builtIns.size}")
        append("Chọn thao tác:")
      },
      "Quản lý thư viện",
      JOptionPane.PLAIN_MESSAGE,
      null,
      options.toTypedArray(),
      options.first(),
    ) as? String ?: return

    when {
      selected == options.first() -> installLibrary(repository)
      selected == options[1] -> buildLibraryPackage()
      selected.startsWith("Gỡ: ") -> {
        val library = installedByLabel[selected.removePrefix("Gỡ: ")] ?: return
        if (JOptionPane.showConfirmDialog(
            this,
            "Gỡ thư viện '${library.name}' khỏi máy này?",
            "Xác nhận gỡ thư viện",
            JOptionPane.YES_NO_OPTION,
          ) != JOptionPane.YES_OPTION
        ) return
        try {
          repository.uninstall(library.name)
          status.text = "Đã gỡ thư viện ${library.name}"
        } catch (e: IOException) {
          showError("Không thể gỡ thư viện: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
          showError("Tên thư viện không hợp lệ: ${e.message ?: e.javaClass.simpleName}")
        }
      }
      selected.startsWith("Tích hợp sẵn: ") -> {
        val library = builtInLabels[selected.removePrefix("Tích hợp sẵn: ")] ?: return
        JOptionPane.showMessageDialog(
          this,
          "${library.importStatement}\n${library.description}",
          "Thư viện ${library.name}",
          JOptionPane.INFORMATION_MESSAGE,
        )
      }
    }
  }

  private fun installLibrary(repository: DesktopLibraryRepository) {
    val chooser = JFileChooser(projectDirectory).apply {
      dialogTitle = "Chọn gói thư viện Sd"
      fileFilter = FileNameExtensionFilter("Gói thư viện Sd (*.sdlib)", "sdlib")
    }
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
    try {
      val bytes = chooser.selectedFile.inputStream().use(SdLibraryPackage::readPackageBytes)
      val library = repository.install(bytes)
      status.text = "Đã cài ${library.name} ${library.version}"
    } catch (e: IOException) {
      showError("Không thể đọc gói thư viện: ${e.message ?: e.javaClass.simpleName}")
    } catch (e: IllegalArgumentException) {
      showError("Không thể cài thư viện: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private fun manageDataFiles() {
    val actions = arrayOf("Tạo tệp", "Nhập tệp", "Xuất tệp", "Xóa tệp", "Đóng")
    while (true) {
      val selected = JOptionPane.showInputDialog(
        this,
        "Quản lý tệp trong thư mục data của dự án.",
        "Tệp dữ liệu",
        JOptionPane.PLAIN_MESSAGE,
        null,
        actions,
        actions.first(),
      ) as? String ?: return
      when (selected) {
        "Tạo tệp" -> createDataFile()
        "Nhập tệp" -> importDataFile()
        "Xuất tệp" -> exportDataFile()
        "Xóa tệp" -> deleteDataFile()
        else -> return
      }
    }
  }

  private fun dataFileNames(): List<String> {
    val directory = File(projectDirectory, "data")
    if (!directory.exists()) return emptyList()
    return directory.listFiles()
      ?.filter { it.isFile }
      ?.map { it.name }
      ?.sortedBy { it.lowercase(Locale.ROOT) }
      ?: throw IOException("Không thể đọc thư mục dữ liệu: ${directory.absolutePath}")
  }

  private fun chooseDataFile(prompt: String): String? {
    val files = try {
      dataFileNames()
    } catch (e: IOException) {
      showError(e.message ?: "Không thể đọc danh sách tệp dữ liệu.")
      return null
    }
    if (files.isEmpty()) {
      showError("Chưa có tệp nào trong thư mục data.")
      return null
    }
    return JOptionPane.showInputDialog(
      this,
      prompt,
      "Chọn tệp dữ liệu",
      JOptionPane.PLAIN_MESSAGE,
      null,
      files.toTypedArray(),
      files.first(),
    ) as? String
  }

  private fun createDataFile() {
    val name = JOptionPane.showInputDialog(this, "Tên tệp trong thư mục data:")?.trim().orEmpty()
    if (name.isBlank()) return
    if (name.contains('/') || name.contains('\\') || name == "." || name == "..") {
      showError("Tên tệp dữ liệu không được chứa đường dẫn.")
      return
    }
    val file = File(File(projectDirectory, "data"), name)
    if (file.exists()) {
      showError("Tệp '$name' đã tồn tại.")
      return
    }
    try {
      writeAtomically(file, ByteArray(0))
      status.text = "Đã tạo data/$name"
    } catch (e: IOException) {
      showError("Không thể tạo tệp dữ liệu: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private fun importDataFile() {
    val chooser = JFileChooser(projectDirectory).apply { dialogTitle = "Nhập tệp vào data" }
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
    val source = chooser.selectedFile
    val target = File(File(projectDirectory, "data"), source.name)
    if (target.exists() && JOptionPane.showConfirmDialog(
        this,
        "Ghi đè data/${source.name}?",
        "Xác nhận nhập tệp",
        JOptionPane.YES_NO_OPTION,
      ) != JOptionPane.YES_OPTION
    ) return
    try {
      writeAtomically(target, source.readBytes())
      status.text = "Đã nhập data/${source.name}"
    } catch (e: IOException) {
      showError("Không thể nhập tệp dữ liệu: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private fun exportDataFile() {
    val name = chooseDataFile("Chọn tệp cần xuất:") ?: return
    val chooser = JFileChooser(projectDirectory).apply {
      dialogTitle = "Xuất tệp dữ liệu"
      selectedFile = File(projectDirectory, name)
    }
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
    try {
      if (!confirmOverwrite(chooser.selectedFile, "Xuất tệp dữ liệu")) return
      writeAtomically(chooser.selectedFile, File(File(projectDirectory, "data"), name).readBytes())
      status.text = "Đã xuất tệp dữ liệu: ${chooser.selectedFile.absolutePath}"
    } catch (e: IOException) {
      showError("Không thể xuất tệp dữ liệu: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private fun deleteDataFile() {
    val name = chooseDataFile("Chọn tệp cần xóa:") ?: return
    if (JOptionPane.showConfirmDialog(
        this,
        "Xóa data/$name?",
        "Xác nhận xóa tệp dữ liệu",
        JOptionPane.YES_NO_OPTION,
      ) != JOptionPane.YES_OPTION
    ) return
    val file = File(File(projectDirectory, "data"), name)
    if (!file.delete()) {
      showError("Không thể xóa data/$name.")
    } else {
      status.text = "Đã xóa data/$name"
    }
  }

  private fun showCurriculum() {
    val samples = CurriculumExamples.samples
    val chooser = JComboBox(samples.map { "${it.chapter} — ${it.title}" }.toTypedArray())
    val details = JTextArea(5, 48).apply {
      isEditable = false
      lineWrap = true
      wrapStyleWord = true
    }
    fun updateDetails() {
      val sample = samples.getOrNull(chooser.selectedIndex) ?: return
      details.text = "${sample.category}\n${sample.description}\nTệp chính: ${sample.entryFile}"
    }
    chooser.addActionListener { updateDetails() }
    updateDetails()
    val panel = JPanel(BorderLayout(8, 8)).apply {
      add(chooser, BorderLayout.NORTH)
      add(JScrollPane(details), BorderLayout.CENTER)
    }
    val choice = JOptionPane.showConfirmDialog(
      this,
      panel,
      "Giáo trình và ví dụ",
      JOptionPane.OK_CANCEL_OPTION,
      JOptionPane.PLAIN_MESSAGE,
    )
    if (choice != JOptionPane.OK_OPTION) return
    val sample = samples.getOrNull(chooser.selectedIndex) ?: return
    if (!saveCurrentFile()) return
    val conflicts = sample.files.keys.filter { File(projectDirectory, it).exists() }
    if (conflicts.isNotEmpty() && JOptionPane.showConfirmDialog(
        this,
        "Ghi đè các tệp trùng tên?\n${conflicts.joinToString("\n")}",
        "Nạp ví dụ giáo trình",
        JOptionPane.YES_NO_OPTION,
      ) != JOptionPane.YES_OPTION
    ) return
    try {
      sample.files.forEach { (name, source) ->
        writeAtomically(File(projectDirectory, name), source.toByteArray(Charsets.UTF_8))
      }
      writeAtomically(File(projectDirectory, ".sdide_entry"), sample.entryFile.toByteArray(Charsets.UTF_8))
      loadProject(projectDirectory, sample.entryFile)
    } catch (e: IOException) {
      showError("Không thể nạp ví dụ: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private fun showCSource() {
    val fileName = activeFile ?: return
    if (!saveCurrentFile()) return
    val sources = try {
      compilationSources()
    } catch (e: IOException) {
      showError("Không thể đọc mã nguồn: ${e.message ?: e.javaClass.simpleName}")
      return
    } catch (e: IllegalArgumentException) {
      showError("Thư viện không hợp lệ: ${e.message ?: e.javaClass.simpleName}")
      return
    }
    output.text = "Đang sinh C99 cho $fileName...\n"
    worker.submit {
      try {
        val compilation = SdIdeEngine.compileAndCheck(fileName, sources)
        val text = if (compilation.hasErrors || compilation.cSourceCode == null) {
          compilation.diagnostics.joinToString("\n") { it.format() }
            .ifBlank { "Không có mã C99 đầu ra." }
        } else {
          buildString {
            appendLine("Mã C99 tham khảo — không phải mã máy tự biên dịch.")
            if (compilation.diagnostics.isNotEmpty()) {
              appendLine(compilation.diagnostics.joinToString("\n") { it.format() })
              appendLine()
            }
            append(compilation.cSourceCode)
          }
        }
        SwingUtilities.invokeLater {
          output.text = text
          status.text = "Đã cập nhật phần xem C99"
        }
      } catch (e: Exception) {
        SwingUtilities.invokeLater {
          output.text = "Không thể sinh mã C99: ${e.message ?: e.javaClass.simpleName}"
          status.text = "Lỗi xem C99"
        }
      }
    }
  }

  private fun applyEditorPreferences() {
    val editorFont = Font(Font.MONOSPACED, Font.PLAIN, editorFontSize)
    editor.font = editorFont
    output.font = editorFont.deriveFont((editorFontSize - 1).coerceAtLeast(10).toFloat())
    val background = if (darkTheme) java.awt.Color(0x1E, 0x1E, 0x1E) else java.awt.Color.WHITE
    val foreground = if (darkTheme) java.awt.Color(0xDD, 0xDD, 0xDD) else java.awt.Color(0x22, 0x22, 0x22)
    editor.background = background
    editor.foreground = foreground
    editor.caretColor = foreground
    output.background = background
    output.foreground = foreground
    status.foreground = foreground
  }

  private fun increaseFontSize() {
    editorFontSize = (editorFontSize + 1).coerceAtMost(22)
    preferences.putInt("fontSize", editorFontSize)
    applyEditorPreferences()
  }

  private fun decreaseFontSize() {
    editorFontSize = (editorFontSize - 1).coerceAtLeast(11)
    preferences.putInt("fontSize", editorFontSize)
    applyEditorPreferences()
  }

  private fun toggleTheme() {
    darkTheme = !darkTheme
    preferences.putBoolean("darkTheme", darkTheme)
    themeButton.text = if (darkTheme) "Sáng" else "Tối"
    applyEditorPreferences()
  }

  private fun compileNative() {
    val mainFile = activeFile ?: return
    if (!saveCurrentFile()) return
    val sources = try {
      compilationSources()
    } catch (e: IOException) {
      showError("Không thể đọc mã nguồn dự án/thư viện: ${e.message ?: e.javaClass.simpleName}")
      return
    } catch (e: IllegalArgumentException) {
      showError("Thư viện không hợp lệ: ${e.message ?: e.javaClass.simpleName}")
      return
    }
    val targetName = mainFile.substringBeforeLast('.', mainFile)
    val extension = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else ""
    val executable = File(projectDirectory, "build/native/$targetName$extension")
    if (executable.exists()) {
      val overwrite = JOptionPane.showConfirmDialog(
        this,
        "Tệp ${executable.absolutePath} đã tồn tại. Ghi đè?",
        "Xác nhận AOT qua C99",
        JOptionPane.YES_NO_OPTION,
      )
      if (overwrite != JOptionPane.YES_OPTION) return
    }

    output.text = "Đang kiểm tra, sinh C99 và gọi GCC/Clang cho $mainFile...\n"
    worker.submit {
      try {
        val compilation = SdIdeEngine.compileAndCheck(mainFile, sources)
        if (compilation.unit == null || compilation.hasErrors) {
          val diagnostics = compilation.diagnostics.joinToString("\n") { it.format() }
          SwingUtilities.invokeLater {
            output.text = diagnostics.ifBlank { "Không thể biên dịch mã nguồn Sd." }
            status.text = "Mã nguồn có lỗi — chưa tạo chương trình native"
          }
          return@submit
        }
        if (compilation.externalPrograms.isNotEmpty()) {
          val modules = compilation.externalPrograms.keys.sorted().joinToString(", ")
          throw SdNativeCompileException(
            "Native AOT chưa ghép mã C của mô-đun nguồn đã import: $modules. Hãy dùng interpreter hoặc bỏ import này.",
          )
        }

        val cSource = compilation.cSourceCode
          ?: throw SdNativeCompileException("Không có mã C99 đầu ra để biên dịch.")
        val result = SdNativeCompiler.compile(cSource, executable)
        val warnings = compilation.diagnostics
          .filter { it.severity == DiagnosticSeverity.WARNING }
          .joinToString("\n") { it.format() }
        val text = buildString {
          appendLine("AOT qua C99 thành công.")
          appendLine("Compiler: ${result.compiler}")
          appendLine("Mã C99: ${result.cSource.absolutePath}")
          appendLine("Chương trình: ${result.executable.absolutePath}")
          if (warnings.isNotBlank()) {
            appendLine()
            appendLine(warnings)
          }
          if (result.compilerOutput.isNotBlank()) {
            appendLine()
            append(result.compilerOutput)
          }
        }
        SwingUtilities.invokeLater {
          output.text = text
          status.text = "Đã tạo binary qua C99/GCC"
        }
      } catch (e: SdNativeCompileException) {
        SwingUtilities.invokeLater {
          output.text = e.message ?: "Biên dịch native thất bại."
          status.text = "Biên dịch native thất bại"
        }
      } catch (e: IOException) {
        SwingUtilities.invokeLater {
          output.text = "Không thể đọc/ghi tệp khi biên dịch native: ${e.message ?: e.javaClass.simpleName}"
          status.text = "Lỗi tệp khi biên dịch native"
        }
      }
    }
  }

  private fun buildLibraryPackage() {
    if (!saveCurrentFile()) return
    val sourceFiles = projectDirectory.listFiles()
      ?.filter { it.isFile && (it.name.endsWith(".sd") || it.name.endsWith(".sdpp")) }
      ?.sortedBy { it.name.lowercase(Locale.ROOT) }
      .orEmpty()
    if (sourceFiles.isEmpty()) {
      showError("Dự án cần có ít nhất một tệp .sd hoặc .sdpp để đóng gói.")
      return
    }

    val defaultName = (activeFile?.substringBeforeLast('.') ?: "library")
      .lowercase(Locale.ROOT)
      .replace(Regex("[^a-z0-9_-]"), "-")
      .trim('-', '_')
      .ifBlank { "library" }
      .let { if (it.firstOrNull()?.isLetter() == true) it else "lib-$it" }
    val nameField = JTextField(defaultName)
    val versionField = JTextField("1.0.0")
    val descriptionField = JTextField()
    val entryField = JComboBox(sourceFiles.map { it.name }.toTypedArray()).apply {
      activeFile?.takeIf { selected -> sourceFiles.any { it.name == selected } }?.let {
        selectedItem = it
      }
    }
    val fields = JPanel(GridLayout(0, 2, 8, 6)).apply {
      add(JLabel("Tên thư viện"))
      add(nameField)
      add(JLabel("Phiên bản"))
      add(versionField)
      add(JLabel("Mô tả"))
      add(descriptionField)
      add(JLabel("Tệp entry"))
      add(entryField)
    }
    if (
      JOptionPane.showConfirmDialog(
        this,
        fields,
        "Đóng gói thư viện Sd / Sd++",
        JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.PLAIN_MESSAGE,
      ) != JOptionPane.OK_OPTION
    ) {
      return
    }

    val outputChooser = JFileChooser(projectDirectory).apply {
      dialogTitle = "Lưu gói thư viện"
      fileFilter = FileNameExtensionFilter("Gói thư viện Sd (*.sdlib)", "sdlib")
      selectedFile = File(projectDirectory, "${nameField.text.trim()}.sdlib")
    }
    if (outputChooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
    val selectedOutput = outputChooser.selectedFile
    val outputFile = if (selectedOutput.name.endsWith(".sdlib", ignoreCase = true)) {
      selectedOutput
    } else {
      File(selectedOutput.parentFile, "${selectedOutput.name}.sdlib")
    }
    if (outputFile.exists() &&
      JOptionPane.showConfirmDialog(
        this,
        "Tệp ${outputFile.absolutePath} đã tồn tại. Ghi đè?",
        "Xác nhận tạo gói thư viện",
        JOptionPane.YES_NO_OPTION,
      ) != JOptionPane.YES_OPTION
    ) {
      return
    }

    val libraryName = nameField.text.trim()
    val version = versionField.text.trim()
    val description = descriptionField.text.trim()
    val entryName = entryField.selectedItem as? String ?: return
    output.text = "Đang kiểm tra và đóng gói thư viện $libraryName...\n"
    worker.submit {
      try {
        val library = SdLibraryInfo(
          name = libraryName,
          version = version,
          description = description,
          entryFile = entryName,
          sourceFiles = sourceFiles.associate { it.name to it.readText(Charsets.UTF_8) },
        )
        val packageBytes = SdLibraryBuildService.build(library)
        val outputDirectory = outputFile.absoluteFile.parentFile
          ?: throw IOException("Không xác định được thư mục đầu ra.")
        if (!outputDirectory.isDirectory && !outputDirectory.mkdirs()) {
          throw IOException("Không thể tạo thư mục đầu ra: ${outputDirectory.absolutePath}")
        }
        val temporaryFile = Files.createTempFile(outputDirectory.toPath(), ".sdlib-", ".tmp")
        try {
          Files.write(temporaryFile, packageBytes)
          try {
            Files.move(
              temporaryFile,
              outputFile.toPath(),
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING,
            )
          } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporaryFile, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
          }
        } finally {
          Files.deleteIfExists(temporaryFile)
        }
        SwingUtilities.invokeLater {
          output.text = "Đã tạo thư viện ${library.name} ${library.version}.\n${outputFile.absolutePath}"
          status.text = "Đóng gói thư viện thành công"
        }
      } catch (e: IllegalArgumentException) {
        SwingUtilities.invokeLater {
          showError(e.message ?: "Thông tin gói thư viện không hợp lệ.")
          status.text = "Không thể đóng gói thư viện"
        }
      } catch (e: IOException) {
        SwingUtilities.invokeLater {
          showError("Không thể đọc/ghi gói thư viện: ${e.message ?: e.javaClass.simpleName}")
          status.text = "Lỗi tệp khi đóng gói thư viện"
        }
      } catch (e: SecurityException) {
        SwingUtilities.invokeLater {
          showError("Không đủ quyền truy cập tệp: ${e.message ?: e.javaClass.simpleName}")
          status.text = "Lỗi quyền truy cập khi đóng gói thư viện"
        }
      }
    }
  }

  private fun projectFileBridge(directory: File) = object : WorkspaceFileBridge {
    private val dataDirectory = File(directory, "data")

    override fun writeFile(name: String, content: String) {
      val safeName = safeDataFileName(name)
      if (safeName.isNotBlank()) {
        if (!dataDirectory.exists() && !dataDirectory.mkdirs()) {
          throw IllegalStateException("Không thể tạo thư mục dữ liệu: ${dataDirectory.absolutePath}")
        }
        File(dataDirectory, safeName).writeText(content)
      }
    }

    override fun readFile(name: String): String? {
      val safeName = safeDataFileName(name)
      if (safeName.isBlank()) return null
      val dataFile = File(dataDirectory, safeName)
      if (dataFile.isFile) return dataFile.readText()
      val projectFile = File(directory, safeName)
      return projectFile.takeIf { it.isFile }?.readText()
    }
  }

  private fun safeDataFileName(name: String) =
    name.substringAfterLast('/').substringAfterLast('\\')

  private fun showError(message: String) {
    JOptionPane.showMessageDialog(this, message, "Sd IDE", JOptionPane.ERROR_MESSAGE)
  }

  private fun isSourceFile(name: String) =
    listOf(".sd", ".sdpp", ".sdall", ".txt").any { name.endsWith(it, ignoreCase = true) }
}
