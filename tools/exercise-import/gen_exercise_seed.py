# -*- coding: utf-8 -*-
"""把 exercises-dataset + zh-CN 中文名包 转成本项目的种子条目。

只做数据搬运与字段映射，不做翻译：中文名全部来自 marcomarcogd 的审校包。
用法见同目录 README.md：python gen_exercise_seed.py [--emit]
"""
import os
import re
import sys
import json
import collections

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get('EXDB_DIR', os.path.join(HERE, '.data'))
SRC = os.path.join(DATA, 'eds.json')
ZH = os.path.join(DATA, 'zhcn.js')
OUT = os.path.join(DATA, 'review.json')
PROJ = os.path.abspath(os.path.join(HERE, '..', '..'))
SEED = os.path.join(PROJ, 'app/src/main/java/com/yfantasyxx/data/seed/ExerciseSeed.kt')

# ---------- 1. 中文名 ----------
src = open(ZH, encoding='utf-8').read()
seg = src[src.find('"0001": {'):]
name_re = re.compile(r'"(\d{4})":\s*\{\s*"name":\s*"((?:[^"\\]|\\.)*)"')
zh = {k: v for k, v in name_re.findall(seg)}

eds = json.load(open(SRC, encoding='utf-8'))

# ---------- 2. 分类 / 器械 ----------
CATEGORY = {
    'chest': 'CHEST', 'back': 'BACK',
    'upper legs': 'LEGS', 'lower legs': 'LEGS',
    'shoulders': 'SHOULDERS', 'neck': 'SHOULDERS',
    'upper arms': 'ARMS', 'lower arms': 'ARMS',
    'waist': 'CORE', 'cardio': 'CARDIO',
}

EQUIPMENT = {
    'barbell': 'BARBELL', 'ez barbell': 'BARBELL',
    'olympic barbell': 'BARBELL', 'trap bar': 'BARBELL',
    'dumbbell': 'DUMBBELL', 'cable': 'CABLE',
    'leverage machine': 'MACHINE', 'smith machine': 'MACHINE',
    'sled machine': 'MACHINE', 'assisted': 'MACHINE',
    'upper body ergometer': 'MACHINE', 'skierg machine': 'MACHINE',
    'stationary bike': 'MACHINE', 'elliptical machine': 'MACHINE',
    'stepmill machine': 'MACHINE',
    'body weight': 'BODYWEIGHT', 'kettlebell': 'KETTLEBELL',
    'band': 'BAND', 'resistance band': 'BAND',
}

# ---------- 3. 肌群：全部落到现有种子已经在用的词 ----------
TARGET = {
    'abs': '腹直肌', 'pectorals': '胸大肌', 'biceps': '肱二头肌',
    'triceps': '肱三头肌', 'glutes': '臀大肌', 'upper back': '中背部',
    'lats': '背阔肌', 'calves': '小腿', 'quads': '股四头肌',
    'forearms': '前臂', 'cardiovascular system': '心肺',
    'hamstrings': '腘绳肌', 'spine': '竖脊肌', 'traps': '斜方肌',
    'abductors': '臀中肌', 'adductors': '内收肌群',
    'serratus anterior': '前锯肌', 'levator scapulae': '肩胛提肌',
}

SECONDARY = {
    'abdominals': '腹直肌', 'back': '中背部', 'biceps': '肱二头肌',
    'brachialis': '肱肌', 'calves': '小腿', 'chest': '胸大肌',
    'core': '核心', 'deltoids': '三角肌中束', 'forearms': '前臂',
    'glutes': '臀大肌', 'groin': '内收肌群', 'hamstrings': '腘绳肌',
    'hip flexors': '髂腰肌', 'inner thighs': '内收肌群',
    'latissimus dorsi': '背阔肌', 'lats': '背阔肌', 'lower abs': '下腹',
    'lower back': '竖脊肌', 'obliques': '腹斜肌',
    'quadriceps': '股四头肌', 'rear deltoids': '三角肌后束',
    'rhomboids': '菱形肌', 'rotator cuff': '肩袖', 'shoulders': '三角肌中束',
    'soleus': '比目鱼肌', 'trapezius': '斜方肌', 'traps': '斜方肌',
    'triceps': '肱三头肌', 'upper back': '中背部', 'upper chest': '上胸',
    'wrist extensors': '前臂', 'wrist flexors': '前臂',
}

