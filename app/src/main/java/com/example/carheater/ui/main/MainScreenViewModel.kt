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
  val stagedReadyHour: Int = 7,
  val stagedReadyMinute: Int = 30,
  val recentReadyTimes: List<Pair<Int, Int>> = listOf(Pair(7, 30), Pair(8, 0), Pair(16, 30)),
  val isHeaterOn: Boolean? = null,
  val powerWatts: Double? = null,
  val availableDevices: List<TelldusDevice> = emptyList(),
  val discoveredTellSticks: List<DiscoveredTellStick> = emptyList(),
  val isBusy: Boolean = false,
  val isRefreshing: Boolean = false,
  val isSyncingSchedule: Boolean = false,
  val showAuthDialog: Boolean = false,
  val statusMessage: String? = null,
  val errorMessage: String? = null,
) {
  val hasUnsavedTimeChanges: Boolean
    get() = stagedReadyHour != scheduleConfig.readyHour || stagedReadyMinute != scheduleConfig.readyMinute
}

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
  private var lastManualCommandTimeMs: Long = 0L
  private var lastManualCommandTargetOn: Boolean? = null
  private var lastScheduleSyncTimeMs: Long = 0L

  init {
    TelldusApiClient.onDeviceMappingRegistered = { lId, cId ->
      storage?.saveDeviceMapping(lId, cId)
    }
    storage?.loadDeviceMappings()?.forEach { (lId, cId) ->
      TelldusApiClient.registerDeviceMapping(lId, cId)
    }

    val loadedAuth = storage?.loadAuthConfig() ?: AuthConfig()
    val loadedSchedule = storage?.loadScheduleConfig() ?: HeaterScheduleConfig()
    val recentTimes = storage?.loadRecentTimes() ?: listOf(Pair(7, 30), Pair(8, 0), Pair(16, 30))
    _uiState.update {
      it.copy(
        authConfig = loadedAuth,
        scheduleConfig = loadedSchedule,
        heatingWindow = HeaterScheduleCalculator.calculate(loadedSchedule),
        stagedReadyHour = loadedSchedule.readyHour,
        stagedReadyMinute = loadedSchedule.readyMinute,
        recentReadyTimes = recentTimes,
      )
    }
    if (loadedAuth.isAuthenticated) {
      fetchDevices(loadedAuth)
      if (loadedAuth.hasCloudCredentials) {
        syncScheduleFromTelldus(silent = true)
      }
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
      runCatching { apiClient.requestLocalToken(trimmedIp, appName = "Defrostify") }
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
      // Immediately refresh switch status and schedule upon resume
      refreshSwitchState(silent = true)
      if (auth.hasCloudCredentials) {
        syncScheduleFromTelldus(silent = true, force = true)
      } else {
        checkAndDisarmOneShotSchedule()
      }
      // Follow-up refresh after 2.5s to ensure Wi-Fi re-association after screen unlock has completed
      viewModelScope.launch {
        delay(2500L)
        refreshSwitchState(silent = true)
      }
    } else {
      // Refresh countdown calculation
      _uiState.update {
        it.copy(heatingWindow = HeaterScheduleCalculator.calculate(it.scheduleConfig))
      }
      checkAndDisarmOneShotSchedule()
    }
    startStatusPolling()
  }

  fun onAppPaused() {
    statusPollJob?.cancel()
    statusPollJob = null
  }

  private fun checkAndDisarmOneShotSchedule() {
    val state = _uiState.value
    val sched = state.scheduleConfig
    if (sched.enabled && sched.activeWeekdays.isEmpty()) {
      val targetDate = sched.targetDate
      val runDate = targetDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
      if (runDate != null) {
        // Once the switch is confirmed OFF, disarm 1 minute after stopTime;
        // if still ON (waiting for Z-Wave retries), give the controller 6 minutes before disarming.
        val graceMinutes = if (state.isHeaterOn == false) 1L else 6L
        val disarmDateTime = runDate.atTime(java.time.LocalTime.of(sched.readyHour, sched.readyMinute))
          .plusMinutes(sched.autoOffOffsetMinutes.toLong() + graceMinutes)
        if (java.time.LocalDateTime.now().isAfter(disarmDateTime)) {
          if (state.authConfig.hasCloudCredentials) {
            // Sync from Telldus first so we never clobber a newer schedule set from another phone
            syncScheduleFromTelldus(silent = true, force = true)
          } else {
            setScheduleEnabled(false)
          }
        }
      }
    }
  }

  private fun startStatusPolling() {
    statusPollJob?.cancel()
    statusPollJob =
      viewModelScope.launch {
        while (true) {
          // When on local Wi-Fi, poll every 15s.
          // When remote (Cloud), poll every 60s (~1 minute) while app is active.
          val intervalMs = if (TelldusApiClient.isLocalReachable()) 15_000L else 60_000L
          delay(intervalMs)
          checkAndDisarmOneShotSchedule()
          val current = _uiState.value
          val auth = current.authConfig
          val deviceId = auth.selectedDeviceId
          if (auth.isAuthenticated && deviceId != null) {
            refreshSwitchState(silent = true)
            if (auth.hasCloudCredentials && !current.isSyncingSchedule) {
              syncScheduleFromTelldus(silent = true, force = false)
            }
          } else {
            _uiState.update {
              it.copy(heatingWindow = HeaterScheduleCalculator.calculate(it.scheduleConfig))
            }
          }
        }
      }
  }

  fun manualRefresh() {
    viewModelScope.launch {
      _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
      try {
        TelldusApiClient.markLocalSucceeded()
        performRefreshSwitchState(silent = false)
        val auth = _uiState.value.authConfig
        if (auth.hasCloudCredentials) {
          performSyncScheduleFromTelldus(silent = false, force = true)
        } else {
          checkAndDisarmOneShotSchedule()
        }
      } finally {
        _uiState.update { it.copy(isRefreshing = false) }
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
          val initialWatts =
            selectedDevice?.let { dev ->
              if (dev.isOn) apiClient.getDevicePowerWatts(updatedAuth, dev.id, dev.name) else 0.0
            }
          _uiState.update {
            it.copy(
              isBusy = false,
              authConfig = updatedAuth,
              availableDevices = list,
              isHeaterOn = selectedDevice?.isOn,
              powerWatts = initialWatts,
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
          val isRateLimit =
            err.message?.contains("429", ignoreCase = true) == true ||
              err.message?.contains("rate limit", ignoreCase = true) == true ||
              err.message?.contains("too many requests", ignoreCase = true) == true
          _uiState.update {
            it.copy(
              isBusy = false,
              errorMessage = if (isRateLimit) null else (err.message ?: "Failed to load devices."),
              statusMessage =
                if (isRateLimit) "Telldus Cloud rate limit active — using local switch configuration."
                else it.statusMessage,
            )
          }
        }
    }
  }

  fun selectDevice(device: TelldusDevice) {
    lastManualCommandTargetOn = null
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
        powerWatts = null,
        statusMessage = "Selected switch: ${device.name} (ID ${device.id})",
      )
    }
    refreshSwitchState(silent = true)
    if (updatedAuth.hasCloudCredentials) {
      syncScheduleFromTelldus(silent = true)
    }
  }

  fun refreshSwitchState(silent: Boolean = false) {
    viewModelScope.launch {
      performRefreshSwitchState(silent = silent)
    }
  }

  private suspend fun performRefreshSwitchState(silent: Boolean = false) {
    val auth = _uiState.value.authConfig
    val deviceId = auth.selectedDeviceId ?: return
    if (!auth.isAuthenticated) return

    if (!silent) {
      _uiState.update { it.copy(isBusy = true, errorMessage = null) }
    }
    runCatching {
      val device = apiClient.getDeviceInfo(auth, deviceId)
      val pendingTarget = lastManualCommandTargetOn
      val elapsedSinceManualMs = System.currentTimeMillis() - lastManualCommandTimeMs
      val effectiveIsOn =
        if (pendingTarget != null && elapsedSinceManualMs in 0..75_000L) {
          if (device.isOn == pendingTarget) {
            lastManualCommandTargetOn = null
          }
          pendingTarget
        } else {
          lastManualCommandTargetOn = null
          device.isOn
        }
      val watts =
        if (!effectiveIsOn) {
          0.0
        } else if (TelldusApiClient.isLocalReachable() || !silent) {
          apiClient.getDevicePowerWatts(auth, deviceId, device.name)
        } else {
          _uiState.value.powerWatts
        }
      Triple(device, effectiveIsOn, watts)
    }
      .onSuccess { (device, effectiveIsOn, watts) ->
        _uiState.update {
          it.copy(
            isBusy = false,
            isHeaterOn = effectiveIsOn,
            powerWatts = if (!effectiveIsOn) 0.0 else (watts ?: it.powerWatts),
            heatingWindow = HeaterScheduleCalculator.calculate(it.scheduleConfig),
            statusMessage =
              if (silent) it.statusMessage
              else "Switch '${device.name}' is currently ${if (effectiveIsOn) "ON" else "OFF"}.",
          )
        }
        checkAndDisarmOneShotSchedule()
      }
      .onFailure { err ->
        val isRateLimit =
          err.message?.contains("429", ignoreCase = true) == true ||
            err.message?.contains("rate limit", ignoreCase = true) == true ||
            err.message?.contains("too many requests", ignoreCase = true) == true
        _uiState.update {
          it.copy(
            isBusy = false,
            errorMessage =
              if (silent || isRateLimit) it.errorMessage
              else (err.message ?: "Failed to query switch status."),
            statusMessage =
              if (!silent && isRateLimit) "Telldus Cloud rate limit active — using last known status."
              else it.statusMessage,
          )
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

    // Temporarily pause background polling so user action gets immediate priority and mutex lock
    statusPollJob?.cancel()

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
          lastManualCommandTimeMs = System.currentTimeMillis()
          lastManualCommandTargetOn = turnOn
          _uiState.update {
            it.copy(
              isBusy = false,
              isHeaterOn = turnOn,
              powerWatts = if (!turnOn) 0.0 else it.powerWatts,
              statusMessage =
                if (turnOn) "Heater turned ON (${auth.selectedDeviceName})."
                else "Heater turned OFF (${auth.selectedDeviceName}).",
            )
          }
          // Resume background polling
          startStatusPolling()
          // When turning ON, wait ~2.5s for the Fibaro meter report to arrive and fetch live wattage
          if (turnOn) {
            val delayMs = if (TelldusApiClient.isLocalReachable()) 2500L else 4000L
            delay(delayMs)
            refreshSwitchState(silent = true)
          }
        }
        .onFailure { err ->
          startStatusPolling()
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
    val h = hour.coerceIn(0, 23)
    val m = minute.coerceIn(0, 59)
    _uiState.update {
      it.copy(
        stagedReadyHour = h,
        stagedReadyMinute = m,
      )
    }
  }

  fun selectRecentTime(hour: Int, minute: Int) {
    updateReadyTime(hour, minute)
  }

  fun commitScheduleTime() {
    val current = _uiState.value
    val h = current.stagedReadyHour
    val m = current.stagedReadyMinute
    storage?.saveRecentTime(h, m)
    val updatedRecent = storage?.loadRecentTimes() ?: current.recentReadyTimes
    val tempConfig = current.scheduleConfig.copy(
      readyHour = h,
      readyMinute = m,
      enabled = true,
    )
    val window = HeaterScheduleCalculator.calculate(tempConfig)
    val targetDate = if (tempConfig.activeWeekdays.isEmpty()) {
      window.nextReadyDateTime.toLocalDate().toString()
    } else {
      null
    }
    val updatedConfig = tempConfig.copy(targetDate = targetDate)
    storage?.saveScheduleConfig(updatedConfig)
    _uiState.update {
      it.copy(
        scheduleConfig = updatedConfig,
        heatingWindow = window,
        recentReadyTimes = updatedRecent,
      )
    }
    syncScheduleToTelldus(updatedConfig)
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
    if (updated.enabled) {
      syncScheduleToTelldus(updated)
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
    if (updated.enabled) {
      syncScheduleToTelldus(updated)
    }
  }

  fun toggleWeekday(day: Int) {
    if (day !in 1..7) return
    val current = _uiState.value.scheduleConfig.activeWeekdays
    val updatedDays = if (current.contains(day)) current - day else current + day
    val targetDate = if (updatedDays.isEmpty() && _uiState.value.scheduleConfig.enabled) {
      val temp = _uiState.value.scheduleConfig.copy(activeWeekdays = updatedDays)
      HeaterScheduleCalculator.calculate(temp).nextReadyDateTime.toLocalDate().toString()
    } else {
      null
    }
    val updated = _uiState.value.scheduleConfig.copy(activeWeekdays = updatedDays, targetDate = targetDate)
    storage?.saveScheduleConfig(updated)
    _uiState.update {
      it.copy(
        scheduleConfig = updated,
        heatingWindow = HeaterScheduleCalculator.calculate(updated),
      )
    }
    if (updated.enabled) {
      syncScheduleToTelldus(updated)
    }
  }

  fun setScheduleEnabled(enabled: Boolean) {
    autoSyncJob?.cancel()
    val current = _uiState.value.scheduleConfig
    val targetDate = if (enabled && current.activeWeekdays.isEmpty()) {
      val tempWindow = HeaterScheduleCalculator.calculate(current.copy(enabled = true))
      tempWindow.nextReadyDateTime.toLocalDate().toString()
    } else {
      current.targetDate
    }
    val updated = current.copy(enabled = enabled, targetDate = targetDate)
    storage?.saveScheduleConfig(updated)
    _uiState.update {
      it.copy(
        scheduleConfig = updated,
        heatingWindow = HeaterScheduleCalculator.calculate(updated),
      )
    }
    syncScheduleToTelldus(updated)
  }

  fun syncScheduleFromTelldus(silent: Boolean = true, force: Boolean = false) {
    viewModelScope.launch {
      performSyncScheduleFromTelldus(silent = silent, force = force)
    }
  }

  private suspend fun performSyncScheduleFromTelldus(silent: Boolean = true, force: Boolean = false) {
    val auth = _uiState.value.authConfig
    val deviceId = auth.selectedDeviceId ?: return

    // 1. Fast path on home Wi-Fi: read schedule metadata directly from the local TellStick ZNet
    //    via /api/sensor/info (< 20ms, zero rate limits, never hits Telldus Cloud 429).
    val localMetaStr = apiClient.getLocalScheduleMetadata(auth, deviceId)
    val localParsed = HeaterScheduleConfig.parseFromMethodValue(localMetaStr)
    if (localParsed != null) {
      val current = _uiState.value.scheduleConfig
      val preHeat = localParsed.preHeatMinutes ?: current.preHeatMinutes
      val postHeat = localParsed.autoOffOffsetMinutes ?: current.autoOffOffsetMinutes
      val readyTime = java.time.LocalTime.of(localParsed.readyHour, localParsed.readyMinute)
      val activeDays = localParsed.activeWeekdays ?: current.activeWeekdays
      var isEnabled = localParsed.active ?: current.enabled
      val targetDate = localParsed.targetDate

      if (isEnabled && activeDays.isEmpty() && !targetDate.isNullOrBlank()) {
        val runDate = runCatching { java.time.LocalDate.parse(targetDate) }.getOrNull()
        if (runDate != null) {
          val graceMinutes = if (_uiState.value.isHeaterOn == false) 1L else 6L
          val disarmDateTime = runDate.atTime(readyTime).plusMinutes(postHeat.toLong() + graceMinutes)
          if (java.time.LocalDateTime.now().isAfter(disarmDateTime)) {
            isEnabled = false
          }
        }
      }

      val syncedConfig =
        current.copy(
          enabled = isEnabled,
          readyHour = readyTime.hour,
          readyMinute = readyTime.minute,
          preHeatMinutes = preHeat,
          autoOffOffsetMinutes = postHeat,
          activeWeekdays = activeDays,
          targetDate = targetDate,
        )
      storage?.saveScheduleConfig(syncedConfig)
      _uiState.update {
        it.copy(
          scheduleConfig = syncedConfig,
          heatingWindow = HeaterScheduleCalculator.calculate(syncedConfig),
          stagedReadyHour = readyTime.hour,
          stagedReadyMinute = readyTime.minute,
        )
      }
      if (localParsed.active == true && !isEnabled) {
        syncScheduleToTelldus(syncedConfig)
      }
      return
    }

    // 2. Fallback when away from home (or if Local ZNet has no metadata yet): query Telldus Cloud
    if (!auth.hasCloudCredentials) return
    val now = System.currentTimeMillis()
    if (!force && now - lastScheduleSyncTimeMs < 60_000L) return

    runCatching {
      val cloudDeviceId = apiClient.resolveCloudDeviceId(auth, deviceId)
      val jobs = apiClient.listScheduleJobs(auth).filter { it.deviceId == cloudDeviceId }
      val onJob = jobs.firstOrNull { it.method == TelldusMethods.TURNON }
      val offJob = jobs.firstOrNull { it.method == TelldusMethods.TURNOFF }
      Triple(onJob, offJob, cloudDeviceId)
    }.onSuccess { (onJob, offJob, _) ->
      lastScheduleSyncTimeMs = System.currentTimeMillis()
      if (onJob != null) {
        val current = _uiState.value.scheduleConfig
        val parsedMeta = HeaterScheduleConfig.parseFromMethodValue(onJob.methodValue)
        val preHeat = parsedMeta?.preHeatMinutes ?: current.preHeatMinutes
        val postHeat = parsedMeta?.autoOffOffsetMinutes ?: current.autoOffOffsetMinutes
        val readyTime = if (parsedMeta != null) {
          java.time.LocalTime.of(parsedMeta.readyHour, parsedMeta.readyMinute)
        } else {
          val onTime = java.time.LocalTime.of(onJob.hour.coerceIn(0, 23), onJob.minute.coerceIn(0, 59))
          onTime.plusMinutes(preHeat.toLong())
        }
        val activeDays = parsedMeta?.activeWeekdays ?: run {
          onJob.weekdays.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()
        }

        var isEnabled = onJob.active
        val targetDate = parsedMeta?.targetDate

        // Auto-disarm single-occurrence schedule once stop time + grace period has passed
        if (isEnabled && activeDays.isEmpty() && !targetDate.isNullOrBlank()) {
          val runDate = runCatching { java.time.LocalDate.parse(targetDate) }.getOrNull()
          if (runDate != null) {
            val graceMinutes = if (_uiState.value.isHeaterOn == false) 1L else 6L
            val disarmDateTime = runDate.atTime(readyTime).plusMinutes(postHeat.toLong() + graceMinutes)
            if (java.time.LocalDateTime.now().isAfter(disarmDateTime)) {
              isEnabled = false
            }
          }
        }

        val syncedConfig =
          current.copy(
            enabled = isEnabled,
            readyHour = readyTime.hour,
            readyMinute = readyTime.minute,
            preHeatMinutes = preHeat,
            autoOffOffsetMinutes = postHeat,
            activeWeekdays = activeDays,
            turnOnJobId = onJob.id,
            turnOffJobId = offJob?.id ?: current.turnOffJobId,
            targetDate = targetDate,
          )
        storage?.saveScheduleConfig(syncedConfig)
        _uiState.update {
          it.copy(
            scheduleConfig = syncedConfig,
            heatingWindow = HeaterScheduleCalculator.calculate(syncedConfig),
            stagedReadyHour = readyTime.hour,
            stagedReadyMinute = readyTime.minute,
          )
        }
        apiClient.saveLocalScheduleMetadata(
          auth = auth,
          deviceId = deviceId,
          deviceName = auth.selectedDeviceName,
          metadata = syncedConfig.toMethodValueString(targetDate),
        )

        // If a single-run schedule just completed and was auto-disarmed, or OFF job is out of sync, update Telldus
        if ((onJob.active && !isEnabled) || (offJob != null && offJob.active != isEnabled)) {
          syncScheduleToTelldus(syncedConfig)
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
    statusPollJob?.cancel()
    activeSyncJob =
      viewModelScope.launch {
        _uiState.update {
          it.copy(
            isSyncingSchedule = true,
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

        val targetDate = if (config.activeWeekdays.isEmpty()) {
          config.targetDate ?: window.nextReadyDateTime.toLocalDate().toString()
        } else {
          null
        }
        val methodValueMetadata = config.toMethodValueString(targetDate)

        // Immediately persist schedule metadata on the local TellStick ZNet (< 20ms, zero rate limits)
        // so any other phone on home Wi-Fi sees the updated schedule right away on swipe-down.
        apiClient.saveLocalScheduleMetadata(
          auth = auth,
          deviceId = deviceId,
          deviceName = auth.selectedDeviceName,
          metadata = methodValueMetadata,
        )

        // Query existing jobs for this device on Telldus only when job IDs are not yet cached
        val cloudDeviceId = apiClient.resolveCloudDeviceId(auth, deviceId)
        val knownOnId = config.turnOnJobId?.takeIf { it > 0 }
        val knownOffId = config.turnOffJobId?.takeIf { it > 0 }
        val (targetOnId, targetOffId) =
          if (knownOnId != null && knownOffId != null) {
            knownOnId to knownOffId
          } else {
            val existingJobs = runCatching { apiClient.listScheduleJobs(auth) }.getOrDefault(emptyList())
            val deviceJobs = existingJobs.filter { it.deviceId == cloudDeviceId }
            val existingOnJob = deviceJobs.firstOrNull { it.method == TelldusMethods.TURNON }
            val existingOffJob = deviceJobs.firstOrNull { it.method == TelldusMethods.TURNOFF }

            // Remove any orphaned/duplicate jobs for this device beyond the primary pair
            deviceJobs.forEach { job ->
              if (job.id != existingOnJob?.id && job.id != existingOffJob?.id) {
                apiClient.removeScheduleJob(auth, job.id)
              }
            }
            (knownOnId ?: existingOnJob?.id ?: 0) to (knownOffId ?: existingOffJob?.id ?: 0)
          }

        val onJob =
          TelldusScheduleJob(
            id = targetOnId,
            deviceId = cloudDeviceId,
            method = TelldusMethods.TURNON,
            methodValue = methodValueMetadata,
            hour = window.startTime.hour,
            minute = window.startTime.minute,
            weekdays = window.startWeekdaysCsv,
            active = config.enabled,
          )
        val savedOnId = apiClient.setScheduleJob(auth, onJob)
        if (savedOnId > 0) {
          val partial = config.copy(turnOnJobId = savedOnId, targetDate = targetDate)
          storage?.saveScheduleConfig(partial)
          _uiState.update { it.copy(scheduleConfig = partial) }
        }

        val offJob =
          TelldusScheduleJob(
            id = targetOffId,
            deviceId = cloudDeviceId,
            method = TelldusMethods.TURNOFF,
            methodValue = "",
            hour = window.stopTime.hour,
            minute = window.stopTime.minute,
            weekdays = window.stopWeekdaysCsv,
            active = config.enabled,
          )
        val savedOffId = apiClient.setScheduleJob(auth, offJob)
        savedOnId to savedOffId
      }
        .onSuccess { (savedOnId, savedOffId) ->
          startStatusPolling()
          val targetDate = if (config.activeWeekdays.isEmpty()) {
            config.targetDate ?: window.nextReadyDateTime.toLocalDate().toString()
          } else {
            null
          }
          val updatedSchedule =
            config.copy(
              turnOnJobId = savedOnId.takeIf { it > 0 } ?: config.turnOnJobId,
              turnOffJobId = savedOffId.takeIf { it > 0 } ?: config.turnOffJobId,
              targetDate = targetDate,
            )
          storage?.saveScheduleConfig(updatedSchedule)
          _uiState.update {
            it.copy(
              isBusy = false,
              isSyncingSchedule = false,
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
          startStatusPolling()
          _uiState.update { it.copy(isSyncingSchedule = false, isBusy = false) }
          if (err is kotlinx.coroutines.CancellationException) return@onFailure
          if (err.message?.contains("too many requests", ignoreCase = true) == true ||
            err.message?.contains("429", ignoreCase = true) == true
          ) {
            _uiState.update {
              it.copy(
                errorMessage = null,
                statusMessage = "Schedule saved locally. Telldus Cloud rate limit reached — retry in a few minutes.",
              )
            }
            return@onFailure
          }
          _uiState.update {
            it.copy(
              errorMessage = "Failed to sync schedule to TellStick (${err.message}).",
            )
          }
        }
    }
  }
}
