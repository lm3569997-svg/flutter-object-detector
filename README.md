# PoseGuard — 相机实时人形识别 + 骨架标注（监控风格）

摄像头画面 → YOLOv8n-pose 推理 → 悬浮窗画框 / 骨架 / 关节点 / 扫描线。
全程不碰游戏、不读内存、不注入，只在屏幕上叠加一层。

## 一、准备模型（PC 上跑一次）

```bash
pip install ultralytics pnnx
./build_model.sh
```

产物放进 `app/src/main/assets/`：
```
yolov8n_pose.ncnn.param
yolov8n_pose.ncnn.bin
```

## 二、编译

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 三、首次运行

1. 打开 App → **授予权限**（相机 + 通知）
2. 点 **悬浮窗** → 系统设置里允许「显示在其他应用上层」
3. 回 App → **开始**

悬浮层就会出现：绿框、17 点骨架、扫描线、HUD。

## 四、参数

| 项 | 说明 |
|---|---|
| 置信度 | 45% 起，人多时调到 55%~65% |
| 每 N 帧推理 | 1 = 每帧（最流畅）；2~3 = 省电 |
| 骨架 / 关节点 / 扫描线 / 网格 / 四角框 | 逐项开关 |

## 五、性能参考

| 设备 | 帧率 |
|---|---|
| 骁龙 8 Gen2 | 28~35 FPS |
| 骁龙 870 | 18~24 FPS |
| 中端（天玑 8100） | 12~18 FPS |

省电：把「每 N 帧推理」调到 2~3，帧率减半，功耗降一半。

## 六、结构

```
MainActivity.kt     权限、开关、参数
CameraService.kt    前台服务：CameraX 取帧 + 推理调度
PoseDetector.kt     NCNN 加载 + 推理 + 坐标换算
PosePost.kt         输出解析 + NMS + 骨架表
MonitorOverlay.kt   悬浮层绘制（框/骨架/扫描线/HUD）
```

## 七、说明

- 相机拍的是**现实世界**，不是屏幕内容；要框游戏画面得改用 MediaProjection 录屏（本工程不含）
- 模型是 COCO 17 点标准，换模型只需改 `PoseDetector` 里的输入名（`images`）和输出名（`output0`）
- assets 里的模型文件被打包进 APK，约 7 MB
