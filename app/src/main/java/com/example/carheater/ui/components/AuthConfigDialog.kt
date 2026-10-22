package com.example.carheater.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.carheater.data.telldus.AuthConfig
import com.example.carheater.data.telldus.ConnectionMode
import com.example.carheater.data.telldus.DiscoveredTellStick
import com.example.carheater.data.telldus.TelldusDevice

@Composable
fun AuthConfigDialog(
  authConfig: AuthConfig,
  discoveredTellSticks: List<DiscoveredTellStick>,
  devices: List<TelldusDevice>,
  isBusy: Boolean,
  statusMessage: String?,
  errorMessage: String?,
  onDismiss: () -> Unit,
  onSaveAuthConfig: (AuthConfig) -> Unit,
  onScanLan: () -> Unit,
  onStartLocalBrowserAuth: (ip: String, onAuthUrlReady: (String) -> Unit) -> Unit,
  onCompleteLocalBrowserAuth: () -> Unit,
  onFetchDevices: (AuthConfig) -> Unit,
  onSelectDevice: (TelldusDevice) -> Unit,
) {
  val context = LocalContext.current
  var draft by remember(authConfig) { mutableStateOf(authConfig) }
  var showConnectionEditor by remember(authConfig.isAuthenticated) {
    mutableStateOf(!authConfig.isAuthenticated)
  }
  var showManualTokenField by remember { mutableStateOf(false) }
  var activePopupAuthUrl by remember { mutableStateOf<String?>(null) }

  if (activePopupAuthUrl != null) {
    ZNetBrowserAuthDialog(
      authUrl = activePopupAuthUrl!!,
      onDismiss = { activePopupAuthUrl = null },
      onAuthCompleted = {
        activePopupAuthUrl = null
        onCompleteLocalBrowserAuth()
      },
    )
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier =
        Modifier
          .fillMaxWidth(0.94f)
          .heightIn(max = 680.dp)
          .clip(RoundedCornerShape(26.dp)),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
    ) {
      Column(
        modifier =
          Modifier
            .padding(18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = "Select Switch & Connection",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
        )

        // 1. Switch Picker Section (Front and Center!)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Heater Switch",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text =
                if (draft.selectedDeviceId != null) {
                  "Active: ${draft.selectedDeviceName} (ID ${draft.selectedDeviceId})"
                } else {
                  "Tap a switch below to select"
                },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          OutlinedButton(
            onClick = {
              onSaveAuthConfig(draft)
              onFetchDevices(draft)
            },
            enabled = !isBusy,
          ) {
            Text(if (isBusy) "Loading..." else "Reload")
          }
        }

        if (devices.isNotEmpty()) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            devices.forEach { device ->
              val isSelected = draft.selectedDeviceId == device.id
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                      if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                      } else {
                        MaterialTheme.colorScheme.surfaceContainer
                      }
                    )
                    .clickable {
                      draft =
                        draft.copy(
                          selectedDeviceId = device.id,
                          selectedDeviceName = device.name,
                        )
                      onSelectDevice(device)
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                RadioButton(
                  selected = isSelected,
                  onClick = {
                    draft =
                      draft.copy(
                        selectedDeviceId = device.id,
                        selectedDeviceName = device.name,
                      )
                    onSelectDevice(device)
                  },
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                  )
                  Text(
                    text = "ID ${device.id} • Currently ${if (device.isOn) "ON" else "OFF"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }

        HorizontalDivider()

        // 2. Connection / Auth Settings (Collapsed by default when already authenticated)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "TellStick Connection",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text =
                if (draft.isAuthenticated) {
                  "Authenticated (${draft.localIp.ifBlank { "Cloud" }})"
                } else {
                  "Not authenticated yet"
                },
              style = MaterialTheme.typography.bodySmall,
              color =
                if (draft.isAuthenticated) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.error
                },
            )
          }
          TextButton(onClick = { showConnectionEditor = !showConnectionEditor }) {
            Text(if (showConnectionEditor) "Hide Auth" else "Edit Auth")
          }
        }

        if (showConnectionEditor) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            FilterChip(
              selected = draft.connectionMode == ConnectionMode.LOCAL_ZNET,
              onClick = {
                draft = draft.copy(connectionMode = ConnectionMode.LOCAL_ZNET)
                onSaveAuthConfig(draft)
              },
              label = { Text("Local ZNet (Wi-Fi)") },
              modifier = Modifier.weight(1f),
            )
            FilterChip(
              selected = draft.connectionMode == ConnectionMode.TELLDUS_CLOUD,
              onClick = {
                draft = draft.copy(connectionMode = ConnectionMode.TELLDUS_CLOUD)
                onSaveAuthConfig(draft)
              },
              label = { Text("Telldus Cloud") },
              modifier = Modifier.weight(1f),
            )
          }

          if (draft.connectionMode == ConnectionMode.LOCAL_ZNET) {
            LocalZNetAuthSection(
              draft = draft,
              discoveredTellSticks = discoveredTellSticks,
              isBusy = isBusy,
              showManualTokenField = showManualTokenField,
              onToggleManualToken = { showManualTokenField = !showManualTokenField },
              onDraftChange = {
                draft = it
                onSaveAuthConfig(it)
              },
              onScanLan = onScanLan,
              onLaunchBrowserAuth = {
                onSaveAuthConfig(draft)
                onStartLocalBrowserAuth(draft.localIp) { authUrl ->
                  activePopupAuthUrl = authUrl
                }
              },
              onReopenAuthUrl = { authUrl ->
                activePopupAuthUrl = authUrl
              },
              onCompleteLocalBrowserAuth = onCompleteLocalBrowserAuth,
            )
          } else {
            CloudAuthSection(
              draft = draft,
              onDraftChange = {
                draft = it
                onSaveAuthConfig(draft)
              },
              onOpenKeysWebpage = {
                runCatching {
                  val intent =
                    Intent(
                      Intent.ACTION_VIEW,
                      Uri.parse("https://pa-api.telldus.com/keys/index"),
                    )
                  context.startActivity(intent)
                }
              },
            )
          }
        }

        if (!errorMessage.isNullOrBlank()) {
          Card(
            colors =
              CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = errorMessage,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(10.dp),
            )
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(onClick = onDismiss) {
            Text("Cancel")
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = {
              onSaveAuthConfig(draft)
              onDismiss()
            }
          ) {
            Text("Done")
          }
        }
      }
    }
  }
}

