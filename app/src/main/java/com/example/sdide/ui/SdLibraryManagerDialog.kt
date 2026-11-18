package com.example.sdide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.sdide.data.SdLibraryCatalog
import com.example.sdide.data.SdLibraryInfo

@Composable
fun SdLibraryManagerDialog(
  installedLibraries: List<SdLibraryInfo>,
  onDismiss: () -> Unit,
  onInstallFromFile: () -> Unit,
  onBuildPackage: () -> Unit,
  sourceFiles: Map<String, String>,
  onUninstall: (String) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Thư viện Sd / Sd++") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          "Thư viện tích hợp sẵn dùng trực tiếp. Cài gói .sdlib ngoại tuyến để nạp mã nguồn Sd/Sd++ qua imp*. Hãy xem mã thư viện trước khi cài.",
          style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
          onClick = onInstallFromFile,
          modifier = Modifier.fillMaxWidth().testTag("install_sd_library_button"),
        ) {
          Text("Cài thư viện từ tệp .sdlib")
        }
        OutlinedButton(
          onClick = onBuildPackage,
          enabled = sourceFiles.keys.any { it.endsWith(".sd", true) || it.endsWith(".sdpp", true) },
          modifier = Modifier.fillMaxWidth().testTag("build_sd_library_button"),
        ) {
          Text("Đóng gói mã nguồn thành .sdlib")
        }
        LazyColumn(
          modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item {
            Text("Tích hợp sẵn", style = MaterialTheme.typography.titleSmall)
          }
          items(SdLibraryCatalog.builtIns, key = { "builtin-${it.name}" }) { library ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
              Text("${library.name} — ${library.title}", style = MaterialTheme.typography.labelLarge)
              Text(library.description, style = MaterialTheme.typography.bodySmall)
              Text(library.importStatement, style = MaterialTheme.typography.labelSmall)
            }
          }
          item {
            Text("Đã cài", style = MaterialTheme.typography.titleSmall)
          }
          if (installedLibraries.isEmpty()) {
            item { Text("Chưa cài thư viện ngoài.", style = MaterialTheme.typography.bodySmall) }
          } else {
            items(installedLibraries, key = { "installed-${it.name}" }) { library ->
              Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text("${library.name} ${library.version}", style = MaterialTheme.typography.labelLarge)
                    Text(library.description, style = MaterialTheme.typography.bodySmall)
                    Text(
                      "Module: ${library.sourceFiles.keys.joinToString()}",
                      style = MaterialTheme.typography.labelSmall,
                    )
                  }
                  TextButton(onClick = { onUninstall(library.name) }) {
                    Text("Gỡ")
                  }
                }
              }
            }
          }
          item {
            Text(
              "Gói .sdlib là tệp ZIP: manifest.properties (format=1, name, version, description, entry) và mã nguồn trong src/*.sd hoặc src/*.sdpp. Ví dụ: imp*collections.ed cho collections.sd.",
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
  )
}

@Composable
internal fun BuildSdLibraryDialog(
  sourceFiles: Map<String, String>,
  defaultEntryFile: String,
  onDismiss: () -> Unit,
  onBuild: (SdLibraryInfo) -> Unit,
) {
  val moduleFiles = sourceFiles.keys.sorted()
  var name by remember { mutableStateOf(defaultEntryFile.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-', '_').ifBlank { "library" }) }
  var version by remember { mutableStateOf("1.0.0") }
  var description by remember { mutableStateOf("") }
  var entryFile by remember { mutableStateOf(defaultEntryFile.takeIf { it in moduleFiles } ?: moduleFiles.firstOrNull().orEmpty()) }
  var showEntryOptions by remember { mutableStateOf(false) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Đóng gói thư viện Sd / Sd++") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Đóng gói các tệp .sd/.sdpp trong dự án thành gói .sdlib để cài hoặc chia sẻ.")
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Tên thư viện") }, singleLine = true)
        OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("Phiên bản") }, singleLine = true)
        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Mô tả") }, singleLine = true)
        TextButton(onClick = { showEntryOptions = true }) {
          Text("Tệp chính: ${entryFile.ifBlank { "Chưa chọn" }}")
        }
        DropdownMenu(expanded = showEntryOptions, onDismissRequest = { showEntryOptions = false }) {
          moduleFiles.forEach { file ->
            DropdownMenuItem(
              text = { Text(file) },
              onClick = {
                entryFile = file
                showEntryOptions = false
              },
            )
          }
        }
        Text("Các module: ${moduleFiles.joinToString()}", style = MaterialTheme.typography.bodySmall)
      }
    },
    confirmButton = {
      Button(
        enabled = name.matches(Regex("[a-z][a-z0-9_-]{0,47}")) &&
          version.isNotBlank() && version.trim().length <= 64 &&
          description.trim().length <= 512 && entryFile in sourceFiles,
        onClick = {
          onBuild(
            SdLibraryInfo(
              name = name.trim(),
              version = version.trim(),
              description = description.trim(),
              entryFile = entryFile,
              sourceFiles = sourceFiles,
            ),
          )
        },
      ) {
        Text("Tiếp tục lưu .sdlib")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
  )
}
