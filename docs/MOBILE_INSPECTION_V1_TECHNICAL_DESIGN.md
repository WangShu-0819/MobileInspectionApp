# MobileInspectionApp V1 技术方案

状态：方案基线 / 分阶段实施文档  
适用工程：`MobileInspectionApp`  
应用包名：`com.wearable.inspection.mobile`

## 1. 文档目的

本文将当前 V1 产品方案固化为一份独立的技术设计文档，用于统一产品边界、数据模型、页面职责、相机约束、检测链路、持久化要求和验收标准。

本文同时区分两种状态：

- **V1 目标闭环**：模板可用、现场可取景、照片可比对、ROI 可确认、算法可执行、结果可追溯、结果可导出。
- **当前工程状态**：模板导入、View 顺序、透明叠加、真实拍照、DPM/OCR 基础能力已完成；拍后比对、Session ROI、Detector、InspectionSession 完整结果和导出仍按阶段推进。

规划能力不得被当作已经实现的功能。每个阶段必须以真实数据、真实设备和可复现证据验收。

## 2. 产品定位

MobileInspectionApp 是面向工业零件的手机视觉质检工作台，不是普通拍照 App、扫码器或 OCR 工具。

核心业务链路为：

```text
Part
  → DPM / OCR / 人工确定零件
  → 加载该零件模板
  → 按 View 1 到 View N 顺序拍摄
  → 模板透明叠加辅助取景
  → 获得现场采集图
  → 模板 / 实拍比对
  → 确认或调整本次 Session ROI
  → 执行 ROI 检测
  → 生成 Inspection Session
  → 保存追溯记录
  → 导出完整结果包
```

V1 优先保证流程完整、稳定、可操作，不以复杂实时视觉算法作为交付前提。

## 3. 交付范围与非目标

### 3.1 V1 目标范围

V1 应支持以下完整业务能力：

1. 创建或导入零件及其有序模板 View。
2. 为模板保存参考图片和标准 Template ROI。
3. 现场选择零件和模板，或通过已绑定 DPM 码切换到已有零件。
4. 使用唯一 CameraController 进行实时取景和真实拍照。
5. 在相机内容区域内叠加模板参考图，辅助复现拍摄位置。
6. 拍照后查看 Template 与 Capture 的独立比对页面。
7. 将 Template ROI 映射为当前照片使用的 Session ROI，并允许有限人工调整。
8. 通过现有 `AlgorithmRegistry` / `RoiInspectionAlgorithm` 执行 ROI 检测。
9. 生成总体结果和逐 ROI 结果，区分 `PASS`、`REVIEW`、`FAIL`、`ERROR`。
10. 保存可解释历史记录，并导出完整结果包。

### 3.2 当前明确暂缓

以下能力不属于当前阶段验收条件：

- Template ROI 配置界面；
- 现场 Session ROI 编辑；
- ROI 微调和 ROI Detector；
- 模板 / 实拍自动配准；
- PASS / FAIL 检测结果生成；
- InspectionSession 完整结果写入；
- 追溯结果记录和结果包导出。

这些能力在本文中保留技术接口和后续实施要求，但当前页面不得用假数据、假 `ALIGNED` 状态或空壳结果冒充已完成。

### 3.3 永久不作为 V1 前置条件

以下高级能力继续放到后续版本，不得阻塞 V1 主流程：

- 自动主体轮廓提取；
- 实时轮廓投影；
- Homography / 自动姿态配准；
- SIFT 或其他自动姿态匹配；
- 自动 `ALIGNED` / `LOST` 判断；
- 自动 ROI 跟踪；
- 复杂差分热力图；
- 后台熄屏持续 CameraX；
- 旧 Leion、G40、HUD、USB 能力。

`tools/contour_extraction/` 中已有源码、标注和验证产物继续保留为 `DEFERRED / POST-MVP`，不得删除或重新接入当前关键路径。

## 4. 总体架构

### 4.1 包和模块边界

新工程使用独立包名：

