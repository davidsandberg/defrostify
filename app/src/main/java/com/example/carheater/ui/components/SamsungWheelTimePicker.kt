package com.example.carheater.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Samsung Galaxy One UI Alarm-style dual scroll wheel time picker (24-hour format).
 */
@Composable
fun SamsungWheelTimePicker(
  hour: Int,
  minute: Int,
  onTimeChanged: (newHour: Int, newMinute: Int) -> Unit,
  modifier: Modifier = Modifier,
  itemHeight: Dp = 42.dp,
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(vertical = 8.dp, horizontal = 14.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Column headers aligned identically to the wheels below
    Row(
      modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "HOUR",
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.2.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(105.dp),
      )
      Text(
        text = " ",
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 10.dp),
      )
      Text(
        text = "MINUTE",
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.2.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(105.dp),
      )
    }

    Box(
      modifier = Modifier.fillMaxWidth().height(itemHeight * 3),
      contentAlignment = Alignment.Center,
    ) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth(0.86f)
            .height(itemHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
      )
      Column(
        modifier = Modifier.fillMaxWidth(0.82f).height(itemHeight),
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        HorizontalDivider(
          thickness = 1.dp,
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
        )
        HorizontalDivider(
          thickness = 1.dp,
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
        )
      }

      val currentHour by rememberUpdatedState(hour.coerceIn(0, 23))
      val currentMinute by rememberUpdatedState(minute.coerceIn(0, 59))
      val currentOnTimeChanged by rememberUpdatedState(onTimeChanged)

      Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        CircularNumberWheel(
          value = hour.coerceIn(0, 23),
          rangeSize = 24,
          itemHeight = itemHeight,
          onValueChange = { newHour ->
            if (newHour != currentHour) {
              currentOnTimeChanged(newHour, currentMinute)
            }
          },
          modifier = Modifier.width(105.dp),
        )

        Text(
          text = ":",
          fontSize = 28.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.padding(horizontal = 10.dp),
        )

        CircularNumberWheel(
          value = minute.coerceIn(0, 59),
          rangeSize = 60,
          itemHeight = itemHeight,
          onValueChange = { newMinute ->
            if (newMinute != currentMinute) {
              currentOnTimeChanged(currentHour, newMinute)
            }
          },
          modifier = Modifier.width(105.dp),
        )
      }
    }
  }
}

private const val VIRTUAL_ITEM_COUNT = 24_000
private const val VIRTUAL_MIDPOINT = VIRTUAL_ITEM_COUNT / 2

@Composable
private fun CircularNumberWheel(
  value: Int,
  rangeSize: Int,
  itemHeight: Dp,
  onValueChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val haptic = LocalHapticFeedback.current
  val coroutineScope = rememberCoroutineScope()
  val density = LocalDensity.current
  val itemHeightPx = remember(itemHeight, density) { with(density) { itemHeight.toPx() } }

  val alignedMidpoint = remember(rangeSize) { VIRTUAL_MIDPOINT - (VIRTUAL_MIDPOINT % rangeSize) }
  val initialTopIndex = remember { alignedMidpoint + value - 1 }

  val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialTopIndex)
  val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

  val centerIndex by remember {
    derivedStateOf {
      val offsetCorrection = if (listState.firstVisibleItemScrollOffset > itemHeightPx / 2f) 1 else 0
      listState.firstVisibleItemIndex + 1 + offsetCorrection
    }
  }

  var lastHapticValue by remember { mutableIntStateOf(value) }
  val currentOnValueChange by rememberUpdatedState(onValueChange)
  var isProgrammaticScroll by remember { androidx.compose.runtime.mutableStateOf(false) }

  LaunchedEffect(listState) {
    snapshotFlow { centerIndex }
      .distinctUntilChanged()
      .collect { idx ->
        if (!isProgrammaticScroll && listState.isScrollInProgress) {
          val selectedVal = ((idx % rangeSize) + rangeSize) % rangeSize
          if (selectedVal != lastHapticValue) {
            lastHapticValue = selectedVal
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnValueChange(selectedVal)
          }
        }
      }
  }

  LaunchedEffect(value) {
    val currentSelected = ((centerIndex % rangeSize) + rangeSize) % rangeSize
    if (currentSelected != value && !listState.isScrollInProgress) {
      isProgrammaticScroll = true
      try {
        lastHapticValue = value
        val currentBase = centerIndex - currentSelected
        val targetCenterIndex = currentBase + value
        listState.scrollToItem((targetCenterIndex - 1).coerceAtLeast(0))
      } finally {
        isProgrammaticScroll = false
      }
    }
  }

  LazyColumn(
    state = listState,
    flingBehavior = flingBehavior,
    modifier = modifier.height(itemHeight * 3),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    items(count = VIRTUAL_ITEM_COUNT, key = { it }) { index ->
      val itemValue = ((index % rangeSize) + rangeSize) % rangeSize

      Box(
        modifier =
          Modifier
            .height(itemHeight)
            .fillMaxWidth()
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
            ) {
              if (index != centerIndex) {
                coroutineScope.launch {
                  listState.animateScrollToItem((index - 1).coerceAtLeast(0))
                }
              }
            }
            .graphicsLayer {
              val layoutInfo = listState.layoutInfo
              val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
              if (itemInfo != null) {
                val viewportCenter =
                  (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                val itemCenter = itemInfo.offset + itemInfo.size / 2f
                val normalizedDistance =
                  (abs(itemCenter - viewportCenter) / itemHeightPx).coerceIn(0f, 1.5f)
                alpha = (1f - (normalizedDistance * 0.62f)).coerceIn(0.22f, 1f)
              } else {
                alpha = 0.22f
              }
            },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "%02d".format(itemValue),
          fontSize = 28.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}
