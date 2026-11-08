"""解析大华 Record 配置，看清录像计划的真实结构。

Java 侧字符串比较被 id/session 字段与空白排版坑了三次，改用真正的 JSON 解析器。
"""
import json
import sys

path = sys.argv[1] if len(sys.argv) > 1 else 'target/record.json'
with open(path, encoding='utf-8') as f:
    raw = f.read()

data = json.loads(raw)
print('顶层字段:', list(data.keys()))
print('params 字段:', list(data.get('params', {}).keys()))

table = data.get('params', {}).get('table')
if table is None:
    print('没有 params.table')
    sys.exit(1)

print('\ntable 字段:', list(table.keys()))
print('table 类型:', type(table).__name__)

ts = table.get('TimeSection')
print(f'\nTimeSection 类型: {type(ts).__name__}, 组数: {len(ts) if ts else 0}')
if ts:
    for gi, group in enumerate(ts):
        if not isinstance(group, list):
            print(f'  组 {gi}: 非数组 {type(group).__name__}')
            continue
        days = [s for s in group if not s.startswith('0 ')]
        print(f'  组 {gi}（{len(group)} 段）: 录制中={days if days else "无"}')

print('\n其他字段:')
for k, v in table.items():
    if k == 'TimeSection':
        continue
    print(f'  {k} = {v!r}')