```text
com.wearable.inspection.mobile
├── app/                       # Application、Activity、依赖装配
├── camera/                    # 唯一 CameraX 所有者
│   ├── CameraController.kt
│   ├── CameraMode.kt
│   ├── CameraState.kt
│   └── analyzer/
├── data/
│   ├── dao/
│   ├── db/
│   ├── entity/
│   ├── image/
│   ├── repository/
│   └── settings/
├── domain/
│   ├── model/
│   └── usecase/
└── ui/
    ├── navigation/
    ├── theme/
    ├── components/
    └── feature/
        ├── capture/
        ├── records/
        ├── profile/
        ├── templates/
        ├── parts/
        └── settings/
```

当前工程部分页面仍位于既有 `ui/screens` 目录。后续如需移动文件，必须先审计导航引用，按 feature 小组迁移，并在每组迁移后执行 Kotlin 编译；不得一次性搬迁全部源码。

### 4.2 数据流原则

- UI 只负责展示状态和发送用户意图。
- ViewModel 管理页面状态、页面生命周期和一次性事件。
- Repository 统一协调 Room、私有图片存储和业务查询。
- DAO 只负责持久化查询，不在 UI 中拼装业务关系。
- CameraController 是全工程唯一的 `ProcessCameraProvider` 所有者。
- 分析器按业务模式独立，但全部通过同一个 CameraController 接入。
- 原始拍照成功只表示图片保存成功，不能自动创建检测通过记录。

## 5. 相机和实时取景设计

### 5.1 唯一相机控制器

所有相机页面共享一个 `CameraController`，禁止建立第二套 CameraX 或在页面中直接持有另一份 `ProcessCameraProvider`。

支持的业务模式为：

```text
IDLE
INSPECTION
DPM_SCAN
STAMP_OCR
TEMPLATE_CAPTURE
```

模式切换必须按以下顺序执行：

```text
停止旧分析器
→ 取消旧任务
→ 关闭旧 Executor
→ unbindAll
→ 构建当前模式所需 UseCase
→ 重新绑定
→ 发布新的 CameraState
```

页面退出时必须释放当前模式的分析资源，返回现场采集后相机能够恢复。所有 `ImageProxy` 必须在成功、失败、取消和异常路径关闭；异步处理只能使用已经复制的数据。

### 5.2 现场实时画面

现场采集使用统一的实时相机和模板叠加区域：

```text
CameraX PreviewView
        +
Template Reference Image Overlay
```

取景约束：

- `PreviewView` 默认使用 `FIT_CENTER`。
- 优先统一 4:3 相机流，竖屏内容表现为 3:4。
- 允许出现 letterbox 黑边，不允许裁切或拉伸相机内容。
- 模板只覆盖真实相机 `contentRect`，不能覆盖 letterbox 区域。
- 模板图片保持原始纵横比。
- “原比例”模式允许留黑边。
- “撑满”模式只能在真实内容区域内缩放，不能让透明叠加消失。
- 记录实际 `contentRect`，后续坐标映射不得使用包含 letterbox 的整个屏幕矩形。

现场采集页面的主任务是辅助人员复现模板拍摄位置，不自动判断零件是否合格，也不根据轮廓自动决定是否允许拍照。

### 5.3 模板透明度

透明度 Slider 的定义：

| 项目 | 规则 |
|---|---|
| 范围 | 0%–80% |
| 默认值 | 45% |
| 0% | 只显示现场相机画面 |
| 增大 | 模板结构逐渐明显 |
| 作用 | 仅改变 Overlay 显示效果 |
| 禁止副作用 | 不重新绑定 CameraX，不创建新的 CameraSession |

现场人员通过孔位、螺栓、边缘、支架和连接件等结构重合关系，手动调整手机位置、距离、旋转角度和拍摄姿态。

### 5.4 Part 和 View 选择

现场采集必须先确定 Part，再按该 Part 的有序 View 列表工作：

```text
Part
  → ordered Views
  → View 1/N
  → View 2/N
  → ...
  → View N/N
```

