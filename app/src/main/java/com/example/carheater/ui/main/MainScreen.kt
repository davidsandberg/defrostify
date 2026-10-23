package com.example.carheater.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.carheater.data.telldus.HeaterScheduleCalculator
import com.example.carheater.data.telldus.HeaterStorage
import com.example.carheater.theme.CarHeaterTheme
import com.example.carheater.ui.components.AuthConfigDialog
import com.example.carheater.ui.components.SamsungWheelTimePicker

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val storage = remember(context) { HeaterStorage(context.applicationContext) }
  val viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(storage = storage) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> viewModel.onAppResumed()
        Lifecycle.Event.ON_PAUSE -> viewModel.onAppPaused()
        else -> {}
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      viewModel.onAppPaused()
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  MainScreenContent(
    state = state,
    onOpenAuthDialog = viewModel::openAuthDialog,
    onTurnOnHeater = { viewModel.setHeaterPower(true) },
    onTurnOffHeater = { viewModel.setHeaterPower(false) },
    onRefreshStatus = { viewModel.refreshSwitchState(silent = false) },
    onReadyTimeChanged = viewModel::updateReadyTime,
    onToggleWeekday = viewModel::toggleWeekday,
    onSetScheduleEnabled = viewModel::setScheduleEnabled,
    onSyncSchedule = { viewModel.syncScheduleToTelldus() },
    modifier = modifier,
  )

  if (state.showAuthDialog) {
    AuthConfigDialog(
      authConfig = state.authConfig,
      preHeatMinutes = state.scheduleConfig.preHeatMinutes,
      autoOffOffsetMinutes = state.scheduleConfig.autoOffOffsetMinutes,
      discoveredTellSticks = state.discoveredTellSticks,
      devices = state.availableDevices,
      isBusy = state.isBusy,
      statusMessage = state.statusMessage,
      errorMessage = state.errorMessage,
      onDismiss = viewModel::closeAuthDialog,
      onSaveAuthConfig = viewModel::updateAuthConfig,
      onPreHeatMinutesChanged = viewModel::updatePreHeatMinutes,
      onAutoOffOffsetChanged = viewModel::updateAutoOffOffset,
      onScanLan = viewModel::scanLanForTellStick,
      onStartLocalBrowserAuth = viewModel::startLocalBrowserAuth,
      onCompleteLocalBrowserAuth = { viewModel.completeLocalBrowserAuth(silentIfPending = false) },
      onAutoFetchCloudKeys = { viewModel.autoFetchCloudKeys(silent = false) },
      onFetchDevices = viewModel::fetchDevices,
      onSelectDevice = viewModel::selectDevice,
    )
  }
}

@Composable
internal fun MainScreenContent(
  state: HeaterUiState,
  onOpenAuthDialog: () -> Unit,
  onTurnOnHeater: () -> Unit,
  onTurnOffHeater: () -> Unit,
  onRefreshStatus: () -> Unit,
  onReadyTimeChanged: (Int, Int) -> Unit,
  onToggleWeekday: (Int) -> Unit,
  onSetScheduleEnabled: (Boolean) -> Unit,
  onSyncSchedule: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // Top Header Bar
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Defrostify",
          fontSize = 22.sp,
          fontWeight = FontWeight.Bold,
        )

        OutlinedButton(
          onClick = onOpenAuthDialog,
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
          Text(
            text = "⚙ Config",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
          )
        }
      }

      if (state.authConfig.isAuthenticated) {
        val targetName =
          state.authConfig.selectedDeviceName.ifBlank { "Tap 'Config' to choose switch" }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier =
            Modifier
              .fillMaxWidth()
              .clickable { onRefreshStatus() },
        ) {
          Text(
            text = "Switch: $targetName",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          SwitchStatusBadge(
            isHeaterOn = state.isHeaterOn,
            powerWatts = state.powerWatts,
          )
        }
      } else {
        Text(
          text = "Not connected to TellStick",
          fontSize = 13.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    // Error Banner (only shown if there is an error)
    if (!state.errorMessage.isNullOrBlank()) {
      Card(
        colors =
          CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = state.errorMessage,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onErrorContainer,
          modifier = Modifier.padding(10.dp),
        )
      }
    }

    // 1. Direct Heater Control Card
    DirectHeaterControlCard(
      isHeaterOn = state.isHeaterOn,
      isBusy = state.isBusy,
      onTurnOn = onTurnOnHeater,
      onTurnOff = onTurnOffHeater,
    )

    // 2. Samsung Alarm-Style Departure Time & Pre-Heat Schedule Card
    ScheduleConfigurationCard(
      state = state,
      onReadyTimeChanged = onReadyTimeChanged,
      onToggleWeekday = onToggleWeekday,
      onSetScheduleEnabled = onSetScheduleEnabled,
      onSyncSchedule = onSyncSchedule,
    )
  }
}

