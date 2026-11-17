#!/bin/bash
set -e
pip install --quiet ultralytics pnnx onnx onnxsim

mkdir -p work && cd work
python3 -c "from ultralytics import YOLO; YOLO('yolov8n-pose.pt').export(format='onnx', imgsz=640, simplify=True, opset=12)"
ls -lh *.onnx

pnnx yolov8n-pose.onnx inputshape=[1,3,640,640]
ls -lh *.ncnn.*

PARAM=$(ls *.ncnn.param | head -1)
BIN=$(ls *.ncnn.bin | head -1)
mkdir -p ../app/src/main/assets
cp "$PARAM" ../app/src/main/assets/yolov8n_pose.ncnn.param
cp "$BIN"   ../app/src/main/assets/yolov8n_pose.ncnn.bin
ls -lh ../app/src/main/assets/