要求：

- 当前 Part 加载其全部启用 View。
- View 顺序以 `displayOrder ASC` 查询，并使用稳定的次级键保证结果确定性。
- 拍照成功后只推进到下一个 View。
- 最后一张成功保存后显示“零件采集完成”。
- 不重复显示多个“采集完成”提示。
- DPM 命中已绑定 Part 时，更新当前 Part、重新加载有序 View，并从 View 1/N 开始。
- 未绑定 DPM 码只提示“未找到对应零件，请先在模板配置中绑定”，不新建零件。

## 6. 模板和 ROI 数据设计

### 6.1 核心关系

```text
Part
 └── ordered InspectionTemplate / View
      ├── Reference Image
      └── Template ROI[]
             ├── 检测方法
             └── 检测参数
```

一个 Part 可以拥有多个检测视角。每个 View 至少需要保存：

- 模板名称或显示名称；
- 所属 Part；
- 稳定的 View 标识；
- `displayOrder`；
- 原始参考图片的私有存储引用；
- 图片宽度、高度和方向元数据；
- Template ROI 列表；
- 创建和更新时间。

每个 Template ROI 至少需要保存：

- ROI 稳定标识；
- ROI 名称；
- 在模板原图中的位置和尺寸；
- 坐标系、图片尺寸和方向语义；
- 绑定的检测算法类型；
- 算法参数；
- 阈值和业务优先级；
- 配置版本或快照引用。

### 6.2 Template ROI 与 Session ROI

两者必须严格区分：

| 类型 | 含义 | 生命周期 | 是否修改模板 |
|---|---|---|---|
| Template ROI | 模板中的标准检测区域 | 随模板配置长期存在 | 是标准配置来源 |
| Session ROI | 本次实拍实际使用的区域 | 只属于当前 Inspection Session | 否 |

目标映射关系为：

```text
Template ROI
  → 根据模板图与实拍图的取景关系映射
  → Session ROI
  → 必要时由现场人员有限微调
```

旧模板 ROI 只有在以下信息全部确认后才能自动迁移：

- 原始图片尺寸；
- 图片方向；
- 坐标原点和单位；
- 坐标是否相对于整图、裁剪图或内容区域；
- 与当前图片存储的映射关系。

若旧 ROI 坐标语义无法可靠确认，必须跳过 ROI 自动导入，并在模板配置中重新配置，不能写入可能错误的坐标。

Session ROI 的 V1 操作范围：

- 移动 ROI；
- 调整宽度；
- 调整高度；
- 不要求旋转 ROI；
- 只影响当前照片和当前 Session；
- 不反向修改 Template ROI。

当前工程暂缓 ROI 配置和 Session ROI 编辑，以上内容是后续阶段接口约束。

## 7. 模板导入

旧 `Wearable Inspection` 工程中的正式模板数据继续复用，不重新采集全部模板。

导入优先级：

1. 使用已迁移的 `TemplatePackageImporter` 导入正式模板包。
2. 使用 `TemplateImportService` 将数据写入当前 Room 和私有图片存储。
3. 通过 `PartEntity`、`InspectionTemplateEntity` 和图片引用恢复 Part、View 与参考图片关系。
4. 对 manifest 中的显式 order 进行保留；缺失、非法或重复 order 使用稳定回退规则。
5. flat directory 使用稳定文件名排序，确保重复导入顺序一致。

导入事务要求：

- 图片必须先验证可读、可解码、宽高有效和方向信息可处理。
- 文件写入使用临时文件和原子移动。
- 导入失败不能留下半成品模板或残留临时文件。
- 不能覆盖用户已有模板或已有本地修改。
- 旧 ROI 不确定时只导入模板图片和 View，不伪造 ROI。

## 8. 拍照和拍后比对

### 8.1 真实拍照

拍照继续使用当前 CameraController 的真实 `ImageCapture` 链路。

拍照成功的最小语义是：

```text
原始实拍图片已成功保存
```

不得将以下状态隐含为拍照成功：

