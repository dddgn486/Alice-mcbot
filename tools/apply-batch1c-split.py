#!/usr/bin/env python3
"""
应用批次 1c 拆分条目到 EXTRACTED_KNOWLEDGE.md

批次 1c 特殊性：
- 原始：13 条（>300字）
- 拆分后：26 条（每条拆成 (上) 和 (下) 两条）

操作：
1. 找到原条目（不带 (上)/(下) 的标题）
2. 删除原条目的整个段落
3. 在同一位置插入 2 条新条目（(上) 和 (下)）
"""

import json
import re
from pathlib import Path
from typing import Dict, List

def load_split_items(json_path: str) -> List[Dict]:
    """加载拆分结果"""
    data = json.loads(Path(json_path).read_text(encoding='utf-8'))
    if isinstance(data, dict) and 'items' in data:
        items = data['items']
    else:
        items = data
    
    print(f"✅ 加载拆分结果：{len(items)} 条（应为 26 条 = 13 对）")
    return items

def group_split_pairs(items: List[Dict]) -> List[tuple]:
    """将拆分条目分组为配对"""
    pairs = []
    for i in range(0, len(items), 2):
        if i + 1 < len(items):
            upper = items[i]
            lower = items[i + 1]
            
            # 验证是配对
            if upper.get('split_part') == 1 and lower.get('split_part') == 2:
                pairs.append((upper, lower))
            else:
                print(f"⚠️ 警告：第 {i} 和 {i+1} 条不是配对")
    
    print(f"✅ 找到 {len(pairs)} 对拆分条目")
    return pairs

def apply_splits(ledger_path: str, pairs: List[tuple], dry_run: bool = True):
    """应用拆分到册子"""
    
    ledger = Path(ledger_path).read_text(encoding='utf-8')
    original_size = len(ledger)
    
    stats = {
        'total_pairs': len(pairs),
        'applied': 0,
        'not_found': 0,
        'failed': [],
        'size_before': original_size,
        'size_after': 0
    }
    
    for i, (upper, lower) in enumerate(pairs, 1):
        path = upper.get('path', '')
        
        # 移除 (上)/(下) 后缀得到原标题
        title_upper = upper.get('title', '')
        title_original = title_upper.replace('(上)', '').replace('（上）', '').strip()
        
        if not path or not title_original:
            stats['not_found'] += 1
            stats['failed'].append(f"[{i}] 缺少路径或标题")
            continue
        
        # 构造原条目的搜索模式
        header_pattern = re.escape(f"#### `{path}` › {title_original}")
        
        # 查找原条目
        match = re.search(header_pattern, ledger)
        if not match:
            stats['not_found'] += 1
            stats['failed'].append(f"[{i}] 未找到原条目：{title_original[:50]}")
            continue
        
        # 找到整个条目段落（从 #### 到下一个 #### 或文件末尾）
        start_pos = match.start()
        next_header = re.search(r'\n####', ledger[start_pos + 1:])
        if next_header:
            end_pos = start_pos + 1 + next_header.start()
        else:
            end_pos = len(ledger)
        
        original_entry = ledger[start_pos:end_pos]
        
        # 构造新的两条条目
        # 保留原条目的结构，只替换标题和摘要
        new_entry_upper = original_entry.replace(
            f"#### `{path}` › {title_original}",
            f"#### `{path}` › {title_upper}"
        )
        
        # 替换摘要
        summary_pattern = r'(- \*\*摘要\*\*：).+?(?=\n- \*\*|$)'
        new_entry_upper = re.sub(
            summary_pattern,
            f"\\1{upper['new_summary']}",
            new_entry_upper,
            flags=re.DOTALL
        )
        
        # 下半部分
        title_lower = lower.get('title', '')
        new_entry_lower = original_entry.replace(
            f"#### `{path}` › {title_original}",
            f"#### `{path}` › {title_lower}"
        )
        new_entry_lower = re.sub(
            summary_pattern,
            f"\\1{lower['new_summary']}",
            new_entry_lower,
            flags=re.DOTALL
        )
        
        # 替换原条目为两条新条目
        new_entries = new_entry_upper + "\n" + new_entry_lower
        ledger = ledger[:start_pos] + new_entries + ledger[end_pos:]
        
        stats['applied'] += 1
        
        if i % 3 == 0:
            print(f"  进度：{i}/{stats['total_pairs']} 对")
    
    stats['size_after'] = len(ledger)
    
    # 保存结果
    if not dry_run:
        Path(ledger_path).write_text(ledger, encoding='utf-8')
        print(f"\n✅ 已写入：{ledger_path}")
    else:
        print(f"\n⚠️ 试运行模式（未写入）")
        # 保存预览
        Path('/tmp/EXTRACTED_KNOWLEDGE_batch1c_preview.md').write_text(ledger, encoding='utf-8')
    
    return stats

def print_stats(stats: Dict):
    """打印统计"""
    print(f"\n{'='*60}")
    print(f"应用统计")
    print(f"{'='*60}")
    print(f"总配对：{stats['total_pairs']} 对")
    print(f"成功应用：{stats['applied']} ({stats['applied']/stats['total_pairs']*100:.1f}%)")
    print(f"未找到/失败：{stats['not_found']} ({stats['not_found']/stats['total_pairs']*100:.1f}%)")
    print(f"\n文件大小：")
    print(f"  处理前：{stats['size_before']:,} 字节")
    print(f"  处理后：{stats['size_after']:,} 字节")
    size_change = stats['size_after'] - stats['size_before']
    print(f"  变化：{size_change:+,} 字节")
    
    if stats['failed']:
        print(f"\n失败条目：")
        for fail in stats['failed']:
            print(f"  {fail}")

if __name__ == '__main__':
    import sys
    
    # 参数
    split_json = '/tmp/compressed_batch1c.json'
    ledger_path = 'docs/EXTRACTED_KNOWLEDGE.md'
    
    dry_run = '--apply' not in sys.argv
    
    if dry_run:
        print("🔍 试运行模式（不写入文件）")
        print("   使用 --apply 参数执行实际写入\n")
    else:
        print("✍️  执行模式（将写入文件）\n")
    
    # 加载数据
    items = load_split_items(split_json)
    pairs = group_split_pairs(items)
    
    # 应用拆分
    stats = apply_splits(ledger_path, pairs, dry_run)
    
    # 打印统计
    print_stats(stats)
    
    if dry_run:
        print(f"\n📄 预览文件已保存：/tmp/EXTRACTED_KNOWLEDGE_batch1c_preview.md")
