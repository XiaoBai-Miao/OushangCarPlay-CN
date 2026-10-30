#!/usr/bin/env python3
"""v144: 删除 0 引用孤儿字符串（207 个键，8 locale 同步删除）。

用法：
    python3 scripts/delete_orphan_strings.py zero_keys_full.txt

对每个资源文件，精确删除 <string name="KEY">...</string> 条目（单行或跨行），
保留文件格式与缩进。删除后自动校验：目标键已全部移除，且无残留。
"""
import re
import sys
import os
from collections import defaultdict

RES_ROOT = "common/src/main/res"


def load_zero_keys(path: str) -> set:
    with open(path, encoding="utf-8") as f:
        return {line.strip() for line in f if line.strip()}


def find_string_blocks(text: str):
    """返回 [(start, end, key)]，覆盖单行与跨行 <string> 块。"""
    blocks = []
    # 匹配 <string name="..."> ... </string>，允许跨行、含 CDATA/实体
    pattern = re.compile(
        r'<string\s+name="([^"]+)"(?:\s+[^>]*)?>(.*?)</string>',
        re.DOTALL,
    )
    for m in pattern.finditer(text):
        blocks.append((m.start(), m.end(), m.group(1)))
    return blocks


def delete_keys_from_file(path: str, zero_keys: set) -> int:
    with open(path, encoding="utf-8") as f:
        original = f.read()
    blocks = find_string_blocks(original)
    # 从后往前删除，避免索引偏移
    removed = 0
    for start, end, key in sorted(blocks, key=lambda x: -x[0]):
        if key in zero_keys:
            original = original[:start] + original[end:]
            removed += 1
    # 只在真正删除过键时写盘，避免误 touch 其他资源文件（git 会标记幽灵 M）
    if removed > 0:
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(original)
    return removed


def main() -> int:
    if len(sys.argv) < 2:
        print("用法: python3 delete_orphan_strings.py zero_keys_full.txt")
        return 2
    zero_keys = load_zero_keys(sys.argv[1])
    print(f"待删除键数: {len(zero_keys)}")

    # 收集所有资源 xml
    xml_files = []
    for root, dirs, files in os.walk(RES_ROOT):
        if "build" in root:
            continue
        for fn in files:
            if fn.endswith(".xml"):
                xml_files.append(os.path.join(root, fn))

    total = 0
    per_file = defaultdict(int)
    for path in sorted(xml_files):
        n = delete_keys_from_file(path, zero_keys)
        if n:
            per_file[path] = n
            total += n
            print(f"  删除 {n:3d}  {path}")

    print(f"\n共删除 {total} 个键条目")

    # 校验：目标键应不再出现在任何资源文件
    remaining = []
    for path in sorted(xml_files):
        with open(path, encoding="utf-8") as f:
            text = f.read()
        for m in re.finditer(r'<string\s+name="([^"]+)"', text):
            if m.group(1) in zero_keys:
                remaining.append((m.group(1), path))
    if remaining:
        print(f"\n!!! 仍有 {len(remaining)} 个目标键残留:")
        for k, p in remaining:
            print(f"  {k} @ {p}")
        return 1
    print("校验通过：所有目标键已移除，无残留。")
    return 0


if __name__ == "__main__":
    sys.exit(main())