/*
 * App lock for Arc: locked apps ask for a PIN however they are opened.
 * The lock itself is enforced natively (accessibility service + PIN screen); this keeps the UI in sync.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// How long an app stays open after its PIN was entered.
class RelockOption {
  final int minutes; // 0 = until the app is left, -1 = until the TV sleeps
  final String label;
  const RelockOption(this.minutes, this.label);
}

const relockOptions = [
  RelockOption(0, 'Until I leave the app'),
  RelockOption(5, '5 minutes'),
  RelockOption(15, '15 minutes'),
  RelockOption(30, '30 minutes'),
  RelockOption(-1, 'Until the TV sleeps'),
];

class AppLockService extends ChangeNotifier {
  static const _channel = MethodChannel('me.efesser.flauncher/method');

  Set<String> _locked = {};
  bool _hasPin = false;
  bool _isActive = false;
  int _relockMinutes = 0;
  String _lockType = 'pin';

  /// 'pin' (four digits) or 'pattern' (4-8 presses of the arrows and OK).
  String get lockType => _lockType;
  String get secretName => _lockType == 'pattern' ? 'pattern' : 'PIN';

  Set<String> get lockedPackages => _locked;
  bool get hasPin => _hasPin;

  /// False when Arc's accessibility service is off: then nothing is actually locked.
  bool get isActive => _isActive;
  int get relockMinutes => _relockMinutes;

  RelockOption get relockOption =>
      relockOptions.firstWhere((o) => o.minutes == _relockMinutes, orElse: () => relockOptions.first);

  AppLockService() {
    refresh();
  }

  bool isLocked(String packageName) => _locked.contains(packageName);

  Future<void> refresh() async {
    try {
      final locked = await _channel.invokeListMethod<String>('appLockGetLocked') ?? [];
      _locked = locked.toSet();
      _hasPin = await _channel.invokeMethod<bool>('appLockHasPin') ?? false;
      _isActive = await _channel.invokeMethod<bool>('appLockIsActive') ?? false;
      _relockMinutes = await _channel.invokeMethod<int>('appLockGetRelockMinutes') ?? 0;
      _lockType = await _channel.invokeMethod<String>('appLockGetType') ?? 'pin';
    } catch (e) {
      debugPrint('App lock: $e');
    }
    notifyListeners();
  }

  /// Opens the PIN screen. [set] chooses a new PIN (asking for the old one first, if any).
  Future<bool> _askPin({bool set = false, String? type}) async {
    try {
      return await _channel.invokeMethod<bool>(
              'appLockRequestPin', {'mode': set ? 'set' : 'verify', if (type != null) 'type': type}) ??
          false;
    } catch (e) {
      debugPrint('App lock PIN: $e');
      return false;
    }
  }

  /// Locking needs a PIN to exist (it is chosen the first time); unlocking needs the PIN.
  Future<void> toggleLock(String packageName) async {
    final lock = !isLocked(packageName);
    if (lock) {
      if (!_hasPin && !await _askPin(set: true)) return;
    } else {
      if (!await _askPin()) return;
    }
    await _channel.invokeMethod('appLockSetLocked', {'packageName': packageName, 'locked': lock});
    await refresh();
  }

  /// Chooses a new secret of [type] (asks for the current one first).
  Future<void> setLockType(String type) async {
    await _askPin(set: true, type: type);
    await refresh();
  }

  Future<void> changePin() async {
    await _askPin(set: true);
    await refresh();
  }

  /// Asks for the PIN (true when there is none yet).
  Future<bool> verifyPin() async => !_hasPin || await _askPin();

  Future<void> setRelock(int minutes) async {
    await _channel.invokeMethod('appLockSetRelockMinutes', {'minutes': minutes});
    await refresh();
  }
}
