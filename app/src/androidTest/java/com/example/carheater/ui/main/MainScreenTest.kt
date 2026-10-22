package com.example.carheater.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for [com.example.carheater.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun setup() {
    composeTestRule.setContent {
      MainScreenContent(
        state = HeaterUiState(isHeaterOn = false),
        onOpenAuthDialog = {},
        onTurnOnHeater = {},
        onTurnOffHeater = {},
        onRefreshStatus = {},
        onReadyTimeChanged = { _, _ -> },
        onAutoOffOffsetChanged = {},
        onToggleWeekday = {},
        onSetScheduleEnabled = {},
        onSyncSchedule = {},
      )
    }
  }

  @Test
  fun directControlAndScheduleElements_exist() {
    composeTestRule.onNodeWithText("Direct Heater Control").assertExists()
    composeTestRule.onNodeWithText("START HEATER").assertExists()
    composeTestRule.onNodeWithText("STOP HEATER").assertExists()
    composeTestRule.onNodeWithText("When Car Should Be Warm").assertExists()
  }
}
