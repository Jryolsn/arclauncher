import 'package:flauncher/providers/vpn_guard_service.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

/// Status bar note: which VPN, where it comes out and with what address. Only shown while a VPN is up,
/// or when an app needs VPN and none is up.
class VpnIndicator extends StatelessWidget {
  const VpnIndicator({super.key});

  static const _shadows = [Shadow(color: Colors.black54, offset: Offset(0, 2), blurRadius: 8)];

  @override
  Widget build(BuildContext context) {
    final vpn = context.watch<VpnGuardService>();
    final status = vpn.status;
    if (!status.up && vpn.guardedPackages.isEmpty) return const SizedBox.shrink();

    if (!status.up) {
      return const Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(Icons.lock_open, size: 18, color: Colors.orangeAccent, shadows: _shadows),
        SizedBox(width: 6),
        Text('VPN off', style: TextStyle(fontSize: 15, color: Colors.orangeAccent, shadows: _shadows)),
      ]);
    }

    // Kept short: details (VPN app, IP, provider) are in the quick panel and on the VPN settings page.
    final place = [
      if (status.flag != null) status.flag!,
      if (status.city != null) status.city! else if (status.country != null) status.country!,
    ].join(' ');
    final parts = [place.isNotEmpty ? place : 'VPN'];
    return Row(mainAxisSize: MainAxisSize.min, children: [
      const Icon(Icons.lock, size: 18, color: Colors.lightGreenAccent, shadows: _shadows),
      const SizedBox(width: 6),
      Text(parts.join(' · '),
          style: const TextStyle(fontSize: 15, color: Colors.white, shadows: _shadows)),
    ]);
  }
}
