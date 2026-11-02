/*
 * VPN guard for Arc: chosen apps only run while a VPN is up, with any VPN app.
 * Enforced natively (accessibility service + VPN screen); this keeps the UI and the indicator in sync.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class VpnStatus {
  final bool up;
  final String? ip;
  final String? country;
  final String? countryCode;
  final String? city;
  final String? org;
  final String? vpnApp;
  final String? vpnAppPackage;

  const VpnStatus({
    this.up = false,
    this.ip,
    this.country,
    this.countryCode,
    this.city,
    this.org,
    this.vpnApp,
    this.vpnAppPackage,
  });

  factory VpnStatus.fromMap(Map<dynamic, dynamic> map) {
    String? s(String key) {
      final value = map[key];
      return value is String && value.isNotEmpty ? value : null;
    }

    return VpnStatus(
      up: map['up'] == true,
      ip: s('ip'),
      country: s('country'),
      countryCode: s('countryCode'),
      city: s('city'),
      org: s('org'),
      vpnApp: s('vpnApp'),
      vpnAppPackage: s('vpnAppPackage'),
    );
  }

  /// Regional indicator letters for the country code, e.g. SE -> 🇸🇪.
  String? get flag {
    final code = countryCode;
    if (code == null || code.length != 2) return null;
    return String.fromCharCodes(code.toUpperCase().codeUnits.map((c) => 0x1F1E6 + c - 0x41));
  }
}

class VpnApp {
  final String packageName;
  final String name;
  const VpnApp(this.packageName, this.name);
}

class VpnGuardService extends ChangeNotifier {
  static const _channel = MethodChannel('me.efesser.flauncher/method');
  static const _events = EventChannel('me.efesser.flauncher/event_vpn');

  Set<String> _guarded = {};
  String _mode = 'block';
  VpnStatus _status = const VpnStatus();
  List<VpnApp> _vpnApps = [];
  StreamSubscription? _subscription;

  Set<String> get guardedPackages => _guarded;

  /// 'block' (no way past without VPN) or 'warn' (with "Open anyway").
  String get mode => _mode;
  VpnStatus get status => _status;

  /// Installed apps that provide a VPN.
  List<VpnApp> get vpnApps => _vpnApps;

  bool isGuarded(String packageName) => _guarded.contains(packageName);

  VpnGuardService() {
    refresh();
    _subscription = _events.receiveBroadcastStream().listen((event) {
      if (event is Map) {
        _status = VpnStatus.fromMap(event);
        notifyListeners();
      }
    }, onError: (e) => debugPrint('VPN guard: $e'));
  }

  Future<void> refresh() async {
    try {
      _guarded = (await _channel.invokeListMethod<String>('vpnGetGuarded') ?? []).toSet();
      _mode = await _channel.invokeMethod<String>('vpnGetMode') ?? 'block';
      final status = await _channel.invokeMapMethod<dynamic, dynamic>('vpnGetStatus');
      if (status != null) _status = VpnStatus.fromMap(status);
      final apps = await _channel.invokeListMethod<Map<dynamic, dynamic>>('vpnGetVpnApps') ?? [];
      _vpnApps = apps.map((a) => VpnApp(a['packageName'] as String, (a['name'] as String?) ?? a['packageName'] as String)).toList();
    } catch (e) {
      debugPrint('VPN guard: $e');
    }
    notifyListeners();
  }

  Future<void> toggleGuarded(String packageName) async {
    await _channel.invokeMethod('vpnSetGuarded', {'packageName': packageName, 'guarded': !isGuarded(packageName)});
    await refresh();
  }

  Future<void> setMode(String mode) async {
    await _channel.invokeMethod('vpnSetMode', {'mode': mode});
    await refresh();
  }

  Future<void> setVpnApp(String packageName) async {
    await _channel.invokeMethod('vpnSetVpnApp', {'packageName': packageName});
    await refresh();
  }

  /// Android's own VPN page (always-on VPN, block connections without VPN). Hidden in Google TV's menus.
  Future<bool> openSystemVpnSettings() async =>
      await _channel.invokeMethod<bool>('vpnOpenSystemSettings') ?? false;

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }
}
