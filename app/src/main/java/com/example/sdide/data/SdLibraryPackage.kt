package com.example.sdide.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Properties
import java.util.zip.ZipInputStream

data class SdLibraryInfo(
  val name: String,
  val version: String,
  val description: String,
  val entryFile: String,
  val sourceFiles: Map<String, String>,
)

object SdLibraryPackage {
  const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024
  private const val MAX_SOURCE_BYTES = 1024 * 1024
  private const val MAX_SOURCE_FILES = 32
  private const val FORMAT_VERSION = "1"

  fun read(input: InputStream): SdLibraryInfo {
    val bytes = readPackageBytes(input)
    if (bytes.isEmpty()) throw IllegalArgumentException("Gói thư viện đang trống.")
    return decode(bytes)
  }

  fun readPackageBytes(input: InputStream): ByteArray {
    val bytes = input.readBounded(MAX_PACKAGE_BYTES)
    require(bytes.isNotEmpty()) { "Gói thư viện đang trống." }
    return bytes
  }

  fun decode(bytes: ByteArray): SdLibraryInfo {
    require(bytes.size <= MAX_PACKAGE_BYTES) { "Gói thư viện vượt quá 8 MiB." }
    val entries = LinkedHashMap<String, ByteArray>()
    var totalSourceBytes = 0
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        require(entries.size < MAX_SOURCE_FILES + 1) {
          "Gói chứa quá nhiều tệp."
        }
        require(!entry.isDirectory) { "Gói thư viện không được chứa thư mục ZIP." }
        val path = entry.name
        require(path == "manifest.properties" || SOURCE_PATH.matches(path)) {
          "Đường dẫn trong gói không hợp lệ: $path"
        }
        require(path !in entries) { "Gói chứa tệp trùng lặp: $path" }
        val limit = if (path == "manifest.properties") 16 * 1024 else MAX_SOURCE_BYTES
        val contents = zip.readBounded(limit)
        if (path.startsWith("src/")) {
          totalSourceBytes += contents.size
          require(totalSourceBytes <= MAX_PACKAGE_BYTES) { "Tổng mã nguồn trong gói vượt quá giới hạn." }
        }
        entries[path] = contents
        zip.closeEntry()
      }
    }

    val manifestBytes = entries["manifest.properties"]
      ?: throw IllegalArgumentException("Gói thiếu manifest.properties.")
    val properties = Properties().apply {
      ByteArrayInputStream(manifestBytes).reader(Charsets.UTF_8).use { load(it) }
    }
    require(properties.getProperty("format") == FORMAT_VERSION) {
      "Phiên bản định dạng thư viện không được hỗ trợ."
    }
    val name = properties.getProperty("name")?.trim().orEmpty()
    require(LIBRARY_NAME.matches(name)) {
      "Tên thư viện chỉ được gồm chữ thường, số, gạch ngang và gạch dưới."
    }
    val version = properties.getProperty("version")?.trim().orEmpty()
    require(version.isNotBlank() && version.length <= 64) { "Phiên bản thư viện không hợp lệ." }
    val description = properties.getProperty("description")?.trim().orEmpty()
    require(description.length <= 512) { "Mô tả thư viện quá dài." }

    val sources = entries
      .filterKeys { it.startsWith("src/") }
      .mapKeys { it.key.removePrefix("src/") }
      .mapValues { (_, content) -> content.toString(Charsets.UTF_8) }
    require(sources.isNotEmpty() && sources.size <= MAX_SOURCE_FILES) {
      "Gói phải có từ 1 đến $MAX_SOURCE_FILES tệp mã nguồn."
    }
    val moduleNames = sources.keys.map { it.substringBeforeLast('.').lowercase() }
    require(moduleNames.distinct().size == moduleNames.size) {
      "Gói không được có hai tệp cùng tên module."
    }
    val entryFile = properties.getProperty("entry")?.trim().orEmpty()
    require(SOURCE_FILE.matches(entryFile) && entryFile in sources) {
      "Tệp entry phải trỏ tới một tệp .sd hoặc .sdpp trong src/."
    }
    return SdLibraryInfo(name, version, description, entryFile, sources)
  }

  private fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
      val count = read(buffer)
      if (count < 0) break
      require(output.size() + count <= maxBytes) { "Gói hoặc tệp trong gói vượt quá giới hạn kích thước." }
      output.write(buffer, 0, count)
    }
    return output.toByteArray()
  }

  private val LIBRARY_NAME = Regex("[a-z][a-z0-9_-]{0,47}")
  private val SOURCE_FILE = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,95}\\.(sd|sdpp)")
  private val SOURCE_PATH = Regex("src/[A-Za-z0-9_-][A-Za-z0-9_.-]{0,95}\\.(sd|sdpp)")
}

object SdLibraryCatalog {
  val builtIns = listOf(
    BuiltInSdLibrary("ioprt", "Nhập / xuất", "In kết quả và đọc đầu vào.", "imp*ioprt.ed"),
    BuiltInSdLibrary("math", "Toán học", "Các hàm toán học tích hợp.", "imp*math.ed"),
    BuiltInSdLibrary("str", "Chuỗi", "Các hàm xử lý chuỗi tích hợp.", "imp*str.ed"),
    BuiltInSdLibrary("mem", "Bộ nhớ", "Cấp phát và giải phóng heap mô phỏng.", "imp*mem.$"),
    BuiltInSdLibrary("file", "Tệp dữ liệu", "Đọc/ghi tệp trong thư mục dữ liệu của IDE.", "imp*file.ed"),
    BuiltInSdLibrary("sys", "Hệ thống", "Các tiện ích hệ thống của máy ảo.", "imp*sys.ed"),
    BuiltInSdLibrary("draw", "Đồ họa", "Lệnh vẽ dùng bảng kết quả IDE.", "imp*draw.ed"),
  )
}

data class BuiltInSdLibrary(
  val name: String,
  val title: String,
  val description: String,
  val importStatement: String,
)
