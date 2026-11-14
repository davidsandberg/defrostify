package com.example.carheater.ui.main

import com.example.carheater.data.telldus.HeaterScheduleCalculator
import com.example.carheater.data.telldus.HeaterScheduleConfig
import java.time.LocalDateTime
import java.time.LocalTime
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MainScreenViewModelTest {
  @Test
  fun scheduleCalculator_computesTwoHourPreHeatAndAutoOff() {
    val config =
      HeaterScheduleConfig(
        enabled = true,
        readyHour = 7,
        readyMinute = 30,
        preHeatMinutes = 120,
        autoOffOffsetMinutes = 30,
        activeWeekdays = setOf(1, 2, 3, 4, 5),
      )
    val now = LocalDateTime.of(2026, 10, 22, 22, 0)
    val window = HeaterScheduleCalculator.calculate(config, now)

    assertEquals(LocalTime.of(7, 30), window.readyTime)
    assertEquals(LocalTime.of(5, 30), window.startTime)
    assertEquals(LocalTime.of(8, 0), window.stopTime)
    assertEquals("1,2,3,4,5", window.startWeekdaysCsv)
    assertEquals("1,2,3,4,5", window.stopWeekdaysCsv)
  }

  @Test
  fun scheduleCalculator_shiftsWeekdaysBackWhenCrossingMidnight() {
    // Ready at 01:00 on Monday (1) -> Heater starts at 23:00 on Sunday (7)
    val config =
      HeaterScheduleConfig(
        enabled = true,
        readyHour = 1,
        readyMinute = 0,
        preHeatMinutes = 120,
        autoOffOffsetMinutes = 30,
        activeWeekdays = setOf(1),
      )
    val now = LocalDateTime.of(2026, 10, 22, 22, 0)
    val window = HeaterScheduleCalculator.calculate(config, now)

    assertEquals(LocalTime.of(23, 0), window.startTime)
    assertEquals("7", window.startWeekdaysCsv)
    assertEquals("1", window.stopWeekdaysCsv)
  }

  @Test
  fun viewModel_updatesReadyTimeAndAutoOffOffset() = runTest {
    val viewModel = MainScreenViewModel()
    viewModel.updateReadyTime(6, 45)
    val stagedState = viewModel.uiState.value
    assertEquals(6, stagedState.stagedReadyHour)
    assertEquals(45, stagedState.stagedReadyMinute)
    assertEquals(true, stagedState.hasUnsavedTimeChanges)

    viewModel.commitScheduleTime()
    viewModel.updateAutoOffOffset(15)

    val state = viewModel.uiState.value
    assertEquals(6, state.scheduleConfig.readyHour)
    assertEquals(45, state.scheduleConfig.readyMinute)
    assertEquals(false, state.hasUnsavedTimeChanges)
    assertEquals(LocalTime.of(4, 45), state.heatingWindow.startTime)
    assertEquals(LocalTime.of(7, 0), state.heatingWindow.stopTime)
  }

  @Test
  fun scheduleConfig_metadataSerializationAndParsing() {
    val singleRunConfig = HeaterScheduleConfig(
      enabled = true,
      readyHour = 8,
      readyMinute = 15,
      preHeatMinutes = 90,
      autoOffOffsetMinutes = 20,
      activeWeekdays = emptySet(),
      targetDate = "2026-11-15",
    )
    val serialized = singleRunConfig.toMethodValueString()
    assertEquals("ready:08:15,pre:90,post:20,days:0,active:1,date:2026-11-15", serialized)

    val parsed = HeaterScheduleConfig.parseFromMethodValue(serialized)
    assertEquals(8, parsed?.readyHour)
    assertEquals(15, parsed?.readyMinute)
    assertEquals(90, parsed?.preHeatMinutes)
    assertEquals(20, parsed?.autoOffOffsetMinutes)
    assertEquals(emptySet<Int>(), parsed?.activeWeekdays)
    assertEquals(true, parsed?.active)
    assertEquals("2026-11-15", parsed?.targetDate)

    val repeatingConfig = HeaterScheduleConfig(
      enabled = false,
      readyHour = 7,
      readyMinute = 0,
      preHeatMinutes = 120,
      autoOffOffsetMinutes = 30,
      activeWeekdays = setOf(1, 2, 3),
    )
    val repeatingSerialized = repeatingConfig.toMethodValueString()
    assertEquals("ready:07:00,pre:120,post:30,days:1-2-3,active:0", repeatingSerialized)

    val parsedRepeating = HeaterScheduleConfig.parseFromMethodValue(repeatingSerialized)
    assertEquals(false, parsedRepeating?.active)
    assertEquals(setOf(1, 2, 3), parsedRepeating?.activeWeekdays)
    assertEquals(null, parsedRepeating?.targetDate)
  }
}
