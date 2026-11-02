/*
 * Arc quick settings: a trimmed settings panel that opens on top of whatever app is playing
 * (hold Home, or the YouTube button), built from the same widgets as Arc's own settings.
 *
 * It runs in its own Flutter engine inside QuickPanelActivity, a see-through activity, so it sits under
 * Arc's dim layer and is dimmed along with the picture.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import 'package:flauncher/actions.dart';
import 'package:flauncher/flauncher_app.dart';
import 'package:flauncher/providers/app_lock_service.dart';
import 'package:flauncher/providers/brightness_service.dart';
import 'package:flauncher/providers/settings_service.dart';
import 'package:flauncher/providers/vpn_guard_service.dart';
import 'package:flauncher/widgets/rounded_switch_list_tile.dart';
import 'package:flauncher/widgets/settings/applications_panel_page.dart';
import 'package:flauncher/widgets/settings/brightness_settings_page.dart';
import 'package:flauncher/widgets/settings/focusable_settings_tile.dart';
import 'package:flauncher/widgets/settings/settings_theme.dart';
import 'package:flauncher/widgets/side_panel_dialog.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _panelChannel = MethodChannel('arc/quick_panel');

/// Goes up by one each time the panel is shown, so it starts fresh (focus on the first row, current values).
final _shown = ValueNotifier<int>(0);

/// The app the panel was opened over, and the VPN status (from the accessibility service).
final _currentApp = ValueNotifier<Map<dynamic, dynamic>?>(null);

Future<void> _loadCurrentApp() async {
  try {
    _currentApp.value = await _panelChannel.invokeMapMethod<dynamic, dynamic>('currentApp');
  } catch (e) {
    _currentApp.value = null;
  }
}

@pragma('vm:entry-point')
Future<void> quickPanelMain() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();
  final settings = SettingsService(prefs);
  final brightness = BrightnessService(prefs);
  final lock = AppLockService();

  _panelChannel.setMethodCallHandler((call) async {
    if (call.method == 'shown') {
      // Arc's settings may have changed since last time.
      await prefs.reload();
      await brightness.applyCurrentSlotBrightness();
      await lock.refresh();
      await _loadCurrentApp();
      _shown.value++;
    }
    return null;
  });

  runApp(MultiProvider(
    providers: [
      ChangeNotifierProvider.value(value: settings),
      ChangeNotifierProvider.value(value: brightness),
      ChangeNotifierProvider.value(value: lock),
    ],
    child: const _QuickPanelApp(),
  ));
  WidgetsBinding.instance.addPostFrameCallback((_) => _panelChannel.invokeMethod('ready'));
}

Future<void> _close() => _panelChannel.invokeMethod('close');

class _QuickPanelApp extends StatelessWidget {
  const _QuickPanelApp();

  static const _back = PrioritizedIntents(orderedIntents: [DismissIntent(), BackIntent()]);

  @override
  Widget build(BuildContext context) {
    return Selector<SettingsService, Color>(
      selector: (_, settings) => settings.accentColor,
      builder: (context, accentColor, _) => MaterialApp(
        debugShowCheckedModeBanner: false,
        title: 'Arc quick settings',
        theme: arcTheme(accentColor),
        shortcuts: {
          ...WidgetsApp.defaultShortcuts,
          const SingleActivator(LogicalKeyboardKey.escape): _back,
          const SingleActivator(LogicalKeyboardKey.gameButtonB): _back,
          const SingleActivator(LogicalKeyboardKey.select): const ActivateIntent(),
        },
        home: PopScope(
          canPop: false,
          onPopInvokedWithResult: (didPop, _) {
            if (!didPop) _close();
          },
          child: ValueListenableBuilder<int>(
            valueListenable: _shown,
            builder: (context, shown, _) => _QuickPanel(key: ValueKey(shown)),
          ),
        ),
      ),
    );
  }
}

class _QuickPanel extends StatelessWidget {
  const _QuickPanel({super.key});

  @override
  Widget build(BuildContext context) {
    final brightness = context.watch<BrightnessService>();
    final lock = context.watch<AppLockService>();
    final textStyle = Theme.of(context).textTheme.bodyMedium;

    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Stack(
        children: [
          // A light scrim next to the panel; the app keeps showing behind it.
          GestureDetector(
            onTap: _close,
            child: Container(
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.centerLeft,
                  end: Alignment.centerRight,
                  colors: [Color(0x66000000), Color(0x00000000)],
                ),
              ),
            ),
          ),
          SidePanelDialog(
            width: SettingsStyle.panelWidth,
            isRightSide: false,
            child: Actions(
              actions: {BackIntent: CallbackAction<BackIntent>(onInvoke: (_) => _close())},
              child: Column(
                children: [
                  const SettingsPageHeader(title: 'Quick settings'),
                  Expanded(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.symmetric(horizontal: 8),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const _CurrentAppCard(),
                          RoundedSwitchListTile(
                            autofocus: true,
                            title: Text('Dim screen', style: textStyle),
                            secondary: Icon(brightness.isDimNow ? Icons.nightlight : Icons.nightlight_outlined),
                            value: brightness.isDimNow,
                            onChanged: (value) => brightness.setDimNow(value),
                          ),
                          BrightnessSlider(
                            label: 'Screen brightness',
                            icon: Icons.nightlight,
                            isCurrentSlot: brightness.isDimNow,
                            brightness: brightness.dimNowBrightness,
                            onChanged: (value) async {
                              // Moving the slider means "dim now", as in the old panel.
                              await brightness.setDimNowBrightness(value);
                              if (!brightness.isDimNow) await brightness.setDimNow(true);
                            },
                          ),
                          const SettingsSectionDivider(),
                          if (lock.hasPin) ...[
                            FocusableSettingsTile(
                              leading: const Icon(Icons.lock_outline),
                              title: Text('Lock apps now', style: textStyle),
                              onPressed: () => _panelChannel.invokeMethod('lockNow'),
                            ),
                            FocusableSettingsTile(
                              leading: const Icon(Icons.timer_outlined),
                              title: Text('Stay unlocked: ${lock.relockOption.label}', style: textStyle),
                              onPressed: () => pickRelock(context, lock),
                            ),
                            const SettingsSectionDivider(),
                          ],
                          FocusableSettingsTile(
                            leading: const Icon(Icons.settings_outlined),
                            title: Text('All launcher settings', style: textStyle),
                            showChevron: true,
                            onPressed: () => _panelChannel.invokeMethod('openLauncherSettings'),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Which app is open and whether its traffic is guarded by VPN, with a switch to require VPN for it.
class _CurrentAppCard extends StatelessWidget {
  const _CurrentAppCard();

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<Map<dynamic, dynamic>?>(
      valueListenable: _currentApp,
      builder: (context, app, _) {
        if (app == null) return const SizedBox.shrink();
        final vpn = VpnStatus.fromMap((app['vpn'] as Map?) ?? const {});
        final packageName = app['packageName'] as String?;
        final name = app['name'] as String? ?? packageName;
        final icon = app['icon'];
        final guarded = app['guarded'] == true;
        final textTheme = Theme.of(context).textTheme;

        final place = [vpn.flag, vpn.city ?? vpn.country].whereType<String>().join(' ');
        final vpnLine = vpn.up
            ? ['VPN on', if (place.isNotEmpty) place, if (vpn.ip != null) vpn.ip!].join(' · ')
            : 'VPN off';
        final Color color = !vpn.up
            ? (guarded ? Colors.orangeAccent : Colors.white60)
            : Colors.lightGreenAccent;

        return Padding(
          padding: const EdgeInsets.fromLTRB(8, 4, 8, 4),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (packageName != null)
                Row(children: [
                  if (icon is Uint8List)
                    ClipRRect(
                      borderRadius: BorderRadius.circular(8),
                      child: Image.memory(icon, width: 40, height: 40),
                    )
                  else
                    const Icon(Icons.apps, size: 40),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(name ?? '', style: textTheme.titleMedium, overflow: TextOverflow.ellipsis),
                  ),
                ]),
              const SizedBox(height: 8),
              Row(children: [
                Icon(vpn.up ? Icons.lock : Icons.lock_open, size: 18, color: color),
                const SizedBox(width: 8),
                Expanded(child: Text(vpnLine, style: textTheme.bodyMedium?.copyWith(color: color))),
              ]),
              if (vpn.up && vpn.vpnApp != null)
                Padding(
                  padding: const EdgeInsets.only(left: 26, top: 2),
                  child: Text([vpn.vpnApp!, if (vpn.org != null) vpn.org!].join(' · '),
                      style: textTheme.bodySmall?.copyWith(color: Colors.white60)),
                ),
              if (packageName != null)
                RoundedSwitchListTile(
                  title: Text('Require VPN for this app', style: textTheme.bodyMedium),
                  secondary: Icon(guarded ? Icons.vpn_lock : Icons.vpn_key_outlined),
                  value: guarded,
                  onChanged: (value) async {
                    await _panelChannel.invokeMethod('setVpnGuarded', {'packageName': packageName, 'guarded': value});
                    await _loadCurrentApp();
                  },
                ),
              const SettingsSectionDivider(),
            ],
          ),
        );
      },
    );
  }
}
