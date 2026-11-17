package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sdide.ui.IdeScreen
import com.example.sdide.ui.SdIdeViewModel
import com.example.ui.theme.SdIdeTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val vm: SdIdeViewModel = viewModel()
      val state by vm.uiState.collectAsStateWithLifecycle()
      SdIdeTheme(darkTheme = state.preferences.darkTheme) {
        IdeScreen(state = state, vm = vm)
      }
    }
  }
}
