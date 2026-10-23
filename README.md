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
  - 个人纪录（PR）排行
- **WebDAV 备份**：整库备份 / 恢复 / 保留最近 5 份；支持训练完成后自动备份
- **CSV 导出**：训练记录导出为 CSV，方便二次分析
- **深色模式**：跟随系统 / 浅色 / 深色
- **单位切换**：kg / lb

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

## 构建

环境要求：JDK 17+、Android SDK（compileSdk 35）。

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

## 路线图

- [ ] 训练过程中的组间休息通知（后台计时）
- [ ] 训练计划 / 周期化
- [ ] 1RM 估算与进步曲线
- [ ] 数据导入（CSV）
- [ ] 小组件（今日训练概览）

## 致谢

- [MIUIX](https://github.com/compose-miuix-ui/miuix) — HyperOS 风格 Compose UI 组件库
- 数据模型与交互设计参考了 [flandyw/Workout](https://github.com/flandyw/Workout)、[mihretu-dev/HomeWorkoutApp](https://github.com/mihretu-dev/HomeWorkoutApp) 等优秀开源健身项目

## License

[Apache-2.0](LICENSE)
