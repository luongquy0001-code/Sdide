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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
