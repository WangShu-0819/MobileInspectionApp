# Batch 5 真机验证证据

## 包名门禁

| 项目 | 值 |
|------|-----|
| **新包名** | `com.wearable.inspection.mobile` |
| **启动组件** | `com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity` |
| **APK 路径** | `app/build/outputs/apk/debug/app-debug.apk` |
| **APK 大小** | 178,356,208 bytes (~170 MB) |
| **APK SHA-256** | `6e2ca7d3f573c1da1af7f9180c23a0dbe8f2f9081eafff5ccf466dcb09c051cc` |
| **安装结果** | `Performing Streamed Install` → `Success` |
| **启动结果** | `Status: ok`, `LaunchState: COLD`, `TotalTime: 1238ms` |
| **前台包校验** | `pidof` → `32550`, `mResumedActivity` → `com.wearable.inspection.mobile/.MainActivity` |
| **相机权限** | `android.permission.CAMERA: granted=true` |
| **测试设备** | HUAWEI YAL-AL10 (Android 10), ERLDU20429005890 |

## 旧包信息

| 项目 | 值 |
|------|-----|
| **旧包名** | `com.wearable.inspection` |
| **旧 APK SHA-256** | `6e14a3b4995f90aff0c77e4af6d10f65ce1d482674370bab75806c3ee16d88aa` |
| **旧 APK 大小** | 117,750,260 bytes (~112 MB) |

## DPM 扫码页面验证

### UIAutomator 结构验证

**主页面 (ui_dump_main.xml)**:
- 页面标题: "现场采集" ✓
- 扫一扫按钮: content-desc="扫一扫", bounds=[783,190][864,271] ✓
- OCR 钢印按钮: content-desc="OCR 钢印", bounds=[945,190][1026,271] ✓
- 底部 Tab: "现场采集" / "追溯记录" / "我的" ✓
- 相机预览区: bounds=[150,338][930,1377] ✓

**DPM 扫码页面 (ui_dump_dpm.xml)**:
- 页面标题: "DPM 扫码" ✓
- 返回按钮: content-desc="返回", bounds=[55,190][136,271] ✓
- 闪光灯按钮: content-desc="开启闪光灯", bounds=[945,190][1026,271] ✓
- 状态文字: "扫描中…" ✓
- 提示文字: "将 DPM 码对准扫描框" ✓
- 相机预览: bounds=[0,338][1080,2218] ✓
- 扫描覆盖层: bounds=[0,619][1080,2059] ✓

### 截图元数据

**screen_dpm.png** (旧截图):
- 路径: `docs/reports/b2/evidence/task1/screen_dpm.png`
- 大小: 1,418,202 bytes
- SHA-256: `b3c1b5e03aeccaeae946814ba326f49f8580deb254e4425b149d451a9e1e0dc3`
- 采集时间: 2026-09-02 08:42
- 对应 APK SHA-256: `00357f7c9c38cc1ff3cd36d2ffc9cb8f3cbd3c898dcb1d926a684a67adf28c1b`
- **截图人工视觉复核待完成** (mimo-v2.5-pro 无法读取 PNG)

**batch5_dpm_scan.png** (新截图):
- 路径: `docs/reports/b2/evidence/task1/batch5_dpm_scan.png`
- 大小: 886,322 bytes
- SHA-256: `6b321fc69e72276b6946344f177988e780229b78531a4132505b4c6f4e159a38`
- 采集时间: 2026-09-02 09:35
- 对应 APK SHA-256: `00357f7c9c38cc1ff3cd36d2ffc9cb8f3cbd3c898dcb1d926a684a67adf28c1b`
- **截图人工视觉复核待完成** (mimo-v2.5-pro 无法读取 PNG)

## 稳定性测试

### 扫码页面往返 10 次

| 次数 | DPM 页面打开 | 返回主页面 | 结果 |
|------|-------------|-----------|------|
| 1 | ✓ | ✓ | PASS |
| 2 | ✓ | ✓ | PASS |
| 3 | ✓ | ✓ | PASS |
| 4 | ✓ | ✓ | PASS |
| 5 | ✓ | ✓ | PASS |
| 6 | ✓ | ✓ | PASS |
| 7 | ✓ | ✓ | PASS |
| 8 | ✓ | ✓ | PASS |
| 9 | ✓ | ✓ | PASS |
| 10 | ✓ | ✓ | PASS |

**结果: 10/10 通过**

注: 使用 `KEYCODE_BACK` 返回，避免 Huawei 手势导航拦截边缘点击。

### 前后台切换 10 次

| 次数 | HOME 键 | 恢复前台 | PID 一致 | 结果 |
|------|---------|---------|---------|------|
| 1 | ✓ | ✓ | 8174 | PASS |
| 2 | ✓ | ✓ | 8174 | PASS |
| 3 | ✓ | ✓ | 8174 | PASS |
| 4 | ✓ | ✓ | 8174 | PASS |
| 5 | ✓ | ✓ | 8174 | PASS |
| 6 | ✓ | ✓ | 8174 | PASS |
| 7 | ✓ | ✓ | 8174 | PASS |
| 8 | ✓ | ✓ | 8174 | PASS |
| 9 | ✓ | ✓ | 8174 | PASS |
| 10 | ✓ | ✓ | 8174 | PASS |

