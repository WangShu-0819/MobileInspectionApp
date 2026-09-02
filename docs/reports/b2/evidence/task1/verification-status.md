# B2 Task 1 — 真机验证状态

## 已完成的自动化验证

### JVM 单元测试（188 @Test，183 passed / 0 failed / 5 skipped）

| 测试类 | @Test | 通过 | 跳过 | 说明 |
|--------|-------|------|------|------|
| CameraControllerTakePhotoTest | 17 | 17 | 0 | |
| CameraControllerTest | 40 | 40 | 0 | |
| MobileImageStoreTest | 11 | 11 | 0 | |
| ContentRectCalculatorTest | 10 | 10 | 0 | |
| DpmAnalyzerTest | 7 | 7 | 0 | missTriggerCount=30 |
| DpmDecodePipelineTest | 8 | 8 | 0 | |
| DpmDimensionModeTest | 11 | 11 | 0 | |
| DpmDumpBudgetTest | 6 | 6 | 0 | |
| DpmFrameAnalyzerTest | 3 | 3 | 0 | |
| DpmFrameQualityTest | 8 | 8 | 0 | |
| DpmGridGateTest | 7 | 7 | 0 | |
| DpmPreprocessorTest | 16 | 16 | 0 | OpenCV 渲染验证 |
| DpmRespondGateTest | 9 | 9 | 0 | |
| DpmScanControlTest | 5 | 5 | 0 | |
| DpmScanRoiMapperTest | 9 | 9 | 0 | |
| DpmScannerTest | 17 | 12 | 5 | 跳过：外部 dump 文件缺失 |
| ZxingDataMatrixDecoderTest | 4 | 4 | 0 | |

### Android Instrumented 测试（20/20 passed）

- 设备：HUAWEI YAL-AL10 (Android 10)
- 运行时间：1m 2s
- 0 skipped, 0 failed

### APK 安装验证

- APK 安装成功：`adb install -r` → Success
- Camera 权限：granted=true
- 应用启动：无 FATAL EXCEPTION
- Firebase 网络超时为系统正常行为（不影响功能）

## 待手动验证（需 DPM 样品）

以下验证需要物理 DPM 样品和新旧 App 对比，无法通过自动化完成：

- [ ] frame-outside-code：对准空白区域 10 秒无响应
- [ ] move-into-frame：从框外移入 DPM 码后识别成功
- [ ] frame-inside-only：框内码识别、框外码忽略
- [ ] 10 次真实扫码（不同样品）
- [ ] same-code dedup：同码连续扫描防重复
- [ ] move-away-and-back：移开再移回识别
- [ ] AUTO/16/18/20 尺寸模式切换
- [ ] 10 次页面往返（现场采集 ↔ 扫码）
- [ ] 10 次前后台切换
- [ ] 新旧 App A/B 对比（每样品识别速度和成功率）

## 忠实度断言状态

| 断言 | 状态 | 说明 |
|------|------|------|
| 无算法省略 | ⚠️ 待验证 | 旧版 4 策略/网格/解码器已迁移；未做 A/B 对比 |
| 无参数变化 | ⚠️ 待验证 | 大部分参数已恢复旧值；Center ROI 维持 1200×1200 |
| 无测试删除 | ✅ | 所有旧版测试逻辑保留 |
| 无硬编码结果 | ✅ | 所有解码为真实运行 |
| CameraX 无回归 | ⚠️ 待验证 | 架构未修改；未做真机回归 |
| 冻结目录未动 | ✅ | tools/contour_extraction/ 未修改 |
