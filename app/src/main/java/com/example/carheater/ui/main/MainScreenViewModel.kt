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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
  private var autoSyncJob: Job? = null
  private var activeSyncJob: Job? = null
  private var statusPollJob: Job? = null
  private var lastLocalTriggerKey: String = ""

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
    if (loadedAuth.localBearerToken.isNotBlank() && !loadedAuth.hasCloudCredentials) {
      autoFetchCloudKeys(silent = true)
    }
  }

  fun autoFetchCloudKeys(silent: Boolean = false) {
    viewModelScope.launch {
      if (!silent) {
        _uiState.update {
          it.copy(
            isBusy = true,
            statusMessage = "Auto-fetching Telldus Cloud keys from active login session...",
            errorMessage = null,
          )
        }
      }
      val cloudKeys = apiClient.fetchCloudKeysFromWebViewSession()
      if (cloudKeys != null) {
        val updatedAuth =
          _uiState.value.authConfig.copy(
            cloudPublicKey = cloudKeys.publicKey,
            cloudPrivateKey = cloudKeys.privateKey,
            cloudToken = cloudKeys.token,
            cloudTokenSecret = cloudKeys.tokenSecret,
          )
        storage?.saveAuthConfig(updatedAuth)
        _uiState.update {
          it.copy(
            isBusy = false,
            authConfig = updatedAuth,
            statusMessage =
              if (silent) it.statusMessage
              else "Telldus Cloud keys configured automatically!",
            errorMessage = null,
          )
        }
        syncScheduleFromTelldus(silent = true)
      } else if (!silent) {
        _uiState.update {
          it.copy(
            isBusy = false,
            errorMessage =
              "Could not auto-fetch Cloud keys yet. Please sign in to TellStick ZNet first so your Telldus session is active.",
          )
        }
      }
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
    if (_uiState.value.scheduleConfig.enabled) {
      syncScheduleToTelldus()
    }
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
          val cloudKeys = apiClient.fetchCloudKeysFromWebViewSession()
          val updatedAuth =
            auth.copy(
              localBearerToken = accessResp.token,
              pendingRequestToken = "",
              pendingAuthUrl = "",
              cloudPublicKey = cloudKeys?.publicKey ?: auth.cloudPublicKey,
              cloudPrivateKey = cloudKeys?.privateKey ?: auth.cloudPrivateKey,
              cloudToken = cloudKeys?.token ?: auth.cloudToken,
              cloudTokenSecret = cloudKeys?.tokenSecret ?: auth.cloudTokenSecret,
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
    startStatusPolling()
  }

  fun onAppPaused() {
    statusPollJob?.cancel()
    statusPollJob = null
  }

  private fun startStatusPolling() {
    statusPollJob?.cancel()
    statusPollJob =
      viewModelScope.launch {
        while (true) {
          delay(5000L)
          val current = _uiState.value
          val auth = current.authConfig
          val deviceId = auth.selectedDeviceId
          if (auth.isAuthenticated && deviceId != null) {
            if (current.scheduleConfig.enabled) {
              val window = current.heatingWindow
              val nowTime = java.time.LocalTime.now()
              val onKey = "ON:${nowTime.hour}:${nowTime.minute}"
              val offKey = "OFF:${nowTime.hour}:${nowTime.minute}"
              if (nowTime.hour == window.startTime.hour &&
                nowTime.minute == window.startTime.minute &&
                lastLocalTriggerKey != onKey
              ) {
                lastLocalTriggerKey = onKey
                if (current.isHeaterOn != true) {
                  runCatching { apiClient.turnOnDevice(auth, deviceId) }
                }
              } else if (nowTime.hour == window.stopTime.hour &&
                nowTime.minute == window.stopTime.minute &&
                lastLocalTriggerKey != offKey
              ) {
                lastLocalTriggerKey = offKey
                if (current.isHeaterOn != false) {
                  runCatching { apiClient.turnOffDevice(auth, deviceId) }
                }
              }
            }
            refreshSwitchState(silent = true)
          } else {
            _uiState.update {
              it.copy(heatingWindow = HeaterScheduleCalculator.calculate(it.scheduleConfig))
            }
          }
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
          if (updatedAuth.hasCloudCredentials &&
            updatedAuth.selectedDeviceId != null &&
            _uiState.value.scheduleConfig.turnOnJobId == null
          ) {
            syncScheduleFromTelldus(silent = true)
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
    // Reset job IDs when switching to a different device so we don't overwrite another switch's job
    val resetSchedule =
      _uiState.value.scheduleConfig.copy(
        turnOnJobId = null,
        turnOffJobId = null,
      )
    storage?.saveScheduleConfig(resetSchedule)
    _uiState.update {
      it.copy(
        authConfig = updatedAuth,
        scheduleConfig = resetSchedule,
        isHeaterOn = device.isOn,
        statusMessage = "Selected switch: ${device.name} (ID ${device.id})",
      )
    }
    if (updatedAuth.hasCloudCredentials) {
      syncScheduleFromTelldus(silent = true)
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
    if (updated.enabled) {
      debouncedSyncSchedule()
    }
  }

  private fun debouncedSyncSchedule(delayMs: Long = 3000L) {
    autoSyncJob?.cancel()
    autoSyncJob =
      viewModelScope.launch {
        delay(delayMs)
        syncScheduleToTelldus(_uiState.value.scheduleConfig)
      }
  }

  fun updatePreHeatMinutes(minutes: Int) {
    val sanitized = minutes.coerceIn(1, 720)
    val updated = _uiState.value.scheduleConfig.copy(preHeatMinutes = sanitized)
    storage?.saveScheduleConfig(updated)
    _uiState.update {
      it.copy(
        scheduleConfig = updated,
        heatingWindow = HeaterScheduleCalculator.calculate(updated),
      )
    }
  }

  fun updateAutoOffOffset(offsetMinutes: Int) {
    val sanitized = offsetMinutes.coerceIn(0, 720)
    val updated = _uiState.value.scheduleConfig.copy(autoOffOffsetMinutes = sanitized)
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
    if (updated.enabled) {
      debouncedSyncSchedule()
    }
  }

  fun setScheduleEnabled(enabled: Boolean) {
    autoSyncJob?.cancel()
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

  fun syncScheduleFromTelldus(silent: Boolean = true) {
    val auth = _uiState.value.authConfig
    val deviceId = auth.selectedDeviceId ?: return
    if (!auth.hasCloudCredentials) return

    viewModelScope.launch {
      runCatching {
        val cloudDeviceId = apiClient.resolveCloudDeviceId(auth, deviceId)
        val jobs = apiClient.listScheduleJobs(auth).filter { it.deviceId == cloudDeviceId }
        val onJob = jobs.firstOrNull { it.method == TelldusMethods.TURNON }
        val offJob = jobs.firstOrNull { it.method == TelldusMethods.TURNOFF }
        Triple(onJob, offJob, cloudDeviceId)
      }.onSuccess { (onJob, offJob, _) ->
        if (onJob != null) {
          val current = _uiState.value.scheduleConfig
          val onTime = java.time.LocalTime.of(onJob.hour.coerceIn(0, 23), onJob.minute.coerceIn(0, 59))
          val readyTime = onTime.plusMinutes(current.preHeatMinutes.toLong())
          val syncedConfig =
            current.copy(
              enabled = onJob.active,
              readyHour = readyTime.hour,
              readyMinute = readyTime.minute,
              turnOnJobId = onJob.id,
              turnOffJobId = offJob?.id ?: current.turnOffJobId,
            )
          storage?.saveScheduleConfig(syncedConfig)
          _uiState.update {
            it.copy(
              scheduleConfig = syncedConfig,
              heatingWindow = HeaterScheduleCalculator.calculate(syncedConfig),
            )
          }
        }
      }
    }
  }

  fun syncScheduleToTelldus(configOverride: HeaterScheduleConfig? = null) {
    val config = configOverride ?: _uiState.value.scheduleConfig
    val initialAuth = _uiState.value.authConfig
    val deviceId = initialAuth.selectedDeviceId
    val window = HeaterScheduleCalculator.calculate(config)

    if (!initialAuth.isAuthenticated || deviceId == null) {
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

    activeSyncJob?.cancel()
    activeSyncJob =
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
        var auth = _uiState.value.authConfig
        if (!auth.hasCloudCredentials) {
          val fetched = apiClient.fetchCloudKeysFromWebViewSession()
          if (fetched != null) {
            auth =
              auth.copy(
                cloudPublicKey = fetched.publicKey,
                cloudPrivateKey = fetched.privateKey,
                cloudToken = fetched.token,
                cloudTokenSecret = fetched.tokenSecret,
              )
            storage?.saveAuthConfig(auth)
            _uiState.update { it.copy(authConfig = auth) }
          }
        }

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
        if (savedOnId > 0) {
          val partial = config.copy(turnOnJobId = savedOnId)
          storage?.saveScheduleConfig(partial)
          _uiState.update { it.copy(scheduleConfig = partial) }
        }

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
          if (err is kotlinx.coroutines.CancellationException) return@onFailure
          if (err.message?.contains("too many requests", ignoreCase = true) == true) {
            _uiState.update {
              it.copy(
                isBusy = false,
                errorMessage = null,
                statusMessage = "Schedule active locally — queued for TellStick cloud sync...",
              )
            }
            debouncedSyncSchedule(delayMs = 25_000L)
            return@onFailure
          }
          _uiState.update {
            it.copy(
              isBusy = false,
              errorMessage = "Failed to sync schedule to TellStick (${err.message}).",
            )
          }
        }
    }
  }
}
