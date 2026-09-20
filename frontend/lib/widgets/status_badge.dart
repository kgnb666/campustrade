import 'package:flutter/material.dart';

/// 基础设施与组件运行状态徽章组件
class StatusBadge extends StatelessWidget {
  final String label;
  final String status;
  final bool isReady;

  const StatusBadge({
    super.key,
    required this.label,
    required this.status,
    this.isReady = true,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: isReady ? Colors.green.withAlpha(25) : Colors.amber.withAlpha(25),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: isReady ? Colors.green : Colors.amber,
          width: 1,
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            isReady ? Icons.check_circle_outline : Icons.pending_outlined,
            size: 16,
            color: isReady ? Colors.green[700] : Colors.amber[800],
          ),
          const SizedBox(width: 6),
          Text(
            label,
            style: const TextStyle(
              fontWeight: FontWeight.bold,
              fontSize: 13,
            ),
          ),
          const SizedBox(width: 4),
          Text(
            status,
            style: TextStyle(
              color: isReady ? Colors.green[800] : Colors.amber[900],
              fontSize: 12,
            ),
          ),
        ],
      ),
    );
  }
}
