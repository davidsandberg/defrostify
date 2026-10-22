package com.example.carheater.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.carheater.data.telldus.AuthConfig
import com.example.carheater.data.telldus.CalculatedHeatingWindow
import com.example.carheater.data.telldus.DiscoveredTellStick
import com.example.carheater.data.telldus.HeaterScheduleCalculator
import com.example.carheater.data.telldus.HeaterScheduleConfig
import com.example.carheater.data.telldus.HeaterStorage
import com.example.carheater.data.telldus.TelldusApiClient
import com.example.carheater.data.telldus.TelldusDevice
import com.example.carheater.data.telldus.TelldusMethods
import com.example.carheater.data.telldus.TelldusScheduleJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HeaterUiState(
  val authConfig: AuthConfig = AuthConfig(),
  val scheduleConfig: HeaterScheduleConfig = HeaterScheduleConfig(),
  val heatingWindow: CalculatedHeatingWindow =
    HeaterScheduleCalculator.calculate(HeaterScheduleConfig()),
  val isHeaterOn: Boolean? = null,
  val availableDevices: List<TelldusDevice> = emptyList(),
  val discoveredTellSticks: List<DiscoveredTellStick> = emptyList(),
  val isBusy: Boolean = false,
  val showAuthDialog: Boolean = false,
  val statusMessage: String? = null,
  val errorMessage: String? = null,
)

