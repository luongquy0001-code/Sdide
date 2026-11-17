package com.example.sdide.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ErrorRose
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SdOrangeLight
import com.example.ui.theme.SdppLimeBright
import com.example.ui.theme.WarnAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeScreen(
  state: IdeUiState,
  vm: SdIdeViewModel,
) {
  var showNewFileDialog by remember { mutableStateOf(false) }
  var showNewDataFileDialog by remember { mutableStateOf(false) }
  var showMoreMenu by remember { mutableStateOf(false) }
  var isWorkbenchExpanded by remember { mutableStateOf(true) }
  var pendingDataExport by remember { mutableStateOf<String?>(null) }
  val snackbarHostState = remember { SnackbarHostState() }
  val sourceImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri?.let(vm::importSourceFile)
  }
  val dataImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri?.let(vm::importDataFile)
  }
  val libraryImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri?.let(vm::installSdLibrary)
  }
  val sourceExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
    uri?.let(vm::exportActiveSource)
  }
  val bytecodeExportLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/octet-stream"),
  ) { uri ->
    uri?.let(vm::exportActiveBytecode)
  }
  val dataExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
    val fileName = pendingDataExport
    if (uri != null && fileName != null) vm.exportDataFile(fileName, uri)
    pendingDataExport = null
  }

  LaunchedEffect(state.fileMessage) {
    state.fileMessage?.let {
      snackbarHostState.showSnackbar(it)
      vm.clearFileMessage()
    }
  }

  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
        ),
        title = {
          Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                text = "Sd IDE",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
              )
              val extBadge = when {
                state.activeFile.endsWith(".sdall") -> ".sdall"
                state.activeFile.endsWith(".sdpp") -> "Sd++"
                else -> "Sd 2"
              }
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
              ) {
                Text(
                  text = extBadge,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
            }
            Text(
              text = state.projectName,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        },
        actions = {
          // Curriculum & Reference Library button
          IconButton(
            onClick = { vm.setCurriculumSheetVisible(true) },
            modifier = Modifier.testTag("open_curriculum_button"),
          ) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Giáo trình và tra cứu cú pháp")
          }

          // C Transpiler button (sdc -S)
          IconButton(
            onClick = { vm.setCViewerVisible(true) },
            modifier = Modifier.testTag("open_c_transpiler_button"),
          ) {
            Icon(Icons.Default.Code, contentDescription = "Sinh mã C sdc -S")
          }

          // Debug / Step button
          FilledTonalIconButton(
            onClick = { vm.runProgram(startInDebugStep = true) },
            modifier = Modifier.testTag("debug_run_button"),
          ) {
            Icon(Icons.Default.BugReport, contentDescription = "Chạy từng bước gỡ lỗi", tint = WarnAmber)
          }

          Spacer(Modifier.width(4.dp))

          // Run or Stop button
          if (state.isRunning) {
            Button(
              onClick = { vm.stopExecution() },
              colors = ButtonDefaults.buttonColors(containerColor = ErrorRose),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
              modifier = Modifier.height(38.dp).testTag("stop_run_button"),
            ) {
              Icon(Icons.Default.Stop, contentDescription = "Dừng", modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(4.dp))
              Text("Dừng", style = MaterialTheme.typography.labelMedium)
            }
          } else {
            Button(
              onClick = {
                isWorkbenchExpanded = true
                vm.runProgram(startInDebugStep = false)
              },
              contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
              modifier = Modifier.height(38.dp).testTag("run_program_button"),
            ) {
              Icon(Icons.Default.PlayArrow, contentDescription = "Chạy chương trình", modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(4.dp))
              Text("Chạy", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
          }

          Box {
            IconButton(
              onClick = { showMoreMenu = true },
              modifier = Modifier.testTag("more_options_button"),
            ) {
              Icon(Icons.Default.MoreVert, contentDescription = "Tùy chọn thêm")
            }
            DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
              DropdownMenuItem(
                text = { Text("Nhập mã nguồn từ thiết bị") },
                onClick = {
                  sourceImportLauncher.launch(arrayOf("*/*"))
                  showMoreMenu = false
                },
              )
              DropdownMenuItem(
                text = { Text("Xuất mã nguồn đang mở") },
                onClick = {
                  sourceExportLauncher.launch(state.activeFile)
                  showMoreMenu = false
                },
              )
              DropdownMenuItem(
                text = { Text("Xuất bytecode SdIDE (.sdbin/.sdppbin)") },
                onClick = {
                  val extension = if (state.entryFile.endsWith(".sdpp", ignoreCase = true)) ".sdppbin" else ".sdbin"
                  val sourceExtension = state.entryFile.substringAfterLast('.', "")
                  val baseName = if (sourceExtension.isEmpty()) state.entryFile else state.entryFile.dropLast(sourceExtension.length + 1)
                  bytecodeExportLauncher.launch("$baseName$extension")
                  showMoreMenu = false
                },
              )
              DropdownMenuItem(
                text = { Text("Thư viện Sd / Sd++") },
                onClick = {
                  vm.setLibraryManagerVisible(true)
                  showMoreMenu = false
                },
              )
              DropdownMenuItem(
                text = { Text("Quản lý bộ nhớ và tệp dữ liệu") },
                onClick = {
                  vm.setStorageManagerVisible(true)
                  showMoreMenu = false
                },
              )
              DropdownMenuItem(
                text = {
                  Text(if (state.preferences.darkTheme) "Giao diện Sáng (Giấy)" else "Giao diện Tối (Terminal)")
                },
                leadingIcon = {
                  Icon(
                    if (state.preferences.darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = null,
                  )
                },
                onClick = {
                  vm.toggleTheme()
                  showMoreMenu = false
                },
              )
              DropdownMenuItem(
                text = { Text("Tăng cỡ chữ (${state.preferences.fontSizeSp + 1}sp)") },
                leadingIcon = { Icon(Icons.Default.FormatSize, contentDescription = null) },
                onClick = { vm.adjustFontSize(1) },
              )
              DropdownMenuItem(
                text = { Text("Giảm cỡ chữ (${state.preferences.fontSizeSp - 1}sp)") },
                leadingIcon = { Icon(Icons.Default.FormatSize, contentDescription = null) },
                onClick = { vm.adjustFontSize(-1) },
              )
              DropdownMenuItem(
                text = { Text("Đặt '${state.activeFile}' làm tệp chạy chính (★)") },
                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = SdOrangeLight) },
                onClick = {
                  vm.setEntryFile(state.activeFile)
                  showMoreMenu = false
                },
              )
            }
          }
        },
      )
    },
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .consumeWindowInsets(innerPadding)
        .imePadding(),
    ) {
      // File Explorer Tabs Row
      FileTabsStrip(
        files = state.files.keys.toList(),
        activeFile = state.activeFile,
        entryFile = state.entryFile,
        onSelectFile = { vm.switchFile(it) },
        onSetEntryFile = { vm.setEntryFile(it) },
        onDeleteFile = { vm.deleteFile(it) },
        onAddFile = { showNewFileDialog = true },
      )

      // Adaptive Editor + Workbench Layout
      BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        val isWideTablet = maxWidth >= 720.dp

        if (isWideTablet) {
          Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.weight(0.56f).fillMaxHeight()) {
              SdCodeEditor(
                value = state.editorValue,
                onValueChange = vm::onEditorValueChange,
                fontSizeSp = state.preferences.fontSizeSp,
                showLineNumbers = state.preferences.showLineNumbers,
                diagnostics = state.diagnostics.filter { it.fileName == null || it.fileName == state.activeFile },
                breakpoints = state.breakpoints,
                debugLine = state.debugLine,
                scrollToLineRequest = state.scrollToLineRequest,
                onToggleBreakpoint = vm::toggleBreakpoint,
                modifier = Modifier.weight(1f),
              )
              QuickSymbolAccessoryBar(
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onUndo = vm::undo,
                onRedo = vm::redo,
                onInsertSnippet = vm::insertSnippet,
              )
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outline)
            WorkbenchPanel(
              state = state,
              onSelectTab = vm::selectTab,
              onJumpToProblem = vm::jumpToProblem,
              onClearConsole = vm::clearConsole,
              onUpdateStdin = vm::updateStdinText,
              onSubmitInteractiveStdin = vm::submitInteractiveStdin,
              onStepInto = vm::debugStepInto,
              onStepOver = vm::debugStepOver,
              onContinue = vm::debugContinue,
              onStop = vm::stopExecution,
              modifier = Modifier.weight(0.44f).fillMaxHeight(),
            )
          }
        } else {
          Column(modifier = Modifier.fillMaxSize()) {
            Box(
              modifier = Modifier
                .weight(if (isWorkbenchExpanded) 0.56f else 1f)
                .fillMaxWidth(),
            ) {
              SdCodeEditor(
                value = state.editorValue,
                onValueChange = vm::onEditorValueChange,
                fontSizeSp = state.preferences.fontSizeSp,
                showLineNumbers = state.preferences.showLineNumbers,
                diagnostics = state.diagnostics.filter { it.fileName == null || it.fileName == state.activeFile },
                breakpoints = state.breakpoints,
                debugLine = state.debugLine,
                scrollToLineRequest = state.scrollToLineRequest,
                onToggleBreakpoint = vm::toggleBreakpoint,
                modifier = Modifier.fillMaxSize(),
              )
            }

            // Toggle bar for compact workbench collapse/expand
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant,
              modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clickable { isWorkbenchExpanded = !isWorkbenchExpanded }
                .testTag("toggle_workbench_bar"),
            ) {
              Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = if (isWorkbenchExpanded) "Thu gọn bảng kết quả" else "Mở bảng kết quả / Gỡ lỗi",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                  imageVector = if (isWorkbenchExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                  contentDescription = "Thu gọn hoặc mở rộng bảng dưới",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(18.dp),
                )
              }
            }

            AnimatedVisibility(
              visible = isWorkbenchExpanded,
              modifier = Modifier.weight(0.44f).fillMaxWidth(),
            ) {
              WorkbenchPanel(
                state = state,
                onSelectTab = vm::selectTab,
                onJumpToProblem = vm::jumpToProblem,
                onClearConsole = vm::clearConsole,
                onUpdateStdin = vm::updateStdinText,
                onSubmitInteractiveStdin = vm::submitInteractiveStdin,
                onStepInto = vm::debugStepInto,
                onStepOver = vm::debugStepOver,
                onContinue = vm::debugContinue,
                onStop = vm::stopExecution,
              )
            }

            QuickSymbolAccessoryBar(
              canUndo = state.canUndo,
              canRedo = state.canRedo,
              onUndo = vm::undo,
              onRedo = vm::redo,
              onInsertSnippet = vm::insertSnippet,
            )
          }
        }
      }
    }
  }

  if (showNewFileDialog) {
    NewFileDialog(
      onDismiss = { showNewFileDialog = false },
      onCreate = { name ->
        vm.createNewFile(name)
        showNewFileDialog = false
      },
    )
  }

  if (state.showStorageManager) {
    DataStorageDialog(
      files = state.dataFiles,
      storagePath = state.storagePath,
      onDismiss = { vm.setStorageManagerVisible(false) },
      onCreate = { name ->
        vm.createDataFile(name)
        showNewDataFileDialog = false
      },
      onImport = { dataImportLauncher.launch(arrayOf("*/*")) },
      onExport = { fileName ->
        pendingDataExport = fileName
        dataExportLauncher.launch(fileName)
      },
      onDelete = vm::deleteDataFile,
      showCreateFileDialog = showNewDataFileDialog,
      onShowCreateFileDialog = { showNewDataFileDialog = it },
    )
  }

  if (state.showLibraryManager) {
    SdLibraryManagerDialog(
      installedLibraries = state.installedLibraries,
      onDismiss = { vm.setLibraryManagerVisible(false) },
      onInstallFromFile = { libraryImportLauncher.launch(arrayOf("*/*")) },
      onUninstall = vm::uninstallSdLibrary,
    )
  }

  if (state.showCurriculumSheet) {
    CurriculumAndReferenceSheet(
      onDismiss = { vm.setCurriculumSheetVisible(false) },
      onSelectSample = { sample -> vm.loadSampleProject(sample) },
    )
  }

  if (state.showCViewer) {
    CTranspilerDialog(
      cCode = state.cSourceCode,
      onDismiss = { vm.setCViewerVisible(false) },
    )
  }
}

