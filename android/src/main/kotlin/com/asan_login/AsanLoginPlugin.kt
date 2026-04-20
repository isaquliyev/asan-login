package com.asan_login

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.asan_login.AsanLoginBridge
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

class AsanLoginPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    private lateinit var channel: MethodChannel
    private lateinit var appContext: Context
    private var activity: Activity? = null

    companion object {
        const val CHANNEL = "asan_login"
        private const val PREFS = "asan_login_prefs"
        private const val KEY_SCHEME = "scheme"

        private var codeConsumed = false
        private var lastConsumedCode: String? = null
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Log.d("AsanLogin", "onAttachedToEngine called")
        appContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)

        Log.d("AsanLogin", "Setting bridge callback, pendingIntent=${AsanLoginBridge.pendingIntent?.data}")
        AsanLoginBridge.onNewIntent = { intent -> processIntent(intent) }
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

            codeConsumed = false
            lastConsumedCode = null

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
        return "${url}client_id=$clientId&redirect_uri=$redirectUri&response_type=$responseType&state=$sessionId&scope=$scope"
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

        val code = data.getQueryParameter("code")
        Log.d("AsanLogin", "code=$code")
        if (code == null) {
            Log.d("AsanLogin", "Returning: code is null")
            return
        }

        Log.d("AsanLogin", "codeConsumed=$codeConsumed, lastConsumedCode=$lastConsumedCode")
        if (codeConsumed) {
            Log.d("AsanLogin", "Returning: code already consumed")
            return
        }
        if (code == lastConsumedCode) {
            Log.d("AsanLogin", "Returning: duplicate code")
            return
        }

        codeConsumed = true
        lastConsumedCode = code
        Log.d("AsanLogin", "Invoking onCodeReceived with code")
        channel.invokeMethod("onCodeReceived", code)
    }
}