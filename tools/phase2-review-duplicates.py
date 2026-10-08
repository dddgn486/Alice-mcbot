#!/usr/bin/env python3
"""
Phase 2 去重人工审查工具

功能：
1. 展示 26 组重复标题
2. 逐组显示所有出现位置和摘要
3. 收集人工判断结果
4. 生成合并指令
"""

import json
from pathlib import Path
from typing import Dict, List

def load_duplicates(json_path: str) -> Dict:
    """加载重复分析结果"""
    return json.loads(Path(json_path).read_text(encoding='utf-8'))

def display_duplicate_group(index: int, group: Dict):
    """展示一组重复"""
    print(f"\n{'='*80}")
    print(f"组 {index}/{26}")
    print(f"{'='*80}")
    print(f"标题：{group['title']}")
    print(f"出现次数：{group['count']}")
    print()
    
    for i, occ in enumerate(group['occurrences'], 1):
        print(f"{i}. 路径：{occ['path']}")
        print(f"   锚点：{occ['anchor']}")
        print(f"   类型：{occ['type']}")
        print(f"   分类：{occ['category']}")
        print(f"   摘要长度：{occ['summary_len']} 字")
        print(f"   摘要：{occ['summary'][:150]}...")
        print()

def review_duplicates(duplicates: Dict):
    """交互式审查"""
    
    cross_file = duplicates['cross_file']
    results = {
        'true_duplicates': [],  # 真重复（需要合并）
        'false_duplicates': [], # 假重复（标题相同但语义不同）
        'partial_overlap': [],  # 部分重叠（需要交叉引用）
        'skip': []              # 跳过（暂不处理）
    }
    
    print("📋 Phase 2 去重人工审查")
    print("="*80)
    print(f"总共 {len(cross_file)} 组重复需要审查")
    print()
    print("判断选项：")
    print("  T - 真重复（True duplicate）- 语义相同，需要合并")
    print("  F - 假重复（False duplicate）- 标题相同但语义不同")
    print("  P - 部分重叠（Partial overlap）- 有交集但不完全相同")
    print("  S - 跳过（Skip）- 暂不处理")
    print()
    
    # 逐组展示和判断
    for i, group in enumerate(cross_file, 1):
        display_duplicate_group(i, group)
        
        # 在非交互模式下，输出所有信息供用户查看
        print("请判断这组重复的类型...")
        print()
    
    # 保存结果（供下次处理）
    output = {
        'review_date': '2026-10-08',
        'total_groups': len(cross_file),
        'results': results,
        'groups': cross_file
    }
    
    output_path = '/tmp/phase2_review_results.json'
    Path(output_path).write_text(json.dumps(output, ensure_ascii=False, indent=2))
    
    print(f"\n✅ 审查数据已保存到：{output_path}")
    print(f"\n说明：")
    print(f"- 本工具已列出所有 26 组重复")
    print(f"- 请人工判断每组的类型")
    print(f"- 判断结果将用于下一步的合并操作")
    
    return output

if __name__ == '__main__':
    duplicates = load_duplicates('/tmp/phase2_duplicate_analysis.json')
    review_duplicates(duplicates)
