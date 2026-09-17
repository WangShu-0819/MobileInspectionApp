# ROI 最终结果、人工改判与证据图执行计划

**用途**：供执行 Agent `mimo` 完成本轮唯一 ROI 任务；主协调负责审阅实现和证据，再更新任务状态与正式报告。

**任务状态来源**：`tasks/todo.md`。本文件只给出实现顺序和验收方法，不改变任务状态。

**范围**：复用现有 ROI 确认、图片存储和批次 ZIP/CSV 链路；本轮不处理其他待办。

## 1. 已有链路与实施前复核

以下基于 `mimo` 的只读审计记录。开始改动前，先重新读取相关源码和当前工作区 diff；如源码现状与下列记录不同，按实际代码修正实施路径并向主协调报告，再继续本任务。

| 部分 | 审计记录 |
|---|---|
| Room | 数据库版本为 v9。现有 `view_roi_confirms` 保存 `softwareResult`、`humanResult`、`humanChangedModel`、`confirmTime`、`overallResult`、`overallConfirmTime` 及稳定关联字段。没有 `overrideTime` 或 `roiEvidencePath`。 |
| 关联与 DAO | `batchId/photoId/photoPath/viewIndex/templateId/roiId` 提供稳定关联；确认行通过既有 DAO 查询，并在事务中按照片替换；继续使用这些路径。 |
| Repository 与图片 | 现场原图来自 `CapturedPhotoEntity.filePath`。确认页的 ROI crop 目前只作为 Bitmap 留在内存；`MobileImageStore` 已管理 DPM 图片，尚无 ROI 改判图持久化接口。 |
| ViewModel | 加载时恢复已保存的 ROI 人工选择和总体选择；模型结果在 `softwareResult`，人工选择在 `humanResult`；改判标记由两者比较得到。当前没有基于模型结果的默认选择，也没有持久化改判图。 |
| 确认页 | 现有页面含模型详情和 ROI UUID；需要按本计划精简显示，同时保留上下结构、固定确认栏和已有选择/推进流程。 |
| CSV/ZIP | 检查点 A 记录的旧 `MANIFEST_HEADER` 为 45 列，已有 ROI 图片路径/状态列，但未写入实际 ROI 文件；批次 ZIP 目前没有 ROI 改判图条目。CSV 已有模型、人工、改判和确认相关字段。本轮新增 `result` 与 `overrideTime` 后为 47 列；实施前仍须从源码复核 header 和各 row builder，旧 45 列的位置不得变化。 |

## 2. 数据语义

### 2.1 唯一最终结果

- 继续复用 `ViewRoiConfirmEntity`；不新增结果实体或第二套 ROI 确认状态。
- `softwareResult` 保存本次推理的原始模型结果；它不会被人工选择覆盖。
- `humanResult` 保存最终人工选择，也是持久化后的规范最终值。模型有 OK/NG 时，新确认默认选择该模型值；未改判时两者相同，改判时 `humanResult` 是最终结果。
- CSV 保留已有字段及原有顺序，并新增一个名为 `result` 的规范最终结果列。旧模型/人工字段作为兼容字段保留；消费者读取最终结论时只使用 `result`。
- `humanChangedModel` 仅在 `softwareResult` 有值且人工选择与之不同时为 true。没有模型结果时，人工选择可以成为最终结果，但不算模型改判，不生成改判图。
- `humanResult` 尚未由模型默认或人员明确选择时不得写入伪造结果；保留现有“全部必填后才能继续”的行为。

### 2.2 默认选择与重载

- 新 ROI 完成推理后，仅当存在模型 OK/NG 且没有先前人工选择时，才将该模型值设为人工按钮的默认选中值。
- 从数据库加载到的人工选择优先；重新进入页面或重新加载模型状态时不得覆盖已保存的人工作用值。
- FEATURE、未配置目标、模型未执行、无可用结果或错误状态均不默认选中 OK/NG。操作状态只显示“部件类别暂不支持”或“模型未执行”这类当前允许的短提示。
- 总体照片结果由人员单独选择和保存，ROI 选择变化不得推导或覆盖总体结果。

## 3. Room 与 ROI 图片持久化

### 3.1 数据库字段

在现有 `view_roi_confirms` 增加两个 nullable 字段：

- `overrideTime: Long?`：仅已保存的模型改判记录有值。
- `roiEvidencePath: String?`：仅已保存的模型改判记录关联到受管理 ROI 图片时有值。

