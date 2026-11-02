import 'package:flauncher/providers/app_lock_service.dart';
import 'package:flauncher/providers/apps_service.dart';
import 'package:flauncher/providers/vpn_guard_service.dart';
import 'package:flauncher/widgets/rounded_switch_list_tile.dart';
import 'package:flauncher/widgets/settings/focusable_settings_tile.dart';
import 'package:flauncher/widgets/settings/settings_theme.dart';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

/// Apps that may only run with a VPN up, what happens when it is off, and the VPN app to open.
class VpnGuardPage extends StatefulWidget {
  static const String routeName = "vpn_guard_panel";

  const VpnGuardPage({super.key});

  @override
  State<VpnGuardPage> createState() => _VpnGuardPageState();
}

class _VpnGuardPageState extends State<VpnGuardPage> {
  @override
  void initState() {
    super.initState();
    context.read<VpnGuardService>().refresh();
    context.read<AppLockService>().refresh();
  }

  @override
  Widget build(BuildContext context) {
    final vpn = context.watch<VpnGuardService>();
    final guardActive = context.watch<AppLockService>().isActive;
    final apps = [...context.watch<AppsService>().applications]
      ..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    final status = vpn.status;
    final body = Theme.of(context).textTheme.bodyMedium;
    final vpnAppName = status.vpnApp ?? 'Not chosen';

    String statusText;
    if (!status.up) {
      statusText = 'VPN is off';
    } else {
      statusText = [
        status.vpnApp ?? 'VPN',
        [status.flag, status.city, status.country].whereType<String>().join(' '),
        if (status.ip != null) status.ip!,
        if (status.org != null) status.org!,
      ].where((s) => s.isNotEmpty).join(' · ');
    }

    return Column(
      children: [
        const SettingsPageHeader(title: 'VPN guard'),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
                child: Row(children: [
                  Icon(status.up ? Icons.lock : Icons.lock_open,
                      size: 18, color: status.up ? Colors.lightGreenAccent : Colors.orangeAccent),
                  const SizedBox(width: 8),
                  Expanded(child: Text(statusText, style: body)),
                ]),
              ),
              if (!guardActive)
                const Padding(
                  padding: EdgeInsets.fromLTRB(16, 0, 16, 8),
                  child: Text(
                    "The guard is off: turn on \"Arc Brightness\" in Android Settings > Accessibility.",
                    style: TextStyle(color: Colors.orangeAccent),
                  ),
                ),
              FocusableSettingsTile(
                autofocus: true,
                leading: const Icon(Icons.block),
                title: Text(
                    'When VPN is off: ${vpn.mode == 'warn' ? 'Warn (can open anyway)' : 'Block'}',
                    style: body),
                onPressed: () => _pickMode(context, vpn),
              ),
              FocusableSettingsTile(
                leading: const Icon(Icons.vpn_key_outlined),
                title: Text('VPN app: $vpnAppName', style: body),
                onPressed: () => _pickVpnApp(context, vpn),
              ),
              FocusableSettingsTile(
                showChevron: true,
                leading: const Icon(Icons.settings_ethernet),
                title: Text('Android VPN settings (always-on, block without VPN)', style: body),
                onPressed: () async {
                  final opened = await vpn.openSystemVpnSettings();
                  if (!opened && context.mounted) {
                    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
                        const SnackBar(content: Text('This TV has no VPN settings page')));
                  }
                },
              ),
              const Padding(
                padding: EdgeInsets.fromLTRB(16, 8, 16, 4),
                child: Text(
                  'If your VPN only tunnels chosen apps, include Arc in that list too. '
                  'Otherwise Arc can\'t see the VPN and treats it as off.',
                  style: TextStyle(color: Colors.white60),
                ),
              ),
              const SettingsSectionDivider(),
              const Padding(
                padding: EdgeInsets.fromLTRB(16, 0, 16, 4),
                child: Text('Apps that need VPN', style: TextStyle(color: Colors.white70)),
              ),
              ...apps.map((app) => RoundedSwitchListTile(
                    value: vpn.isGuarded(app.packageName),
                    onChanged: (_) => vpn.toggleGuarded(app.packageName),
                    title: Text(app.name, style: body),
                    secondary: _AppIcon(app.packageName),
                  )),
            ],
          ),
        ),
      ],
    );
  }
}

Future<void> _pickMode(BuildContext context, VpnGuardService vpn) async {
  const modes = {
    'block': 'Block: no way in without VPN',
    'warn': 'Warn: can open anyway',
  };
  final chosen = await showDialog<String>(
    context: context,
    builder: (dialogContext) => SimpleDialog(
      title: const Text('When VPN is off'),
      children: modes.entries
          .map((mode) => FocusableSettingsTile(
                autofocus: mode.key == vpn.mode,
                frameLeading: false,
                leading: Icon(mode.key == vpn.mode ? Icons.radio_button_checked : Icons.radio_button_unchecked),
                title: Text(mode.value, style: Theme.of(dialogContext).textTheme.bodyMedium),
                onPressed: () => Navigator.of(dialogContext).pop(mode.key),
              ))
          .toList(),
    ),
  );
  if (chosen != null) await vpn.setMode(chosen);
}

Future<void> _pickVpnApp(BuildContext context, VpnGuardService vpn) async {
  final current = vpn.status.vpnAppPackage;
  final chosen = await showDialog<String>(
    context: context,
    builder: (dialogContext) => SimpleDialog(
      title: const Text('VPN app'),
      children: vpn.vpnApps.isEmpty
          ? [
              const Padding(
                padding: EdgeInsets.all(16),
                child: Text('No VPN app installed'),
              )
            ]
          : vpn.vpnApps
              .map((app) => FocusableSettingsTile(
                    autofocus: app.packageName == current,
                    frameLeading: false,
                    leading: Icon(
                        app.packageName == current ? Icons.radio_button_checked : Icons.radio_button_unchecked),
                    title: Text(app.name, style: Theme.of(dialogContext).textTheme.bodyMedium),
                    onPressed: () => Navigator.of(dialogContext).pop(app.packageName),
                  ))
              .toList(),
    ),
  );
  if (chosen != null) await vpn.setVpnApp(chosen);
}

class _AppIcon extends StatelessWidget {
  final String packageName;
  const _AppIcon(this.packageName);

  @override
  Widget build(BuildContext context) => FutureBuilder<Uint8List>(
        future: context.read<AppsService>().getAppIcon(packageName),
        builder: (context, snapshot) => snapshot.hasData
            ? Image.memory(snapshot.data!, width: 32, height: 32)
            : const SizedBox(width: 32, height: 32),
      );
}