- 检测通过；
- 模板已经对齐；
- ROI 已经确认；
- PASS / FAIL 已经产生；
- InspectionSession 已经完整写入。

图片保存必须具备：唯一文件名、有效 JPEG、有效方向、无空文件、无 `.part` 残留，并能够通过当前 App 私有存储引用读取。

### 8.2 独立比对页面

每个 View 拍摄完成后进入独立的 Template / Capture 比对页面。页面需要同时加载：

- 模板参考图；
- 当前实拍图；
- 当前 View 和 Part 信息；
- 比对模式；
- ROI 状态；
- 下一步操作。

第一版支持三种观察方式：

1. **直接切换**：`Template → Capture → Template → Capture`。
2. **Alpha Overlay**：两张图叠加，用 Slider 调整两张图的显示比例。
3. **Blink Comparison**：按住或点击后快速交替显示两张图。

页面还需要支持局部缩放和平移，以检查螺栓缺失、边缘变化、附件变化和孔位变化等细节。

V1 不要求复杂自动配准，也不要求差分热力图。比对页面的人工观察结果不能直接替代 Detector 结果。

## 9. ROI 检测链路

完成 Session ROI 确认后，才允许进入自动检测：

```text
实拍原图
  → Session ROI
  → 裁剪当前 ROI 图像
  → 获取对应 Template ROI 图像
  → 图像预处理
  → AlgorithmRegistry
  → RoiInspectionAlgorithm
  → RoiInspectionResult
```

每个 ROI 配置必须明确：

- 检测目标；
- 算法类型；
- 算法参数；
- 阈值；
- `PASS / REVIEW / FAIL / ERROR` 的判定规则；
- 是否属于关键 ROI。

不得为每个页面重新建立独立算法框架。新增算法统一注册到现有 `AlgorithmRegistry` / `RoiInspectionAlgorithm` 体系，通过稳定的算法类型和参数对象调用。

### 9.1 V1 首个通用算法

第一版先选择一个能够跑通完整链路的通用差异检测算法，判断模板 ROI 和实拍 ROI 是否存在明显结构差异。

算法输出至少包含：

- `score`；
- 算法名称和版本；
- 实际输入图片引用；
- 使用的 ROI 坐标；
- 算法参数快照；
- 结果状态；
- 错误码和错误信息（如有）。

后续再逐步增加螺栓存在、孔位、螺纹和缺件等专项 Detector，不能改变既有检测调用协议。

### 9.2 结果状态

| 状态 | 含义 |
|---|---|
| `PASS` | 结构与模板基本一致，满足阈值 |
| `REVIEW` | 存在一定差异，需要人工确认 |
| `FAIL` | 差异明显，未满足检测要求 |
| `ERROR` | 图片、算法、参数或数据链路异常，不能得出可靠结论 |

`REVIEW`、`FAIL` 和 `ERROR` 必须独立显示，不能全部归类为“不合格”。

总体 Session 结果由业务规则聚合。例如任意关键 ROI 为 `FAIL` 时总体可为 `FAIL`；但 `ERROR` 的处理必须明确为检测异常，不能无提示地转换成 `FAIL`。

## 10. Inspection Session 和追溯

一次正式检测完成后形成一个 `InspectionSession`，至少关联：

- Part；
- 模板和 View；
- 模板版本或配置快照；
- 原始实拍图片；
- Template ROI；
- 实际 Session ROI；
- 算法名称、版本和参数；
- 每个 ROI 的 Score 和状态；
- 总体结果；
- 检测时间；
- 设备和应用版本（如业务需要）；
- 错误信息和人工复核信息。

必须保存配置快照，而不是只保存当前模板的外键。这样即使未来修改模板、ROI 或算法参数，历史记录仍能按照当时的检测条件解释。

检测记录页面作为一级入口，支持按以下条件查询：

- 零件；
- 时间范围；
- 总体结果；
- 关键 ROI 结果。

