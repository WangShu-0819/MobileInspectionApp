# B2 Task 1 — 真机验证状态

## 包名门禁（2026-09-02 Batch 5 验证通过）

| 项目 | 值 |
|------|-----|
| **新包名** | `com.wearable.inspection.mobile` |
| **启动组件** | `com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity` |
| **APK SHA-256** | `00357f7c9c38cc1ff3cd36d2ffc9cb8f3cbd3c898dcb1d926a684a67adf28c1b` |
| **前台包校验** | `pidof` → PID 存在, `mResumedActivity` → 新包 |
| **旧包 PID** | 空（已 force-stop） |

## 已完成的自动化验证

### JVM 单元测试（208 @Test，203 passed / 0 failed / 5 skipped）

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
| **DpmFrameConstraintTest** | **17** | **17** | **0** | **2026-09-02 新增：框内/框外约束、ROI 映射、参数验证** |
| DpmFrameQualityTest | 8 | 8 | 0 | |
| DpmGridGateTest | 7 | 7 | 0 | |
| DpmOfflineDecodeTest | 3 | 3 | 0 | |
| DpmPreprocessorTest | 16 | 16 | 0 | OpenCV 渲染验证 |
| DpmRespondGateTest | 9 | 9 | 0 | |
| DpmScanControlTest | 5 | 5 | 0 | |
| DpmScanRoiMapperTest | 9 | 9 | 0 | |
| DpmScannerTest | 17 | 12 | 5 | 跳过：外部 dump 文件缺失 |
| ZxingDataMatrixDecoderTest | 4 | 4 | 0 | |

### Android Instrumented 测试（30/30 passed）

- 设备：HUAWEI YAL-AL10 (Android 10)
- 运行时间：1m 4s
- 0 skipped, 0 failed
- **新增 DpmSettingsInstrumentedTest（10 项）：SharedPreferences 持久化、默认值、非法值回退**

### APK 安装验证

- APK 安装成功：`adb install -r` → Success
- Camera 权限：granted=true
- 应用启动：无 FATAL EXCEPTION
- Firebase 网络超时为系统正常行为（不影响功能）

## Batch 5 真机验证（2026-09-02）

### DPM 扫码页面

- [x] "扫一扫"进入 DPM_SCAN 页面 ✓
- [x] 页面标题 "DPM 扫码" ✓
- [x] 返回按钮存在 ✓
- [x] 闪光灯按钮存在 ✓
- [x] 状态文字 "扫描中…" ✓
- [x] 提示文字 "将 DPM 码对准扫描框" ✓
- [x] 相机预览正常 ✓
- [x] 扫描覆盖层存在 ✓

### 稳定性测试

- [x] 扫码页面往返 10 次：10/10 通过 ✓
- [x] 前后台切换 10 次：10/10 通过，PID 一致 ✓
- [x] Logcat 门禁 8 项全部 0 违规 ✓

### 相机生命周期

- [x] DPM 页面退出后相机正确关闭 ✓
- [x] 返回主页面后 INSPECTION 相机恢复 ✓
- [x] 无 Camera already in use ✓
- [x] 无 ImageProxy 泄漏 ✓
- [x] 无 RejectedExecutionException ✓

## 2026-09-02 新增验证

### 冷启动稳定性（10/10 通过）

| 次数 | LaunchState | TotalTime | 结果 |
|------|-------------|-----------|------|
| 1-10 | COLD | 1158-1191ms | 全部 PASS |

平均启动时间: 1171ms

### Logcat 门禁（6/6 通过）

| 检查项 | 匹配数 | 结果 |
|--------|--------|------|
| FATAL EXCEPTION | 0 | ✓ |
| Camera already in use | 0 | ✓ |
| ImageProxy leak | 0 | ✓ |
| RejectedExecutionException | 0 | ✓ |
| Duplicate binding | 0 | ✓ |
| ANR in | 0 | ✓ |

### DPM 专属测试

- [x] DpmSettingsInstrumentedTest（10 项，真机通过）：SharedPreferences 持久化、默认值、非法值回退
- [x] DpmFrameConstraintTest（17 项，JVM 通过）：框内/框外约束、ROI 映射、参数验证

## 待完成（需要物理 DPM 样品）

- [ ] frame-outside-code：对准空白区域 10 秒无响应（真机验证）
- [ ] move-into-frame：从框外移入 DPM 码后识别成功（真机验证）
- [ ] 10 次真实扫码（不同样品）
- [ ] 新旧 App A/B 对比（每样品识别速度和成功率）

## 忠实度断言状态

| 断言 | 状态 | 说明 |
|------|------|------|
| 无算法省略 | ✅ 代码审计 | 4 策略/网格/解码器已迁移；Stage1-4 完整；A/B 待物理样品 |
| 无参数变化 | ✅ 代码审计 | centerCropRatio=0.5f、roiTargetWidth=400、missTriggerCount=30、gridMissThreshold=8、gridCooldownMs=1500 |
| 无测试删除 | ✅ | 所有旧版测试逻辑保留 |
| 无硬编码结果 | ✅ | 所有解码为真实运行 |
| CameraX 无回归 | ✅ | 真机往返/前后台/日志门禁通过 |
| 冻结目录未动 | ✅ | tools/contour_extraction/ 未修改 |

## 截图人工视觉复核

以下截图需要人工视觉复核（mimo-v2.5-pro 无法读取 PNG）：

| 截图 | SHA-256 | 说明 |
|------|---------|------|
| screen_dpm.png | `b3c1b5e0...` | 旧截图，待复核 |
| batch5_dpm_scan.png | `6b321fc6...` | 新截图，待复核 |
