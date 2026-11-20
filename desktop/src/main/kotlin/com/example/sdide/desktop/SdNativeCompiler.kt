package com.example.sdide.desktop

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

data class SdNativeCompilation(
  val executable: File,
  val cSource: File,
  val compiler: String,
  val compilerOutput: String,
)

class SdNativeCompileException(message: String) : IllegalStateException(message)

object SdNativeCompiler {
  private const val COMPILE_TIMEOUT_SECONDS = 120L
  private val SUPPORTED_OPTIMIZATION_FLAGS = setOf("-O0", "-O2", "-O3")

  fun compile(
    cSource: String,
    executable: File,
    compilerCandidates: List<String> = defaultCompilerCandidates(),
    optimizationFlag: String = "-O2",
  ): SdNativeCompilation {
    if (optimizationFlag !in SUPPORTED_OPTIMIZATION_FLAGS) {
      throw SdNativeCompileException("Mức tối ưu không được hỗ trợ: $optimizationFlag")
    }
    val outputDirectory = executable.absoluteFile.parentFile
      ?: throw SdNativeCompileException("Không xác định được thư mục đầu ra native.")
    if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
      throw SdNativeCompileException("Không thể tạo thư mục đầu ra: ${outputDirectory.absolutePath}")
    }

    val sourceFile = File(outputDirectory, "${executable.nameWithoutExtension}.c")
    Files.writeString(sourceFile.toPath(), cSource)

    var logFile: File? = null
    var stagedExecutable: File? = null
    try {
      logFile = Files.createTempFile("sdide-native-", ".log").toFile()
      stagedExecutable = Files.createTempFile(outputDirectory.toPath(), "sdide-native-", ".tmp").toFile()
      val missingCompilers = mutableListOf<String>()
      for (compiler in compilerCandidates) {
        val compilerLog = logFile
          ?: throw SdNativeCompileException("Không thể tạo tệp nhật ký biên dịch.")
        val temporaryExecutable = stagedExecutable
          ?: throw SdNativeCompileException("Không thể tạo tệp thực thi tạm.")
        val command = buildList {
          add(compiler)
          addAll(listOf("-std=c99", optimizationFlag, sourceFile.absolutePath, "-o", temporaryExecutable.absolutePath))
          if (!System.getProperty("os.name").startsWith("Windows", true)) add("-lm")
        }
        val process = try {
          ProcessBuilder(command)
            .directory(outputDirectory)
            .redirectErrorStream(true)
            .redirectOutput(compilerLog)
            .start()
        } catch (e: IOException) {
          missingCompilers += "$compiler: ${e.message ?: "không thể khởi chạy"}"
          continue
        }

        val completed = try {
          process.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
          process.destroyForcibly()
          Thread.currentThread().interrupt()
          throw SdNativeCompileException("Đã hủy quá trình biên dịch native.")
        }
        if (!completed) {
          process.destroyForcibly()
          process.waitFor()
          throw SdNativeCompileException("Biên dịch native quá ${COMPILE_TIMEOUT_SECONDS}s và đã bị dừng.")
        }

        val compilerOutput = compilerLog.readText()
        if (process.exitValue() != 0) {
          throw SdNativeCompileException(
            buildString {
              appendLine("Biên dịch C99 thất bại với $compiler (mã ${process.exitValue()}).")
              if (compilerOutput.isNotBlank()) append(compilerOutput)
              else append("Không có thông báo lỗi từ compiler.")
            },
          )
        }
        Files.move(temporaryExecutable.toPath(), executable.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return SdNativeCompilation(executable, sourceFile, compiler, compilerOutput)
      }

      throw SdNativeCompileException(
        buildString {
          appendLine("Không tìm thấy GCC/Clang để tạo chương trình native.")
          appendLine("Cài GCC hoặc Clang, hoặc đặt biến môi trường SD_CC thành đường dẫn compiler.")
          if (missingCompilers.isNotEmpty()) append(missingCompilers.joinToString("\n"))
        },
      )
    } finally {
      logFile?.let { Files.deleteIfExists(it.toPath()) }
      stagedExecutable?.let { Files.deleteIfExists(it.toPath()) }
    }
  }

  private fun defaultCompilerCandidates(): List<String> {
    val configuredCompiler = System.getenv("SD_CC")?.trim()?.takeIf { it.isNotEmpty() }
    return if (configuredCompiler != null) {
      listOf(configuredCompiler)
    } else {
      listOf("clang", "gcc", "cc")
    }
  }
}
