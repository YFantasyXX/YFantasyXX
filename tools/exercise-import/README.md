# 动作库导入工具

`ExerciseSeed.kt` 里 `// ==== GENERATED:BEGIN ====` 与 `END` 之间的内容由这里的脚本生成，
**不要手改**，下次生成会覆盖。手写的 118 条在生成块之前，不受影响。

## 数据来源

| 用途 | 仓库 | 许可 |
| --- | --- | --- |
| 动作本体（部位 / 器械 / 目标肌 / 协同肌） | [hasaneyldrm/exercises-dataset](https://github.com/hasaneyldrm/exercises-dataset) | MIT |
| 简体中文动作名 | [marcomarcogd/exercises-dataset-zh-cn](https://github.com/marcomarcogd/exercises-dataset-zh-cn) | MIT |

**只取文字数据，不取任何媒体。** 两个仓库里的缩略图和 GIF 都来自 Gym visual，
有独立的媒体授权限制（180×180、必须署名），本项目不使用，因此也不受其约束。
`ATTRIBUTION` 一节仍保留来源说明。

## 为什么只用有中文名的部分

数据集 1324 条，中文名包只覆盖 704 条（id 0001–0999 区间，人工审校过）。
剩下 620 条是 id ≥ 1000 的新增动作，多数是拉伸、辅助器械和 `(male)` 变体，没有译名。
不做机器翻译硬凑数量——「动作库」里塞满机翻怪名，比条数少更难用。

## 用法

```bash
# 1. 取数据（约 18 MB，不入库）
mkdir -p .data && cd .data
curl -L -o eds.json https://raw.githubusercontent.com/hasaneyldrm/exercises-dataset/main/data/exercises.json
curl -L -o zhcn.js  https://raw.githubusercontent.com/marcomarcogd/exercises-dataset-zh-cn/main/locales/zh-CN.js
cd ..

# 2. 只看统计，不写文件
python3 gen_exercise_seed.py

# 3. 写回 ExerciseSeed.kt（幂等，可重复执行）
python3 gen_exercise_seed.py --emit
```

数据目录默认是 `./.data`，可用环境变量 `EXDB_DIR` 覆盖。

## 映射规则

- **分类**：`body_part` → `ExerciseCategory`。`neck` 并入「肩」（没有「颈」分类）。
- **器械**：28 种归并到项目的 8 种。`assisted` / `sled machine` / `smith machine` 都算「器械」，
  `ez barbell` / `trap bar` / `olympic barbell` 都算「杠铃」。
- **器械以名称为准**：源数据里「固定器械俯身划船」的 `equipment` 填的是 `barbell`，
  名字和字段打架时按名字走。
- **肌群**：`target` / `secondary_muscles` 全部映射到 `ExerciseSeed` 已在用的中文词。
  这一步不能省——热力图按肌群名聚合，出现「胸大肌」和「胸肌」两个词就会裂成两格。
  `delts` 按动作名细分到前/中/后束。
- **去重**：剥掉开头器械词后核心名完全相等的，视为与手写的 118 条重复，跳过。
  判据必须收得紧——按「子串包含」判会把「大猩猩式引体向上」当成「引体向上」删掉。
- **分档**：不在脚本里做，`ex()` 调用 `LiftPattern.infer()` 推断。

## 已知瑕疵

源数据有几处本身标错，脚本只修了能确认的（腕弯举被标成 `biceps`、提踵被标成 `glutes`、
「绳索单臂俯身侧平举」被归到胸）。其余按原样保留——比动作名对不上更糟的是猜错。
