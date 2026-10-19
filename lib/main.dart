
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:image/image.dart' as img;
import 'package:image_picker/image_picker.dart';
import 'package:tflite_flutter/tflite_flutter.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ObjectDetectorApp());
}

class ObjectDetectorApp extends StatelessWidget {
  const ObjectDetectorApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'AI 目标检测',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal),
        useMaterial3: true,
      ),
      home: const DetectorPage(),
    );
  }
}

class Detection {
  final String label;
  final double score;

  // 坐标均为相对于原图宽高的 0~1 比例。
  final double left;
  final double top;
  final double right;
  final double bottom;

  const Detection({
    required this.label,
    required this.score,
    required this.left,
    required this.top,
    required this.right,
    required this.bottom,
  });
}

class DetectorPage extends StatefulWidget {
  const DetectorPage({super.key});

  @override
  State<DetectorPage> createState() => _DetectorPageState();
}

class _DetectorPageState extends State<DetectorPage> {
  static const String _modelPath =
      'assets/models/efficientdet_lite0.tflite';

  static const double _threshold = 0.35;

  // EfficientDet-Lite0 使用 COCO 类别。
  // 标签顺序必须与所下载模型的标签文件一致。
  static const List<String> _labels = [
    'person', 'bicycle', 'car', 'motorcycle', 'airplane',
    'bus', 'train', 'truck', 'boat', 'traffic light',
    'fire hydrant', 'stop sign', 'parking meter', 'bench',
    'bird', 'cat', 'dog', 'horse', 'sheep', 'cow',
    'elephant', 'bear', 'zebra', 'giraffe', 'backpack',
    'umbrella', 'handbag', 'tie', 'suitcase', 'frisbee',
    'skis', 'snowboard', 'sports ball', 'kite',
    'baseball bat', 'baseball glove', 'skateboard',
    'surfboard', 'tennis racket', 'bottle', 'wine glass',
    'cup', 'fork', 'knife', 'spoon', 'bowl', 'banana',
    'apple', 'sandwich', 'orange', 'broccoli', 'carrot',
    'hot dog', 'pizza', 'donut', 'cake', 'chair', 'couch',
    'potted plant', 'bed', 'dining table', 'toilet', 'tv',
    'laptop', 'mouse', 'remote', 'keyboard', 'cell phone',
    'microwave', 'oven', 'toaster', 'sink', 'refrigerator',
    'book', 'clock', 'vase', 'scissors', 'teddy bear',
    'hair drier', 'toothbrush',
  ];

  final ImagePicker _picker = ImagePicker();

  Interpreter? _interpreter;
  Tensor? _inputTensor;

  File? _imageFile;
  List<Detection> _detections = [];

  bool _loadingModel = true;
  bool _processing = false;
  String? _error;
  String _status = '正在初始化模型...';
  int _inferenceMs = 0;

  int _inputWidth = 320;
  int _inputHeight = 320;

  @override
  void initState() {
    super.initState();
    _loadModel();
  }

  Future<void> _loadModel() async {
    try {
      final interpreter = await Interpreter.fromAsset(
        _modelPath,
        options: InterpreterOptions()..threads = 4,
      );

      final input = interpreter.getInputTensor(0);
      final shape = input.shape;

      if (shape.length != 4 || shape[0] != 1 || shape[3] != 3) {
        interpreter.close();
        throw Exception(
          '模型输入形状不符合预期：${shape.toString()}',
        );
      }

      if (input.type != TensorType.uint8 &&
          input.type != TensorType.float32) {
        interpreter.close();
        throw Exception(
          '暂不支持模型输入类型：${input.type}',
        );
      }

      if (!mounted) {
        interpreter.close();
        return;
      }

      setState(() {
        _interpreter = interpreter;
        _inputTensor = input;
        _inputHeight = shape[1];
        _inputWidth = shape[2];
        _loadingModel = false;
        _status = '模型已就绪，请拍照或选择图片';
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loadingModel = false;
        _error = '模型加载失败：$e';
        _status = '初始化失败';
      });
    }
  }

