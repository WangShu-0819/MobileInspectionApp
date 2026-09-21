# V4/AKAZE 单张照片配准

低  
RD-001 · 拓普车桥末检

基于现有模板参考图、模板 ROI、现场采集照片和图像区域/旋转信息，实现一次性 V4/AKAZE 静态照片配准。完成 AKAZE 特征提取、BFMatcher/Lowe ratio 匹配、当前 Android OpenCV 能力支持的 GMS/稳健 Homography 估计、内点数/内点比例/重投影误差/空间覆盖/投影四边形质量门禁，并输出稳定的 `RegistrationResult` 和模板 ROI 四角投影。配准失败时丢弃不可靠的映射 ROI，仅返回明确失败或 `FALLBACK_FULL_IMAGE` 建议，整图 NanoDet 业务由后续任务接入。保持现有 ROI 人工改判、总体结果确认、数据库字段、ZIP/CSV 导出、NanoDet 模型/阈值和 CameraX 语义不变。

责任人：待确认

里程碑：开发实现

计划：待排期

实际：2026-09-21 至 待定

计划 未估算 · 待审核

已审核进度  
0%

更新进度 · 0% · 待确认

2026-09-21 · 已完成 V4/AKAZE 方案设计、边界审计和执行 Agent 指令编写；当前尚未开始源码实现。下一步先审计现有图片存储、旋转/EXIF、`contentRect`、ROI 坐标映射和 OpenCV 依赖，再实现静态配准及 JVM 几何/失败路径测试。当前不实现 CaptureComparisonScreen、Session ROI 编辑 UI、ALIKED + LightGlue、双方案 fallback、实时轮廓、自动姿态匹配、新 CameraX 或整图检测业务。 · 本次 0h（其中加班 0h）

补充信息

查看详情：[`V4_AKAZE_REGISTRATION_AGENT_INSTRUCTION.md`](V4_AKAZE_REGISTRATION_AGENT_INSTRUCTION.md)  
设计方案：[`../docs/reports/b3/PHOTO_REGISTRATION_ENGINE_OPTIONS.md`](../docs/reports/b3/PHOTO_REGISTRATION_ENGINE_OPTIONS.md)  
更新进度：待执行 Agent handback 后由主协调审计填写
