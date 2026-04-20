import 'dart:developer' as logger;

import 'package:flutter/services.dart';
import 'package:rxdart/rxdart.dart';

import 'helpers/uuid_helper.dart';

class AsanLoginController {
  AsanLoginController._() {
    logger.log('AsanLogin: Controller created, setting up method call handler');
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  static AsanLoginController? _instance;

  static AsanLoginController get instance {
    if (_instance == null) {
      logger.log('AsanLogin: Creating new controller instance');
    }
    return _instance ??= AsanLoginController._();
  }

  static const _channel = MethodChannel('asan_login');

  ReplaySubject<String> _asanCodeSubject = ReplaySubject<String>(maxSize: 1);

  Stream<String> get asanCodeStream => _asanCodeSubject.stream;

  String? _lastConsumedCode;

  Future<void> _handleMethodCall(MethodCall call) async {
    logger.log('AsanLogin: _handleMethodCall received: ${call.method}');
    if (call.method != 'onCodeReceived' || call.arguments == null) {
      logger.log('AsanLogin: Ignoring call (not onCodeReceived or null args)');
      return;
    }

    final code = call.arguments as String;
    logger.log('AsanLogin: Received code: ${code.substring(0, code.length > 10 ? 10 : code.length)}...');

    if (code == _lastConsumedCode) {
      logger.log('AsanLogin: duplicate code received, dropping.');
      return;
    }

    _lastConsumedCode = code;
    logger.log('AsanLogin: _asanCodeSubject.isClosed = ${_asanCodeSubject.isClosed}');

    if (_asanCodeSubject.isClosed) {
      logger.log('AsanLogin: Subject was closed, creating new one');
      _asanCodeSubject = ReplaySubject<String>(maxSize: 1);
    }

    logger.log('AsanLogin: Adding code to subject');
    _asanCodeSubject.add(code);
    logger.log('AsanLogin: Code added to subject successfully');
  }

  Future<void> performLogin({
    required String url,
    required String clientId,
    required String redirectUri,
    String scope = 'openid certificate',
    String responseType = 'code',
    required String scheme,
  }) async {
    try {
      final sessionId = UuidHelper.generateUuid();
      logger.log('AsanLogin: starting login with UUID: $sessionId');
      await _channel.invokeMethod(
        'performLogin',
        {
          'url': url,
          'clientId': clientId,
          'redirectUri': redirectUri,
          'scope': scope,
          'sessionId': sessionId,
          'responseType': responseType,
          'scheme': scheme,
        },
      );
    } on PlatformException catch (e) {
      logger.log('AsanLogin: failed to perform login: ${e.message}');
    }
  }

  void reset() {
    logger.log('AsanLogin: resetting controller.');
    _lastConsumedCode = null;
    if (!_asanCodeSubject.isClosed) {
      _asanCodeSubject.close();
    }
    _asanCodeSubject = ReplaySubject<String>(maxSize: 1);
  }

  void dispose() => _asanCodeSubject.close();
}