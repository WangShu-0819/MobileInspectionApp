# JUnit XML 汇总

## 全量 JVM 测试

- 命令：`gradlew.bat testDebugUnitTest assembleDebug`
- Gradle 结果：`BUILD SUCCESSFUL`，退出码 `0`
- XML suite 数：`103`
- Tests：`1497`；Failures：`0`；Errors：`0`；Skipped：`5`
- 完整逐 suite 计数：`junit_unit_test_summary.json`

| 相关 suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| RoiSimilarityFallbackTest | 5 | 0 | 0 | 0 |
| InspectionExcelExporterTest | 20 | 0 | 0 | 0 |
| InspectionZipExportArchiveTest | 4 | 0 | 0 | 0 |
| RoiEvidenceExportTest | 9 | 0 | 0 | 0 |
| DpmScanEvidenceContractTest | 24 | 0 | 0 | 0 |

## 当前真实图像 Android parity

XML：`connected_real_image_parity.xml`

```text
tests=2 failures=0 errors=0 skipped=0
timestamp=2026-09-28T08:21:56 UTC
device=SEA-AL10 - 10
cases=exp23RealImageMatchesDesktopNcnn, exp22RealImageMatchesDesktopNcnn
Gradle exit code=0
```

同一轮的详细运行数据见 `current_production_jni_exp22.json`、`current_production_jni_exp23.json`；原始 Android logcat 行保存在 `current_real_image_parity_logcat.txt`。