保持现有 `confirmTime` 含义不变。执行前再核对 Room 当前版本和 schema；若仍是 v9，使用真实 `MIGRATION_9_10`，将两个字段加入 `ALL_MIGRATIONS` 并导出 schema v10。旧行新字段为 null，原模型/人工结果、改判标记、照片总体结果及稳定关联不得丢失。为 migration 增加从真实 v9 schema 打开的测试。

### 3.2 证据图片

- 使用现有 ROI 坐标映射和当前照片生成 ROI 原始裁剪图，按项目已有受管理图片存储方式写入应用私有目录；不保存屏幕截图或带临时 UI 标记的画面。
- 文件名必须由稳定的 `batchId/photoId/templateId/viewIndex/roiId` 关联生成，不依赖列表序号、展示名称或时间排序。实体中保存实际受管理文件路径。
- 新改判先写临时文件、校验非空后原子落盘，再将路径与 `overrideTime` 一同写入确认记录。文件创建/校验失败时，确认操作不得报告成功或覆盖既有确认行；向页面报告可重试的保存错误。
- 对同一条已保存改判再次确认且结果未变时，保留既有证据路径及改判时间。人员恢复到模型结果后，清空改判时间和路径；数据库更新成功后再删除旧受管理文件。数据库保存失败时保留旧文件和旧记录。
- 只允许读取、替换或删除 ROI 图片存储目录下由应用管理的路径。删除只针对当前稳定确认记录引用的文件。

## 4. 确认页

保留现有上下布局、间距、卡片组织方式、固定确认栏和导航流程。页面仅呈现：

- 零件名称和视角进度；
- 人可读的 ROI 编号与 ROI 类型（螺纹、螺母、部件）；
- ROI 图片、人工 ROI OK/NG 选择、人工总体 OK/NG 选择；
- 操作状态和“确认并继续”。

模型结果通过 OK/NG 按钮默认选中状态体现，不单独显示模型建议。ROI 图片显示原始裁剪内容，不叠加检测框。不得在页面显示 ROI UUID、分数、阈值、模型版本、模型详情或额外的 ROI 名称/检测框计数文案。没有模型结果时两个按钮均未选中，必须由人员选择；所有 ROI 与总体结果有值后才启用“确认并继续”。

## 5. CSV 与批次 ZIP

### 5.1 CSV 兼容

- 保留所有既有 header 字段及相对顺序；新增列只追加在 header 尾部，不在旧列中间插入。
- 在尾部添加 `result` 和 `overrideTime`（或项目约定的等价中文表头）。照片行、DPM 行和 ROI 行均补齐相同列宽；既有 header 索引和既有外部 CSV 合同保持有效。
- `result` 写入已确认的 `humanResult`。同时保留已有 `softwareResult`、`humanResult`、`humanChangedModel` 和确认时间列的原有语义。
- `ROI图ZIP路径` 只写入实际已写入 ZIP 的条目名；必须从导出过程记录的“确认记录稳定键 → 成功 ZIP entry”映射产生。改判记录缺文件、空文件或读失败时，不填预期路径，并在状态列明确说明。未改判时路径为空且状态表示未发生改判。

### 5.2 ZIP 写入顺序和稳定回链

1. 按 `batchId/photoId/templateId/viewIndex/roiId` 查询确认记录。
2. 只为 `humanChangedModel == true` 且证据路径确实指向受管理非空文件的记录写入 `roi_evidence/` 条目。
3. 每个文件成功写入并关闭 ZIP entry 后，记录其准确 entry path；不能根据源文件名提前填写 CSV。
4. 生成 CSV 时按同一稳定关联读取上述映射，确保每个非空 `ROI图ZIP路径` 都能在 ZIP 条目列表中找到。
5. 历史改判行可能因 migration 而没有 ROI 图片；保留记录和旧 CSV 字段，路径留空并标明缺失，不能伪称有证据图。当前新增改判若无法保存证据，则在确认阶段失败，不落成缺证据的成功改判。

保持 ZIP 中 View 原图与 DPM 成功帧/ROI 的现有目录、过滤、字节复制及 CSV 行为。DPM 独立 ZIP 不变。

## 6. 实施顺序与检查点

### 检查点 A：基线审计

- 阅读当前 entity、DAO、migration/schema、Repository、ViewModel、确认页、图片存储、两个 exporter 及相关测试。
- 执行 `git status --short` 和目标文件 diff；报告实际基线和最小计划文件列表。
- 确认唯一在办任务仍为 `tasks/todo.md` 中的 ROI 项；不覆盖其他工作区改动。

### 检查点 B：数据与改判状态

- 实现 nullable 字段和 migration；保持所有旧行可读。
- 实现默认选择、旧人工值优先、总体结果独立、改判时间/路径字段，以及原始 ROI crop 的受管理保存和失败处理。
- 先运行 entity、migration、image store、ViewModel 定向测试并读回 Room 行，确认改判与未改判的数据形状。

