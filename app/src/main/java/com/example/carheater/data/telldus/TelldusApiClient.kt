package com.example.carheater.data.telldus

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class TelldusApiException(
  message: String,
  val httpCode: Int? = null,
  val rawBody: String? = null,
  cause: Throwable? = null,
) : Exception(message, cause)

class TelldusApiClient {

  /**
   * Broadcasts a UDP "D" discovery packet on port 30303 across all local IPv4 broadcast addresses
   * to discover TellStick ZNet Lite v1/v2 and TellStick Net devices on the LAN.
   */
  suspend fun discoverTellSticksOnLan(timeoutMs: Int = 2200): List<DiscoveredTellStick> =
    withContext(Dispatchers.IO) {
      val discovered = linkedMapOf<String, DiscoveredTellStick>()
      val socket = DatagramSocket()
      try {
        socket.broadcast = true
        socket.soTimeout = 600
        val payload = "D".toByteArray(Charsets.US_ASCII)

        val broadcastTargets = mutableSetOf<InetAddress>()
        runCatching { broadcastTargets.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
          val interfaces = NetworkInterface.getNetworkInterfaces()
          while (interfaces.hasMoreElements()) {
            val netIf = interfaces.nextElement()
            if (!netIf.isUp || netIf.isLoopback) continue
            for (ifAddr in netIf.interfaceAddresses) {
              val bcast = ifAddr.broadcast
              if (bcast is Inet4Address) {
                broadcastTargets.add(bcast)
              }
            }
          }
        }

        for (target in broadcastTargets) {
          runCatching {
            val packet = DatagramPacket(payload, payload.size, target, 30303)
            socket.send(packet)
          }
        }

        val startTime = System.currentTimeMillis()
        val buffer = ByteArray(1024)
        while (System.currentTimeMillis() - startTime < timeoutMs) {
          try {
            val receivePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(receivePacket)
            val ip = receivePacket.address.hostAddress ?: continue
            val text = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8).trim()
            val parts = text.split(":")
            if (parts.isNotEmpty()) {
              val item =
                DiscoveredTellStick(
                  ipAddress = ip,
                  product = parts.getOrNull(0) ?: "TellStick",
                  mac = parts.getOrNull(1) ?: "",
                  activationCode = parts.getOrNull(2) ?: "",
                  firmwareVersion = parts.getOrNull(3) ?: "",
                )
              discovered[ip] = item
            }
          } catch (_: Exception) {
            // Timeout on this receive slice; loop until timeoutMs expires
          }
        }
      } finally {
        runCatching { socket.close() }
      }
      discovered.values.toList()
    }

  /**
   * Step 1 of TellStick ZNet Local API browser-popup authentication:
   * PUT http://<ip>/api/token with form param app=CarHeater
   */
  suspend fun requestLocalToken(
    rawIp: String,
    appName: String = "CarHeater",
  ): TelldusTokenRequestResponse =
    withContext(Dispatchers.IO) {
      val baseUrl = normalizeLocalBaseUrl(rawIp)
      val endpoint = "$baseUrl/api/token"
      val body = "app=${urlEncode(appName)}"
      val responseText =
        executeHttp(
          url = endpoint,
          method = "PUT",
          headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
          body = body,
        )
      val json = JSONObject(responseText)
      if (json.has("error")) {
        throw TelldusApiException("ZNet error: ${json.optString("error")}", rawBody = responseText)
      }
      val token = json.optString("token")
      val authUrl = json.optString("authUrl")
      if (token.isBlank() || authUrl.isBlank()) {
        throw TelldusApiException(
          "Unexpected response from $endpoint: $responseText",
          rawBody = responseText,
        )
      }
      TelldusTokenRequestResponse(token = token, authUrl = authUrl)
    }

  /**
   * Step 3 of TellStick ZNet Local API authentication:
   * GET http://<ip>/api/token?token=<requestToken>
   * Returns the long-lived JWT Bearer token after the user authorizes in the browser popup.
   */
  suspend fun exchangeLocalToken(
    rawIp: String,
    requestToken: String,
  ): TelldusAccessTokenResponse =
    withContext(Dispatchers.IO) {
      val baseUrl = normalizeLocalBaseUrl(rawIp)
      val endpoint = "$baseUrl/api/token?token=${urlEncode(requestToken.trim())}"
      val responseText = executeHttp(url = endpoint, method = "GET")
      val json = JSONObject(responseText)
      if (json.has("error")) {
        val err = json.optString("error")
        throw TelldusApiException(
          "Authorization not completed yet ($err). Approve the request in the browser popup first, then tap Complete Login.",
          rawBody = responseText,
        )
      }
      val jwt = json.optString("token")
      if (jwt.isBlank()) {
        throw TelldusApiException(
          "No access token returned: $responseText",
          rawBody = responseText,
        )
      }
      TelldusAccessTokenResponse(
        token = jwt,
        allowRenew = json.optBoolean("allowRenew", true),
        expires = if (json.has("expires")) json.optLong("expires") else null,
      )
    }

  /**
   * Lists controllable switches (TURNON/TURNOFF) from either ZNet Local API or Telldus Live Cloud API.
   */
  suspend fun listDevices(auth: AuthConfig): List<TelldusDevice> =
    withContext(Dispatchers.IO) {
      val supportedMethods =
        TelldusMethods.TURNON or TelldusMethods.TURNOFF or TelldusMethods.DIM
      val responseText =
        callTelldusEndpoint(
          auth = auth,
          path = "devices/list",
          queryParams = mapOf("supportedMethods" to supportedMethods.toString()),
        )
      val json = JSONObject(responseText)
      checkForApiError(json, responseText)
      val deviceArray = json.optJSONArray("device") ?: JSONArray()
      val devices = mutableListOf<TelldusDevice>()
      for (i in 0 until deviceArray.length()) {
        val obj = deviceArray.optJSONObject(i) ?: continue
        val methods = obj.optInt("methods", 0)
        // Filter out read-only meters/sensors (methods == 0) that cannot be turned on/off
        if ((methods and (TelldusMethods.TURNON or TelldusMethods.TURNOFF)) == 0) {
          continue
        }
        val id = obj.optInt("id")
        val clientDeviceId = obj.optString("clientDeviceId", "").toIntOrNull()
        if (id > 0 && clientDeviceId != null && clientDeviceId > 0) {
          registerDeviceMapping(localId = clientDeviceId, cloudId = id)
        }
        devices.add(
          TelldusDevice(
            id = id,
            name = obj.optString("name", "Device #${id}"),
            state = obj.optInt("state", TelldusMethods.TURNOFF),
            stateValue = obj.optString("statevalue", "").ifBlank { null },
            methods = methods,
            type = obj.optString("type", "device"),
            clientDeviceId = clientDeviceId,
            clientName = obj.optString("clientName", "").ifBlank { null },
          )
        )
      }
      devices
    }

  /**
   * Resolves a local ZNet device ID (e.g. `31` for "Switch" or `111` for "Belysning Pergola")
   * to its Telldus Live Cloud device ID (e.g. `7293259` or `17070039`).
   */
  suspend fun resolveCloudDeviceId(auth: AuthConfig, deviceId: Int): Int =
    withContext(Dispatchers.IO) {
      if (deviceId >= 100_000) return@withContext deviceId
      val mapped = toCloudDeviceId(deviceId)
      if (mapped != deviceId) return@withContext mapped
      if (!auth.hasCloudCredentials) return@withContext deviceId

      runCatching { listDevices(auth) }
      toCloudDeviceId(deviceId)
    }

  /**
   * Queries current live state of a single switch.
   */
  suspend fun getDeviceInfo(auth: AuthConfig, deviceId: Int): TelldusDevice =
    withContext(Dispatchers.IO) {
      val supportedMethods =
        TelldusMethods.TURNON or TelldusMethods.TURNOFF or TelldusMethods.DIM
      val responseText =
        callTelldusEndpoint(
          auth = auth,
          path = "device/info",
          queryParams =
            mapOf(
              "id" to deviceId.toString(),
              "supportedMethods" to supportedMethods.toString(),
            ),
        )
      val obj = JSONObject(responseText)
      checkForApiError(obj, responseText)
      TelldusDevice(
        id = deviceId,
        name = obj.optString("name", "Device #$deviceId"),
        state = obj.optInt("state", TelldusMethods.TURNOFF),
        stateValue = obj.optString("statevalue", "").ifBlank { null },
        methods = obj.optInt("methods", TelldusMethods.TURNON or TelldusMethods.TURNOFF),
        type = obj.optString("type", "device"),
        clientDeviceId = obj.optString("clientDeviceId", "").toIntOrNull() ?: toLocalDeviceId(deviceId),
        clientName = obj.optString("clientName", "").ifBlank { null },
      )
    }

  /**
   * Queries current live power consumption in Watts (`scale = 2`) from the switch's built-in
   * Z-Wave wattmeter sensor (`sensor/info` locally, or `sensors/list` via Telldus Cloud).
   * Returns `null` if the switch has no wattmeter sensor or cannot be queried.
   */
  suspend fun getDevicePowerWatts(
    auth: AuthConfig,
    deviceId: Int,
    deviceName: String = auth.selectedDeviceName,
  ): Double? =
    withContext(Dispatchers.IO) {
      val localId = toLocalDeviceId(deviceId)
      val cloudId = toCloudDeviceId(deviceId)
      if (auth.connectionMode == ConnectionMode.LOCAL_ZNET &&
        auth.localIp.isNotBlank() &&
        auth.localBearerToken.isNotBlank() &&
        isLocalReachable()
      ) {
        val localWatts =
          runCatching {
            val responseText =
              callLocalZNetEndpoint(
                auth = auth,
                path = "sensor/info",
                queryParams = mapOf("id" to localId.toString()),
              )
            val obj = JSONObject(responseText)
            checkForApiError(obj, responseText)
            markLocalSucceeded()
            extractWattScale2(obj.optJSONArray("data"))
          }.getOrNull()
        if (localWatts != null) {
          return@withContext localWatts
        }
      }

      if (auth.hasCloudCredentials) {
        val now = System.currentTimeMillis()
        val cached = cachedCloudWattMap[deviceId] ?: cachedCloudWattMap[cloudId]
        if (cached != null && (now - cached.first < 60_000L)) {
          return@withContext cached.second
        }
        runCatching {
          val responseText =
            callCloudEndpoint(
              auth = auth,
              path = "sensors/list",
              queryParams = mapOf("includeValues" to "1", "includeScale" to "1"),
            )
          val obj = JSONObject(responseText)
          checkForApiError(obj, responseText)
          val sensors = obj.optJSONArray("sensor") ?: JSONArray()
          val trimmedName = deviceName.trim()
          var resultWatt: Double? = null
          for (i in 0 until sensors.length()) {
            val s = sensors.optJSONObject(i) ?: continue
            val sensorLocalId = s.optString("sensorId", "").toIntOrNull()
            val sensorCloudId = s.optInt("id", 0)
            val sName = s.optString("name", "").trim()
            val idMatches = (cloudId > 0 && sensorCloudId == cloudId) ||
              (localId > 0 && sensorLocalId == localId)
            val nameMatches =
              trimmedName.isNotEmpty() && sName.equals(trimmedName, ignoreCase = true)
            if (idMatches || nameMatches) {
              val w = extractWattScale2(s.optJSONArray("data"))
              if (w != null) {
                resultWatt = w
                break
              }
            }
          }
          if (resultWatt != null) {
            cachedCloudWattMap[deviceId] = Pair(now, resultWatt)
            cachedCloudWattMap[cloudId] = Pair(now, resultWatt)
          }
          resultWatt
        }.getOrNull()
      } else {
        null
      }
    }

  private fun extractWattScale2(dataArray: JSONArray?): Double? {
    if (dataArray == null) return null
    for (i in 0 until dataArray.length()) {
      val item = dataArray.optJSONObject(i) ?: continue
      val name = item.optString("name", "")
      val scale = item.optString("scale", "")
      if (name.equals("watt", ignoreCase = true) && scale == "2") {
        val v = item.optString("value", "").toDoubleOrNull() ?: item.optDouble("value")
        if (!v.isNaN()) return v
      }
    }
    return null
  }

  /**
   * Immediately turns ON the specified device (`device/turnOn`).
   */
  suspend fun turnOnDevice(auth: AuthConfig, deviceId: Int): String =
    withContext(Dispatchers.IO) {
      val responseText =
        callTelldusEndpoint(
          auth = auth,
          path = "device/turnOn",
          queryParams = mapOf("id" to deviceId.toString()),
        )
      val json = JSONObject(responseText)
      checkForApiError(json, responseText)
      val status = json.optString("status", "success")
      if (!status.equals("success", ignoreCase = true)) {
        throw TelldusApiException("Device turnOn returned status: $status", rawBody = responseText)
      }
      status
    }

  /**
   * Immediately turns OFF the specified device (`device/turnOff`).
   */
  suspend fun turnOffDevice(auth: AuthConfig, deviceId: Int): String =
    withContext(Dispatchers.IO) {
      val responseText =
        callTelldusEndpoint(
          auth = auth,
          path = "device/turnOff",
          queryParams = mapOf("id" to deviceId.toString()),
        )
      val json = JSONObject(responseText)
      checkForApiError(json, responseText)
      val status = json.optString("status", "success")
      if (!status.equals("success", ignoreCase = true)) {
        throw TelldusApiException("Device turnOff returned status: $status", rawBody = responseText)
      }
      status
    }

  /**
   * Lists scheduled jobs from `/scheduler/jobList`.
   */
  suspend fun listScheduleJobs(auth: AuthConfig): List<TelldusScheduleJob> =
    withContext(Dispatchers.IO) {
      val responseText = callTelldusEndpoint(auth = auth, path = "scheduler/jobList")
      val json = JSONObject(responseText)
      checkForApiError(json, responseText)
      val jobArray = json.optJSONArray("job") ?: JSONArray()
      val jobs = mutableListOf<TelldusScheduleJob>()
      for (i in 0 until jobArray.length()) {
        val obj = jobArray.optJSONObject(i) ?: continue
        jobs.add(
          TelldusScheduleJob(
            id = obj.optInt("id"),
            deviceId = obj.optInt("deviceId"),
            method = obj.optInt("method", TelldusMethods.TURNON),
            methodValue = obj.optString("methodValue", ""),
            hour = obj.optInt("hour", 0),
            minute = obj.optInt("minute", 0),
            type = obj.optString("type", "time"),
            weekdays = obj.optString("weekdays", "1,2,3,4,5,6,7"),
            active = obj.optInt("active", 1) == 1,
            reps = obj.optInt("reps", 1),
          )
        )
      }
      jobs
    }

  /**
   * Creates or updates a scheduled job on the Telldus scheduler (`/scheduler/setJob`).
   * Automatically resolves device IDs to Telldus Cloud `id`.
   * Returns the saved job ID.
   */
  suspend fun setScheduleJob(auth: AuthConfig, job: TelldusScheduleJob): Int =
    withContext(Dispatchers.IO) {
      val targetDeviceId = toCloudDeviceId(job.deviceId)
      val jobKey = "$targetDeviceId:${job.method}"
      val jobSignature =
        "${job.id}:$targetDeviceId:${job.method}:${job.hour}:${job.minute}:${job.weekdays}:${job.active}"
      if (job.id > 0 && lastSyncedJobSignatureCache[jobKey] == jobSignature) {
        return@withContext job.id
      }

      val params =
        linkedMapOf(
          "id" to job.id.toString(),
          "deviceId" to targetDeviceId.toString(),
          "method" to job.method.toString(),
          "methodValue" to job.methodValue,
          "type" to job.type,
          "hour" to job.hour.toString(),
          "minute" to job.minute.toString(),
          "weekdays" to job.weekdays,
          "active" to if (job.active) "1" else "0",
          "reps" to job.reps.toString(),
        )
      val responseText =
        callTelldusEndpoint(
          auth = auth,
          path = "scheduler/setJob",
          queryParams = params,
        )
      val json = JSONObject(responseText)
      checkForApiError(json, responseText)
      val savedId = json.optInt("id", job.id)
      if (savedId > 0) {
        lastSyncedJobSignatureCache[jobKey] =
          "$savedId:$targetDeviceId:${job.method}:${job.hour}:${job.minute}:${job.weekdays}:${job.active}"
      }
      savedId
    }

  /**
   * Automatically fetches or generates private Telldus Live Cloud OAuth 1.0a keys
   * (`Public key`, `Private key`, `Token`, `Token secret`) from `https://pa-api.telldus.com/keys/generatePrivate`
   * using the active `login.telldus.com` session cookies stored in Android's [ android.webkit.CookieManager ].
   */
  suspend fun fetchCloudKeysFromWebViewSession(): TelldusCloudKeys? =
    withContext(Dispatchers.IO) {
      runCatching {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val jar = linkedMapOf<String, MutableMap<String, String>>()

        fun seedCookies(domain: String, url: String) {
          val raw = cookieManager.getCookie(url) ?: return
          val map = jar.getOrPut(domain) { linkedMapOf() }
          raw.split(";").forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
              val k = part.substring(0, idx).trim()
              val v = part.substring(idx + 1).trim()
              map[k] = v
            }
          }
        }

        seedCookies("login.telldus.com", "http://login.telldus.com")
        seedCookies("login.telldus.com", "https://login.telldus.com")
        seedCookies("pa-api.telldus.com", "https://pa-api.telldus.com")

        fun followGet(startUrl: String, maxRedirects: Int = 8): String {
          var currentUrl = startUrl
          for (step in 0 until maxRedirects) {
            val u = URL(currentUrl)
            val host = u.host
            val conn = (u.openConnection() as HttpURLConnection)
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val hostCookies = jar[host]
            if (!hostCookies.isNullOrEmpty()) {
              conn.setRequestProperty(
                "Cookie",
                hostCookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
              )
            }
            val code = conn.responseCode
            val setCookies = conn.headerFields["Set-Cookie"].orEmpty()
            if (setCookies.isNotEmpty()) {
              val map = jar.getOrPut(host) { linkedMapOf() }
              for (sc in setCookies) {
                val pair = sc.substringBefore(";")
                val idx = pair.indexOf('=')
                if (idx > 0) {
                  map[pair.substring(0, idx).trim()] = pair.substring(idx + 1).trim()
                }
                cookieManager.setCookie("https://$host", sc)
              }
            }
            if (code in 300..399) {
              val loc = conn.getHeaderField("Location") ?: break
              currentUrl =
                if (loc.startsWith("/")) {
                  "${u.protocol}://${u.host}$loc"
                } else {
                  loc
                }
              conn.disconnect()
              continue
            }
            val body =
              conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            return body
          }
          return ""
        }

        // First establish OpenID session on pa-api.telldus.com, then call generatePrivate
        followGet("https://pa-api.telldus.com/keys/index")
        val html = followGet("https://pa-api.telldus.com/keys/generatePrivate")

        fun extractTdValue(label: String): String {
          val regex =
            Regex(
              """<td>\s*${Regex.escape(label)}\s*</td>\s*<td>\s*([^<\s]+)\s*</td>""",
              RegexOption.IGNORE_CASE,
            )
          return regex.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        }

        val pub = extractTdValue("Public key:")
        val priv = extractTdValue("Private key:")
        val tok = extractTdValue("Token:")
        val sec = extractTdValue("Token secret:")
        if (pub.isNotBlank() && priv.isNotBlank() && tok.isNotBlank() && sec.isNotBlank()) {
          TelldusCloudKeys(
            publicKey = pub,
            privateKey = priv,
            token = tok,
            tokenSecret = sec,
          )
        } else {
          null
        }
      }.getOrNull()
    }

  private suspend fun callTelldusEndpoint(
    auth: AuthConfig,
    path: String,
    queryParams: Map<String, String> = emptyMap(),
  ): String {
    // TellStick ZNet Lite v2 does not expose /api/scheduler/* locally; always route scheduler/*
    // directly to Telldus Live Cloud when Cloud credentials are available.
    if (path.startsWith("scheduler/")) {
      if (auth.hasCloudCredentials) {
        return callCloudEndpoint(auth, path, mapParamsForCloud(queryParams))
      } else {
        throw TelldusApiException(
          "Telldus Cloud keys are required for controller scheduling. Open Switch & Auth -> Telldus Cloud -> Auto-Fetch Cloud Keys."
        )
      }
    }

    return when (auth.connectionMode) {
      ConnectionMode.LOCAL_ZNET -> {
        if (!isLocalReachable() && auth.hasCloudCredentials) {
          // Fast-path to Cloud if local ZNet was recently unreachable
          callCloudEndpoint(auth, path, mapParamsForCloud(queryParams))
        } else {
          try {
            val res = callLocalZNetEndpoint(auth, path, mapParamsForLocal(queryParams))
            val json = JSONObject(res)
            checkForApiError(json, res)
            markLocalSucceeded()
            res
          } catch (e: Exception) {
            markLocalFailed()
            if (auth.hasCloudCredentials) {
              callCloudEndpoint(auth, path, mapParamsForCloud(queryParams))
            } else {
              if (e is TelldusApiException) throw e
              else throw TelldusApiException("Local ZNet request failed: ${e.message}", cause = e)
            }
          }
        }
      }
      ConnectionMode.TELLDUS_CLOUD -> callCloudEndpoint(auth, path, mapParamsForCloud(queryParams))
    }
  }

  private fun callLocalZNetEndpoint(
    auth: AuthConfig,
    path: String,
    queryParams: Map<String, String>,
  ): String {
    if (auth.localIp.isBlank()) {
      throw TelldusApiException("Local ZNet IP address is not configured.")
    }
    if (auth.localBearerToken.isBlank()) {
      throw TelldusApiException("Not authenticated with ZNet Lite v2 yet.")
    }
    val baseUrl = normalizeLocalBaseUrl(auth.localIp)
    val queryString =
      if (queryParams.isEmpty()) {
        ""
      } else {
        "?" +
          queryParams.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
          }
      }
    val fullUrl = "$baseUrl/api/${path.trimStart('/')}$queryString"
    return executeHttp(
      url = fullUrl,
      method = "GET",
      headers = mapOf("Authorization" to "Bearer ${auth.localBearerToken.trim()}"),
      connectTimeoutMs = 1200,
      readTimeoutMs = 2500,
    )
  }

  private suspend fun callCloudEndpoint(
    auth: AuthConfig,
    path: String,
    queryParams: Map<String, String>,
  ): String {
    val baseEndpoint = "https://pa-api.telldus.com/json/${path.trimStart('/')}"
    val queryString =
      if (queryParams.isEmpty()) {
        ""
      } else {
        "?" +
          queryParams.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
          }
      }
    val fullUrl = "$baseEndpoint$queryString"

    return cloudRateLimitMutex.withLock {
      var lastError: TelldusApiException? = null
      val minIntervalMs = if (path.startsWith("scheduler/setJob")) 5800L else 1200L
      val backoffDelaysMs = longArrayOf(6_000L, 12_000L, 20_000L)
      for (attempt in 1..4) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastCloudCallTimestampMs
        if (elapsed in 0 until minIntervalMs) {
          delay(minIntervalMs - elapsed)
        }
        lastCloudCallTimestampMs = System.currentTimeMillis()

        val oauthHeader =
          buildOAuth1Header(
            method = "GET",
            baseUrl = baseEndpoint,
            queryParams = queryParams,
            consumerKey = auth.cloudPublicKey.trim(),
            consumerSecret = auth.cloudPrivateKey.trim(),
            token = auth.cloudToken.trim(),
            tokenSecret = auth.cloudTokenSecret.trim(),
          )
        try {
          val responseText =
            executeHttp(
              url = fullUrl,
              method = "GET",
              headers = mapOf("Authorization" to oauthHeader),
            )
          return@withLock responseText
        } catch (e: TelldusApiException) {
          lastError = e
          val isRateLimited = e.httpCode == 429 ||
            e.message?.contains("429", ignoreCase = true) == true ||
            e.message?.contains("too many requests", ignoreCase = true) == true
          if (isRateLimited && attempt <= backoffDelaysMs.size) {
            delay(backoffDelaysMs[attempt - 1])
            lastCloudCallTimestampMs = System.currentTimeMillis()
            continue
          }
          throw e
        }
      }
      throw lastError ?: TelldusApiException("Cloud API request failed after retries")
    }
  }

  private fun buildOAuth1Header(
    method: String,
    baseUrl: String,
    queryParams: Map<String, String>,
    consumerKey: String,
    consumerSecret: String,
    token: String,
    tokenSecret: String,
  ): String {
    val nonceBytes = ByteArray(16)
    SecureRandom().nextBytes(nonceBytes)
    val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
    val timestamp = (System.currentTimeMillis() / 1000L).toString()

    val oauthParams =
      linkedMapOf(
        "oauth_consumer_key" to consumerKey,
        "oauth_nonce" to nonce,
        "oauth_signature_method" to "HMAC-SHA1",
        "oauth_timestamp" to timestamp,
        "oauth_token" to token,
        "oauth_version" to "1.0",
      )

    val allParams = mutableListOf<Pair<String, String>>()
    oauthParams.forEach { (k, v) -> allParams.add(percentEncode(k) to percentEncode(v)) }
    queryParams.forEach { (k, v) -> allParams.add(percentEncode(k) to percentEncode(v)) }
    allParams.sortWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })

    val normalizedParams = allParams.joinToString("&") { "${it.first}=${it.second}" }
    val signatureBaseString =
      "${method.uppercase()}&${percentEncode(baseUrl)}&${percentEncode(normalizedParams)}"
    val signingKey = "${percentEncode(consumerSecret)}&${percentEncode(tokenSecret)}"

    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(signingKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
    val rawHmac = mac.doFinal(signatureBaseString.toByteArray(Charsets.UTF_8))
    val signature = Base64.getEncoder().encodeToString(rawHmac)

    oauthParams["oauth_signature"] = signature
    return "OAuth " +
      oauthParams.entries.joinToString(", ") { (k, v) ->
        "${percentEncode(k)}=\"${percentEncode(v)}\""
      }
  }

  private fun executeHttp(
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
    connectTimeoutMs: Int = 8000,
    readTimeoutMs: Int = 8000,
  ): String {
    val connection = (URL(url).openConnection() as HttpURLConnection)
    try {
      connection.requestMethod = method
      connection.connectTimeout = connectTimeoutMs
      connection.readTimeout = readTimeoutMs
      connection.setRequestProperty("Accept", "application/json")
      headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }

      if (body != null) {
        connection.doOutput = true
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
          writer.write(body)
          writer.flush()
        }
      }

      val statusCode = connection.responseCode
      val stream =
        if (statusCode in 200..299) {
          connection.inputStream
        } else {
          connection.errorStream ?: connection.inputStream
        }

      val responseText =
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

      if (statusCode !in 200..299) {
        throw TelldusApiException(
          "HTTP $statusCode from $url: ${responseText.take(240)}",
          httpCode = statusCode,
          rawBody = responseText,
        )
      }
      return responseText
    } catch (e: TelldusApiException) {
      throw e
    } catch (e: Exception) {
      throw TelldusApiException("Network error calling $url: ${e.message ?: e.javaClass.simpleName}")
    } finally {
      connection.disconnect()
    }
  }

  private fun checkForApiError(json: JSONObject, rawText: String) {
    if (json.has("error") && !json.isNull("error")) {
      throw TelldusApiException(
        "Telldus API error: ${json.optString("error")}",
        rawBody = rawText,
      )
    }
  }

  companion object {
    private val localToCloudMap =
      java.util.concurrent.ConcurrentHashMap<Int, Int>().apply {
        put(111, 17070039)
        put(31, 7293259)
        put(46, 13648343)
        put(48, 13812098)
        put(52, 14003984)
      }
    private val cloudToLocalMap =
      java.util.concurrent.ConcurrentHashMap<Int, Int>().apply {
        put(17070039, 111)
        put(7293259, 31)
        put(13648343, 46)
        put(13812098, 48)
        put(14003984, 52)
      }

    fun toLocalDeviceId(id: Int): Int {
      if (id < 100_000) return id
      return cloudToLocalMap[id] ?: id
    }

    fun toCloudDeviceId(id: Int): Int {
      if (id >= 100_000) return id
      return localToCloudMap[id] ?: id
    }

    fun registerDeviceMapping(localId: Int, cloudId: Int) {
      if (localId > 0 && cloudId > 0) {
        localToCloudMap[localId] = cloudId
        cloudToLocalMap[cloudId] = localId
      }
    }

    fun mapParamsForLocal(queryParams: Map<String, String>): Map<String, String> {
      if (!queryParams.containsKey("id") && !queryParams.containsKey("deviceId")) return queryParams
      val mapped = queryParams.toMutableMap()
      queryParams["id"]?.toIntOrNull()?.let { mapped["id"] = toLocalDeviceId(it).toString() }
      queryParams["deviceId"]?.toIntOrNull()?.let { mapped["deviceId"] = toLocalDeviceId(it).toString() }
      return mapped
    }

    fun mapParamsForCloud(queryParams: Map<String, String>): Map<String, String> {
      if (!queryParams.containsKey("id") && !queryParams.containsKey("deviceId")) return queryParams
      val mapped = queryParams.toMutableMap()
      queryParams["id"]?.toIntOrNull()?.let { mapped["id"] = toCloudDeviceId(it).toString() }
      queryParams["deviceId"]?.toIntOrNull()?.let { mapped["deviceId"] = toCloudDeviceId(it).toString() }
      return mapped
    }

    private val lastSyncedJobSignatureCache =
      java.util.concurrent.ConcurrentHashMap<String, String>()
    private val cloudRateLimitMutex = Mutex()
    @Volatile private var lastCloudCallTimestampMs: Long = 0L
    @Volatile private var lastLocalFailureTimestampMs: Long = 0L
    private val cachedCloudWattMap = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Double>>()

    fun isLocalReachable(): Boolean {
      val now = System.currentTimeMillis()
      val lastFailure = lastLocalFailureTimestampMs
      return lastFailure == 0L || (now - lastFailure > 60_000L)
    }

    fun markLocalFailed() {
      lastLocalFailureTimestampMs = System.currentTimeMillis()
    }

    fun markLocalSucceeded() {
      lastLocalFailureTimestampMs = 0L
    }

    fun normalizeLocalBaseUrl(rawIp: String): String {
      val trimmed = rawIp.trim().removeSuffix("/")
      val withoutApi = trimmed.removeSuffix("/api")
      return if (withoutApi.startsWith("http://") || withoutApi.startsWith("https://")) {
        withoutApi
      } else {
        "http://$withoutApi"
      }
    }

    private fun urlEncode(value: String): String =
      URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun percentEncode(value: String): String =
      URLEncoder.encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")
  }
}
