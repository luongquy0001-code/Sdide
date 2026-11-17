package com.example.sdide.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.sdide.engine.WorkspaceFileBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sd_ide_prefs")

data class IdePreferences(
  val darkTheme: Boolean = true,
  val fontSizeSp: Int = 14,
  val showLineNumbers: Boolean = true,
)

data class WorkspaceState(
  val projectName: String,
  val entryFile: String,
  val files: LinkedHashMap<String, String>,
)

class ProjectRepository(private val context: Context) {

  companion object {
    private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
    private val KEY_FONT_SIZE = intPreferencesKey("font_size_sp")
    private val KEY_LINE_NUMS = booleanPreferencesKey("show_line_numbers")
    private const val META_ENTRY_FILE = ".sdide_entry"
  }

  private val currentProjectDir: File
    get() {
      val externalFiles = context.getExternalFilesDir(null) ?: context.filesDir
      val dir = File(externalFiles, "SdIDE/active_project")
      val isNewDirectory = !dir.exists()
      if (!dir.exists() && !dir.mkdirs()) {
        throw IllegalStateException("Không thể tạo thư mục dự án: ${dir.absolutePath}")
      }

      val legacyDir = File(context.filesDir, "sd_workspace/active_project")
      if (isNewDirectory && legacyDir.isDirectory) {
        legacyDir.copyRecursively(target = dir, overwrite = false)
      }
      return dir
    }

  private val sdLibrariesDir: File
    get() = File(currentProjectDir.parentFile, "libraries").apply {
      if (!exists() && !mkdirs()) {
        throw IllegalStateException("Không thể tạo thư mục thư viện Sd: $absolutePath")
      }
    }

  fun storageDirectoryPath(): String = currentProjectDir.absolutePath

  val preferencesFlow: Flow<IdePreferences> = context.dataStore.data.map { prefs ->
    IdePreferences(
      darkTheme = prefs[KEY_DARK_THEME] ?: true,
      fontSizeSp = (prefs[KEY_FONT_SIZE] ?: 14).coerceIn(11, 22),
      showLineNumbers = prefs[KEY_LINE_NUMS] ?: true,
    )
  }

  suspend fun setDarkTheme(isDark: Boolean) {
    context.dataStore.edit { it[KEY_DARK_THEME] = isDark }
  }

  suspend fun setFontSize(sizeSp: Int) {
    context.dataStore.edit { it[KEY_FONT_SIZE] = sizeSp.coerceIn(11, 22) }
  }

  fun loadOrSeedWorkspace(): WorkspaceState {
    val dir = currentProjectDir
    val codeFiles = listCodeFiles(dir)
    if (codeFiles.isEmpty()) {
      val starter = CurriculumExamples.samples.first()
      return saveSampleAsWorkspace(starter)
    }
    val entryMarker = File(dir, META_ENTRY_FILE)
    val storedEntry = if (entryMarker.exists()) entryMarker.readText().trim() else ""
    val map = LinkedHashMap<String, String>()
    for (f in codeFiles.sortedBy { it.name }) {
      map[f.name] = f.readText()
    }
    val entry = if (storedEntry in map) storedEntry else map.keys.first()
    return WorkspaceState(
      projectName = "Dự án Sd IDE",
      entryFile = entry,
      files = map,
    )
  }

  fun saveSampleAsWorkspace(sample: SampleProject): WorkspaceState {
    val dir = currentProjectDir
    dir.listFiles()?.forEach { it.deleteRecursively() }
    val filesMap = LinkedHashMap<String, String>()
    for ((name, content) in sample.files) {
      File(dir, name).writeText(content)
      filesMap[name] = content
    }
    File(dir, META_ENTRY_FILE).writeText(sample.entryFile)
    return WorkspaceState(
      projectName = sample.title,
      entryFile = sample.entryFile,
      files = filesMap,
    )
  }

  fun saveFile(fileName: String, content: String) {
    val safeName = sanitizeFileName(fileName)
    require(isSourceFileName(safeName)) { "Định dạng tệp mã nguồn không được hỗ trợ: $safeName" }
    File(currentProjectDir, safeName).writeText(content)
  }

  fun setEntryFile(fileName: String) {
    File(currentProjectDir, META_ENTRY_FILE).writeText(sanitizeFileName(fileName))
  }

  fun deleteFile(fileName: String) {
    File(currentProjectDir, sanitizeFileName(fileName)).delete()
  }

  fun importSourceFile(fileName: String, content: String): String {
    val safeName = sanitizeFileName(fileName)
    require(isSourceFileName(safeName)) { "Chỉ nhập tệp .sd, .sdpp, .sdall hoặc .txt." }
    saveFile(safeName, content)
    return safeName
  }

  fun installedSdLibraries(): List<SdLibraryInfo> =
    libraryPackageFiles().map { file ->
      file.inputStream().use(SdLibraryPackage::read)
    }.sortedBy { it.name }

  fun installedLibrarySources(): Map<String, String> {
    val sources = LinkedHashMap<String, String>()
    for (library in installedSdLibraries()) {
      for ((fileName, source) in library.sourceFiles) {
        val moduleName = fileName.substringBeforeLast('.').lowercase()
        require(sources.keys.none { it.substringBeforeLast('.').lowercase() == moduleName }) {
          "Nhiều thư viện cùng cung cấp module '$moduleName'."
        }
        sources[fileName] = source
      }
    }
    return sources
  }

