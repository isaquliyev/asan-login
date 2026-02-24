package com.asan_login

import android.content.Intent
import android.net.Uri
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import android.app.Activity

class AsanLoginPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private var scheme: String? = null
    private var activity: Activity? = null
    private var codeConsumed = false
    private var lastConsumedCode: String? = null

    // Store reference so we can remove it later
    private var newIntentListener: ((Intent) -> Boolean)? = null

    companion object {
        const val CHANNEL = "asan_login"
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity

        // Create listener and store reference
        val listener: (Intent) -> Boolean = { intent ->
            handleNewIntent(intent)
            true
        }
        newIntentListener = listener
        binding.addOnNewIntentListener(listener)
    }

    override fun onDetachedFromActivity() {
        activity = null
        newIntentListener = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
        newIntentListener = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity

        // Re-register fresh listener
        val listener: (Intent) -> Boolean = { intent ->
            handleNewIntent(intent)
            true
        }
        newIntentListener = listener
        binding.addOnNewIntentListener(listener)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        if (call.method == "performLogin") {
            val url = call.argument<String>("url") ?: ""
            val clientId = call.argument<String>("clientId") ?: ""
            val redirectUri = call.argument<String>("redirectUri") ?: ""
            val scope = call.argument<String>("scope") ?: ""
            val sessionId = call.argument<String>("sessionId") ?: ""
            val responseType = call.argument<String>("responseType") ?: ""
            scheme = call.argument<String>("scheme")

            codeConsumed = false
            lastConsumedCode = null

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

    private fun handleNewIntent(intent: Intent) {
        val data = intent.data

        if (data == null || scheme == null || data.scheme != scheme) return

        val code = data.getQueryParameter("code") ?: return

        if (codeConsumed) return
        if (code == lastConsumedCode) return

        codeConsumed = true
        lastConsumedCode = code
        channel.invokeMethod("onCodeReceived", code)
    }
}