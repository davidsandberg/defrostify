package com.example.carheater.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Inline view (rendered directly inside [AuthConfigDialog] so there are no stacked/overlaid dialogs)
 * for TellStick ZNet Lite v2 authentication.
 *
 * - Step 1 (on `login.telldus.com`): Shows ONLY the native Material 3 Email & Password form while
 *   keeping the WebView hidden (1x1 dp) in the background for OpenID session state.
 * - Step 2 (redirected back to `http://<znet-ip>/api/authorize`): Hides the native login form and
 *   shows the TellStick ZNet approval page so the user can tap "Authorize", then completes login.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun ZNetBrowserAuthContent(
  authUrl: String,
  onBack: () -> Unit,
  onAuthCompleted: () -> Unit,
) {
  var currentUrl by remember { mutableStateOf(authUrl) }
  var isLoading by remember { mutableStateOf(true) }
  var statusHint by remember {
    mutableStateOf("Step 1 of 2: Sign in with your Telldus Live account.")
  }
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var bridgeRef by remember { mutableStateOf<TelldusHttpLoginBridge?>(null) }

  // Show the native login card while we are waiting on the initial redirect or on login.telldus.com
  val showNativeLoginForm =
    currentUrl.contains("login.telldus.com") || currentUrl.contains("/web/login")

  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Header bar inside the single dialog
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "TellStick ZNet Sign-In",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = statusHint,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      TextButton(onClick = onBack) {
        Text("Back")
      }
    }

    if (isLoading) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    // Step 1: Clean native Email & Password card (WebView is hidden below so nothing overlaps!)
    if (showNativeLoginForm) {
      Card(
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
          ),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text = "Telldus Live Credentials",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
          )
          OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Telldus Email") },
            singleLine = true,
            keyboardOptions =
              KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
              ),
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Telldus Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
              KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
              ),
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(modifier = Modifier.height(4.dp))
          Button(
            onClick = {
              val targetUrl = currentUrl
              bridgeRef?.submitLoginOverPlainHttp(targetUrl, email.trim(), password)
            },
            enabled = email.isNotBlank() && password.isNotBlank() && !isLoading,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Sign In to Telldus")
          }
        }
      }
    }

    // Step 2: TellStick Local Approval WebView (hidden 1dp while on Step 1 so there is no double form!)
    Box(
      modifier =
        if (showNativeLoginForm) {
          Modifier.size(1.dp).alpha(0f)
        } else {
          Modifier
            .fillMaxWidth()
            .height(360.dp)
            .clip(RoundedCornerShape(16.dp))
        }
    ) {
      AndroidView(
        modifier =
          if (showNativeLoginForm) {
            Modifier.size(1.dp)
          } else {
            Modifier.fillMaxWidth().height(360.dp)
          },
        factory = { context ->
          WebView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnTouchListener { v, event ->
              when (event.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_UP -> {
                  if (!v.hasFocus()) {
                    v.requestFocus()
                  }
                }
              }
              false
            }

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            val bridge =
              TelldusHttpLoginBridge(
                webView = this,
                onStatus = { msg ->
                  Handler(Looper.getMainLooper()).post { statusHint = msg }
                },
                onAuthorized = {
                  Handler(Looper.getMainLooper()).post { onAuthCompleted() }
                },
              )
            bridgeRef = bridge

            addJavascriptInterface(bridge, "TelldusAuthBridge")

            webViewClient =
              object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                  super.onPageStarted(view, url, favicon)
                  isLoading = true
                  if (url != null) {
                    currentUrl = url
                  }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                  super.onPageFinished(view, url)
                  isLoading = false
                  if (url != null) {
                    currentUrl = url
                    if (url.contains("/api/authorize") && !url.contains("login.telldus.com")) {
                      statusHint =
                        "Step 2 of 2: Tap 'Authorize' below, then tap 'Complete Login'."
                      // Detect if the page already says access was granted / authorized
                      view?.evaluateJavascript(
                        """
                        (function() {
                          var text = (document.body && document.body.innerText) ? document.body.innerText : '';
                          var hasForm = document.querySelector('form') !== null;
                          if (!hasForm && (text.indexOf('authorized') !== -1 || text.indexOf('Authorized') !== -1 || text.indexOf('success') !== -1)) {
                            window.TelldusAuthBridge.notifyAuthorized();
                          }
                        })();
                        """.trimIndent(),
                        null,
                      )
                    }
                  }
                }

                override fun shouldOverrideUrlLoading(
                  view: WebView?,
                  request: WebResourceRequest?,
                ): Boolean {
                  val target = request?.url?.toString() ?: return false
                  if (target.startsWith("https://login.telldus.com")) {
                    val plainHttp = "http://" + target.removePrefix("https://")
                    view?.loadUrl(plainHttp)
                    return true
                  }
                  return false
                }

                override fun shouldInterceptRequest(
                  view: WebView?,
                  request: WebResourceRequest?,
                ): WebResourceResponse? {
                  val reqUrl = request?.url?.toString() ?: return null
                  val method = request.method?.uppercase() ?: "GET"

                  if (method == "GET" &&
                    (reqUrl.startsWith("http://login.telldus.com") ||
                      reqUrl.startsWith("https://login.telldus.com"))
                  ) {
                    val plainUrl =
                      if (reqUrl.startsWith("https://")) {
                        "http://" + reqUrl.removePrefix("https://")
                      } else {
                        reqUrl
                      }
                    return fetchTelldusLoginWithoutHsts(plainUrl)
                  }
                  return super.shouldInterceptRequest(view, request)
                }
              }

            loadUrl(authUrl)
          }
        },
      )
    }

    if (!showNativeLoginForm) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedButton(onClick = onBack) {
          Text("Cancel")
        }
        Button(onClick = onAuthCompleted) {
          Text("Complete Login")
        }
      }
    }
  }
}

