package com.example.carheater.data.telldus

import android.content.Context
import android.content.SharedPreferences

class HeaterStorage(context: Context) {
  private val prefs: SharedPreferences =
    context.getSharedPreferences("car_heater_prefs", Context.MODE_PRIVATE)

  fun loadAuthConfig(): AuthConfig {
    val modeName =
      prefs.getString("connection_mode", ConnectionMode.LOCAL_ZNET.name)
        ?: ConnectionMode.LOCAL_ZNET.name
    val mode =
      runCatching { ConnectionMode.valueOf(modeName) }.getOrDefault(ConnectionMode.LOCAL_ZNET)
    val selectedId =
      if (prefs.contains("selected_device_id")) {
        prefs.getInt("selected_device_id", -1).takeIf { it > 0 }
      } else {
        null
      }

    return AuthConfig(
      connectionMode = mode,
      localIp = prefs.getString("local_ip", "") ?: "",
      localBearerToken = prefs.getString("local_bearer_token", "") ?: "",
      pendingRequestToken = prefs.getString("pending_request_token", "") ?: "",
      pendingAuthUrl = prefs.getString("pending_auth_url", "") ?: "",
      cloudPublicKey = prefs.getString("cloud_public_key", "") ?: "",
      cloudPrivateKey = prefs.getString("cloud_private_key", "") ?: "",
      cloudToken = prefs.getString("cloud_token", "") ?: "",
      cloudTokenSecret = prefs.getString("cloud_token_secret", "") ?: "",
      selectedDeviceId = selectedId,
      selectedDeviceName = prefs.getString("selected_device_name", "") ?: "",
    )
  }

  fun saveAuthConfig(config: AuthConfig) {
    prefs
      .edit()
      .putString("connection_mode", config.connectionMode.name)
      .putString("local_ip", config.localIp.trim())
      .putString("local_bearer_token", config.localBearerToken.trim())
      .putString("pending_request_token", config.pendingRequestToken.trim())
      .putString("pending_auth_url", config.pendingAuthUrl.trim())
      .putString("cloud_public_key", config.cloudPublicKey.trim())
      .putString("cloud_private_key", config.cloudPrivateKey.trim())
      .putString("cloud_token", config.cloudToken.trim())
      .putString("cloud_token_secret", config.cloudTokenSecret.trim())
      .apply {
        if (config.selectedDeviceId != null) {
          putInt("selected_device_id", config.selectedDeviceId)
        } else {
          remove("selected_device_id")
        }
      }
      .putString("selected_device_name", config.selectedDeviceName)
      .apply()
  }

  fun loadScheduleConfig(): HeaterScheduleConfig {
    val weekdaysCsv = prefs.getString("active_weekdays", "") ?: ""
    val weekdays =
      weekdaysCsv
        .split(",")
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 1..7 }
        .toSet()

    val turnOnJobId =
      if (prefs.contains("turn_on_job_id")) {
        prefs.getInt("turn_on_job_id", -1).takeIf { it > 0 }
      } else {
        null
      }
    val turnOffJobId =
      if (prefs.contains("turn_off_job_id")) {
        prefs.getInt("turn_off_job_id", -1).takeIf { it > 0 }
      } else {
        null
      }

    return HeaterScheduleConfig(
      enabled = prefs.getBoolean("schedule_enabled", false),
      readyHour = prefs.getInt("ready_hour", 7).coerceIn(0, 23),
      readyMinute = prefs.getInt("ready_minute", 30).coerceIn(0, 59),
      preHeatMinutes = prefs.getInt("pre_heat_minutes", 120),
      autoOffOffsetMinutes = prefs.getInt("auto_off_offset_minutes", 30),
      activeWeekdays = weekdays,
      turnOnJobId = turnOnJobId,
      turnOffJobId = turnOffJobId,
    )
  }

  fun saveScheduleConfig(config: HeaterScheduleConfig) {
    prefs
      .edit()
      .putBoolean("schedule_enabled", config.enabled)
      .putInt("ready_hour", config.readyHour)
      .putInt("ready_minute", config.readyMinute)
      .putInt("pre_heat_minutes", config.preHeatMinutes)
      .putInt("auto_off_offset_minutes", config.autoOffOffsetMinutes)
      .putString("active_weekdays", config.activeWeekdays.sorted().joinToString(","))
      .apply {
        if (config.turnOnJobId != null) {
          putInt("turn_on_job_id", config.turnOnJobId)
        } else {
          remove("turn_on_job_id")
        }
        if (config.turnOffJobId != null) {
          putInt("turn_off_job_id", config.turnOffJobId)
        } else {
          remove("turn_off_job_id")
        }
      }
      .apply()
  }
}