@Composable
private fun DataStorageDialog(
  files: List<String>,
  storagePath: String,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
  onImport: () -> Unit,
  onExport: (String) -> Unit,
  onDelete: (String) -> Unit,
  showCreateFileDialog: Boolean,
  onShowCreateFileDialog: (Boolean) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Bộ nhớ dữ liệu của Sd IDE") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "Workspace: $storagePath",
          style = MaterialTheme.typography.labelSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = onImport) { Text("Nhập tệp") }
          TextButton(onClick = { onShowCreateFileDialog(true) }) { Text("Tạo tệp") }
        }
        if (files.isEmpty()) {
          Text("Chưa có tệp dữ liệu.")
        } else {
          LazyColumn(modifier = Modifier.height(220.dp)) {
            items(files, key = { it }) { file ->
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(file, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { onExport(file) }) { Text("Xuất") }
                TextButton(onClick = { onDelete(file) }) { Text("Xóa") }
              }
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
  )

  if (showCreateFileDialog) {
    var fileName by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { onShowCreateFileDialog(false) },
      title = { Text("Tạo tệp dữ liệu") },
      text = {
        OutlinedTextField(
          value = fileName,
          onValueChange = { fileName = it },
          label = { Text("Tên tệp") },
          singleLine = true,
        )
      },
      confirmButton = {
        TextButton(
          onClick = { onCreate(fileName) },
          enabled = fileName.isNotBlank(),
        ) { Text("Tạo") }
      },
      dismissButton = {
        TextButton(onClick = { onShowCreateFileDialog(false) }) { Text("Hủy") }
      },
    )
  }
}

