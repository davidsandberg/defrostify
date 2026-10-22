package com.example.carheater.data.telldus

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Telldus method constants used by both TellStick ZNet Local API and Telldus Live Cloud API.
 */
object TelldusMethods {
  const val TURNON = 1
  const val TURNOFF = 2
  const val BELL = 4
  const val TOGGLE = 8
  const val DIM = 16
  const val LEARN = 32
}

enum class ConnectionMode {
  LOCAL_ZNET,
  TELLDUS_CLOUD,
}

data class AuthConfig(
  val connectionMode: ConnectionMode = ConnectionMode.LOCAL_ZNET,
  // Local ZNet Lite v2 settings
  val localIp: String = "",
  val localBearerToken: String = "",
  val pendingRequestToken: String = "",
  val pendingAuthUrl: String = "",
  // Telldus Live Cloud OAuth 1.0a settings (for hybrid/remote access)
  val cloudPublicKey: String = "",
  val cloudPrivateKey: String = "",
  val cloudToken: String = "",
  val cloudTokenSecret: String = "",
  // Selected Fibaro / Heater Switch
  val selectedDeviceId: Int? = null,
  val selectedDeviceName: String = "",
) {
  val isAuthenticated: Boolean
    get() =
      when (connectionMode) {
        ConnectionMode.LOCAL_ZNET -> localIp.isNotBlank() && localBearerToken.isNotBlank()
        ConnectionMode.TELLDUS_CLOUD ->
          cloudPublicKey.isNotBlank() &&
            cloudPrivateKey.isNotBlank() &&
            cloudToken.isNotBlank() &&
            cloudTokenSecret.isNotBlank()
      }
}

data class DiscoveredTellStick(
  val ipAddress: String,
  val product: String,
  val mac: String,
  val activationCode: String,
  val firmwareVersion: String,
)

data class TelldusTokenRequestResponse(
  val token: String,
  val authUrl: String,
)

data class TelldusAccessTokenResponse(
  val token: String,
  val allowRenew: Boolean = true,
  val expires: Long? = null,
)

data class TelldusDevice(
  val id: Int,
  val name: String,
  val state: Int,
  val stateValue: String? = null,
  val methods: Int = TelldusMethods.TURNON or TelldusMethods.TURNOFF,
  val type: String = "device",
) {
  val isOn: Boolean
    get() = state == TelldusMethods.TURNON
}

data class TelldusScheduleJob(
  val id: Int,
  val deviceId: Int,
  val method: Int,
  val methodValue: String = "",
  val hour: Int,
  val minute: Int,
  val type: String = "time",
  val weekdays: String, // e.g. "1,2,3,4,5,6,7" where 1=Mon..7=Sun
  val active: Boolean = true,
  val reps: Int = 1,
)

data class HeaterScheduleConfig(
  val enabled: Boolean = false,
  val readyHour: Int = 7,
  val readyMinute: Int = 30,
  val preHeatMinutes: Int = 120, // 2 hours before ready time
  val autoOffOffsetMinutes: Int = 30, // Turn off 30 minutes after ready time (0 = at ready time)
  val activeWeekdays: Set<Int> = emptySet(), // 1=Mon..7=Sun; empty means next single occurrence
  val turnOnJobId: Int? = null,
  val turnOffJobId: Int? = null,
)

data class CalculatedHeatingWindow(
  val readyTime: LocalTime,
  val startTime: LocalTime,
  val stopTime: LocalTime,
  val startWeekdaysCsv: String,
  val stopWeekdaysCsv: String,
  val nextStartDateTime: LocalDateTime,
  val nextReadyDateTime: LocalDateTime,
  val nextStopDateTime: LocalDateTime,
  val durationUntilStart: Duration,
)

object HeaterScheduleCalculator {
  /**
   * Computes the Turn-ON time (2 hours before readyTime), Auto-OFF time,
   * and properly shifted Telldus weekday strings (1=Monday .. 7=Sunday) if midnight is crossed.
   */
  fun calculate(
    config: HeaterScheduleConfig,
    now: LocalDateTime = LocalDateTime.now(),
  ): CalculatedHeatingWindow {
    val readyTime = LocalTime.of(config.readyHour.coerceIn(0, 23), config.readyMinute.coerceIn(0, 59))
    val startTime = readyTime.minusMinutes(config.preHeatMinutes.toLong())
    val stopTime = readyTime.plusMinutes(config.autoOffOffsetMinutes.toLong())

    val readyMinutesOfDay = readyTime.hour * 60 + readyTime.minute
    val startDayOffset =
      if (readyMinutesOfDay - config.preHeatMinutes < 0) -1 else 0
    val stopDayOffset =
      if (readyMinutesOfDay + config.autoOffOffsetMinutes >= 24 * 60) 1 else 0

    // Determine effective ready weekdays (1=Monday .. 7=Sunday)
    val nextReadyDateTime = computeNextReadyDateTime(readyTime, config.activeWeekdays, now)
    val effectiveReadyWeekdays: Set<Int> =
      if (config.activeWeekdays.isNotEmpty()) {
        config.activeWeekdays
      } else {
        setOf(nextReadyDateTime.dayOfWeek.value)
      }

    val startWeekdays =
      effectiveReadyWeekdays
        .map { day -> shiftWeekday(day, startDayOffset) }
        .sorted()
        .joinToString(",")

    val stopWeekdays =
      effectiveReadyWeekdays
        .map { day -> shiftWeekday(day, stopDayOffset) }
        .sorted()
        .joinToString(",")

    val nextStartDateTime = nextReadyDateTime.minusMinutes(config.preHeatMinutes.toLong())
    val nextStopDateTime = nextReadyDateTime.plusMinutes(config.autoOffOffsetMinutes.toLong())
    val durationUntilStart = Duration.between(now, nextStartDateTime)

    return CalculatedHeatingWindow(
      readyTime = readyTime,
      startTime = startTime,
      stopTime = stopTime,
      startWeekdaysCsv = startWeekdays,
      stopWeekdaysCsv = stopWeekdays,
      nextStartDateTime = nextStartDateTime,
      nextReadyDateTime = nextReadyDateTime,
      nextStopDateTime = nextStopDateTime,
      durationUntilStart = durationUntilStart,
    )
  }

  private fun computeNextReadyDateTime(
    readyTime: LocalTime,
    activeWeekdays: Set<Int>,
    now: LocalDateTime,
  ): LocalDateTime {
    var candidate = now.toLocalDate().atTime(readyTime)
    // If today's ready time has already passed, start checking from tomorrow
    if (!candidate.isAfter(now)) {
      candidate = candidate.plusDays(1)
    }
    if (activeWeekdays.isEmpty()) {
      return candidate
    }
    for (i in 0..7) {
      val check = candidate.plusDays(i.toLong())
      if (activeWeekdays.contains(check.dayOfWeek.value)) {
        return check
      }
    }
    return candidate
  }

  internal fun shiftWeekday(day: Int, offsetDays: Int): Int {
    // day is 1..7 (Monday..Sunday)
    val zeroBased = (day - 1 + offsetDays).mod(7)
    return zeroBased + 1
  }

  fun formatCountdown(duration: Duration): String {
    if (duration.isNegative || duration.isZero) {
      return "Heater start time reached (active heating window)"
    }
    val totalMinutes = duration.toMinutes()
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return buildString {
      append("Heater starts in ")
      if (days > 0) append("${days}d ")
      if (hours > 0 || days > 0) append("${hours}h ")
      append("${minutes}m")
    }
  }
}