@Composable
private fun LocalZNetAuthSection(
  draft: AuthConfig,
  discoveredTellSticks: List<DiscoveredTellStick>,
  isBusy: Boolean,
  showManualTokenField: Boolean,
  onToggleManualToken: () -> Unit,
  onDraftChange: (AuthConfig) -> Unit,
  onScanLan: () -> Unit,
  onLaunchBrowserAuth: () -> Unit,
  onReopenAuthUrl: (String) -> Unit,
  onCompleteLocalBrowserAuth: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = draft.localIp,
        onValueChange = { onDraftChange(draft.copy(localIp = it)) },
        label = { Text("ZNet Local IP") },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
      OutlinedButton(
        onClick = onScanLan,
        enabled = !isBusy,
      ) {
        Text("Scan LAN")
      }
    }

    if (discoveredTellSticks.isNotEmpty()) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        discoveredTellSticks.forEach { item ->
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(
                  1.dp,
                  MaterialTheme.colorScheme.outlineVariant,
                  RoundedCornerShape(10.dp),
                )
                .clickable { onDraftChange(draft.copy(localIp = item.ipAddress)) }
                .padding(10.dp)
          ) {
            Text(
              text = "${item.product} — ${item.ipAddress} (FW ${item.firmwareVersion})",
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.Medium,
            )
          }
        }
      }
    }

    Button(
      onClick = onLaunchBrowserAuth,
      enabled = draft.localIp.isNotBlank() && !isBusy,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Authenticate in Browser Popup")
    }

    if (draft.pendingRequestToken.isNotBlank()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = onCompleteLocalBrowserAuth,
          enabled = !isBusy,
          modifier = Modifier.weight(1f),
        ) {
          Text("Complete Login")
        }
        if (draft.pendingAuthUrl.isNotBlank()) {
          OutlinedButton(onClick = { onReopenAuthUrl(draft.pendingAuthUrl) }) {
            Text("Reopen")
          }
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text =
          if (draft.localBearerToken.isNotBlank()) {
            "Token saved"
          } else {
            "No token yet"
          },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      TextButton(onClick = onToggleManualToken) {
        Text(if (showManualTokenField) "Hide Token" else "Manual Token")
      }
    }

    if (showManualTokenField) {
      OutlinedTextField(
        value = draft.localBearerToken,
        onValueChange = { onDraftChange(draft.copy(localBearerToken = it)) },
        label = { Text("Local JWT Bearer Token") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun CloudAuthSection(
  draft: AuthConfig,
  onDraftChange: (AuthConfig) -> Unit,
  onOpenKeysWebpage: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(
      onClick = onOpenKeysWebpage,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Open Telldus API Keys Page")
    }
    OutlinedTextField(
      value = draft.cloudPublicKey,
      onValueChange = { onDraftChange(draft.copy(cloudPublicKey = it)) },
      label = { Text("Public Key") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = draft.cloudPrivateKey,
      onValueChange = { onDraftChange(draft.copy(cloudPrivateKey = it)) },
      label = { Text("Private Key") },
      visualTransformation = PasswordVisualTransformation(),
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = draft.cloudToken,
      onValueChange = { onDraftChange(draft.copy(cloudToken = it)) },
      label = { Text("Token") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = draft.cloudTokenSecret,
      onValueChange = { onDraftChange(draft.copy(cloudTokenSecret = it)) },
      label = { Text("Token Secret") },
      visualTransformation = PasswordVisualTransformation(),
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
