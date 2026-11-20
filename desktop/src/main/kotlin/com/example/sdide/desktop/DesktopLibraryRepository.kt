package com.example.sdide.desktop

import com.example.sdide.data.SdLibraryCatalog
import com.example.sdide.data.SdLibraryInfo
import com.example.sdide.data.SdLibraryPackage
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DesktopLibraryRepository(private val directory: File) {
  fun installedLibraries(): List<SdLibraryInfo> {
    if (!directory.exists() && !directory.mkdirs()) {
      throw IOException("Không thể tạo thư mục thư viện: ${directory.absolutePath}")
    }
    val files = directory.listFiles()
      ?: throw IOException("Không thể đọc thư mục thư viện: ${directory.absolutePath}")
    return files.filter { it.isFile && it.name.endsWith(".sdlib", ignoreCase = true) }
      .map { file -> file.inputStream().use(SdLibraryPackage::read) }
      .sortedBy { it.name }
  }

  fun sourceFiles(): Map<String, String> {
    val sources = LinkedHashMap<String, String>()
    for (library in installedLibraries()) {
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

  fun install(packageBytes: ByteArray): SdLibraryInfo {
    val library = SdLibraryPackage.decode(packageBytes)
    require(SdLibraryCatalog.builtIns.none { it.name == library.name }) {
      "Tên '${library.name}' đã được dùng bởi thư viện tích hợp sẵn."
    }
    val existingModules = installedLibraries()
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

    if (!directory.exists() && !directory.mkdirs()) {
      throw IOException("Không thể tạo thư mục thư viện: ${directory.absolutePath}")
    }
    val target = File(directory, "${library.name}.sdlib").toPath()
    val temporary = Files.createTempFile(directory.toPath(), ".${library.name}-", ".tmp")
    try {
      Files.write(temporary, packageBytes)
      try {
        Files.move(
          temporary,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
    return library
  }

  fun uninstall(name: String) {
    require(name.matches(Regex("[a-z][a-z0-9_-]{0,47}"))) { "Tên thư viện không hợp lệ." }
    val file = File(directory, "$name.sdlib")
    if (file.exists() && !file.delete()) {
      throw IOException("Không thể gỡ thư viện '$name'.")
    }
  }
}