  Future<void> _pickImage(ImageSource source) async {
    if (_processing || _loadingModel) return;

    try {
      final XFile? selected = await _picker.pickImage(
        source: source,
        imageQuality: 95,
        maxWidth: 2000,
        maxHeight: 2000,
      );

      if (selected == null || !mounted) return;

      setState(() {
        _imageFile = File(selected.path);
        _detections = [];
        _processing = true;
        _error = null;
        _status = '正在识别...';
      });

      await _detect(selected.path);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '无法打开图片：$e';
        _processing = false;
        _status = '操作失败';
      });
    }
  }

  Future<void> _detect(String path) async {
    final interpreter = _interpreter;
    final inputTensor = _inputTensor;

    if (interpreter == null || inputTensor == null) {
      if (!mounted) return;
      setState(() {
        _processing = false;
        _error = '模型尚未加载完成';
      });
      return;
    }

    try {
      final bytes = await File(path).readAsBytes();
      final decoded = img.decodeImage(bytes);

      if (decoded == null) {
        throw Exception('图片解码失败，请选择 JPG 或 PNG 图片');
      }

      // 处理手机照片中的 EXIF 方向信息。
      final original = img.bakeOrientation(decoded);

      // EfficientDet-Lite0 常用输入为 320×320×3。
      // 使用与模型输入形状一致的实际尺寸。
      final resized = img.copyResize(
        original,
        width: _inputWidth,
        height: _inputHeight,
        interpolation: img.Interpolation.linear,
      );

      final isUint8 = inputTensor.type == TensorType.uint8;

      final input = List.generate(
        1,
        (_) => List.generate(
          _inputHeight,
          (y) => List.generate(
            _inputWidth,
            (x) {
              final p = resized.getPixel(x, y);
              return [
                isUint8 ? p.r.toInt() : p.r.toDouble() / 255.0,
                isUint8 ? p.g.toInt() : p.g.toDouble() / 255.0,
                isUint8 ? p.b.toInt() : p.b.toDouble() / 255.0,
              ];
            },
          ),
        ),
      );

      final tensors = interpreter.getOutputTensors();
      if (tensors.length < 4) {
        throw Exception(
          '预期模型提供 4 个输出张量，实际为 ${tensors.length} 个',
        );
      }

      // EfficientDet-Lite0 的标准输出顺序：
      // 0: boxes [1, N, 4]
      // 1: classes [1, N]
      // 2: scores [1, N]
      // 3: num_detections [1]
      final outputs = <int, Object>{};

      for (var i = 0; i < tensors.length; i++) {
        outputs[i] = _makeBuffer(
          tensors[i].shape,
          tensors[i].type == TensorType.int32 ||
              tensors[i].type == TensorType.int64,
        );
      }

      final stopwatch = Stopwatch()..start();
      interpreter.runForMultipleInputs([input], outputs);
      stopwatch.stop();

      final boxes = _flatten(outputs[0]!);
      final classes = _flatten(outputs[1]!);
      final scores = _flatten(outputs[2]!);
      final countValues = _flatten(outputs[3]!);

      final count = countValues.isNotEmpty
          ? countValues.first.toInt().clamp(0, scores.length)
          : scores.length;

      final results = <Detection>[];

      for (var i = 0; i < count; i++) {
        if (i >= scores.length || i >= classes.length) break;
        if ((i * 4 + 3) >= boxes.length) break;

        final score = scores[i].toDouble();
        if (!score.isFinite || score < _threshold) continue;

        final classIndex = classes[i].round();

        if (classIndex < 0 || classIndex >= _labels.length) {
          continue;
        }

        // 标准 EfficientDet 输出的坐标顺序为：
        // [ymin, xmin, ymax, xmax]，范围通常为 0~1。
        final top = boxes[i * 4].toDouble();
        final left = boxes[i * 4 + 1].toDouble();
        final bottom = boxes[i * 4 + 2].toDouble();
        final right = boxes[i * 4 + 3].toDouble();

        if (![top, left, bottom, right].every(
          (v) => v.isFinite,
        )) {
          continue;
        }

        final l = left.clamp(0.0, 1.0).toDouble();
        final t = top.clamp(0.0, 1.0).toDouble();
        final r = right.clamp(0.0, 1.0).toDouble();
        final b = bottom.clamp(0.0, 1.0).toDouble();

        if (r <= l || b <= t) continue;

        results.add(
          Detection(
            label: _labels[classIndex],
            score: score,
            left: l,
            top: t,
            right: r,
            bottom: b,
          ),
        );
      }

      results.sort((a, b) => b.score.compareTo(a.score));

      if (!mounted) return;

      setState(() {
        _detections = results;
        _inferenceMs = stopwatch.elapsedMilliseconds;
        _processing = false;
        _status = '识别完成：${results.length} 个目标';
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _processing = false;
        _status = '识别失败';
        _error = '推理过程中发生错误：$e';
      });
    }
  }

  // 根据张量形状递归创建输出缓冲区。
  List<dynamic> _makeBuffer(List<int> shape, bool integer) {
    if (shape.isEmpty) {
      return <dynamic>[];
    }

    dynamic build(int dimension) {
      if (dimension == shape.length - 1) {
        return List<dynamic>.filled(
          shape[dimension],
          integer ? 0 : 0.0,
        );
      }

      return List<dynamic>.generate(
        shape[dimension],
        (_) => build(dimension + 1),
      );
    }

    return build(0) as List<dynamic>;
  }

  List<num> _flatten(Object value) {
    final result = <num>[];

    void visit(dynamic item) {
      if (item is num) {
        result.add(item);
      } else if (item is List) {
        for (final child in item) {
          visit(child);
        }
      }
    }

    visit(value);
    return result;
  }

  @override
  void dispose() {
    _interpreter?.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('AI 目标检测'),
        centerTitle: true,
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: Container(
                width: double.infinity,
                margin: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: Colors.black.withValues(alpha: 0.04),
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: Colors.black12),
                ),
                clipBehavior: Clip.antiAlias,
                child: _buildPreview(),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(
                        _error == null
                            ? Icons.info_outline
                            : Icons.error_outline,
                        color: _error == null
                            ? Colors.teal
                            : Colors.red,
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          _status,
                          style: const TextStyle(
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ],
                  ),
                  if (_inferenceMs > 0) ...[
                    const SizedBox(height: 8),
                    Text(
                      '推理耗时：$_inferenceMs ms'
                      '  ·  置信度阈值：'
                      '${(_threshold * 100).round()}%',
                    ),
                  ],
                  if (_error != null) ...[
                    const SizedBox(height: 8),
                    Text(
                      _error!,
                      style: const TextStyle(color: Colors.red),
                    ),
                  ],
                  const SizedBox(height: 12),
                  if (_detections.isNotEmpty)
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: _detections.take(12).map((d) {
                        return Chip(
                          avatar: const Icon(
                            Icons.check_circle_outline,
                            size: 18,
                          ),
                          label: Text(
                            '${d.label} '
                            '${(d.score * 100).toStringAsFixed(0)}%',
                          ),
                        );
                      }).toList(),
                    ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: FilledButton.icon(
                          onPressed: _loadingModel || _processing
                              ? null
                              : () => _pickImage(ImageSource.camera),
                          icon: const Icon(Icons.camera_alt),
                          label: const Text('拍照识别'),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: OutlinedButton.icon(
                          onPressed: _loadingModel || _processing
                              ? null
                              : () => _pickImage(ImageSource.gallery),
                          icon: const Icon(Icons.photo_library),
                          label: const Text('相册选择'),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPreview() {
    if (_loadingModel) {
      return const Center(
        child: CircularProgressIndicator(),
      );
    }

    if (_imageFile == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(
                Icons.document_scanner_outlined,
                size: 72,
                color: Colors.teal,
              ),
              const SizedBox(height: 16),
              const Text(
                '选择图片开始目标检测',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 8),
              const Text(
                '支持拍照和相册选图，识别在本地完成',
                textAlign: TextAlign.center,
              ),
              if (_error != null) ...[
                const SizedBox(height: 12),
                const Text(
                  '请检查模型文件及模型输出格式',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.red),
                ),
              ],
            ],
          ),
        ),
      );
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        return Stack(
          fit: StackFit.expand,
          children: [
            Image.file(
              _imageFile!,
              fit: BoxFit.contain,
              errorBuilder: (_, error, stackTrace) {
                return const Center(child: Text('图片无法显示'));
              },
            ),
            if (_detections.isNotEmpty)
              CustomPaint(
                painter: DetectionPainter(
                  detections: _detections,
                  imageFile: _imageFile!,
                ),
              ),
            if (_processing)
              Container(
                color: Colors.black38,
                child: const Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      CircularProgressIndicator(color: Colors.white),
                      SizedBox(height: 12),
                      Text(
                        '正在检测目标...',
                        style: TextStyle(color: Colors.white),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

class DetectionPainter extends CustomPainter {
  final List<Detection> detections;
  final File imageFile;

  DetectionPainter({
    required this.detections,
    required this.imageFile,
  });

  @override
  void paint(Canvas canvas, Size size) {
    // Image.file 使用 BoxFit.contain，先计算图片实际绘制区域。
    // 这样可避免图片留白区域导致边界框偏移。
    final image = _decodedSize;
    if (image == null) return;

    final scale = math.min(
      size.width / image.width,
      size.height / image.height,
    );

    final drawnWidth = image.width * scale;
    final drawnHeight = image.height * scale;
    final offsetX = (size.width - drawnWidth) / 2;
    final offsetY = (size.height - drawnHeight) / 2;

    final boxPaint = Paint()
      ..color = Colors.greenAccent
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.5;

    final fillPaint = Paint()
      ..color = Colors.green.withValues(alpha: 0.8)
      ..style = PaintingStyle.fill;

    for (final d in detections) {
      final rect = Rect.fromLTRB(
        offsetX + d.left * drawnWidth,
        offsetY + d.top * drawnHeight,
        offsetX + d.right * drawnWidth,
        offsetY + d.bottom * drawnHeight,
      );

      canvas.drawRect(rect, boxPaint);

      final label =
          '${d.label} ${(d.score * 100).toStringAsFixed(0)}%';

      final textPainter = TextPainter(
        text: TextSpan(
          text: label,
          style: const TextStyle(
            color: Colors.white,
            fontSize: 12,
            fontWeight: FontWeight.bold,
          ),
        ),
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: size.width);

      final labelTop = math.max(0.0, rect.top - textPainter.height - 4);
      final labelLeft = rect.left.clamp(
        0.0,
        math.max(0.0, size.width - textPainter.width - 8),
      );

      final labelRect = Rect.fromLTWH(
        labelLeft,
        labelTop,
        textPainter.width + 8,
        textPainter.height + 4,
      );

      canvas.drawRRect(
        RRect.fromRectAndRadius(labelRect, const Radius.circular(4)),
        fillPaint,
      );

      textPainter.paint(
        canvas,
        Offset(labelLeft + 4, labelTop + 2),
      );
    }
  }

  // 绘制层需要图片尺寸；图片在预览层中异步加载，
  // 这里缓存一次解码结果用于确定绘制区域。
  img.Image? get _decodedSize {
    try {
      final bytes = imageFile.readAsBytesSync();
      return img.decodeImage(bytes);
    } catch (_) {
      return null;
    }
  }

  @override
  bool shouldRepaint(covariant DetectionPainter oldDelegate) {
    return oldDelegate.detections != detections ||
        oldDelegate.imageFile.path != imageFile.path;
  }
}
