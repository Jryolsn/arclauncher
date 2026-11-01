import 'package:flauncher/flauncher_channel.dart';
import 'package:flauncher/widgets/settings/settings_theme.dart';
import 'package:flutter/material.dart';

/// The quick panel (hold Home) runs through Arc's accessibility service, which Android only lets
/// the user switch on. This page shows its status and opens the right screen.
class QuickPanelSettingsPage extends StatefulWidget {
  static const String routeName = "quick_panel_settings_panel";

  const QuickPanelSettingsPage({Key? key}) : super(key: key);

  @override
  State<QuickPanelSettingsPage> createState() => _QuickPanelSettingsPageState();
}

class _QuickPanelSettingsPageState extends State<QuickPanelSettingsPage> with WidgetsBindingObserver {
  final _channel = FLauncherChannel();
  bool? _enabled;
  bool _openFailed = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  // Coming back from Android's settings screen.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final enabled = await _channel.isAccessibilityRunning();
    if (mounted) setState(() => _enabled = enabled);
  }

  Future<void> _open() async {
    final opened = await _channel.openAccessibilitySettings();
    if (mounted) setState(() => _openFailed = !opened);
  }

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    final enabled = _enabled ?? false;
    return Column(
      children: [
        const SettingsPageHeader(title: 'Quick panel'),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.all(8),
            children: [
              Row(
                children: [
                  Icon(enabled ? Icons.check_circle : Icons.warning_amber_rounded,
                      color: enabled ? Colors.green : Colors.orange),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _enabled == null
                          ? 'Checking...'
                          : enabled
                              ? 'Accessibility service is on'
                              : 'Accessibility service is off',
                      style: textTheme.titleMedium,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                'Hold Home (or press the YouTube button) to open the quick panel over any app. '
                'This needs the "Arc Brightness" accessibility service.',
                style: textTheme.bodySmall,
              ),
              const SizedBox(height: 16),
              if (!enabled) ...[
                ElevatedButton.icon(
                  autofocus: true,
                  onPressed: _open,
                  icon: const Icon(Icons.settings),
                  label: const Text('Open accessibility settings'),
                ),
                const SizedBox(height: 8),
                Text(
                  _openFailed
                      ? 'This TV has no direct link. Open the system Settings > Accessibility, '
                          'choose Arc Brightness and switch it on.'
                      : 'Choose Arc Brightness in the list and switch it on, then press Back.',
                  style: textTheme.bodySmall,
                ),
              ] else
                TextButton.icon(
                  autofocus: true,
                  onPressed: _refresh,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Check again'),
                ),
            ],
          ),
        ),
      ],
    );
  }
}