internal class TelldusHttpLoginBridge(
  private val webView: WebView,
  private val onStatus: (String) -> Unit,
  private val onAuthorized: () -> Unit = {},
) {
  private val mainHandler = Handler(Looper.getMainLooper())

  @JavascriptInterface
  fun notifyAuthorized() {
    onStatus("Authorization confirmed! Completing login...")
    onAuthorized()
  }

  @JavascriptInterface
  fun submitLoginOverPlainHttp(actionUrl: String, email: String, password: String) {
    onStatus("Signing in to Telldus over plain HTTP...")
    Thread {
      try {
        val plainActionUrl =
          if (actionUrl.startsWith("https://")) {
            "http://" + actionUrl.removePrefix("https://")
          } else {
            actionUrl
          }

        val cookieManager = CookieManager.getInstance()
        val existingCookies = cookieManager.getCookie(plainActionUrl) ?: ""

        val conn = (URL(plainActionUrl).openConnection() as HttpURLConnection)
        conn.instanceFollowRedirects = false
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        if (existingCookies.isNotBlank()) {
          conn.setRequestProperty("Cookie", existingCookies)
        }
        conn.doOutput = true

        val postData =
          "email=${URLEncoder.encode(email, "UTF-8")}&password=${URLEncoder.encode(password, "UTF-8")}"
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
          writer.write(postData)
          writer.flush()
        }

        val responseCode = conn.responseCode
        val setCookies = conn.headerFields["Set-Cookie"].orEmpty()
        for (cookie in setCookies) {
          cookieManager.setCookie(plainActionUrl, cookie)
        }

        val location = conn.getHeaderField("Location")
        if (responseCode in 300..399 && !location.isNullOrBlank()) {
          val resolvedLocation =
            when {
              location.startsWith("https://login.telldus.com") ->
                "http://" + location.removePrefix("https://")
              location.startsWith("/") -> {
                val parsed = URL(plainActionUrl)
                "http://${parsed.host}$location"
              }
              else -> location
            }
          onStatus("Login succeeded! Loading TellStick Authorize page...")
          mainHandler.post { webView.loadUrl(resolvedLocation) }
        } else {
          val html =
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
          val patched = injectPlainHttpFormHook(html, plainActionUrl)
          onStatus("Login failed — please check your Telldus email and password.")
          mainHandler.post {
            webView.loadDataWithBaseURL(
              plainActionUrl,
              patched,
              "text/html",
              "UTF-8",
              plainActionUrl,
            )
          }
        }
        conn.disconnect()
      } catch (e: Exception) {
        onStatus("Login error: ${e.message}")
      }
    }.start()
  }
}

private fun fetchTelldusLoginWithoutHsts(plainUrl: String): WebResourceResponse? {
  return try {
    val cookieManager = CookieManager.getInstance()
    val cookies = cookieManager.getCookie(plainUrl) ?: ""

    val conn = (URL(plainUrl).openConnection() as HttpURLConnection)
    conn.instanceFollowRedirects = false
    conn.requestMethod = "GET"
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    if (cookies.isNotBlank()) {
      conn.setRequestProperty("Cookie", cookies)
    }

    val statusCode = conn.responseCode
    val setCookies = conn.headerFields["Set-Cookie"].orEmpty()
    for (cookie in setCookies) {
      cookieManager.setCookie(plainUrl, cookie)
    }

    val contentTypeHeader = conn.contentType ?: "text/html; charset=UTF-8"
    val mimeType = contentTypeHeader.substringBefore(";").trim().ifBlank { "text/html" }

    if (mimeType == "text/html" && statusCode in 200..299) {
      val rawHtml = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
      val patchedHtml = injectPlainHttpFormHook(rawHtml, plainUrl)
      WebResourceResponse(
        "text/html",
        "UTF-8",
        ByteArrayInputStream(patchedHtml.toByteArray(Charsets.UTF_8)),
      )
    } else {
      val bytes = conn.inputStream.use { it.readBytes() }
      WebResourceResponse(
        mimeType,
        "UTF-8",
        ByteArrayInputStream(bytes),
      )
    }
  } catch (_: Exception) {
    null
  }
}

private fun injectPlainHttpFormHook(html: String, pageUrl: String): String {
  val escapedUrl = pageUrl.replace("'", "\\'")
  val hookAndStyle =
    """
    <style type="text/css">
      html, body {
        background-color: #263238 !important;
        color: #ffffff !important;
      }
      input, input[type="email"], input[type="password"], input[type="text"] {
        color: #111111 !important;
        background-color: #ffffff !important;
        -webkit-text-fill-color: #111111 !important;
        opacity: 1 !important;
        font-size: 16px !important;
        padding: 8px !important;
        border: 1px solid #666666 !important;
      }
    </style>
    <script type="text/javascript">
    (function() {
      function hookLogin() {
        var form = document.forms['formLogin'] || document.querySelector('form');
        if (!form) return;
        var doSubmit = function(e) {
          if (e) e.preventDefault();
          var emailEl = document.getElementById('email');
          var passEl = document.getElementById('password');
          if (emailEl && passEl && window.TelldusAuthBridge) {
            window.TelldusAuthBridge.submitLoginOverPlainHttp('$escapedUrl', emailEl.value, passEl.value);
            return false;
          }
        };
        form.onsubmit = doSubmit;
        form.submit = doSubmit;
      }
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', hookLogin);
      } else {
        hookLogin();
      }
    })();
    </script>
    """.trimIndent()
  return if (html.contains("</body>", ignoreCase = true)) {
    html.replace("</body>", "$hookAndStyle</body>", ignoreCase = true)
  } else {
    html + hookAndStyle
  }
}
