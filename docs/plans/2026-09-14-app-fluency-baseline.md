# App 端流畅度优化 · 基线测量归档

> 测量日期：2026-09-14 ｜ 构建：release（`103649e` + Minor 修复 `cbe1463`，minify 已开，debug 签名）
> 设备：89a5f9e1（24122RKC7C，Android 16 / 澎湃 OS）｜ 采集：`docs/plans/perf-capture.sh`

## 三场景基线数据

| 场景 | 指标 | 实测 | 目标 | 结论 |
|---|---|---|---|---|
| 冷启动 | `TotalTime` 中位数（3 次） | **229ms**（234/229/190） | <1500ms | ✅ 优秀 |
| 记账页滚动（人工 5 屏） | Janky / 90th 帧 | **0.18% / 9ms** | <5% / <32ms | ✅ 优秀 |
| 底部 tab 切一圈 | Janky / 90th 帧 | **0.00% / 8ms** | <5% / <32ms | ✅ 优秀 |

辅助数据：
- 采集滚动基线时 `gfxinfo reset` 输出的历史统计（app 日常使用累计 1875 帧）：Janky 0.11%、90th 9ms，与场景测量一致。
- 渲染管线 Skia (Vulkan)，GPU 90th 8ms，非瓶颈。
- 对照组：debug 包 `am start -W` TotalTime 4764ms——与 release 的 229ms 相差约 20 倍，差值为 debug 构建固有开销。

## 结论

1. **release 包三场景全部远优于目标线，无真实性能问题**。冷启动无需 Baseline Profile（计划 Task 6 判定标准：收益 <15% 不引入——229ms 已无下降空间需求）。
2. 「进入 app 掉帧」的体感可归因于 **debug 包**：开发期设备上安装的就是 DEBUGGABLE 包，Compose debug 检查 + 无 R8 优化导致冷启动 4.7s、长尾帧严重（90th 150ms）。日常使用请安装 release 包（`./gradlew :app:installRelease`）。

## 对计划的影响（测量驱动纪律：测量没指向的不做）

- Task 3（主题同步直读）：性能前提消失。唯一残留价值是消除冷启动首帧主题闪变——但 229ms 的冷启动里 DataStore 首读窗口极短，是否肉眼可感知待确认（见「待确认」）。
- Task 4（Room/Coil 后台预热）：229ms 冷启动下无可期收益，**不做**。
- Task 5（contentType）：滚动 0.18% Janky 无优化空间，**不做**。
- Task 6（中期复测）：无修复项需要复测，Baseline Profile 已判定不引入，**取消**。
- Task 7（nav 重组面收窄 / blur 收敛）：条件不满足，**跳过**。
- Task 8（最终回归）：无代码改动，仅保留基线归档与分支收尾。

## 待确认（已确认）

**2026-09-14 用户确认：release 包滚动/切页/冷启动体感已无卡顿。** 据此：

- Task 3（主题同步直读）**不做**：性能前提不成立，229ms 冷启动下主题闪变窗口不可感知，用户亦未报告闪变；保留 Flow 热更新路径原状。
- 计划 Task 3-7 全部取消/跳过（理由见上节），无代码修复项落地。
- 最终收尾：单测全绿确认 + `perf/app-fluency` 合并回 main。
- **最大实际成果**：日常设备改用 release 包（此前为 debug 包），冷启动 4.7s → 0.23s。后续开发期请保持「设备日常用 release、验证用 release」的习惯，debug 包只用于断点调试。