# 进热力图的词只留常规训练肌群；足/手/踝/小腿前侧/胸锁乳突肌是噪声
SECONDARY_DROP = {'小腿前侧', '胸锁乳突肌'}

# ---------- 4. 现有种子 ----------
seed_src = open(SEED, encoding='utf-8').read()
# 只取生成块之前的手写部分做基准：脚本是幂等的，跑第二遍时生成块已经在文件里了，
# 把它也算进「现有」会让每条新数据都跟自己撞名，结果一条都进不去
seed_head = seed_src.split('// ==== GENERATED:BEGIN ====')[0]
cur = re.findall(
    r'ex\("([^"]+)", *ExerciseCategory\.(\w+), *Equipment\.(\w+), *"([^"]*)", *"([^"]*)"',
    seed_head)
cur_names = [c[0] for c in cur]
cur_by_name = {c[0]: c for c in cur}


def delta_target(name):
    """delts 是整块三角肌，现有词表按前中后三束分，按动作名落到具体束。"""
    if re.search(r'后束|俯身.*飞鸟|反向飞鸟', name):
        return '三角肌后束'
    if re.search(r'前平举|前举|推举|推肩', name):
        return '三角肌前束'
    return '三角肌中束'


# 名称里写明的器械优先于 equipment 字段：
# 数据集里「固定器械俯身划船」的 equipment 填的是 barbell，字段和名字打架时以名字为准
NAME_EQ = [
    (re.compile(r'史密斯机'), 'MACHINE'),
    (re.compile(r'固定器械|雪橇机|雪橇|器械'), 'MACHINE'),
    (re.compile(r'绳索'), 'CABLE'),
    (re.compile(r'壶铃'), 'KETTLEBELL'),
    (re.compile(r'弹力带'), 'BAND'),
    (re.compile(r'哑铃'), 'DUMBBELL'),
    (re.compile(r'杠铃|曲杆|六角杠|奥林匹克'), 'BARBELL'),
]


def eq_from_name(name, fallback):
    for pat, eq in NAME_EQ:
        if pat.search(name):
            return eq
    return fallback


# 判断重复时剥掉的器械前缀（长词在前，避免「奥林匹克杠铃」被「杠铃」先吃掉）
EQ_PREFIX = re.compile(
    r'^(奥林匹克杠铃|曲杆|固定器械|史密斯机|雪橇机|雪橇|杠铃|哑铃|绳索|壶铃|弹力带|药球|'
    r'健身球|BOSU\s*球|悬吊带|滑轮|辅助|负重)')


def core_name(name):
    """剥掉开头的器械词，得到「动作本身」的名字。"""
    prev = None
    cur = name
    while prev != cur:
        prev = cur
        cur = EQ_PREFIX.sub('', cur).strip()
    return cur


def is_dup(new_name, new_eq):
    """与现有 118 条语义重复？

    判据故意收得很紧：**剥掉器械前缀后核心名完全相等**才算重复。
    早先按「子串 + 修饰词」判会误杀一大片——「大猩猩式引体向上」「单臂反手引体向上」
    都是不同动作，只因为它们含「引体向上」就被当成重复。同器械的「哑铃弯举 / 杠铃弯举」
    也不能因为互为子串就合并，那是两个动作。
    """
    if new_name in cur_by_name:
        return '同名'
    core = core_name(new_name)
    for old in cur_names:
        if old == new_name:
            continue
        if core == old or core == core_name(old):
            if cur_by_name[old][2] == new_eq:
                return f'重复 {old}'
    return None


