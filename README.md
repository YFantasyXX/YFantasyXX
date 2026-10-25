# YFantasyXX

一个用 **MIUIX（HyperOS 风格 Compose UI）** 打造的 Android 健身训练记录 App。

本地优先：所有数据存在自己的手机里（Room / SQLite），不注册账号、不上传第三方服务器；需要多端备份时，可接入**你自己的 WebDAV 网盘**（坚果云 / Nextcloud / InfiniCLOUD 等）。

## 功能

- **训练记录**：开始空训练或从模板开始，逐个动作记录组数、重量、次数，自动记录训练时长
- **组间休息计时**：完成一组自动开始倒计时（默认 90s，可调），支持 +30s / 跳过
- **动作库**：内置 775 个动作（胸 / 背 / 腿 / 肩 / 手臂 / 核心 / 有氧 / 全身），带主次肌群；支持搜索、分类筛选、自定义动作
- **训练模板**：把常练的动作组合存成模板（如"推日""腿日"），一键开练；可设目标次数区间与进阶策略
- **训练中快捷操作**：
  - **复制上一组**：一键把上组重量次数填进下一组
  - **替换动作**：器械被占时换成同类动作，已完成的组全部保留
  - **杠铃片计算器**：按当前重量反推每边挂什么片（杆重与配片可设）
  - **超级组**：两个动作交替做，只在组末休息
  - **拖动排序**：按住左侧把手上下拖，贴边自动滚动，相邻卡片实时让位
- **自动进阶**：模板动作可选「线性加重」或「双重进阶」，训练页按上次表现给出本次该上的重量，一键应用
- **历史记录**：日历视图 + 搜索（训练名 / 动作名），可删除、可分享成卡片
- **分享卡片**：一次训练的时长 / 容量 / 亮点动作生成图片分享
- **数据统计**：
  - 近 8 周训练容量柱状图
  - 近 30 天部位训练分布
  - 近 7 天肌群负荷热力图（主肌群记 1 组、次要肌群记 0.5 组，含恢复状态）
  - 体重趋势折线图
  - 个人纪录（PR）排行：按**估算 1RM** 排序，100 kg × 10 强于 110 kg × 3
- **e1RM 曲线**：动作详情页画估算 1RM 趋势，一次训练一个点，能看出有没有真的在进步；曲线会随着训练记录变多，自动从「群体均值」换成「按你自己历史校准的版本」
  - 换算按动作类型分档（硬拉 / 深蹲 / 推 / 拉 / 孤立），不是一条曲线套所有动作：同一次数下硬拉离 1RM 比卧推更远，用同一条线算会低估硬拉十几个百分点
- **周目标与轮换**：设每周训练次数目标（首页显示进度）；模板文件夹可标记为「轮换计划」，练完 A 自动提示下一个 B
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
│   ├── seed/            # 内置动作库种子数据（775 条，末段由脚本生成）
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

```
tools/
└── exercise-import/     # 动作库导入脚本 + 数据来源说明
```

## 动作数据来源

内置动作库分两部分：

