import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:image_picker/image_picker.dart';
import '../../controllers/auth_controller.dart';
import '../../models/category_model.dart';
import '../../routes/app_routes.dart';
import '../../services/goods_service.dart';
import '../../widgets/ai_goods_assistant_sheet.dart';
import '../../widgets/goods_thumbnail.dart';
import '../../models/status_enums.dart';
import '../../utils/api_error.dart';

/// 发布闲置商品页面
class CreateGoodsPage extends StatefulWidget {
  const CreateGoodsPage({super.key});

  @override
  State<CreateGoodsPage> createState() => _CreateGoodsPageState();
}

class _CreateGoodsPageState extends State<CreateGoodsPage> {
  final _formKey = GlobalKey<FormState>();
  final GoodsService _goodsService = GoodsService();
  final AuthController _authController = Get.find<AuthController>();

  final TextEditingController _titleCtrl = TextEditingController();
  final TextEditingController _priceCtrl = TextEditingController();
  final TextEditingController _originPriceCtrl = TextEditingController();
  final TextEditingController _locationCtrl = TextEditingController();
  final TextEditingController _descCtrl = TextEditingController();

  final List<String> _uploadedImages = [];
  final List<CategoryModel> _categories = [];
  CategoryModel? _selectedCategory;

  String _conditionLevel = '95新';
  final List<String> _conditionOptions = ['全新', '95新', '9成新', '8成新', '7成新及以下'];

  bool _isUploadingImage = false;
  bool _isSubmitting = false;

  /// 编辑模式：由「我的商品」或商品详情页传入商品 ID 时进入；为空表示新建
  String? _editingGoodsId;
  bool get _isEditing => _editingGoodsId != null;

  /// 详情里带回来的分类 ID：分类列表是异步加载的，先存下来等列表到位再匹配
  String? _pendingCategoryId;
  bool _isLoadingDetail = false;

  @override
  void initState() {
    super.initState();
    _checkStudentVerification();
    _loadCategories();

    // 带商品 ID 进来即为编辑模式（ID 全程按字符串传递，避免 Web 端精度截断）
    final args = Get.arguments;
    if (args != null && args.toString().isNotEmpty) {
      _editingGoodsId = args.toString();
      _loadGoodsForEdit(_editingGoodsId!);
    }
  }