class MainScreenViewModel(
  private val storage: HeaterStorage? = null,
  private val apiClient: TelldusApiClient = TelldusApiClient(),
) : ViewModel() {

  private val _uiState = MutableStateFlow(HeaterUiState())
  val uiState: StateFlow<HeaterUiState> = _uiState.asStateFlow()

  init {
    val loadedAuth = storage?.loadAuthConfig() ?: AuthConfig()
    val loadedSchedule = storage?.loadScheduleConfig() ?: HeaterScheduleConfig()
    _uiState.update {
      it.copy(
        authConfig = loadedAuth,
        scheduleConfig = loadedSchedule,
        heatingWindow = HeaterScheduleCalculator.calculate(loadedSchedule),
      )
    }
    if (loadedAuth.isAuthenticated) {
      fetchDevices(loadedAuth)
    }
  }

  fun openAuthDialog() {
    _uiState.update { it.copy(showAuthDialog = true, errorMessage = null) }
  }

  fun closeAuthDialog() {
    _uiState.update { it.copy(showAuthDialog = false) }
  }

  fun updateAuthConfig(newConfig: AuthConfig) {
    storage?.saveAuthConfig(newConfig)
    _uiState.update { it.copy(authConfig = newConfig) }
  }

  fun scanLanForTellStick() {
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isBusy = true,
          statusMessage = "Scanning local Wi-Fi (UDP port 30303) for TellStick ZNet...",
          errorMessage = null,
        )
      }
      runCatching { apiClient.discoverTellSticksOnLan() }
        .onSuccess { list ->
          val currentAuth = _uiState.value.authConfig
          val updatedAuth =
            if (currentAuth.localIp.isBlank() && list.isNotEmpty()) {
              currentAuth.copy(localIp = list.first().ipAddress).also { storage?.saveAuthConfig(it) }
            } else {
              currentAuth
            }
          _uiState.update {
            it.copy(
              isBusy = false,
              authConfig = updatedAuth,
              discoveredTellSticks = list,
              statusMessage =
                if (list.isNotEmpty()) {
                  "Found ${list.size} TellStick device(s): ${list.joinToString { d -> "${d.product} (${d.ipAddress})" }}"
                } else {
                  "No TellStick responded to UDP broadcast. Please enter the ZNet IP address manually."
                },
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isBusy = false,
              errorMessage = "LAN scan error: ${err.message}",
            )
          }
        }
    }
  }

  fun startLocalBrowserAuth(ip: String, onAuthUrlReady: (String) -> Unit) {
    val trimmedIp = ip.trim()
    if (trimmedIp.isBlank()) {
      _uiState.update { it.copy(errorMessage = "Please enter the TellStick ZNet IP address first.") }
      return
    }
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isBusy = true,
          statusMessage = "Requesting token from ZNet at $trimmedIp...",
          errorMessage = null,
        )
      }
      runCatching { apiClient.requestLocalToken(trimmedIp, appName = "CarHeater") }
        .onSuccess { tokenResp ->
          val updatedAuth =
            _uiState.value.authConfig.copy(
              localIp = trimmedIp,
              pendingRequestToken = tokenResp.token,
              pendingAuthUrl = tokenResp.authUrl,
            )
          storage?.saveAuthConfig(updatedAuth)
          _uiState.update {
            it.copy(
              isBusy = false,
              authConfig = updatedAuth,
              statusMessage =
                "Browser popup launched. Log in and approve access, then return here.",
            )
          }
          onAuthUrlReady(tokenResp.authUrl)
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isBusy = false,
              errorMessage = err.message ?: "Failed to request token from ZNet.",
            )
          }
        }
    }
  }

  fun completeLocalBrowserAuth(silentIfPending: Boolean = false) {
    val auth = _uiState.value.authConfig
    if (auth.localIp.isBlank() || auth.pendingRequestToken.isBlank()) {
      if (!silentIfPending) {
        _uiState.update {
          it.copy(errorMessage = "No pending browser login token. Tap '1. Authenticate in Browser Popup' first.")
        }
      }
      return
    }
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isBusy = true,
          statusMessage = "Exchanging request token for JWT Bearer token...",
          errorMessage = null,
        )
      }
      runCatching { apiClient.exchangeLocalToken(auth.localIp, auth.pendingRequestToken) }
        .onSuccess { accessResp ->
          val updatedAuth =
            auth.copy(
              localBearerToken = accessResp.token,
              pendingRequestToken = "",
              pendingAuthUrl = "",
            )
          storage?.saveAuthConfig(updatedAuth)
          _uiState.update {
            it.copy(
              isBusy = false,
              authConfig = updatedAuth,
              statusMessage = "Authenticated with ZNet Lite v2! Loading switches...",
              errorMessage = null,
            )
          }
          fetchDevices(updatedAuth)
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isBusy = false,
              errorMessage = if (silentIfPending) null else err.message,
              statusMessage =
                if (silentIfPending) {
                  "Waiting for browser approval. Tap '2. Complete Login' once approved."
                } else {
                  it.statusMessage
                },
            )
          }
        }
    }
  }

  fun onAppResumed() {
    val auth = _uiState.value.authConfig
    if (auth.pendingRequestToken.isNotBlank() && auth.localBearerToken.isBlank()) {
      completeLocalBrowserAuth(silentIfPending = true)
    } else if (auth.isAuthenticated && auth.selectedDeviceId != null) {
      refreshSwitchState(silent = true)
    } else {
      // Refresh countdown calculation
      _uiState.update {
        it.copy(heatingWindow = HeaterScheduleCalculator.calculate(it.scheduleConfig))
      }
    }
  }

  fun fetchDevices(authOverride: AuthConfig? = null) {
    val auth = authOverride ?: _uiState.value.authConfig
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isBusy = true,
          statusMessage = "Fetching switches from Telldus...",
          errorMessage = null,
        )
      }
      runCatching { apiClient.listDevices(auth) }
        .onSuccess { list ->
          val selectedId = auth.selectedDeviceId ?: list.firstOrNull()?.id
          val selectedDevice = list.firstOrNull { d -> d.id == selectedId } ?: list.firstOrNull()
          val updatedAuth =
            if (selectedDevice != null) {
              auth.copy(
                selectedDeviceId = selectedDevice.id,
                selectedDeviceName = selectedDevice.name,
              ).also { storage?.saveAuthConfig(it) }
            } else {
              auth
            }
          _uiState.update {
            it.copy(
              isBusy = false,
              authConfig = updatedAuth,
              availableDevices = list,
              isHeaterOn = selectedDevice?.isOn,
              statusMessage =
                if (list.isNotEmpty()) {
                  "Loaded ${list.size} switch(es)."
                } else {
                  "Connected, but no switches were returned by devices/list."
                },
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isBusy = false,
              errorMessage = err.message ?: "Failed to load devices.",
            )
          }
        }
    }
  }

  fun selectDevice(device: TelldusDevice) {
    val updatedAuth =
      _uiState.value.authConfig.copy(
        selectedDeviceId = device.id,
        selectedDeviceName = device.name,
      )
    storage?.saveAuthConfig(updatedAuth)
    _uiState.update {
      it.copy(
        authConfig = updatedAuth,
        isHeaterOn = device.isOn,
        statusMessage = "Selected switch: ${device.name} (ID ${device.id})",
      )
    }
  }

  fun refreshSwitchState(silent: Boolean = false) {
    val auth = _uiState.value.authConfig
    val deviceId = auth.selectedDeviceId ?: return
    if (!auth.isAuthenticated) return

    viewModelScope.launch {
      if (!silent) {
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }
      }
      runCatching { apiClient.getDeviceInfo(auth, deviceId) }
        .onSuccess { device ->
          _uiState.update {
            it.copy(
              isBusy = false,
              isHeaterOn = device.isOn,
              heatingWindow = HeaterScheduleCalculator.calculate(it.scheduleConfig),
              statusMessage =
                if (silent) it.statusMessage
                else "Switch '${device.name}' is currently ${if (device.isOn) "ON" else "OFF"}.",
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isBusy = false,
              errorMessage = if (silent) it.errorMessage else (err.message ?: "Failed to query switch status."),
            )
          }
        }
    }
  }

  fun setHeaterPower(turnOn: Boolean) {
    val auth = _uiState.value.authConfig
    val deviceId = auth.selectedDeviceId
    if (!auth.isAuthenticated || deviceId == null) {
      _uiState.update {
        it.copy(
          showAuthDialog = true,
          errorMessage = "Please configure authentication and select your Fibaro switch first.",
        )
      }
      return
    }

    viewModelScope.launch {
      val actionLabel = if (turnOn) "Starting heater (turnOn)..." else "Stopping heater (turnOff)..."
      _uiState.update {
        it.copy(
          isBusy = true,
          statusMessage = actionLabel,
          errorMessage = null,
        )
      }
      runCatching {
        if (turnOn) {
          apiClient.turnOnDevice(auth, deviceId)
        } else {
          apiClient.turnOffDevice(auth, deviceId)
        }
      }
        .onSuccess {
          _uiState.update {
            it.copy(
              isBusy = false,
              isHeaterOn = turnOn,
              statusMessage =
                if (turnOn) "Heater turned ON (${auth.selectedDeviceName})."
                else "Heater turned OFF (${auth.selectedDeviceName}).",
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isBusy = false,
              errorMessage = err.message ?: "Failed to control heater switch.",
            )
          }
        }
    }
  }

  fun updateReadyTime(hour: Int, minute: Int) {
    val updated =
      _uiState.value.scheduleConfig.copy(
        readyHour = hour.coerceIn(0, 23),
        readyMinute = minute.coerceIn(0, 59),
      )
    storage?.saveScheduleConfig(updated)
    _uiState.update {
      it.copy(
        scheduleConfig = updated,
        heatingWindow = HeaterScheduleCalculator.calculate(updated),
      )
    }
  }

  fun updateAutoOffOffset(offsetMinutes: Int) {
    val updated = _uiState.value.scheduleConfig.copy(autoOffOffsetMinutes = offsetMinutes)
    storage?.saveScheduleConfig(updated)
    _uiState.update {
      it.copy(
        scheduleConfig = updated,
        heatingWindow = HeaterScheduleCalculator.calculate(updated),
      )
    }
  }

  fun toggleWeekday(day: Int) {
    if (day !in 1..7) return
    val current = _uiState.value.scheduleConfig.activeWeekdays
    val updatedDays = if (current.contains(day)) current - day else current + day
    val updated = _uiState.value.scheduleConfig.copy(activeWeekdays = updatedDays)
    storage?.saveScheduleConfig(updated)
    _uiState.update {
      it.copy(
        scheduleConfig = updated,
        heatingWindow = HeaterScheduleCalculator.calculate(updated),
      )
    }
  }

  fun setScheduleEnabled(enabled: Boolean) {
    val updated = _uiState.value.scheduleConfig.copy(enabled = enabled)
    storage?.saveScheduleConfig(updated)
    _uiState.update {
      it.copy(
        scheduleConfig = updated,
        heatingWindow = HeaterScheduleCalculator.calculate(updated),
      )
    }
    syncScheduleToTelldus(updated)
  }

  fun syncScheduleToTelldus(configOverride: HeaterScheduleConfig? = null) {
    val config = configOverride ?: _uiState.value.scheduleConfig
    val auth = _uiState.value.authConfig
    val deviceId = auth.selectedDeviceId
    val window = HeaterScheduleCalculator.calculate(config)

    if (!auth.isAuthenticated || deviceId == null) {
      _uiState.update {
        it.copy(
          errorMessage =
            "Schedule saved locally (ON %02d:%02d, OFF %02d:%02d). Connect to TellStick to sync to controller."
              .format(
                window.startTime.hour,
                window.startTime.minute,
                window.stopTime.hour,
                window.stopTime.minute,
              )
        )
      }
      return
    }

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isBusy = true,
          statusMessage =
            "Syncing schedule to controller (ON %02d:%02d, OFF %02d:%02d)..."
              .format(
                window.startTime.hour,
                window.startTime.minute,
                window.stopTime.hour,
                window.stopTime.minute,
              ),
          errorMessage = null,
        )
      }
      runCatching {
        val onJob =
          TelldusScheduleJob(
            id = config.turnOnJobId ?: 0,
            deviceId = deviceId,
            method = TelldusMethods.TURNON,
            hour = window.startTime.hour,
            minute = window.startTime.minute,
            weekdays = window.startWeekdaysCsv,
            active = config.enabled,
          )
        val savedOnId = apiClient.setScheduleJob(auth, onJob)

        val offJob =
          TelldusScheduleJob(
            id = config.turnOffJobId ?: 0,
            deviceId = deviceId,
            method = TelldusMethods.TURNOFF,
            hour = window.stopTime.hour,
            minute = window.stopTime.minute,
            weekdays = window.stopWeekdaysCsv,
            active = config.enabled,
          )
        val savedOffId = apiClient.setScheduleJob(auth, offJob)
        Pair(savedOnId, savedOffId)
      }
        .onSuccess { (savedOnId, savedOffId) ->
          val updatedSchedule =
            config.copy(
              turnOnJobId = savedOnId.takeIf { it > 0 } ?: config.turnOnJobId,
              turnOffJobId = savedOffId.takeIf { it > 0 } ?: config.turnOffJobId,
            )
          storage?.saveScheduleConfig(updatedSchedule)
          _uiState.update {
            it.copy(
              isBusy = false,
              scheduleConfig = updatedSchedule,
              heatingWindow = window,
              statusMessage =
                if (config.enabled) {
                  "Schedule active on TellStick: Heater ON at %02d:%02d, Car warm at %02d:%02d, Auto-OFF at %02d:%02d."
                    .format(
                      window.startTime.hour,
                      window.startTime.minute,
                      window.readyTime.hour,
                      window.readyTime.minute,
                      window.stopTime.hour,
                      window.stopTime.minute,
                    )
                } else {
                  "Schedule disabled on TellStick."
                },
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isBusy = false,
              errorMessage =
                "Failed to sync schedule to TellStick (${err.message}). " +
                  "Note: If local ZNet firmware blocks /api/scheduler, add Telldus Live Cloud keys in Setup.",
            )
          }
        }
    }
  }
}
