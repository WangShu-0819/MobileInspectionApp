# Task 3：CameraController 模式与生命周期 — 真机验收报告

> 状态：✅ 真机模式切换验收完成
> 日期：2026-09-01
> 设备：HONOR YAL-AL10, ERLDU20429005890
> 关联需求：tasks/todo.md → Task 3

---

## 一、整改内容（已通过代码复验）

1. **统一并发边界** — 所有公共方法经过同一 Mutex 串行执行
2. **ImageProxy 所有权** — CameraController 拥有 ImageProxy，在 finally 中关闭
3. **引用和 Observer 管理** — WeakReference + 显式 removeObserver
4. **可注入测试架构** — CameraBinder 接口 + FakeCameraBinder

---

## 二、真机模式切换 20 轮验收

### 测试方法
- 使用 `CameraModeTestActivity` 自动化测试
- 通过 `adb shell am start` 启动
- 每轮切换：INSPECTION → DPM_SCAN → STAMP_OCR → TEMPLATE_CAPTURE → IDLE → INSPECTION
- 每次切换间隔 500ms

### 测试结果
```
轮次: 20
每轮切换数: 5
总切换数: 100
通过: 100
失败: 0
```

### 每轮详情（logcat 摘要）
```
第 1 轮: ✓ DPM_SCAN ✓ STAMP_OCR ✓ TEMPLATE_CAPTURE ✓ IDLE ✓ INSPECTION
第 2 轮: ✓ DPM_SCAN ✓ STAMP_OCR ✓ TEMPLATE_CAPTURE ✓ IDLE ✓ INSPECTION
...
第 20 轮: ✓ DPM_SCAN ✓ STAMP_OCR ✓ TEMPLATE_CAPTURE ✓ IDLE ✓ INSPECTION
```

所有切换均返回 `currentMode` 与请求模式一致，`isActive` 状态正常。

---

## 三、logcat 禁止模式检查

| 模式 | 出现次数 |
|------|---------|
| Camera already in use | 0 |
| RejectedExecutionException | 0 |
| ImageProxy leak | 0 |
| IllegalStateException: 已永久释放 | 0 |
| SurfaceProvider 已失效 | 0 |
| LifecycleOwner 已失效 | 0 |
| Observer 重复 | 0 |
| bindToLifecycle 失败 | 0 |

**结论：8 项禁止模式全部 0 次匹配。**

---

## 四、APK 信息

| 项目 | 值 |
|------|-----|
| Commit | c804fc3 |
| APK 路径 | app/build/outputs/apk/debug/app-debug.apk |
| 文件大小 | 178,130,762 bytes (≈170 MB) |
| SHA-256 | da686cf63283b8a1b151adcda49fda2490ae1216f7255c8d0695a5bda91cba8d |
| 安装时间 | 2026-09-01 13:31 |
| 设备型号 | HONOR YAL-AL10 |
| 设备序列号 | ERLDU20429005890 |

---

## 五、待完成

- [ ] 真机 Tab 往返 10 次
- [ ] 真机前后台切换 10 次

---

## 六、证据文件

- `docs/reports/b1/evidence/task3/task3_logcat.txt` — 完整 logcat 日志
- `docs/reports/b1/evidence/task3/task3_mode_switch_20x.txt` — 模式切换测试日志
