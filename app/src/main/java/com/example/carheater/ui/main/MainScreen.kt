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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
      if (event == Lifecycle.Event.ON_RESUME) {
        viewModel.onAppResumed()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  MainScreenContent(
    state = state,
    onOpenAuthDialog = viewModel::openAuthDialog,
    onTurnOnHeater = { viewModel.setHeaterPower(true) },
    onTurnOffHeater = { viewModel.setHeaterPower(false) },
    onRefreshStatus = { viewModel.refreshSwitchState(silent = false) },
    onReadyTimeChanged = viewModel::updateReadyTime,
    onAutoOffOffsetChanged = viewModel::updateAutoOffOffset,
    onToggleWeekday = viewModel::toggleWeekday,
    onSetScheduleEnabled = viewModel::setScheduleEnabled,
    onSyncSchedule = { viewModel.syncScheduleToTelldus() },
    modifier = modifier,
  )

  if (state.showAuthDialog) {
    AuthConfigDialog(
      authConfig = state.authConfig,
      discoveredTellSticks = state.discoveredTellSticks,
      devices = state.availableDevices,
      isBusy = state.isBusy,
      statusMessage = state.statusMessage,
      errorMessage = state.errorMessage,
      onDismiss = viewModel::closeAuthDialog,
      onSaveAuthConfig = viewModel::updateAuthConfig,
      onScanLan = viewModel::scanLanForTellStick,
      onStartLocalBrowserAuth = viewModel::startLocalBrowserAuth,
      onCompleteLocalBrowserAuth = { viewModel.completeLocalBrowserAuth(silentIfPending = false) },
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
  onAutoOffOffsetChanged: (Int) -> Unit,
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
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Defrostify",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
        )
        val connectionSubtitle =
          if (state.authConfig.isAuthenticated) {
            val targetName =
              state.authConfig.selectedDeviceName.ifBlank { "Tap 'Switch & Auth' to choose switch" }
            "Switch: $targetName"
          } else {
            "Not connected to TellStick"
          }
        Text(
          text = connectionSubtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      OutlinedButton(
        onClick = onOpenAuthDialog,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
      ) {
        Text(
          text = if (state.authConfig.isAuthenticated) "Switch & Auth" else "Configure Auth",
          fontSize = 13.sp,
          maxLines = 1,
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
      deviceName = state.authConfig.selectedDeviceName,
      statusMessage = state.statusMessage,
      onTurnOn = onTurnOnHeater,
      onTurnOff = onTurnOffHeater,
      onRefresh = onRefreshStatus,
    )

    // 2. Samsung Alarm-Style Departure Time & Pre-Heat Schedule Card
    ScheduleConfigurationCard(
      state = state,
      onReadyTimeChanged = onReadyTimeChanged,
      onAutoOffOffsetChanged = onAutoOffOffsetChanged,
      onToggleWeekday = onToggleWeekday,
      onSetScheduleEnabled = onSetScheduleEnabled,
      onSyncSchedule = onSyncSchedule,
    )
  }
}

@Composable
private fun DirectHeaterControlCard(
  isHeaterOn: Boolean?,
  isBusy: Boolean,
  deviceName: String,
  statusMessage: String?,
  onTurnOn: () -> Unit,
  onTurnOff: () -> Unit,
  onRefresh: () -> Unit,
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
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Direct Heater Control",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
          val statusLabel =
            when (isHeaterOn) {
              true -> "STATUS: ON (${deviceName.ifBlank { "Switch" }})"
              false -> "STATUS: OFF (${deviceName.ifBlank { "Switch" }})"
              null -> statusMessage ?: "Tap Refresh or select switch"
            }
          Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelMedium,
            color =
              if (isHeaterOn == true) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        TextButton(
          onClick = onRefresh,
          enabled = !isBusy,
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
          Text("Refresh", fontSize = 13.sp)
        }
      }

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
            text = "START HEATER",
            fontSize = 14.sp,
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
            text = "STOP HEATER",
            fontSize = 14.sp,
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
  onAutoOffOffsetChanged: (Int) -> Unit,
  onToggleWeekday: (Int) -> Unit,
  onSetScheduleEnabled: (Boolean) -> Unit,
  onSyncSchedule: () -> Unit,
) {
  val schedule = state.scheduleConfig
  val window = state.heatingWindow

  Card(
    shape = RoundedCornerShape(24.dp),
    colors =
      CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Title + Master Schedule Switch
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "When Car Should Be Warm",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = HeaterScheduleCalculator.formatCountdown(window.durationUntilStart),
            style = MaterialTheme.typography.bodySmall,
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

      // Quick Morning Presets
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        val presets = listOf(6 to 30, 7 to 0, 7 to 30, 8 to 0)
        presets.forEach { (h, m) ->
          SuggestionChip(
            onClick = { onReadyTimeChanged(h, m) },
            label = { Text("%02d:%02d".format(h, m), fontSize = 12.sp) },
          )
        }
      }

      // Calculated 2-Hour Pre-Heat & Auto-Off Summary Timeline
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f))
            .padding(vertical = 8.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TimelineMetric(
          label = "HEATER ON (-2h)",
          timeText = "%02d:%02d".format(window.startTime.hour, window.startTime.minute),
          highlight = true,
        )
        Text(
          text = "→",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        TimelineMetric(
          label = "CAR WARM",
          timeText = "%02d:%02d".format(window.readyTime.hour, window.readyTime.minute),
          highlight = false,
        )
        Text(
          text = "→",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        TimelineMetric(
          label = "AUTO OFF (+${schedule.autoOffOffsetMinutes}m)",
          timeText = "%02d:%02d".format(window.stopTime.hour, window.stopTime.minute),
          highlight = false,
        )
      }

      // Samsung Alarm-style Day-of-Week Selector
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val daySummary =
          if (schedule.activeWeekdays.isEmpty()) {
            "Next occurrence (${window.nextReadyDateTime.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }})"
          } else {
            "Repeating on selected days"
          }
        Text(
          text = daySummary,
          style = MaterialTheme.typography.labelSmall,
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

      // Auto Turn-Off Offset Configuration
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = "Auto-off:",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(0, 15, 30, 60).forEach { mins ->
          FilterChip(
            selected = schedule.autoOffOffsetMinutes == mins,
            onClick = { onAutoOffOffsetChanged(mins) },
            label = {
              Text(
                text = if (mins == 0) "0m" else "+${mins}m",
                fontSize = 11.sp,
                maxLines = 1,
              )
            },
            modifier = Modifier.weight(1f),
          )
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
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      fontSize = 9.sp,
      color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
      textAlign = TextAlign.Center,
    )
    Text(
      text = timeText,
      style = MaterialTheme.typography.titleMedium,
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
      onAutoOffOffsetChanged = {},
      onToggleWeekday = {},
      onSetScheduleEnabled = {},
      onSyncSchedule = {},
      modifier = Modifier.padding(16.dp),
    )
  }
}
