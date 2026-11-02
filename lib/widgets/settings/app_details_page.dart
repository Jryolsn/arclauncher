
import 'package:flauncher/providers/app_lock_service.dart';
import 'package:flauncher/providers/vpn_guard_service.dart';
import 'dart:typed_data';

import 'package:flauncher/models/app.dart';
import 'package:flauncher/models/category.dart';
import 'package:flauncher/providers/apps_service.dart';
import 'package:flauncher/widgets/add_to_category_dialog.dart';
import 'package:flauncher/widgets/settings/focusable_settings_tile.dart';
import 'package:flauncher/widgets/settings/settings_theme.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:flauncher/l10n/app_localizations.dart';

class AppDetailsPage extends StatelessWidget {
  static const String routeName = "app_details_page";

  final App application;

  const AppDetailsPage({Key? key, required this.application}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    AppLocalizations localizations = AppLocalizations.of(context)!;
    AppsService appsService = context.watch<AppsService>();
    AppLockService appLock = context.watch<AppLockService>();
    VpnGuardService vpnGuard = context.watch<VpnGuardService>();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            FutureBuilder<Uint8List>(
              future: appsService.getAppIcon(application.packageName),
              builder: (context, snapshot) {
                if (snapshot.hasData) {
                  return Image.memory(snapshot.data!, width: 50, height: 50);
                }
                return const Icon(Icons.android, size: 50);
              },
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    application.name,
                    style: Theme.of(context).textTheme.titleLarge,
                    overflow: TextOverflow.ellipsis,
                    maxLines: 1,
                  ),
                  Text(
                    "v${application.version}",
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),
        const SettingsSectionDivider(),
        Expanded(
          child: ListView(
            children: [
              _buildListTile(
                context,
                icon: Icons.open_in_new,
                title: localizations.open,
                onTap: () async {
                  await appsService.launchApp(application);
                  Navigator.of(context).pop(); // Close settings after launch? Or stay? Dialog behavior was close.
                },
              ),
              _buildListTile(
                context,
                icon: appsService.isAppInFavorites(application) ? Icons.star : Icons.star_border,
                title: appsService.isAppInFavorites(application) ? localizations.removeFromFavorites : localizations.addToFavorites,
                onTap: () => appsService.toggleFavorite(application),
              ),
              _buildListTile(
                context,
                icon: application.hidden ? Icons.visibility : Icons.visibility_off_outlined,
                title: application.hidden ? localizations.show : localizations.hide,
                onTap: () {
                   if (application.hidden) {
                     appsService.showApplication(application);
                   } else {
                     appsService.hideApplication(application);
                   }
                },
              ),
              _buildListTile(
                context,
                icon: appLock.isLocked(application.packageName) ? Icons.lock_open : Icons.lock_outline,
                title: appLock.isLocked(application.packageName) ? 'Unlock' : 'Lock',
                onTap: () => appLock.toggleLock(application.packageName),
              ),
              _buildListTile(
                context,
                icon: vpnGuard.isGuarded(application.packageName) ? Icons.vpn_lock : Icons.vpn_key_outlined,
                title: vpnGuard.isGuarded(application.packageName) ? "Don't require VPN" : 'Require VPN',
                onTap: () => vpnGuard.toggleGuarded(application.packageName),
              ),
              if (!application.hidden)
                _buildListTile(
                  context,
                  icon: Icons.add_box_outlined,
                  title: localizations.addToCategory,
                  onTap: () => showDialog<Category>(
                    context: context,
                    builder: (_) => AddToCategoryDialog(application),
                  ),
                ),
              const SettingsSectionDivider(),
              _buildListTile(
                context,
                icon: Icons.info_outlined,
                title: localizations.appInfo,
                onTap: () => appsService.openAppInfo(application),
              ),
              _buildListTile(
                context,
                icon: Icons.delete_outlined,
                title: localizations.uninstall,
                onTap: () async {
                    await appsService.uninstallApp(application);
                    Navigator.of(context).pop();
                },
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildListTile(BuildContext context,
      {required IconData icon, required String title, required VoidCallback onTap}) {
    return FocusableSettingsTile(
      leading: Icon(icon),
      title: Text(title, style: Theme.of(context).textTheme.bodyMedium),
      onPressed: onTap,
    );
  }
}
