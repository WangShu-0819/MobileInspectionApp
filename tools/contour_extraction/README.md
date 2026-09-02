# DCIM 零件外轮廓提取

离线批量工具，融合旧 V3/V4 的前景约束与几何输出思路。它输出可供后续相机投影使用的归一化轮廓点，不接入当前 Android 相机链路。

统一使用已有 Conda 环境 `D:\ProgramData\anaconda3\envs\dinov2`，不要在本目录重新创建 `.venv` 或复制依赖包：

```powershell
& "D:\ProgramData\anaconda3\envs\dinov2\python.exe" extract_contours.py `
  "D:\study\Textile_defects\Wearable Inspection\DCIM\DCIM" output
```

每张图片输出：

- `*_mask.png`：二值前景；
- `*_overlay.jpg`：黄色外轮廓叠加预览；
- `*_contour.json`：归一化轮廓点和质量指标。

`manifest.json` 汇总所有结果。结果会标记为 `NEEDS_CONFIRMATION` 或 `NEEDS_RECAPTURE`，只能作为候选 mask，不能据此生成检测通过结论。

传统批次算法使用 `extract_contours_traditional.py`。它先执行 Otsu/Canny 外轮廓提取，失败时才使用中央前景约束的 GrabCut。青色检验标记只是候选评分的低权重辅助，缺失时算法照常执行；只有“亮色内芯 + 黑色圆形安装座”的局部安全扩张要求存在有效标记和可信闭合圆环。最终结果只保留一个主要连通区域并平滑小裂缝，不再把零件之间的大面积背景空隙填成工件。

质量门禁会用覆盖率、边界余量、轮廓实心度和大空隙比例识别明显的高光碎片、截边或背景误纳风险。`NEEDS_RECAPTURE` 结果不得作为投影模板；`NEEDS_CONFIRMATION` 仍需目视确认，因为在多个相邻黑色零件与深色背景接触时，传统像素分割无法判断业务上指定的是哪一个零件。

当前批次运行命令：

```powershell
& "D:\ProgramData\anaconda3\envs\dinov2\python.exe" extract_contours_traditional.py `
  "D:\study\Textile_defects\Wearable Inspection\DCIM\DCIM" key_region_output
```

相机对准使用稀疏结构引导线，而不是填充 mask：

```powershell
& "D:\ProgramData\anaconda3\envs\dinov2\python.exe" extract_alignment_guides.py `
  "D:\study\Textile_defects\Wearable Inspection\DCIM\DCIM" alignment_guide_output
```

每张可靠模板输出透明 `*_guide.png`、预览 `*_guide_overlay.jpg` 和归一化折线
`*_guide.json`。质量门禁失败的图片输出 `RECAPTURE_REQUIRED`，不生成误导工人的线稿。