### 检查点 C：确认页与 ZIP/CSV 纵向闭环

- 精简页面到允许显示项，补 Compose/UI 状态测试。
- 扩展 CSV 和 ZIP；补真实文件写入、真实 ZIP 解包、路径存在性和文件 SHA-256 回读断言。
- 测试未改判、OK→NG、NG→OK、模型未执行/FEATURE、总体结果独立、旧 CSV 兼容和 DPM 导出回归。

### 检查点 D：完整验证与设备证据

- 执行定向与全量 JVM 测试、Kotlin 编译、Room migration instrumented 测试和主 APK 构建；分别报告命令、退出码、测试计数及既有失败。
- 至少记录并执行 `:app:compileDebugKotlin`、新增/相关 `:app:testDebugUnitTest`、完整 `:app:testDebugUnitTest`、Room migration 对应 `:app:connectedDebugAndroidTest`、`:app:assembleDebug :app:assembleDebugAndroidTest` 的实际命令；测试类名以代码中的真实类名为准。
- 如运行过 `connectedDebugAndroidTest`，测试后按 `AGENTS.md` 停止新旧包、重装主 APK、用完整组件启动，并重新核对包安装、两个包 PID 和前台 Activity。
- 真机只使用 `com.wearable.inspection.mobile/com.wearable.inspection.mobile.MainActivity`。结构化页面核对可用 UIAutomator XML/bounds、包名、日志和文件证据；mimo 不读取或视觉分析 PNG/JPG，视觉项标“等待用户人工视觉复核”。
- 回填交付报告需要的实际修改文件、migration、APK 路径/时间/大小/SHA-256、数据库/文件/ZIP/CSV 回链证据、前序能力回归矩阵、限制和 Git 状态；主协调更新 `tasks/todo.md` 与正式报告，等待用户验收。mimo 不提交 Git。

## 7. 自动化验收矩阵

| 范围 | 必须证明 |
|---|---|
| 结果语义 | 有模型结果且未改判时 `result == softwareResult`；双向改判时 `result == humanResult`；原模型值、人工值、改判标记及时间正确。 |
| 非执行状态 | FEATURE、未配置、无推理结果和推理错误不默认选择，不自动生成 OK/NG；显式人工选择后可保存最终结果，且不伪称模型改判。 |
| 总体结果 | 改变任一 ROI 不改变总体结果；总体结果只能由总体 OK/NG 控件选择。 |
| 重载与稳定关联 | Room migration 保留旧值；新字段旧行为空；按稳定 batch/photo/template/view/roi 关联重载结果和证据路径。 |
| 图片 | 改判时图片真实生成、可重读且非空；未改判时不新建图片；失败时不生成成功确认；撤销改判后清理对应的受管理文件且不误删别的记录。 |
| CSV | 旧字段和列索引不变；新 `result` 与最终值一致；改判时间和状态正确；每个非空 ROI ZIP 路径都对应真实 ZIP entry。 |
| ZIP | 用真实 `ZipInputStream` 解包；核对改判图片 entry、CSV 路径和 entry 对应字节 SHA-256；未改判无新增图片；DPM 成功图在批次包中的字节及路径回归通过。 |
| UI | 只显示允许的字段，ROI 图片无检测框叠加；模型建议、UUID、分数、阈值、模型版本不在 Compose 语义树中；非执行状态提示准确；按钮默认选择和继续按钮门槛正确。 |

## 8. 预计文件范围

执行前以检查点 A 产出的最小文件清单为准。预计涉及：

- `app/src/main/java/com/wearable/inspection/mobile/data/entity/ViewRoiConfirmEntity.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/db/AppDatabase.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/db/Migrations.kt`
- `app/schemas/` 对应的 v10 Room schema
- `app/src/main/java/com/wearable/inspection/mobile/data/image/MobileImageStore.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt`（仅在现有接口不能复用时）
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModel.kt`
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/export/InspectionExcelExporter.kt`
- `app/src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt`
- 对应 entity、migration、image store、ViewModel、Compose、CSV、ZIP archive 测试

## 9. 明确不在范围内

- DPM 解码、扫码会话绑定、DPM 文件存储或独立 DPM ZIP 改动。
- NanoDet 算法、模型、阈值和阈值校准；CameraX、导航架构。
- 批次/ZIP 清理、批量多选导出、自动对齐、自动 ROI 和其他新检测能力。
- 新建平行 ROI、结果或批次模型；提交 Git 或整理其他工作区改动。
