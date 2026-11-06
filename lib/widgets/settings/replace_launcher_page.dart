import 'package:flauncher/flauncher_channel.dart';
import 'package:flauncher/widgets/rounded_switch_list_tile.dart';
import 'package:flauncher/widgets/settings/settings_theme.dart';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Lets Arc take over Home without making it the default launcher, so the stock home screen can stay
/// installed. The accessibility service does the work (ArcAccessibilityService, key "replace_launcher").
class ReplaceLauncherPage extends StatefulWidget {
  static const String routeName = "replace_launcher_panel";

  const ReplaceLauncherPage({Key? key}) : super(key: key);

  @override
  State<ReplaceLauncherPage> createState() => _ReplaceLauncherPageState();
}

class _ReplaceLauncherPageState extends State<ReplaceLauncherPage> with WidgetsBindingObserver {
  static const _key = "replace_launcher";
  final _channel = FLauncherChannel();
  bool _replace = false;
  bool? _serviceOn;
  bool _isDefault = false;
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

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.reload();
    final serviceOn = await _channel.isAccessibilityRunning();
    final isDefault = await _channel.isDefaultLauncher();
    if (!mounted) return;
    setState(() {
      _replace = prefs.getBool(_key) ?? false;
      _serviceOn = serviceOn;
      _isDefault = isDefault;
    });
  }

  Future<void> _setReplace(bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_key, value);
    if (mounted) setState(() => _replace = value);
  }

  Future<void> _openAccessibility() async {
    final opened = await _channel.openAccessibilitySettings();
    if (mounted) setState(() => _openFailed = !opened);
  }

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    final serviceOn = _serviceOn ?? true;
    return Column(
      children: [
        const SettingsPageHeader(title: 'Replace launcher'),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.all(8),
            children: [
              RoundedSwitchListTile(
                autofocus: true,
                title: Text('Replace current launcher', style: textTheme.bodyMedium),
                secondary: Icon(_replace ? Icons.home : Icons.home_outlined),
                value: _replace,
                onChanged: _setReplace,
              ),
              const SizedBox(height: 8),
              Text(
                'Home opens Arc, and Arc is shown whenever the stock home screen would appear '
                '(after start-up or when you leave an app). The stock launcher stays installed, '
                'so nothing has to be disabled.',
                style: textTheme.bodySmall,
              ),
              if (_isDefault) ...[
                const SizedBox(height: 12),
                Text(
                  'Arc is already the default home app, so this setting has nothing to do right now.',
                  style: textTheme.bodySmall,
                ),
              ],
              if (_replace && !serviceOn) ...[
                const SizedBox(height: 16),
                Row(
                  children: [
                    const Icon(Icons.warning_amber_rounded, color: Colors.orange),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text('Accessibility service is off', style: textTheme.titleMedium),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                ElevatedButton.icon(
                  onPressed: _openAccessibility,
                  icon: const Icon(Icons.settings),
                  label: const Text('Open accessibility settings'),
                ),
                const SizedBox(height: 8),
                Text(
                  _openFailed
                      ? 'This TV has no direct link. Open the system Settings > Accessibility, '
                          'choose Arc Brightness and switch it on.'
                      : 'This needs the "Arc Brightness" accessibility service. Switch it on, then press Back.',
                  style: textTheme.bodySmall,
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}