  fun installSdLibrary(packageBytes: ByteArray): SdLibraryInfo {
    val library = SdLibraryPackage.decode(packageBytes)
    require(SdLibraryCatalog.builtIns.none { it.name == library.name }) {
      "Tên '${library.name}' đã được dùng bởi thư viện tích hợp sẵn."
    }
    val existingModules = installedSdLibraries()
      .filterNot { it.name == library.name }
      .flatMap { it.sourceFiles.keys }
      .map { it.substringBeforeLast('.').lowercase() }
      .toSet()
    val builtInModules = SdLibraryCatalog.builtIns.map { it.name }.toSet()
    val builtInCollision = library.sourceFiles.keys.firstOrNull {
      it.substringBeforeLast('.').lowercase() in builtInModules
    }
    require(builtInCollision == null) {
      "Module '$builtInCollision' trùng với thư viện tích hợp sẵn."
    }
    val conflictingModule = library.sourceFiles.keys.firstOrNull {
      it.substringBeforeLast('.').lowercase() in existingModules
    }
    require(conflictingModule == null) {
      "Module '${conflictingModule?.substringBeforeLast('.')}' đã được cung cấp bởi thư viện khác."
    }

    val target = File(sdLibrariesDir, "${library.name}.sdlib")
    val temporary = File(sdLibrariesDir, ".${library.name}.tmp")
    val backup = File(sdLibrariesDir, ".${library.name}.bak")
    if (temporary.exists() && !temporary.delete()) {
      throw IllegalStateException("Không thể dọn gói cài đặt tạm của '${library.name}'.")
    }
    temporary.writeBytes(packageBytes)
    if (backup.exists() && !backup.delete()) {
      temporary.delete()
      throw IllegalStateException("Không thể dọn bản sao lưu của '${library.name}'.")
    }
    if (target.exists() && !target.renameTo(backup)) {
      temporary.delete()
      throw IllegalStateException("Không thể thay thế thư viện '${library.name}'.")
    }
    if (!temporary.renameTo(target)) {
      if (backup.exists()) backup.renameTo(target)
      temporary.delete()
      throw IllegalStateException("Không thể cài thư viện '${library.name}'.")
    }
    if (backup.exists() && !backup.delete()) {
      target.delete()
      backup.renameTo(target)
      throw IllegalStateException("Không thể hoàn tất cập nhật thư viện '${library.name}'.")
    }
    return library
  }

  fun uninstallSdLibrary(name: String) {
    require(name.matches(Regex("[a-z][a-z0-9_-]{0,47}"))) { "Tên thư viện không hợp lệ." }
    val libraryFile = File(sdLibrariesDir, "$name.sdlib")
    if (libraryFile.exists() && !libraryFile.delete()) {
      throw IllegalStateException("Không thể gỡ thư viện '$name'.")
    }
  }

  private fun libraryPackageFiles(): List<File> {
    val files = sdLibrariesDir.listFiles()
      ?: throw IllegalStateException("Không thể đọc thư mục thư viện Sd: ${sdLibrariesDir.absolutePath}")
    return files.filter { it.isFile && it.name.endsWith(".sdlib", ignoreCase = true) }
  }

  fun listDataFiles(): List<String> {
    val dir = File(currentProjectDir, "data")
    if (!dir.exists()) return emptyList()
    return (dir.listFiles() ?: throw IllegalStateException("Không thể đọc thư mục dữ liệu: ${dir.absolutePath}"))
      .filter { it.isFile }
      .map { it.name }
      .sorted()
  }

  fun writeDataFile(fileName: String, contents: ByteArray) {
    val safeName = sanitizeFileName(fileName)
    require(safeName.isNotBlank()) { "Tên tệp dữ liệu không được để trống." }
    val dir = File(currentProjectDir, "data")
    if (!dir.exists() && !dir.mkdirs()) {
      throw IllegalStateException("Không thể tạo thư mục dữ liệu: ${dir.absolutePath}")
    }
    File(dir, safeName).writeBytes(contents)
  }

  fun readDataFile(fileName: String): ByteArray {
    val safeName = sanitizeFileName(fileName)
    return File(File(currentProjectDir, "data"), safeName).readBytes()
  }

  fun deleteDataFile(fileName: String) {
    val safeName = sanitizeFileName(fileName)
    val file = File(File(currentProjectDir, "data"), safeName)
    if (file.exists() && !file.delete()) {
      throw IllegalStateException("Không thể xóa tệp dữ liệu: $safeName")
    }
  }

  fun createFileBridge(): WorkspaceFileBridge {
    val dir = File(currentProjectDir, "data").apply { mkdirs() }
    return object : WorkspaceFileBridge {
      override fun writeFile(name: String, content: String) {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
        if (safe.isNotBlank()) {
          File(dir, safe).writeText(content)
        }
      }

      override fun readFile(name: String): String? {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
        val dataFile = File(dir, safe)
        if (dataFile.exists()) return dataFile.readText()
        val projFile = File(currentProjectDir, safe)
        if (projFile.exists()) return projFile.readText()
        return null
      }
    }
  }

  private fun listCodeFiles(dir: File): List<File> {
    return (dir.listFiles() ?: emptyArray()).filter {
      it.isFile && !it.name.startsWith(".") &&
        listOf(".sd", ".sdpp", ".sdall", ".txt").any { extension ->
          it.name.endsWith(extension, ignoreCase = true)
        }
    }
  }

  private fun sanitizeFileName(name: String): String {
    return name.trim().substringAfterLast('/').substringAfterLast('\\')
  }

  private fun isSourceFileName(fileName: String): Boolean =
    listOf(".sd", ".sdpp", ".sdall", ".txt").any { fileName.endsWith(it, ignoreCase = true) }
}
