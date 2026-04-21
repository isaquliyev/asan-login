package com.asan_login

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.asan_login.AsanLoginBridge
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.net.URLDecoder
import java.net.URLEncoder

class AsanLoginPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    private lateinit var channel: MethodChannel
    private lateinit var appContext: Context
    private var activity: Activity? = null

    companion object {
        const val CHANNEL = "asan_login"
        private const val PREFS = "asan_login_prefs"
        private const val KEY_SCHEME = "scheme"
        private const val DELIVERY_TIMEOUT_MS = 600L
        private const val MAX_DELIVERY_RETRY = 5

        private var pendingCode: String? = null
        private var ackedCode: String? = null
        private var pendingCallbackMethod: String? = null
        private var pendingCallbackPayload: String? = null
        private var activeDeliveryToken: Long = 0L
        private var deliverySequence: Long = 0L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Log.d("AsanLogin", "onAttachedToEngine called")
        appContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)

        Log.d("AsanLogin", "Setting bridge callback, pendingIntent=${AsanLoginBridge.pendingIntent?.data}")
        AsanLoginBridge.onNewIntent = { intent -> processIntent(intent) }
        flushPendingCallback()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        AsanLoginBridge.onNewIntent = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        Log.d("AsanLogin", "onMethodCall: ${call.method}")
        if (call.method == "performLogin") {
            val scheme = call.argument<String>("scheme")
            Log.d("AsanLogin", "performLogin called with scheme=$scheme")

            // Persist scheme so it survives process death
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SCHEME, scheme)
                .apply()
            Log.d("AsanLogin", "Scheme saved to SharedPreferences")

            pendingCode = null
            ackedCode = null
            pendingCallbackMethod = null
            pendingCallbackPayload = null
            activeDeliveryToken = 0L

            val url = call.argument<String>("url") ?: ""
            val clientId = call.argument<String>("clientId") ?: ""
            val redirectUri = call.argument<String>("redirectUri") ?: ""
            val scope = call.argument<String>("scope") ?: ""
            val sessionId = call.argument<String>("sessionId") ?: ""
            val responseType = call.argument<String>("responseType") ?: ""

            Log.d("AsanLogin", "Calling performLogin with redirectUri=$redirectUri")
            performLogin(url, clientId, redirectUri, scope, sessionId, responseType)
            result.success(null)
        } else {
            result.notImplemented()
        }
    }

    private fun performLogin(
        url: String,
        clientId: String,
        redirectUri: String,
        scope: String,
        sessionId: String,
        responseType: String
    ) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(getAsanUrl(url, clientId, redirectUri, scope, sessionId, responseType))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity?.startActivity(intent)
    }

    private fun getAsanUrl(
        url: String,
        clientId: String,
        redirectUri: String,
        scope: String,
        sessionId: String,
        responseType: String
    ): String {
        val urlWithDelimiter = when {
            url.contains("?") && (url.endsWith("&") || url.endsWith("?")) -> url
            url.contains("?") -> "$url&"
            else -> "$url?"
        }
        val encodedRedirectUri = URLEncoder.encode(redirectUri, "UTF-8")
        val encodedScope = URLEncoder.encode(scope, "UTF-8")

        return "${urlWithDelimiter}client_id=$clientId&redirect_uri=$encodedRedirectUri&response_type=$responseType&state=$sessionId&scope=$encodedScope"
    }

    private fun processIntent(intent: Intent) {
        Log.d("AsanLogin", "processIntent called")
        val data = intent.data
        Log.d("AsanLogin", "intent.data = $data")
        if (data == null) {
            Log.d("AsanLogin", "Returning: data is null")
            return
        }

        val resolvedScheme = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCHEME, null)
        Log.d("AsanLogin", "resolvedScheme=$resolvedScheme, data.scheme=${data.scheme}")

        if (resolvedScheme == null || data.scheme != resolvedScheme) {
            Log.d("AsanLogin", "Returning: scheme mismatch or null")
            return
        }

        val fragment = data.fragment
        val allQueryParams = data.queryParameterNames.associateWith { key ->
            data.getQueryParameter(key)
        }
        Log.d("AsanLogin", "queryParams=$allQueryParams, fragment=$fragment")

        val error = data.getQueryParameter("error") ?: getParamFromFragment(fragment, "error")
        val errorDescription = data.getQueryParameter("error_description")
            ?: getParamFromFragment(fragment, "error_description")
        if (error != null) {
            val errorPayload = buildString {
                append(error)
                if (!errorDescription.isNullOrBlank()) {
                    append(": ")
                    append(errorDescription)
                }
            }
            Log.d("AsanLogin", "Login callback returned error=$errorPayload")
            queueCallback("onLoginError", errorPayload)
            return
        }

        val code = data.getQueryParameter("code") ?: getParamFromFragment(fragment, "code")
        Log.d("AsanLogin", "code(from query/fragment)=$code")
        if (code.isNullOrBlank()) {
            Log.d("AsanLogin", "Returning: code is null or blank")
            queueCallback("onLoginError", "code_missing")
            return
        }

        Log.d("AsanLogin", "pendingCode=$pendingCode, ackedCode=$ackedCode")
        if (code == ackedCode) {
            Log.d("AsanLogin", "Returning: code already acked")
            return
        }

        if (code == pendingCode) {
            Log.d("AsanLogin", "Duplicate code is pending ack; forcing re-delivery")
            flushPendingCallback()
            return
        }

        if (pendingCode != null && pendingCode != code) {
            Log.d("AsanLogin", "Replacing unacked pendingCode=$pendingCode with new code")
        }
        pendingCode = code
        Log.d("AsanLogin", "Invoking onCodeReceived with code")
        queueCallback("onCodeReceived", code)
    }

    private fun getParamFromFragment(fragment: String?, key: String): String? {
        if (fragment.isNullOrBlank()) return null
        return fragment.split("&")
            .firstNotNullOfOrNull { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.isEmpty() || parts[0] != key) {
                    null
                } else {
                    URLDecoder.decode(parts.getOrNull(1).orEmpty(), "UTF-8")
                }
            }
    }

    private fun queueCallback(method: String, payload: String) {
        pendingCallbackMethod = method
        pendingCallbackPayload = payload
        flushPendingCallback()
    }

    private fun flushPendingCallback() {
        val method = pendingCallbackMethod ?: return
        val payload = pendingCallbackPayload
        if (payload == null) {
            pendingCallbackMethod = null
            return
        }
        deliverToDart(method, payload, 0)
    }

    private fun deliverToDart(method: String, payload: String, attempt: Int) {
        mainHandler.post {
            if (!isStillPending(method, payload)) {
                Log.d("AsanLogin", "Skip delivery: callback no longer pending for method=$method")
                return@post
            }

            val deliveryToken = nextDeliveryToken()
            activeDeliveryToken = deliveryToken
            var callbackHandled = false

            mainHandler.postDelayed({
                if (!callbackHandled &&
                    activeDeliveryToken == deliveryToken &&
                    isStillPending(method, payload)
                ) {
                    Log.d("AsanLogin", "Delivery timed out for method=$method attempt=$attempt")
                    retryDelivery(method, payload, attempt)
                }
            }, DELIVERY_TIMEOUT_MS)

            Log.d("AsanLogin", "Delivering callback to Dart method=$method attempt=$attempt")
            channel.invokeMethod(method, payload, object : Result {
                override fun success(result: Any?) {
                    if (callbackHandled) return
                    callbackHandled = true
                    Log.d("AsanLogin", "Dart callback acked for method=$method")
                    if (method == "onCodeReceived") {
                        ackedCode = payload
                        if (pendingCode == payload) {
                            pendingCode = null
                        }
                    }
                    clearPending(method, payload)
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    if (callbackHandled) return
                    callbackHandled = true
                    Log.d(
                        "AsanLogin",
                        "Dart callback error method=$method code=$errorCode message=$errorMessage"
                    )
                    retryDelivery(method, payload, attempt)
                }

                override fun notImplemented() {
                    if (callbackHandled) return
                    callbackHandled = true
                    Log.d("AsanLogin", "Dart callback not implemented for method=$method")
                    retryDelivery(method, payload, attempt)
                }
            })
        }
    }

    private fun retryDelivery(method: String, payload: String, attempt: Int) {
        if (!isStillPending(method, payload)) {
            Log.d("AsanLogin", "Skip retry: callback no longer pending for method=$method")
            return
        }
        if (attempt >= MAX_DELIVERY_RETRY) {
            Log.d("AsanLogin", "Giving up callback delivery after retries for method=$method")
            return
        }
        mainHandler.postDelayed({ deliverToDart(method, payload, attempt + 1) }, 350L)
    }

    private fun clearPending(method: String, payload: String) {
        if (pendingCallbackMethod == method && pendingCallbackPayload == payload) {
            pendingCallbackMethod = null
            pendingCallbackPayload = null
            activeDeliveryToken = 0L
        }
    }

    private fun isStillPending(method: String, payload: String): Boolean {
        return pendingCallbackMethod == method && pendingCallbackPayload == payload
    }

    private fun nextDeliveryToken(): Long {
        deliverySequence += 1L
        return deliverySequence
    }
}