历史详情至少显示原始图、模板图、总体结果、逐 ROI 结果、算法、Score 和异常原因，并允许人工复核后记录最终确认结果。

当前工程的“检测结果”页面仍为空状态 shell；在 InspectionSession 完整写入之前，不得显示虚假的历史结果。

## 11. 结果包导出

结果形成完整 InspectionSession 和 ROI 结果后，复用已迁移的 `ResultPackager`，不重新发明第二套导出框架。

导出应生成一个完整结果包，而不是只导出一个 Excel 文件：

```text
result-package/
├── manifest
├── Excel
├── original-images/
├── result-images/
├── roi-images/
├── config-snapshot/
└── status-and-errors/
```

manifest 至少需要描述：

- 导出格式版本；
- Session 标识；
- Part、模板和 View；
- 每张图片的路径和校验信息；
- ROI、算法和参数快照；
- 总体及逐 ROI 状态；
- 错误信息；
- 生成时间和应用版本。

导出前必须检查所有必需文件存在、路径安全、内容可读，并在失败时给出可理解的错误信息。导出成功只能在结果包实际生成并验证后显示。

当前阶段不应添加导出按钮来制造空导出结果；待 InspectionSession 和 ROI 检测接通后再接入 ResultPackager。

## 12. DPM 与 OCR 边界

### 12.1 DPM

DPM 复用现有解码链和唯一 CameraController，不新增扫码算法或第二套 CameraX。

现场采集顶部入口保持现状：

- 只支持手机相机实时扫码；
- 已绑定码命中后切换已有 Part 和其有序 View；
- 从 View 1/N 重新开始；
- 未绑定码只提示先在模板配置绑定；
- 不在现场扫码流程中新建零件或录入未知零件；
- 模板配置支持扫码绑定或更换绑定；
- 已绑定到其他 Part 的码必须拒绝覆盖。

旧版生产行为基线必须保留：ZXing `DataMatrixReader` 主解码、中心 ROI、预处理策略轮转、双极性尝试、全图降采样、ML Kit DATA_MATRIX 兜底、帧节流、响应门、连续 miss 对焦和旧版网格兜底。

工程中的 `DPM_data/` 可用于离线基线和测试记录，但不能把相册图片导入当成现场实时扫码验收，也不能用离线结果伪造绑定成功。

### 12.2 OCR

钢印 OCR 已完成核心算法和 CameraX/UI 集成。OCR 是零件确定辅助能力，不替代模板加载、现场取景、拍照、比对和 ROI 检测主链路。

当前开发重点回到核心视觉检测闭环；DPM/OCR 在主流程完成后统一执行整机回归。

## 13. “我的”页面职责

“我的”保持为精简的工作台入口，V1 主要包含：

- 模板配置；
- 零件管理；
- 模板包管理；
- 应用设置；
- 版本和诊断信息。

模板配置负责维护标准模板、View 顺序、参考图片和 Template ROI。现场采集只负责选择 Part、加载模板、辅助取景和执行当前检测，不直接修改标准检测配置。

## 14. 分阶段实施顺序

按以下顺序推进，每一步都要在真实链路上验收：

### 阶段 0：当前基础能力保持

- 保持新包名和新工程边界。
- 保持 B1 相机基础、真实拍照和存储能力。
- 保持旧模板导入、View 顺序和透明叠加。
- 保持 DPM/OCR 已有实现，不重复开发。

### 阶段 1：拍后 Template / Capture 比对

- 新增独立比对页面。
- 接通直接切换、Alpha Overlay、Blink。
- 支持缩放和平移。
- 只展示真实模板图和真实实拍图。
- 验收每个 View 的图片对应关系和页面返回路径。

### 阶段 2：Session ROI

- 明确模板图和实拍图的坐标映射。
- 从 Template ROI 生成 Session ROI。
- 支持移动、宽度和高度微调。
- 保存当前 Session 的 ROI 快照。
- 验收不修改模板 ROI，页面离开和重复进入状态正确。

### 阶段 3：首个 Detector

