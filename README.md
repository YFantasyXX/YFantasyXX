# YFantasyXX

一个用 **MIUIX（HyperOS 风格 Compose UI）** 打造的 Android 健身训练记录 App。

本地优先：所有数据存在自己的手机里（Room / SQLite），不注册账号、不上传第三方服务器；需要多端备份时，可接入**你自己的 WebDAV 网盘**（坚果云 / Nextcloud / InfiniCLOUD 等）。

## 功能

- **训练记录**：开始空训练或从模板开始，逐个动作记录组数、重量、次数，自动记录训练时长
- **组间休息计时**：完成一组自动开始倒计时（默认 90s，可调），支持 +30s / 跳过
- **动作库**：内置 50+ 常见动作（胸 / 背 / 腿 / 肩 / 手臂 / 核心 / 有氧 / 全身），支持搜索、分类筛选、自定义动作
- **训练模板**：把常练的动作组合存成模板（如"推日""腿日"），一键开练
- **历史记录**：按时间查看每次训练的完整组记录，可删除
- **数据统计**：
  - 近 8 周训练容量柱状图
  - 近 30 天部位训练分布
  - 体重趋势折线图
  - 个人纪录（PR）排行：按**估算 1RM** 排序，100 kg × 10 强于 110 kg × 3
- **动作排序**：训练中按住左侧把手上下拖动即可调整动作顺序，相邻卡片实时让位，松手滑入空位
- **WebDAV 备份**：整库备份 / 恢复 / 保留最近 5 份；支持训练完成后自动备份
- **CSV 导出**：训练记录导出为 CSV，方便二次分析
- **悬浮底栏**：胶囊形底部导航，可选毛玻璃（默认关闭，开启会有额外 GPU 开销）
- **深色模式**：跟随系统 / 浅色 / 深色
- **单位切换**：kg / lb
- **高刷新率**：启动时请求屏幕最高刷新率（设置页可查看当前实际 Hz）

## UI