@Composable
private fun FileTabsStrip(
  files: List<String>,
  activeFile: String,
  entryFile: String,
  onSelectFile: (String) -> Unit,
  onSetEntryFile: (String) -> Unit,
  onDeleteFile: (String) -> Unit,
  onAddFile: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column {
      LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        items(files) { file ->
          val isActive = file == activeFile
          val isEntry = file == entryFile
          val bg = if (isActive) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
          val border = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

          Row(
            modifier = Modifier
              .height(36.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(bg)
              .border(1.dp, border, RoundedCornerShape(8.dp))
              .clickable { onSelectFile(file) }
              .padding(start = 8.dp, end = 4.dp)
              .testTag("file_tab_$file"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(
              imageVector = if (isEntry) Icons.Default.Star else Icons.Default.StarOutline,
              contentDescription = if (isEntry) "Tệp chạy chính" else "Đặt làm tệp chạy chính",
              tint = if (isEntry) SdOrangeLight else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier
                .size(16.dp)
                .clickable { onSetEntryFile(file) },
            )
            Text(
              text = file,
              fontFamily = JetBrainsMonoFamily,
              fontSize = 12.sp,
              fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
              color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (files.size > 1) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Xóa tệp $file",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                  .size(16.dp)
                  .clickable { onDeleteFile(file) },
              )
            }
          }
        }
        item {
          IconButton(
            onClick = onAddFile,
            modifier = Modifier.size(36.dp).testTag("add_file_button"),
          ) {
            Icon(Icons.Default.Add, contentDescription = "Tạo tệp mới", tint = SdppLimeBright)
          }
        }
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
  }
}

@Composable
private fun NewFileDialog(
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
) {
  var name by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Tạo tệp mã nguồn mới") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "Nhập tên tệp kèm đuôi .sd (Sd 2), .sdpp (Sd++) hoặc .sdall (đa ngăn).",
          style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Ví dụ: tien_ich.sd hoặc main.sdpp") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("new_file_name_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onCreate(name) },
        enabled = name.isNotBlank(),
        modifier = Modifier.testTag("confirm_new_file_button"),
      ) {
        Text("Tạo")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Hủy")
      }
    },
  )
}
