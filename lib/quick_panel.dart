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