# ---------- 5. 组装 ----------
out, dups, unmapped, bad_eq = [], [], [], []
for e in eds:
    eid = e['id']
    if eid not in zh:
        continue
    nm = re.sub(r'\s+', ' ', zh[eid].replace('в°', '°').replace('в', '')).strip()

    eq = EQUIPMENT.get(e['equipment'])
    if not eq:
        bad_eq.append((nm, e['equipment']))
        eq = 'OTHER'
    # 名称优先：字段写 barbell、名字写着「固定器械」时按名字走
    eq = eq_from_name(nm, eq)
    cat = CATEGORY.get(e['body_part'])
    if not cat:
        unmapped.append((nm, e['body_part']))
        cat = 'CORE'

    tgt = e['target']
    primary = delta_target(nm) if tgt == 'delts' else TARGET.get(tgt)
    if not primary:
        unmapped.append((nm, tgt))
        continue
    # 源数据个别 target 标错：腕弯举标成 biceps、提踵标成 glutes
    if '腕弯举' in nm:
        primary = '前臂'
    elif '提踵' in nm and primary != '小腿':
        primary = '小腿'

    secs = []
    for m in (e.get('secondary_muscles') or []):
        v = SECONDARY.get(m)
        if v and v != primary and v not in SECONDARY_DROP and v not in secs:
            secs.append(v)

    d = is_dup(nm, eq)
    if d:
        dups.append((nm, d))
        continue

    # 名字写着侧平举/前平举却挂在胸下，是源数据标错（如「绳索单臂俯身侧平举」）；
    # 带「上拉」的是上拉类动作，不动
    if re.search(r'侧平举|前平举', nm) and cat != 'SHOULDERS' and '上拉' not in nm:
        cat = 'SHOULDERS'

    out.append({
        'id': eid, 'name': nm, 'en': e['name'],
        'category': cat, 'equipment': eq,
        'primary': primary, 'secondary': ','.join(secs[:3]),
    })

# 数据集自身有 6 条重名（同名不同 equipment），只能留一条：
# 动作库按名字去重，重名会让 seedMissing 的差集判断失真
_seen = set()
_uniq = []
for o in out:
    if o['name'] in _seen:
        dups.append((o['name'], '同名(数据集内)'))
        continue
    _seen.add(o['name'])
    _uniq.append(o)
out = _uniq

for o in out:
    assert '"' not in o['name'] and '\\' not in o['name'], o

print(f"有中文名 {len(zh)} / 候选 {len(out)} / 跳过重复 {len(dups)}")
print(f"未映射: 分类 {len(unmapped)} 器械 {len(bad_eq)}")
print("\n=== 判为重复而跳过 ===")
for nm, d in dups:
    print(f"  {nm:30s} <- {d}")
print("\n=== 新增按分类 ===", collections.Counter(o['category'] for o in out).most_common())
print("=== 新增按器械 ===", collections.Counter(o['equipment'] for o in out).most_common())
print("=== 新增按主肌群 ===", collections.Counter(o['primary'] for o in out).most_common())
print(f"\n总计: 118 + {len(out)} = {118 + len(out)}")

json.dump(out, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)

if '--emit' in sys.argv:
    CAT = 'ExerciseCategory.'
    EQ = 'Equipment.'
    LABEL = [('CHEST', '胸'), ('BACK', '背'), ('LEGS', '腿'), ('SHOULDERS', '肩'),
             ('ARMS', '手臂'), ('CORE', '核心'), ('CARDIO', '有氧')]
    lines = [
        '        // 以下来自 exercises-dataset（MIT License），中文译名取自它的简体中文语言包；',
        '        // 由 tools/exercise-import/gen_exercise_seed.py 生成，手改会被下次生成覆盖。',
    ]
    for cat, cn in LABEL:
        group = [o for o in out if o['category'] == cat]
        if not group:
            continue
        lines.append('')
        lines.append('        // ---------------- ' + cn + '（扩充） ----------------')
        for o in group:
            lines.append(
                f'        ex("{o["name"]}", {CAT}{o["category"]}, {EQ}{o["equipment"]}, '
                f'"{o["primary"]}", "{o["secondary"]}"),')
    block = '\n'.join(lines)

    marker = '        // ==== GENERATED:BEGIN ===='
    endmark = '        // ==== GENERATED:END ===='
    if marker in seed_src:
        seed_src = re.sub(
            re.escape(marker) + r'.*?' + re.escape(endmark),
            lambda m: marker + '\n' + block + '\n' + endmark,
            seed_src, flags=re.S)
    else:
        # 插到列表里最后一个 ex(...) 之后；不能找 '    )'，
        # 文件末尾 ex() 函数自己的闭括号也在那儿
        last = list(re.finditer(r'^[ \t]*ex\(".*?\),[ \t]*$', seed_src, flags=re.M))[-1]
        pos = last.end()
        seed_src = (seed_src[:pos] + '\n' + marker + '\n' + block + '\n'
                    + endmark + seed_src[pos:])
    open(SEED, 'w', encoding='utf-8', newline='\n').write(seed_src)
    print(f"\n已写入 {SEED}（新增 {len(out)} 条）")