**结果: 10/10 通过，PID 保持一致 (8174)**

## Logcat 门禁检查

| 检查项 | 匹配数 | 结果 |
|--------|--------|------|
| Camera already in use | 0 | ✓ |
| FATAL EXCEPTION | 0 | ✓ |
| ImageProxy leak | 0 | ✓ |
| RejectedExecutionException | 0 | ✓ |
| OutOfMemoryError | 0 | ✓ |
| NullPointerException | 0 | ✓ |
| IllegalStateException | 0 | ✓ |
| duplicate binding | 0 | ✓ |

**结果: 8/8 门禁全部通过**

## 冷启动稳定性测试（2026-09-02 新增）

### 10 次冷启动

| 次数 | LaunchState | TotalTime | PID | 结果 |
|------|-------------|-----------|-----|------|
| 1 | COLD | 1173ms | 17057 | PASS |
| 2 | COLD | 1172ms | 17320 | PASS |
| 3 | COLD | 1182ms | 17526 | PASS |
| 4 | COLD | 1170ms | 17735 | PASS |
| 5 | COLD | 1191ms | 17936 | PASS |
| 6 | COLD | 1161ms | 18142 | PASS |
| 7 | COLD | 1165ms | 18339 | PASS |
| 8 | COLD | 1158ms | 18536 | PASS |
| 9 | COLD | 1166ms | 18728 | PASS |
| 10 | COLD | 1167ms | 18927 | PASS |

**结果: 10/10 通过，平均启动时间 1171ms**

### Logcat 门禁（2026-09-02 重新验证）

| 检查项 | 匹配数 | 结果 |
|--------|--------|------|
| FATAL EXCEPTION | 0 | ✓ |
| Camera already in use | 0 | ✓ |
| ImageProxy leak | 0 | ✓ |
| RejectedExecutionException | 0 | ✓ |
| Duplicate binding | 0 | ✓ |
| ANR in | 0 | ✓ |

**结果: 6/6 门禁全部通过**

## DPM 专属测试（2026-09-02 新增）

### DpmSettingsInstrumentedTest（10 项，真机通过）

| 测试 | 说明 | 结果 |
|------|------|------|
| defaultDimensionModeIsAuto | 默认值为 AUTO | ✓ |
| writeAndReadDimensionModes | 4 种模式读写 | ✓ |
| dimensionModePersistsAcrossInstances | 跨实例持久化 | ✓ |
| illegalValueFallsBackToAuto | 非法值回退 | ✓ |
| nullValueFallsBackToAuto | null 回退 | ✓ |
| emptyStringFallsBackToAuto | 空字符串回退 | ✓ |
| caseSensitiveMatch | 大小写敏感 | ✓ |
| allModesHaveCorrectLabel | 标签正确 | ✓ |
| allModesHaveCorrectDimensions | 尺寸数组正确 | ✓ |
| parseRoundTrip | 解析往返 | ✓ |

### DpmFrameConstraintTest（17 项，JVM 通过）

| 测试 | 说明 | 结果 |
|------|------|------|
| roiMapper_fullOverlap | 全交集映射 | ✓ |
| roiMapper_partialOverlap | 部分交集裁剪 | ✓ |
| roiMapper_noOverlap | 无交集返回 null | ✓ |
| roiMapper_emptyScreenRect | 空输入处理 | ✓ |
| roiMapper_emptyContentRect | 空内容区处理 | ✓ |
| roiMapper_scaledMapping | 旋转后映射 | ✓ |
| roiMapper_withLetterbox | letterbox 映射 | ✓ |
| roiOutsideFrame | 框外返回 null | ✓ |
| roiPartialOutside | 部分超出裁剪 | ✓ |
| scanRoiPresent_skipsFullImage | 框内跳过全图 | ✓ |
| roiMapper_consistentWithBounds | 边界一致性 | ✓ |
| dimensionMode_defaultIsAuto | 默认 AUTO | ✓ |
| dimensionMode_parseRoundTrip | 解析往返 | ✓ |
| dimensionMode_dimensionsArray | 尺寸数组 | ✓ |
| gridGate_oldParamsCorrect | 旧版参数 | ✓ |
| gridGate_missThreshold8 | 网格门控参数 | ✓ |
| respondGate_rearmBehavior | 重放行行为 | ✓ |

## 待完成项（需要物理 DPM 样品）

### 框内/框外码真机验证

- [ ] 框内码可以识别（真机验证）
- [ ] 框外码不响应（真机验证）
- [ ] 10 次真实扫码

### 新旧 App A/B 对比

- [ ] 使用相同样本分别测试 OLD 和 NEW
- [ ] 每轮启动一方前先停止另一方
- [ ] 记录逐样本识别结果和响应时间

## 证据文件清单

| 文件 | 说明 |
|------|------|
| `ui_dump_main.xml` | 主页面 UIAutomator 结构 |
| `ui_dump_dpm.xml` | DPM 扫码页面 UIAutomator 结构 |
| `screen_dpm.png` | 旧截图 (待视觉复核) |
| `batch5_dpm_scan.png` | 新截图 (待视觉复核) |
| `batch5_evidence.md` | 本证据文档 |