| 来源 | 条数 | 许可 |
| --- | --- | --- |
| 手写整理（常用动作，按训练习惯命名与划分肌群） | 118 | 本项目 |
| [hasaneyldrm/exercises-dataset](https://github.com/hasaneyldrm/exercises-dataset) + [简体中文语言包](https://github.com/marcomarcogd/exercises-dataset-zh-cn) | 657 | MIT |

数据集共 1324 条，其中 704 条有经过审校的简体中文译名，去掉与手写部分重复的之后剩 657 条。
**只取文字字段（部位 / 器械 / 目标肌 / 协同肌 / 译名），没有使用任何图片或 GIF**——
那部分媒体的版权归 Gym visual，有独立的使用限制，本项目不涉及。

中文名不做机器翻译补全，所以 id ≥ 1000 的 620 条新动作没有收录。
生成过程可复现，见 [`tools/exercise-import/`](tools/exercise-import/)。

## 借鉴与参考

训练智能与交互细节的一部分，是读完 [DuarteSantos8/openGym](https://github.com/DuarteSantos8/openGym) 的源码之后重新设计的。
对方是 React + Node 的 Web 应用、AGPL-3.0；这里是 Kotlin + Compose + Room、Apache-2.0，
**没有复制任何代码**，借鉴的是思路、公式与判定方式，实现全部重写。

| 本项目位置 | 借鉴了什么 | 具体实现 |
| --- | --- | --- |
| `data/LiftPattern.kt` + `data/OneRepMax.kt` | 单条 1RM 公式只在低次数区间可信，应多公式加权融合 | 分档优先：`LiftPattern` 给每类动作一张「次数 → 占 1RM 比例」表（硬拉 / 深蹲 / 推 / 拉 / 孤立），`1RM = 重量 / 比例`；分档不明时才回落到七条公式（Epley / Brzycki / Lombardi / O'Conner / Wathan / Lander / Mayhew）按固有可靠度加权；超过 12 次返回 `null`，不猜 |
| `data/RepMaxCalibration.kt` | 群体均值曲线对个人未必准（有人天生 rep-max 更平），但直接拟合重量又不可比 | 用**幂变换** `table(r)^γ` 保留原表的凸形状，只调「掉得多快」；取对数后是直线 `ln w = ln 1RM + γ·ln table(r)`，单个动作做最小二乘求 γ；跨动作只合并**无量纲的 γ**（组数加权中位数），不合并重量；γ 向 1 收缩，攒够 3 个动作才完全采信 |
| `WorkoutRepository` 的 PR 口径 | 力量指标用**估算 1RM**排序，而不是最大重量 | 100 kg × 10（推类，e1RM ≈ 143）排在 110 kg × 3（≈ 120）之前；PR 卡片上的「最佳组」和「e1RM」来自同一组，不再出现「最大重量 × 最大次数」这种从未发生过的组合 |
| `ui/workout/WorkoutDragSort.kt` | 拖动排序在**起手时一次性快照**所有行的几何，整场手势只读快照；落点用「数有多少行的中心在我之上」 | `buildDragSnapshot()` + `DragSnapshot.targetFor()`。原来是每帧读实时布局，容易形成「读布局 → 改视觉 → 再读」的反馈环；改成快照后反馈环从设计上消失，落点判据也从上下两个分支收敛成一句 `count { 中心在我之上 }` |
| `data/Progression.kt` | 进阶引擎的判定顺序：先看上次是否全部摸到次数上限，再决定「加重」还是「加次数」；下肢的绝对增量大于上肢 | `Progression.suggest()` 是纯函数：`allHitTop` 时才加重并把次数打回下限，否则按 double / linear 分别给「次数 +1」或「重量不变重来」；深蹲一类 +5 kg、上肢 +2.5 kg |
| `WorkoutRepository.muscleLoads()` | 疲劳度要按肌群累加，且**次要肌群也要计入**（否则卧推只算胸） | 主肌群记 1 组、次要肌群记 0.5 组，窗口 7 天；再按「最近一次训练距今多久」给出恢复中 / 恢复期 / 可练 |

补充两点说明：

- 拖动排序的快照方案不能直接照搬到 `LazyColumn`——它只对非虚拟化列表成立。这里做了改造：只快照**当前可见**的 item，下标加上 `baseIndex` 基址，因此拖出屏幕外的部分不会被误算。
- 快照方案还有一个已知边界：**拖不到可视区之外**。解决办法是贴边自动滚动——滚动后重拍快照，并把「槽位走动」折算成视觉位移的补偿（`scrollCompensation`），三者必须一起对齐，否则卡片会从手指底下溜走。
- e1RM 的曲线分两层：底层是**群体均值**（常用 RPE 对照表 + Richens & Cleather 2014 对深蹲 / 卧推 / 硬拉 rep-max 关系的量级），上层是**个人校准**（`RepMaxCalibration`）。校准有明确的冷启动边界：一个动作至少要有两个次数档、且跨度 ≥ 3 次，才连得出斜率；整档攒够 3 个动作才完全采信个人数据，之前都向群体均值收缩。所以新装或刚加的动作一律先按群体曲线走——不是没生效，是样本不够。

## 路线图

- [x] 训练过程中的组间休息通知（后台计时）
- [x] 1RM 估算（按动作类型分档查表，分档不明时七公式加权融合；PR 按 e1RM 排序）
- [x] e1RM 个人校准（用自己不同次数档的最好成绩拟合 rep-max 曲线，样本不足时向群体均值收缩）
- [x] e1RM 进步曲线（动作详情页）
- [x] 自动进阶（线性 / 双重进阶）
- [x] 肌群热力图与恢复状态
- [x] 计划轮换（A/B/C 顺序循环）
- [x] 超级组
- [ ] 训练周期化（把轮换扩展成 4~6 周一个中周期，带减载周）
- [ ] 数据导入（CSV）
- [ ] 小组件（今日训练概览）
- [ ] 动作动图 / 教学视频（受包体积限制，考虑按需下载）

## 致谢

- [MIUIX](https://github.com/compose-miuix-ui/miuix) — HyperOS 风格 Compose UI 组件库
- [DuarteSantos8/openGym](https://github.com/DuarteSantos8/openGym) — 1RM 估算与拖动排序的设计参考（见上节）
- 数据模型与交互设计参考了 [flandyw/Workout](https://github.com/flandyw/Workout)、[mihretu-dev/HomeWorkoutApp](https://github.com/mihretu-dev/HomeWorkoutApp) 等优秀开源健身项目

## License

[Apache-2.0](LICENSE)