- 实现一个通用结构差异 Detector。
- 接入 `AlgorithmRegistry`。
- 定义 score、阈值和四态结果。
- 对图片异常、ROI 越界、算法异常返回 `ERROR`。

### 阶段 4：InspectionSession 和结果详情

- 写入完整 Session 和逐 ROI 记录。
- 保存模板、ROI、算法和参数快照。
- 建立检测结果列表和详情。
- 支持人工复核记录。

### 阶段 5：ResultPackager 和整机验收

- 复用 ResultPackager 生成完整结果包。
- 校验 manifest、Excel、图片、ROI 和配置快照。
- 完成 DPM、OCR、检测主链整机回归。
- 使用新包名门禁完成真机验收。

## 15. 测试与验收要求

### 15.1 自动化测试

至少覆盖：

- Room migration 和数据关系；
- manifest order、缺失 order、重复 order 和 flat directory；
- Part 与 DPM 查询、更新和冲突绑定；
- View 顺序查询和重复导入稳定性；
- 图片导入、损坏图片、空文件和临时文件清理；
- CameraController 模式切换、生命周期和资源释放；
- 拍照并发、取消、超时和页面离开；
- Template / Capture 比对状态；
- ROI 映射、边界和 Session ROI 不回写模板；
- Detector 四态结果和异常路径；
- InspectionSession 快照完整性；
- ResultPackager 文件完整性和失败回滚。

### 15.2 真机验收

真机验收必须使用：

```text
设备：HONOR YAL-AL10
serial：ERLDU20429005890
新包：com.wearable.inspection.mobile
启动组件：com.wearable.inspection.mobile/.MainActivity
```

每轮验收前必须停止新旧包、显式安装当前主 APK，并使用完整组件名启动。启动后检查：

- 新包存在；
- 新包 PID 非空；
- 旧包 PID 为空；
- 前台 Activity 为新包 MainActivity。

截图、交互和日志证据必须同时记录：实际文件路径、包名、APK SHA-256、采集步骤和时间。

图片结构化证据可以证明页面层级、控件 bounds 和运行状态，但不能替代对颜色、裁切、拉伸、遮挡和整体美观度的人工视觉复核。

### 15.3 V1 闭环验收标准

完整闭环至少应满足：

```text
模板导入 / 配置成功
  → Part 选择成功
  → View 1 到 View N 顺序正确
  → 每个 View 生成真实照片
  → Template / Capture 比对可用
  → Session ROI 可确认
  → 至少一个 Detector 真正运行
  → PASS / REVIEW / FAIL / ERROR 正确显示
  → InspectionSession 可追溯
  → ResultPackager 生成并校验完整结果包
```

任何一步使用固定假图、固定假坐标、假 `ALIGNED`、假检测结果或假导出成功，都不能计为闭环通过。

## 16. 当前实现状态摘要

### 已完成

- 新工程和新包名边界；
- B1 相机基础能力；
- 真实拍照和图片私有存储；
- 旧模板导入和模板图片透明叠加 MVP；
- View 顺序持久化、导入和查询；
- Part 选择、按序现场采集和完成提示；
- DPM 迁移软件链和模板配置绑定流程；
- 钢印 OCR 核心及 CameraX/UI 集成。

### 当前阶段继续暂缓

- 拍后比对页面；
- Template ROI 配置和 Session ROI 微调；
- Detector 接入和 PASS / REVIEW / FAIL / ERROR 结果；
- InspectionSession 完整写入；
- 追溯记录详情；
- 完整结果包导出。

## 17. V1 判断标准

当前 V1 的唯一主线是：

```text
模板能用
→ 人能对准
→ 照片能比
→ ROI 能调
→ 算法能跑
→ 结果能存
→ 历史能查
→ 数据能导出
```

先完成一条真实、可解释、可追溯的业务链，再增加高级视觉算法。任何新增功能都必须说明它服务于哪一个闭环节点、依赖哪些真实数据、如何验收，以及是否会破坏当前唯一 CameraController、模板坐标和历史追溯语义。