  void _checkStudentVerification() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final user = _authController.currentUser.value;
      if (user == null || !VerifyStatus.fromCode(user.verifyStatus).isVerified) {
        showDialog(
          context: context,
          barrierDismissible: false,
          builder: (ctx) => AlertDialog(
            title: const Text('需要校园认证'),
            content: const Text('发布闲置二手商品必须具备在校生校园身份，请先完成高校学生认证。'),
            actions: [
              TextButton(
                onPressed: () {
                  Navigator.pop(ctx);
                  Get.back();
                },
                child: const Text('取消'),
              ),
              ElevatedButton(
                onPressed: () {
                  Navigator.pop(ctx);
                  Get.offNamed(AppRoutes.studentVerify);
                },
                child: const Text('前往认证'),
              ),
            ],
          ),
        );
      }
    });
  }

  /// 分类加载失败原因（空字符串表示正常）：页面内联展示 + 重试入口。
  /// 不用 snackbar：这个失败发生在页面打开瞬间，弹窗式提示会在页面还没稳定时出现，
  /// 用户也容易错过；内联提示更符合"可见降级"。
  String _categoryError = '';

  Future<void> _loadCategories() async {
    try {
      final list = await _goodsService.getCategories();
      if (!mounted) return;
      setState(() {
        _categoryError = '';
        _categories.clear();
        // 展平为所有叶子分类供选择
        for (var parent in list) {
          if (parent.children.isNotEmpty) {
            _categories.addAll(parent.children);
          } else {
            _categories.add(parent);
          }
        }
        if (_categories.isNotEmpty) {
          // 编辑模式下优先匹配商品原分类，匹配不到再退回第一个
          final match = _pendingCategoryId == null
              ? const <CategoryModel>[]
              : _categories.where((c) => c.id == _pendingCategoryId).toList();
          _selectedCategory = match.isNotEmpty ? match.first : _categories.first;
          if (match.isNotEmpty) _pendingCategoryId = null;
        }
      });
    } catch (e, stack) {
      debugPrint('[CreateGoodsPage] _loadCategories error: $e\n$stack');
      if (!mounted) return;
      setState(() {
        _categoryError = describeApiError(e, fallback: '分类加载失败，请检查网络后重试');
      });
    }
  }

  /// 编辑模式：拉取商品详情并回填表单
  Future<void> _loadGoodsForEdit(String goodsId) async {
    setState(() => _isLoadingDetail = true);
    try {
      final detail = await _goodsService.getGoodsDetail(goodsId);
      // await 之后必须重新确认 State 仍然挂载，否则在已销毁的页面上 setState/导航
      if (!mounted) return;
      if (detail == null) {
        Get.snackbar('提示', '商品不存在或已下架，无法编辑');
        Get.back();
        return;
      }
      setState(() {
        _titleCtrl.text = detail.title;
        _descCtrl.text = detail.description;
        _priceCtrl.text = _formatNumber(detail.price);
        _originPriceCtrl.text =
            detail.originalPrice == null ? '' : _formatNumber(detail.originalPrice!);
        _locationCtrl.text = detail.location ?? '';
        if (_conditionOptions.contains(detail.conditionLevel)) {
          _conditionLevel = detail.conditionLevel;
        }
        _uploadedImages
          ..clear()
          ..addAll(detail.images);

        _pendingCategoryId = detail.categoryId;
        final match = _categories.where((c) => c.id == detail.categoryId).toList();
        if (match.isNotEmpty) {
          _selectedCategory = match.first;
          _pendingCategoryId = null;
        }
      });
    } catch (e, stack) {
      // 详情拉取失败（断网/超时）不再冒充"商品不存在"：给出原因并留在页面
      debugPrint('[CreateGoodsPage] _loadGoodsForEdit id=$goodsId error: $e\n$stack');
      if (!mounted) return;
      Get.snackbar('加载失败',
          describeApiError(e, fallback: '商品详情加载失败，请检查网络后重试'),
          snackPosition: SnackPosition.BOTTOM);
    } finally {
      if (mounted) setState(() => _isLoadingDetail = false);
    }
  }

  /// 去掉多余的小数尾巴：60.00 -> 60，60.50 -> 60.50
  String _formatNumber(double value) {
    final text = value.toStringAsFixed(2);
    return text.endsWith('.00') ? text.substring(0, text.length - 3) : text;
  }

  Future<void> _pickAndUploadImage() async {
    if (_uploadedImages.length >= 9) {
      Get.snackbar('提示', '最多可上传 9 张商品图片');
      return;
    }

    final picker = ImagePicker();
    final XFile? pickedFile = await picker.pickImage(
      source: ImageSource.gallery,
      imageQuality: 85,
    );

    if (pickedFile == null || !mounted) return;

    setState(() => _isUploadingImage = true);

    try {
      final bytes = await pickedFile.readAsBytes();
      final url = await _goodsService.uploadImageBytes(bytes, pickedFile.name);
      if (!mounted) return;
      setState(() {
        _uploadedImages.add(url);
      });
      Get.snackbar('成功', '图片上传成功',
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.green.shade600,
          colorText: Colors.white);
    } catch (e) {
      Get.snackbar('上传失败', describeApiError(e, fallback: '图片上传失败'),
          snackPosition: SnackPosition.BOTTOM);
    } finally {
      if (mounted) setState(() => _isUploadingImage = false);
    }
  }

  Future<void> _submitGoods() async {
    if (!_formKey.currentState!.validate()) return;
    if (_selectedCategory == null) {
      Get.snackbar('提示', '请选择商品所属分类');
      return;
    }

    setState(() => _isSubmitting = true);

    try {
      final data = {
        'title': _titleCtrl.text.trim(),
        'description': _descCtrl.text.trim(),
        'categoryId': _selectedCategory!.id,
        'price': double.parse(_priceCtrl.text.trim()),
        'originalPrice': _originPriceCtrl.text.isNotEmpty
            ? double.tryParse(_originPriceCtrl.text.trim())
            : null,
        'conditionLevel': _conditionLevel,
        'location': _locationCtrl.text.trim(),
        'images': _uploadedImages,
      };

      if (_isEditing) {
        await _goodsService.updateGoods(_editingGoodsId!, data);
        if (!mounted) return;
        Get.snackbar(
          '修改成功',
          '商品信息已更新！',
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.green.shade600,
          colorText: Colors.white,
        );
      } else {
        await _goodsService.createGoods(data);
        if (!mounted) return;
        Get.snackbar(
          '发布成功',
          '商品已进入出售状态！',
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.green.shade600,
          colorText: Colors.white,
        );
      }
      Get.back(result: true);
    } catch (e) {
      Get.snackbar(
        _isEditing ? '修改失败' : '发布失败',
        describeApiError(e,
            fallback: _isEditing ? '商品修改失败' : '商品发布失败'),
        snackPosition: SnackPosition.BOTTOM,
      );
    } finally {
      if (mounted) setState(() => _isSubmitting = false);
    }
  }

  @override
  void dispose() {
    _titleCtrl.dispose();
    _priceCtrl.dispose();
    _originPriceCtrl.dispose();
    _locationCtrl.dispose();
    _descCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_isEditing ? '编辑闲置商品' : '发布闲置商品'),
      ),
      body: Form(
        key: _formKey,
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 0. AI 提示横幅
              Container(
                margin: const EdgeInsets.only(bottom: 16),
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                decoration: BoxDecoration(
                  color: Colors.indigo.shade50,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.indigo.shade100),
                ),
                child: Row(
                  children: [
                    Icon(Icons.auto_awesome, color: Colors.indigo.shade700, size: 20),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '已接入 DeepSeek AI 助手：支持帮写文案、智能分类与合理估价',
                        style: TextStyle(
                          fontSize: 12,
                          color: Colors.indigo.shade800,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ),
                  ],
                ),
              ),

              // 1. 图片选择区域
              const Text(
                '商品图片 (最多9张)',
                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
              ),
              const SizedBox(height: 10),
              _buildImagePickerGrid(),
              const SizedBox(height: 20),

              // 2. 标题输入
              TextFormField(
                controller: _titleCtrl,
                decoration: const InputDecoration(
                  labelText: '商品标题 *',
                  hintText: '例: iPad Air 5 64G 深空灰 考研急出',
                  border: OutlineInputBorder(),
                  prefixIcon: Icon(Icons.title),
                ),
                validator: (val) {
                  if (val == null || val.trim().isEmpty) return '请输入商品标题';
                  if (val.trim().length > 100) return '标题长度不能超过100字符';
                  return null;
                },
              ),
              const SizedBox(height: 16),

              // 3. 分类选择与 AI 分类推荐
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text('商品分类 *', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
                  TextButton.icon(
                    onPressed: () {
                      AiGoodsAssistantSheet.showCategoryAssistant(
                        context: context,
                        title: _titleCtrl.text.trim(),
                        desc: _descCtrl.text.trim(),
                        availableCategories: _categories,
                        onAdopt: (cat) {
                          setState(() {
                            _selectedCategory = cat;
                          });
                        },
                      );
                    },
                    icon: const Icon(Icons.auto_awesome, size: 16, color: Colors.indigo),
                    label: const Text(
                      'AI 推荐分类',
                      style: TextStyle(color: Colors.indigo, fontSize: 13),
                    ),
                  ),
                ],
              ),
              DropdownButtonFormField<CategoryModel>(
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  prefixIcon: Icon(Icons.category_outlined),
                ),
                initialValue: _selectedCategory,
                items: _categories.map((cat) {
                  return DropdownMenuItem(
                    value: cat,
                    child: Text(cat.name),
                  );
                }).toList(),
                onChanged: (cat) => setState(() => _selectedCategory = cat),
              ),
              // 分类加载失败的可见降级：内联提示 + 重新加载
              if (_categoryError.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Row(
                    children: [
                      const Icon(Icons.error_outline, size: 16, color: Colors.orange),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          _categoryError,
                          style: TextStyle(fontSize: 12, color: Colors.orange[900]),
                        ),
                      ),
                      TextButton(
                        onPressed: _loadCategories,
                        child: const Text('重新加载', style: TextStyle(fontSize: 12)),
                      ),
                    ],
                  ),
                ),
              const SizedBox(height: 16),

              // 4. 售价与原价与 AI 估价
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text('价格设定 *', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
                  TextButton.icon(
                    onPressed: () {
                      AiGoodsAssistantSheet.showPriceAssistant(
                        context: context,
                        title: _titleCtrl.text.trim(),
                        desc: _descCtrl.text.trim(),
                        originalPrice: _originPriceCtrl.text.isNotEmpty
                            ? double.tryParse(_originPriceCtrl.text.trim())
                            : null,
                        conditionLevel: _conditionLevel,
                        onAdopt: (price) {
                          setState(() {
                            _priceCtrl.text = price.toStringAsFixed(2);
                          });
                        },
                      );
                    },
                    icon: const Icon(Icons.auto_awesome, size: 16, color: Colors.indigo),
                    label: const Text(
                      'AI 智能估价',
                      style: TextStyle(color: Colors.indigo, fontSize: 13),
                    ),
                  ),
                ],
              ),
              Row(
                children: [
                  Expanded(
                    child: TextFormField(
                      controller: _priceCtrl,
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      decoration: const InputDecoration(
                        labelText: '出售价格 (¥) *',
                        border: OutlineInputBorder(),
                        prefixIcon: Icon(Icons.attach_money),
                      ),
                      validator: (val) {
                        if (val == null || val.trim().isEmpty) return '请输入价格';
                        final p = double.tryParse(val.trim());
                        if (p == null || p <= 0) return '价格须大于0';
                        return null;
                      },
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextFormField(
                      controller: _originPriceCtrl,
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      decoration: const InputDecoration(
                        labelText: '入手原价 (¥ 选填)',
                        border: OutlineInputBorder(),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),

              // 5. 成色单选
              const Text(
                '成色级别',
                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                children: _conditionOptions.map((cond) {
                  final isSelected = _conditionLevel == cond;
                  return ChoiceChip(
                    label: Text(cond),
                    selected: isSelected,
                    onSelected: (selected) {
                      if (selected) setState(() => _conditionLevel = cond);
                    },
                  );
                }).toList(),
              ),
              const SizedBox(height: 16),

              // 6. 交易地点
              TextFormField(
                controller: _locationCtrl,
                decoration: const InputDecoration(
                  labelText: '校园面交地点',
                  hintText: '例: 紫荆公寓1号楼楼下 / 逸夫图书馆',
                  border: OutlineInputBorder(),
                  prefixIcon: Icon(Icons.place_outlined),
                ),
              ),
              const SizedBox(height: 16),

              // 7. 商品详情描述与 AI 帮写
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text('详细描述', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
                  TextButton.icon(
                    onPressed: () {
                      AiGoodsAssistantSheet.showDescriptionAssistant(
                        context: context,
                        title: _titleCtrl.text.trim(),
                        roughDesc: _descCtrl.text.trim(),
                        conditionLevel: _conditionLevel,
                        onAdopt: (newTitle, newDesc) {
                          setState(() {
                            if (_titleCtrl.text.trim().isEmpty) {
                              _titleCtrl.text = newTitle;
                            }
                            _descCtrl.text = newDesc;
                          });
                        },
                      );
                    },
                    icon: const Icon(Icons.auto_awesome, size: 16, color: Colors.indigo),
                    label: const Text(
                      'AI 帮写描述',
                      style: TextStyle(color: Colors.indigo, fontSize: 13),
                    ),
                  ),
                ],
              ),
              TextFormField(
                controller: _descCtrl,
                maxLines: 4,
                decoration: const InputDecoration(
                  labelText: '规格、购买时间、转让原因等',
                  border: OutlineInputBorder(),
                  alignLabelWithHint: true,
                ),
              ),
              const SizedBox(height: 28),

              // 8. 提交按钮
              SizedBox(
                width: double.infinity,
                height: 48,
                child: ElevatedButton.icon(
                  onPressed: (_isSubmitting || _isLoadingDetail) ? null : _submitGoods,
                  icon: (_isSubmitting || _isLoadingDetail)
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                        )
                      : Icon(_isEditing ? Icons.save : Icons.publish),
                  label: Text(_isLoadingDetail
                      ? '正在加载商品信息...'
                      : (_isSubmitting
                          ? (_isEditing ? '正在保存...' : '正在发布...')
                          : (_isEditing ? '保存修改' : '确认发布商品'))),
                  style: ElevatedButton.styleFrom(
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 图片选择网格组件
  Widget _buildImagePickerGrid() {
    return Wrap(
      spacing: 10,
      runSpacing: 10,
      children: [
        // 已上传图片缩略图
        ..._uploadedImages.asMap().entries.map((entry) {
          final index = entry.key;
          final url = entry.value;
          return Stack(
            clipBehavior: Clip.none,
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: GoodsThumbnail(
                  imageUrl: url,
                  width: 80,
                  height: 80,
                  borderRadius: 0,
                ),
              ),
              Positioned(
                top: -6,
                right: -6,
                child: GestureDetector(
                  onTap: () => setState(() => _uploadedImages.removeAt(index)),
                  child: const CircleAvatar(
                    radius: 11,
                    backgroundColor: Colors.red,
                    child: Icon(Icons.close, size: 13, color: Colors.white),
                  ),
                ),
              ),
            ],
          );
        }),

        // 添加图片按钮
        if (_uploadedImages.length < 9)
          InkWell(
            onTap: _isUploadingImage ? null : _pickAndUploadImage,
            borderRadius: BorderRadius.circular(8),
            child: Container(
              width: 80,
              height: 80,
              decoration: BoxDecoration(
                color: Colors.grey.shade100,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.grey.shade300, style: BorderStyle.solid),
              ),
              child: Center(
                child: _isUploadingImage
                    ? const SizedBox(
                        width: 24,
                        height: 24,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(Icons.add_photo_alternate_outlined, color: Colors.grey.shade600),
                          const SizedBox(height: 4),
                          Text('添加图片', style: TextStyle(fontSize: 10, color: Colors.grey.shade600)),
                        ],
                      ),
              ),
            ),
          ),
      ],
    );
  }
}