界面完全使用 [MIUIX](https://github.com/compose-miuix-ui/miuix) 组件构建，视觉风格贴近小米 HyperOS：大标题顶栏、卡片式分组、squircle 圆角、HyperOS 蓝主题。

## 技术栈

| 模块 | 方案 |
| --- | --- |
| 语言 | Kotlin |
| UI | Jetpack Compose + [MIUIX](https://github.com/compose-miuix-ui/miuix) `0.9.1` |
| 架构 | MVVM + 单向数据流，手动依赖容器（无 Hilt） |
| 数据库 | Room `2.8.x` |
| 导航 | Navigation Compose |
| 异步 | Kotlin Coroutines / Flow |
| 网络 | OkHttp `5.x`（自实现轻量 WebDAV 客户端） |
| 偏好存储 | DataStore Preferences + AndroidKeyStore 加密密码 |
| 构建 | AGP `8.13.x` / Gradle `8.14.3` / Kotlin `2.3.20` |
| 运行要求 | Android 12L+（minSdk 32）、compileSdk 37 |

## 构建

环境要求：JDK 17+、Android SDK Platform 37。

> 本应用 minSdk 为 **32（Android 12L）**：悬浮底栏的毛玻璃效果依赖 `miuix-blur`，
> 该库要求 API 32+。如需支持更低版本，可移除 `miuix-blur` 依赖并把底栏改为纯色。

```bash
git clone https://github.com/YFantasyXX/YFantasyXX.git
cd YFantasyXX
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> Wrapper 的 `distributionUrl` 指向腾讯云 Gradle 镜像，国内下载更快；如在海外，可改回 `https://services.gradle.org/distributions/gradle-8.14.3-bin.zip`。
>
> 首次构建请确保 `local.properties` 中 `sdk.dir` 指向你的 Android SDK。

## WebDAV 配置

设置页 → WebDAV 同步，填入：

| 服务 | 服务器地址示例 |
| --- | --- |
| 坚果云 | `https://dav.jianguoyun.com/dav/` （需在坚果云「安全选项」生成**应用密码**） |
| Nextcloud | `https://your-domain/remote.php/dav/files/用户名/` |
| InfiniCLOUD | `https://your-id.teracloud.jp/dav/` |

备份文件保存于该目录下的 `YFantasyXX/` 子目录，文件名为 `yfantasyxx-backup-yyyyMMdd-HHmmss.db`，仅保留最近 5 份。恢复会覆盖本地数据库，恢复后应用自动重建数据库连接。

> 密码使用 AndroidKeyStore（AES/GCM）加密后保存在本机 DataStore，不会明文落盘。

## 项目结构

```
app/src/main/java/com/yfantasyxx/
├── data/
│   ├── db/              # Room 实体、DAO、数据库
│   ├── prefs/           # DataStore 设置 + 密码加密
│   ├── seed/            # 内置动作库种子数据
│   └── WorkoutRepository.kt
├── sync/
│   ├── WebDavClient.kt  # 极简 WebDAV 客户端（PROPFIND/MKCOL/PUT/GET/DELETE）
│   └── BackupManager.kt # 整库备份 / 恢复 / 清理
├── ui/
│   ├── home/            # 今日概览
│   ├── workout/         # 训练进行中（组记录 + 休息计时）
│   ├── exercises/       # 动作库与动作详情
│   ├── templates/       # 训练模板
│   ├── history/         # 历史与单次训练详情
│   ├── stats/           # 图表统计与 PR
│   ├── settings/        # 设置与 WebDAV
│   ├── components/      # 通用组件 + Canvas 图表
│   └── theme/           # MIUIX 主题
└── YFantasyXXApplication.kt
```

## 借鉴与参考

训练智能与交互细节的一部分，是读完 [DuarteSantos8/openGym](https://github.com/DuarteSantos8/openGym) 的源码之后重新设计的。
对方是 React + Node 的 Web 应用、AGPL-3.0；这里是 Kotlin + Compose + Room、Apache-2.0，
**没有复制任何代码**，借鉴的是思路、公式与判定方式，实现全部重写。

| 本项目位置 | 借鉴了什么 | 具体实现 |
| --- | --- | --- |
| `data/OneRepMax.kt` | 单条 1RM 公式只在低次数区间可信，应多公式加权融合；次数越高越不该给数字 | 七条公式（Epley / Brzycki / Lombardi / O'Conner / Wathan / Lander / Mayhew）按可靠度加权，整体置信度随次数衰减；超过 12 次返回 `null`，不猜 |
| `WorkoutRepository` 的 PR 口径 | 力量指标用**估算 1RM**排序，而不是最大重量 | 100 kg × 10（e1RM ≈ 131）排在 110 kg × 3（≈ 119）之前；PR 卡片上的「最佳组」和「e1RM」来自同一组，不再出现「最大重量 × 最大次数」这种从未发生过的组合 |
| `ui/workout/WorkoutDragSort.kt` | 拖动排序在**起手时一次性快照**所有行的几何，整场手势只读快照；落点用「数有多少行的中心在我之上」 | `buildDragSnapshot()` + `DragSnapshot.targetFor()`。原来是每帧读实时布局，容易形成「读布局 → 改视觉 → 再读」的反馈环；改成快照后反馈环从设计上消失，落点判据也从上下两个分支收敛成一句 `count { 中心在我之上 }` |

补充两点说明：

- 拖动排序的快照方案不能直接照搬到 `LazyColumn`——它只对非虚拟化列表成立。这里做了改造：只快照**当前可见**的 item，下标加上 `baseIndex` 基址，因此拖出屏幕外的部分不会被误算。
- 疲劳度模型、进阶引擎（`nextPrescription`）等设计也读过，但尚未实现，列在下方路线图里。

## 路线图

- [ ] 训练过程中的组间休息通知（后台计时）
- [ ] 训练计划 / 周期化
- [x] 1RM 估算（七公式加权融合，PR 按 e1RM 排序）
- [ ] e1RM 进步曲线
- [ ] 自动进阶（线性 / 双重进阶）
- [ ] 数据导入（CSV）
- [ ] 小组件（今日训练概览）

## 致谢

- [MIUIX](https://github.com/compose-miuix-ui/miuix) — HyperOS 风格 Compose UI 组件库
- [DuarteSantos8/openGym](https://github.com/DuarteSantos8/openGym) — 1RM 估算与拖动排序的设计参考（见上节）
- 数据模型与交互设计参考了 [flandyw/Workout](https://github.com/flandyw/Workout)、[mihretu-dev/HomeWorkoutApp](https://github.com/mihretu-dev/HomeWorkoutApp) 等优秀开源健身项目

## License

[Apache-2.0](LICENSE)
