import 'package:flutter/material.dart';

/// 商品缩略图（统一图片解码尺寸与占位表现）
///
/// 为什么需要它：
/// 1. **按显示尺寸解码**：`Image.network` 默认按原图分辨率解码。商品图常见 1000px+，
///    而列表里的缩略图只有 76~90 逻辑像素；不设 `cacheWidth/cacheHeight` 时，
///    每张图都会以原图尺寸（例如 1200×1200×4B ≈ 5.5MB）进入图片缓存，
///    列表滚动几屏就会把内存吃满并触发频繁 GC/丢帧。
///    这里按「显示逻辑尺寸 × devicePixelRatio」推导解码尺寸，既不糊也不会解码过大。
/// 2. **统一占位**：加载中与加载失败都给出同尺寸的占位块，避免图片加载过程中的布局跳动；
///    失败时展示与占位块一致的破图图标，不再出现"一半灰块一半图标"的割裂观感。
///
/// 使用方式与 `Image.network` 基本一致，但要求调用方给出确定的尺寸
/// （显式 [width]/[height]，或外层已有紧约束，例如 `SizedBox`/`Expanded` + `StackFit.expand`）。
class GoodsThumbnail extends StatelessWidget {
  const GoodsThumbnail({
    super.key,
    required this.imageUrl,
    this.width,
    this.height,
    this.fit = BoxFit.cover,
    this.borderRadius = 8,
    this.placeholderIcon = Icons.image_outlined,
  });

  /// 图片地址；为空时直接渲染占位块（不发起请求）。
  final String? imageUrl;

  /// 显示宽度（逻辑像素）。为 null 时取父级约束宽度。
  final double? width;

  /// 显示高度（逻辑像素）。为 null 时取父级约束高度。
  final double? height;

  final BoxFit fit;

  /// 圆角半径；传 0 表示不裁剪。
  final double borderRadius;

  /// 占位/失败时展示的图标（例如订单列表用 `receipt_long` 语义更贴切）。
  final IconData placeholderIcon;

  /// 超过该逻辑尺寸的图片在加载中额外展示进度指示器（小缩略图只显示纯占位块，避免噪点）。
  static const double _spinnerThreshold = 120;

  @override
  Widget build(BuildContext context) {
    final String? url = imageUrl;
    if (url == null || url.isEmpty) {
      return _clip(_placeholder(context));
    }

    return _clip(
      LayoutBuilder(
        builder: (context, constraints) {
          final double? logicalWidth = width ??
              (constraints.hasBoundedWidth ? constraints.maxWidth : null);
          final double? logicalHeight = height ??
              (constraints.hasBoundedHeight ? constraints.maxHeight : null);
          final double devicePixelRatio = MediaQuery.devicePixelRatioOf(context);

          return Image.network(
            url,
            width: width,
            height: height,
            fit: fit,
            // 按显示尺寸解码：避免原图分辨率进入图片缓存（列表内存占用的主要来源）
            cacheWidth: _decodeSize(logicalWidth, devicePixelRatio),
            cacheHeight: _decodeSize(logicalHeight, devicePixelRatio),
            loadingBuilder: (context, child, loadingProgress) {
              if (loadingProgress == null) return child;
              final bool showSpinner = (logicalWidth ?? 0) >= _spinnerThreshold ||
                  (logicalHeight ?? 0) >= _spinnerThreshold;
              return _placeholder(context, showSpinner: showSpinner);
            },
            errorBuilder: (context, error, stackTrace) =>
                _placeholder(context, broken: true),
          );
        },
      ),
    );
  }

  Widget _clip(Widget child) {
    if (borderRadius <= 0) return child;
    return ClipRRect(borderRadius: BorderRadius.circular(borderRadius), child: child);
  }

  /// 解码尺寸：显示逻辑像素 × DPR，向上取整；无法确定尺寸时返回 null（交由框架自适应）。
  static int? _decodeSize(double? logicalSize, double devicePixelRatio) {
    if (logicalSize == null || logicalSize <= 0 || logicalSize.isInfinite) return null;
    final int pixels = (logicalSize * devicePixelRatio).ceil();
    return pixels > 0 ? pixels : null;
  }

  Widget _placeholder(BuildContext context, {bool broken = false, bool showSpinner = false}) {
    final Color background = Colors.grey.shade100;
    final Color foreground = Colors.grey.shade300;
    return Container(
      width: width,
      height: height,
      color: background,
      alignment: Alignment.center,
      child: showSpinner
          ? SizedBox(
              width: 18,
              height: 18,
              child: CircularProgressIndicator(strokeWidth: 2, color: foreground),
            )
          : Icon(broken ? Icons.broken_image_outlined : placeholderIcon, size: 32, color: foreground),
    );
  }
}
