package com.asan_login

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import az.gov.etabib.AsanLoginBridge
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

class AsanLoginPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    private lateinit var channel: MethodChannel
    private var activity: Activity? = null

    companion object {
        const val CHANNEL = "asan_login"
        private const val PREFS = "asan_login_prefs"
        private const val KEY_SCHEME = "scheme"

        private var codeConsumed = false
        private var lastConsumedCode: String? = null
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)

        // Connect to MainActivity via bridge
        // If MainActivity already parked a pending intent, the bridge setter fires it immediately
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
        if (call.method == "performLogin") {
            val scheme = call.argument<String>("scheme")

            // Persist scheme so it survives process death
            activity?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(KEY_SCHEME, scheme)
                ?.apply()

            codeConsumed = false
            lastConsumedCode = null

            val url = call.argument<String>("url") ?: ""
            val clientId = call.argument<String>("clientId") ?: ""
            val redirectUri = call.argument<String>("redirectUri") ?: ""
            val scope = call.argument<String>("scope") ?: ""
            val sessionId = call.argument<String>("sessionId") ?: ""
            val responseType = call.argument<String>("responseType") ?: ""

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
        val data = intent.data ?: return

        // Try in-memory scheme first, fall back to persisted value for process death case
        val resolvedScheme = activity?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_SCHEME, null)

        if (resolvedScheme == null || data.scheme != resolvedScheme) return

        val code = data.getQueryParameter("code") ?: return

        if (codeConsumed) return
        if (code == lastConsumedCode) return

        codeConsumed = true
        lastConsumedCode = code

        channel.invokeMethod("onCodeReceived", code)
    }
}