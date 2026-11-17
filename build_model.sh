#!/bin/bash
# 导出 YOLOv8n-pose -> ONNX -> NCNN
# 需要 PC 上：python3 + pip install ultralytics pnnx
set -e
mkdir -p work && cd work

echo "[1/3] 下载/导出 ONNX"
python3 - <<'PY'
from ultralytics import YOLO
m = YOLO("yolov8n-pose.pt")
m.export(format="onnx", imgsz=640, simplify=True, opset=12)
PY

echo "[2/3] ONNX -> NCNN"
pnnx yolov8n-pose.onnx inputshape=[1,3,640,640]

echo "[3/3] 拷贝到 assets"
OUT=../app/src/main/assets
cp yolov8n_pose.ncnn.param "$OUT/z" 2>/dev/null || true
ls *.param *.bin 2>/dev/null
PHP=$(ls yolov8n-pose.ncnn.param 2>/dev/null || ls *.ncnn.param 2>/dev/null | head -1)
PBIN=$(ls yolov8n-pose.ncnn.bin   2>/dev/null || ls *.ncnn.bin   2>/dev/null | head -1)
if [ -n "$PHP" ] && [ -n "$PBIN" ]; then
  cp "$PHP" "$OUT/yolov8n_pose.ncnn.param"
  cp "$PBIN" "$OUT/yolov8n_pose.ncnn.bin"
  echo "已放入 assets："
  ls -lh "$OUT"/yolov8n_pose.ncnn.*
else
  echo "转换产物没找到，检查 pnnx 输出文件名后手动改名"
fi
