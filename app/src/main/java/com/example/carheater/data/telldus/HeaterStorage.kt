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
        111
      }

    return AuthConfig(
      connectionMode = mode,
      localIp = prefs.getString("local_ip", "")?.ifBlank { null } ?: DEFAULT_LOCAL_IP,
      localBearerToken =
        prefs.getString("local_bearer_token", "")?.ifBlank { null } ?: DEFAULT_LOCAL_BEARER_TOKEN,
      pendingRequestToken = prefs.getString("pending_request_token", "") ?: "",
      pendingAuthUrl = prefs.getString("pending_auth_url", "") ?: "",
      cloudPublicKey =
        prefs.getString("cloud_public_key", "")?.ifBlank { null } ?: DEFAULT_CLOUD_PUBLIC_KEY,
      cloudPrivateKey =
        prefs.getString("cloud_private_key", "")?.ifBlank { null } ?: DEFAULT_CLOUD_PRIVATE_KEY,
      cloudToken =
        prefs.getString("cloud_token", "")?.ifBlank { null } ?: DEFAULT_CLOUD_TOKEN,
      cloudTokenSecret =
        prefs.getString("cloud_token_secret", "")?.ifBlank { null } ?: DEFAULT_CLOUD_TOKEN_SECRET,
      selectedDeviceId = selectedId,
      selectedDeviceName =
        prefs.getString("selected_device_name", "")?.ifBlank { null } ?: DEFAULT_DEVICE_NAME,
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
        85962992
      }
    val turnOffJobId =
      if (prefs.contains("turn_off_job_id")) {
        prefs.getInt("turn_off_job_id", -1).takeIf { it > 0 }
      } else {
        85962994
      }

    val targetDate = prefs.getString("target_date", null)

    return HeaterScheduleConfig(
      enabled = prefs.getBoolean("schedule_enabled", false),
      readyHour = prefs.getInt("ready_hour", 7).coerceIn(0, 23),
      readyMinute = prefs.getInt("ready_minute", 30).coerceIn(0, 59),
      preHeatMinutes = prefs.getInt("pre_heat_minutes", 120),
      autoOffOffsetMinutes = prefs.getInt("auto_off_offset_minutes", 30),
      activeWeekdays = weekdays,
      turnOnJobId = turnOnJobId,
      turnOffJobId = turnOffJobId,
      targetDate = targetDate,
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
        if (config.targetDate != null) {
          putString("target_date", config.targetDate)
        } else {
          remove("target_date")
        }
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

  fun loadRecentTimes(): List<Pair<Int, Int>> {
    val raw = prefs.getString("recent_ready_times", "") ?: ""
    if (raw.isBlank()) {
      return listOf(Pair(7, 30), Pair(8, 0), Pair(16, 30))
    }
    return raw.split(";")
      .mapNotNull { entry ->
        val parts = entry.split(":")
        if (parts.size == 2) {
          val h = parts[0].toIntOrNull()
          val m = parts[1].toIntOrNull()
          if (h != null && m != null) Pair(h.coerceIn(0, 23), m.coerceIn(0, 59)) else null
        } else null
      }
      .distinct()
      .take(3)
  }

  fun saveRecentTime(hour: Int, minute: Int) {
    val h = hour.coerceIn(0, 23)
    val m = minute.coerceIn(0, 59)
    val existing = loadRecentTimes().toMutableList()
    existing.removeAll { it.first == h && it.second == m }
    existing.add(0, Pair(h, m))
    val serialized = existing.take(3).joinToString(";") { "${it.first}:${it.second}" }
    prefs.edit().putString("recent_ready_times", serialized).apply()
  }

  fun loadDeviceMappings(): Map<Int, Int> {
    val raw = prefs.getString("device_mappings", "") ?: ""
    if (raw.isBlank()) return emptyMap()
    return raw.split(";")
      .mapNotNull { entry ->
        val parts = entry.split(":")
        if (parts.size == 2) {
          val local = parts[0].toIntOrNull()
          val cloud = parts[1].toIntOrNull()
          if (local != null && cloud != null) local to cloud else null
        } else null
      }
      .toMap()
  }

  fun saveDeviceMapping(localId: Int, cloudId: Int) {
    if (localId <= 0 || cloudId <= 0) return
    val current = loadDeviceMappings().toMutableMap()
    current[localId] = cloudId
    val serialized = current.entries.joinToString(";") { "${it.key}:${it.value}" }
    prefs.edit().putString("device_mappings", serialized).apply()
  }

  companion object {
    private const val DEFAULT_LOCAL_IP = "192.168.1.17"
    private const val DEFAULT_LOCAL_BEARER_TOKEN =
      "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCIsImF1ZCI6IkNhckhlYXRlciIsImV4cCI6MTgyNDMwNDc3NH0.e30.Oozx2yHNEsI0M7URjd2GenBQO__0cFTC77uhrljfgWg"
    private const val DEFAULT_CLOUD_PUBLIC_KEY = "FEHUVEW84RAFR5SP22RABURUPHAFRUNU"
    private const val DEFAULT_CLOUD_PRIVATE_KEY = "ZUXEVEGA9USTAZEWRETHAQUBUR69U6EF"
    private const val DEFAULT_CLOUD_TOKEN = "4eaaaf53ce9a55cc8a92a210fa36edff069cfafd6"
    private const val DEFAULT_CLOUD_TOKEN_SECRET = "884f66e31344382d814d63b46327e0e7"
    private const val DEFAULT_DEVICE_NAME = "Belysning Pergola"
  }
}
