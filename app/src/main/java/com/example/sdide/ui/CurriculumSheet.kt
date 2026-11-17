package com.example.sdide.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.sdide.data.CurriculumExamples
import com.example.sdide.data.SampleProject
import com.example.sdide.engine.SdErrorCatalog
import com.example.ui.theme.ErrorRose
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.WarnAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurriculumAndReferenceSheet(
  onDismiss: () -> Unit,
  onSelectSample: (SampleProject) -> Unit,
) {
  BackHandler { onDismiss() }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var selectedTab by remember { mutableIntStateOf(0) }
  var searchQuery by remember { mutableStateOf("") }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.86f),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text("Giáo trình & Tra cứu Sd / Sd++", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onDismiss) {
          Icon(Icons.Default.Close, contentDescription = "Đóng")
        }
      }

      TabRow(selectedTabIndex = selectedTab) {
        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Bài mẫu") })
        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Cú pháp") })
        Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Mã lỗi E/W") })
      }

      if (selectedTab != 0) {
        OutlinedTextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
          placeholder = { Text(if (selectedTab == 1) "Tìm từ khóa cú pháp..." else "Tìm mã lỗi (vd: E205, W201, Heap)...") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }

      when (selectedTab) {
        0 -> {
          LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            items(CurriculumExamples.samples) { sample ->
              Card(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { onSelectSample(sample) }
                  .testTag("sample_card_${sample.id}"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
              ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(sample.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                      Text(
                        text = "${sample.chapter} • ${sample.files.size} tệp",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                      )
                    }
                  }
                  Text(sample.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  Text(
                    text = "Mở vào trình soạn thảo (${sample.entryFile})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                  )
                }
              }
            }
          }
        }
        1 -> {
          val filtered = CurriculumExamples.cheatsheet.filter {
            searchQuery.isBlank() ||
              it.syntax.contains(searchQuery, ignoreCase = true) ||
              it.meaning.contains(searchQuery, ignoreCase = true) ||
              it.category.contains(searchQuery, ignoreCase = true)
          }
          LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(filtered) { item ->
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  Text(item.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                  Text(item.syntax, fontFamily = JetBrainsMonoFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                  Text(item.meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
            }
          }
        }
        2 -> {
          val filtered = SdErrorCatalog.entries.filter {
            searchQuery.isBlank() ||
              it.code.contains(searchQuery, ignoreCase = true) ||
              it.title.contains(searchQuery, ignoreCase = true) ||
              it.detail.contains(searchQuery, ignoreCase = true)
          }
          LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(filtered) { entry ->
              val isErr = entry.code.startsWith("E")
              val tint = if (isErr) ErrorRose else WarnAmber
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = RoundedCornerShape(4.dp), color = tint.copy(alpha = 0.2f)) {
                      Text(
                        text = entry.code,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                      )
                    }
                    Text(entry.title, style = MaterialTheme.typography.titleSmall)
                  }
                  Text("${entry.phase} • Chương ${entry.chapter}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  Text(entry.detail, style = MaterialTheme.typography.bodyMedium)
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun CTranspilerDialog(
  cCode: String?,
  onDismiss: () -> Unit,
) {
  BackHandler { onDismiss() }
  val context = LocalContext.current
  val scroll = rememberScrollState()

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth(0.94f)
        .fillMaxHeight(0.84f),
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surface,
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column {
            Text("Mã C99 sinh bởi sdc -S", style = MaterialTheme.typography.titleMedium)
            Text("Chương 20: Ánh xạ int64_t, double, Heap malloc/free", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Row {
            IconButton(
              onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("sdc C99", cCode ?: ""))
                Toast.makeText(context, "Đã sao chép mã C", Toast.LENGTH_SHORT).show()
              },
            ) {
              Icon(Icons.Default.ContentCopy, contentDescription = "Sao chép mã C")
            }
            IconButton(onClick = onDismiss) {
              Icon(Icons.Default.Close, contentDescription = "Đóng")
            }
          }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          color = MaterialTheme.colorScheme.background,
          shape = RoundedCornerShape(10.dp),
        ) {
          SelectionContainer {
            Text(
              text = cCode ?: "// Hãy sửa lỗi cú pháp trước khi sinh mã C (sdc -S).",
              fontFamily = JetBrainsMonoFamily,
              fontSize = 12.sp,
              lineHeight = 18.sp,
              modifier = Modifier
                .verticalScroll(scroll)
                .padding(12.dp),
            )
          }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
          Text("Đóng")
        }
      }
    }
  }
}
