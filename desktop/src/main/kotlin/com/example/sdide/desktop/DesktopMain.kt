package com.example.sdide.desktop

import com.example.sdide.data.CurriculumExamples
import com.example.sdide.engine.SdIdeEngine
import com.example.sdide.engine.StdinProvider
import com.example.sdide.engine.WorkspaceFileBridge
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
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
  private var projectDirectory = File(System.getProperty("user.home"), "SdIDE")
  private var activeFile: String? = null
  private var loadingFile = false

  init {
    defaultCloseOperation = DO_NOTHING_ON_CLOSE
    minimumSize = Dimension(820, 560)
    setSize(1100, 760)
    setLocationRelativeTo(null)

    editor.font = Font(Font.MONOSPACED, Font.PLAIN, 14)
    editor.tabSize = 4
    output.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
    output.isEditable = false

    val toolbar = JPanel(FlowLayout(FlowLayout.LEADING)).apply {
      add(button("Mở dự án", ::chooseProject))
      add(button("Mở tệp", ::chooseSourceFile))
      add(button("Tệp mới", ::createSourceFile))
      add(button("Lưu") { saveCurrentFile() })
      add(button("Chạy", ::runProgram))
      add(filePicker)
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

  private fun runProgram() {
    val mainFile = activeFile ?: return
    if (!saveCurrentFile()) return
    val runDirectory = projectDirectory
    val sources = projectDirectory.listFiles()
      ?.filter { it.isFile && isSourceFile(it.name) }
      ?.associate { it.name to it.readText() }
      .orEmpty()
    output.text = "Đang chạy $mainFile...\n"
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
        )
        val text = buildString {
          append(result.output)
          if (result.output.isNotEmpty() && !result.output.endsWith('\n')) append('\n')
          result.diagnostics.forEach { append(it.format()).append('\n') }
          append(if (result.success) "Hoàn tất (mã ${result.exitCode})." else "Chương trình thất bại.")
        }
        SwingUtilities.invokeLater {
          output.text = text
          status.text = if (result.success) "Chạy thành công" else "Chạy thất bại — xem chẩn đoán"
        }
      } catch (e: Exception) {
        SwingUtilities.invokeLater {
          output.text = "Không thể chạy chương trình: ${e.message ?: e.javaClass.simpleName}"
          status.text = "Lỗi thực thi"
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