@Composable
private fun SwitchStatusBadge(
  isHeaterOn: Boolean?,
  powerWatts: Double? = null,
) {
  val bgColor =
    when (isHeaterOn) {
      true -> MaterialTheme.colorScheme.primaryContainer
      false -> MaterialTheme.colorScheme.surfaceContainerHighest
      null -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
  val textColor =
    when (isHeaterOn) {
      true -> MaterialTheme.colorScheme.primary
      false -> MaterialTheme.colorScheme.onSurfaceVariant
      null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
  val wattSuffix =
    powerWatts?.let { w ->
      val formatted =
        when {
          w < 0.05 -> "0 W"
          w < 10.0 -> String.format(java.util.Locale.US, "%.1f W", w)
          else -> "${kotlin.math.round(w).toInt()} W"
        }
      " · $formatted"
    }.orEmpty()
  val label =
    when (isHeaterOn) {
      true -> "● ON$wattSuffix"
      false -> "○ OFF$wattSuffix"
      null -> "…"
    }

  Box(
    modifier =
      Modifier
        .clip(RoundedCornerShape(50))
        .background(bgColor)
        .border(
          width = 1.dp,
          color =
            if (isHeaterOn == true) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant,
          shape = RoundedCornerShape(50),
        )
        .padding(horizontal = 8.dp, vertical = 2.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      fontSize = 11.sp,
      fontWeight = FontWeight.ExtraBold,
      color = textColor,
    )
  }
}

@Composable
private fun DirectHeaterControlCard(
  isHeaterOn: Boolean?,
  isBusy: Boolean,
  onTurnOn: () -> Unit,
  onTurnOff: () -> Unit,
) {
  Card(
    shape = RoundedCornerShape(22.dp),
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (isHeaterOn == true) {
            MaterialTheme.colorScheme.primaryContainer
          } else {
            MaterialTheme.colorScheme.surfaceContainer
          }
      ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = "Direct Control",
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Button(
          onClick = onTurnOn,
          enabled = !isBusy,
          shape = RoundedCornerShape(14.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
          modifier = Modifier.weight(1f).height(46.dp),
        ) {
          Text(
            text = "● ON",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
          )
        }

        OutlinedButton(
          onClick = onTurnOff,
          enabled = !isBusy,
          shape = RoundedCornerShape(14.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
          colors =
            ButtonDefaults.outlinedButtonColors(
              contentColor = MaterialTheme.colorScheme.error
            ),
          modifier = Modifier.weight(1f).height(46.dp),
        ) {
          Text(
            text = "○ OFF",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
          )
        }
      }
    }
  }
}

@Composable
private fun ScheduleConfigurationCard(
  state: HeaterUiState,
  onReadyTimeChanged: (Int, Int) -> Unit,
  onToggleWeekday: (Int) -> Unit,
  onSetScheduleEnabled: (Boolean) -> Unit,
  onSyncSchedule: () -> Unit,
) {
  val schedule = state.scheduleConfig
  val window = state.heatingWindow

  Card(
    shape = RoundedCornerShape(22.dp),
    colors =
      CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Title + Master Schedule Switch
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(
            text = "Schedule",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
          )
          val scheduleSubtitle =
            when {
              state.isSyncingSchedule -> "⏳ Syncing schedule to TellStick..."
              schedule.enabled ->
                "${HeaterScheduleCalculator.formatCountdown(window.durationUntilStart)} · ✓ Synced"
              else -> HeaterScheduleCalculator.formatCountdown(window.durationUntilStart)
            }
          Text(
            text = scheduleSubtitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
          )
        }
        Switch(
          checked = schedule.enabled,
          onCheckedChange = onSetScheduleEnabled,
        )
      }

      // Samsung Galaxy One UI Alarm-style Wheel Picker
      SamsungWheelTimePicker(
        hour = schedule.readyHour,
        minute = schedule.readyMinute,
        onTimeChanged = onReadyTimeChanged,
      )

      // Calculated Pre-Heat & Auto-Off Summary Timeline
      val preHeatBadge =
        if (schedule.preHeatMinutes >= 60 && schedule.preHeatMinutes % 60 == 0) {
          "-${schedule.preHeatMinutes / 60}h"
        } else {
          "-${schedule.preHeatMinutes}m"
        }
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f))
            .padding(vertical = 10.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TimelineMetric(
          label = "ON ($preHeatBadge)",
          timeText = "%02d:%02d".format(window.startTime.hour, window.startTime.minute),
          highlight = true,
        )
        Text(
          text = "→",
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        TimelineMetric(
          label = "READY",
          timeText = "%02d:%02d".format(window.readyTime.hour, window.readyTime.minute),
          highlight = false,
        )
        Text(
          text = "→",
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        TimelineMetric(
          label = "OFF (+${schedule.autoOffOffsetMinutes}m)",
          timeText = "%02d:%02d".format(window.stopTime.hour, window.stopTime.minute),
          highlight = false,
        )
      }

      // Samsung Alarm-style Day-of-Week Selector
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val daySummary =
          if (schedule.activeWeekdays.isEmpty()) {
            "Next occurrence (${window.nextReadyDateTime.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }})"
          } else {
            "Repeating on selected days"
          }
        Text(
          text = daySummary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val dayLabels =
          listOf(
            1 to "M",
            2 to "T",
            3 to "W",
            4 to "T",
            5 to "F",
            6 to "S",
            7 to "S",
          )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          dayLabels.forEach { (dayNum, letter) ->
            val selected = schedule.activeWeekdays.contains(dayNum)
            val isWeekend = dayNum >= 6
            Box(
              modifier =
                Modifier
                  .size(36.dp)
                  .clip(CircleShape)
                  .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                  )
                  .border(
                    width = if (selected) 0.dp else 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                  )
                  .clickable { onToggleWeekday(dayNum) },
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = letter,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color =
                  when {
                    selected -> MaterialTheme.colorScheme.onPrimary
                    isWeekend -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                  },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TimelineMetric(
  label: String,
  timeText: String,
  highlight: Boolean,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
      text = label,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
      textAlign = TextAlign.Center,
    )
    Text(
      text = timeText,
      fontSize = 16.sp,
      fontWeight = FontWeight.ExtraBold,
      color =
        if (highlight) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSecondaryContainer,
    )
  }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 820)
@Composable
fun MainScreenPreview() {
  CarHeaterTheme {
    MainScreenContent(
      state = HeaterUiState(isHeaterOn = false),
      onOpenAuthDialog = {},
      onTurnOnHeater = {},
      onTurnOffHeater = {},
      onRefreshStatus = {},
      onReadyTimeChanged = { _, _ -> },
      onToggleWeekday = {},
      onSetScheduleEnabled = {},
      onSyncSchedule = {},
      modifier = Modifier.padding(16.dp),
    )
  }
}
