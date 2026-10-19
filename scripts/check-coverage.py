#!/usr/bin/env python3
"""覆盖率门禁：解析 JaCoCo 聚合报告，未达标则退出码非零。

为什么自己写而不用现成的 action：

  1. **确定性**。第三方 action 在不同事件类型（push / pull_request /
     workflow_dispatch）下行为不一致，尤其是「仅对比变更文件」这类功能
     在非 PR 场景下会静默跳过。门禁最忌讳的就是「有时候不生效」。
  2. **可解释**。面试官问「你们的覆盖率门禁怎么定的」，能逐行讲清楚
     判定逻辑，比说「用了个 action」有说服力。
  3. **零依赖**。少一个供应链攻击面 —— 这在受监管行业是实际考量，
     每引入一个第三方 action 都要走一次安全评审。

阈值不搞一刀切：不同包的职责不同，合理的覆盖率水平也不同。
全局卡一个数字会逼着人给 DTO 写无意义的测试来凑数。
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass


@dataclass
class Coverage:
    covered: int = 0
    missed: int = 0

    @property
    def total(self) -> int:
        return self.covered + self.missed

    @property
    def ratio(self) -> float:
        return self.covered / self.total if self.total else 1.0

    def __add__(self, other: "Coverage") -> "Coverage":
        return Coverage(self.covered + other.covered, self.missed + other.missed)


def read_counters(element: ET.Element) -> dict[str, Coverage]:
    """从 XML 元素读取各类型的计数器。"""
    result: dict[str, Coverage] = {}
    for counter in element.findall("counter"):
        ctype = counter.get("type")
        if ctype:
            result[ctype] = Coverage(
                covered=int(counter.get("covered", 0)),
                missed=int(counter.get("missed", 0)),
            )
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="JaCoCo 聚合覆盖率门禁")
    parser.add_argument("report", help="聚合报告 jacoco.xml 路径")
    parser.add_argument("--min-line", type=float, default=0.80, help="全局行覆盖率下限")
    parser.add_argument("--min-branch", type=float, default=0.70, help="全局分支覆盖率下限")
    parser.add_argument(
        "--min-module-line",
        type=float,
        default=0.60,
        help="单个模块的行覆盖率下限（防止用高覆盖率的模块掩盖零覆盖的模块）",
    )
    args = parser.parse_args()

    try:
        root = ET.parse(args.report).getroot()
    except (OSError, ET.ParseError) as exc:
        print(f"::error::无法读取覆盖率报告 {args.report}: {exc}", file=sys.stderr)
        print(
            "提示：报告为空通常意味着 demo-coverage 模块没有拿到各模块的 jacoco.exec。"
            "检查 demo-api 的 spring-boot-maven-plugin 是否配了 <classifier>exec</classifier> ——"
            "不加它，repackage 会把主 artifact 换成 fat jar，类被塞进 BOOT-INF/classes，"
            "聚合时扫不到，报告就是空的。",
            file=sys.stderr,
        )
        return 1

    totals = read_counters(root)
    line = totals.get("LINE", Coverage())
    branch = totals.get("BRANCH", Coverage())

    print("=" * 68)
    print("覆盖率门禁")
    print("=" * 68)

    # ---- 全局判定 ----
    failures: list[str] = []

    print(f"\n{'指标':<12}{'覆盖':>10}{'总数':>10}{'覆盖率':>12}{'下限':>10}  结果")
    print("-" * 68)

    for label, cov, threshold in (
        ("行覆盖率", line, args.min_line),
        ("分支覆盖率", branch, args.min_branch),
    ):
        ok = cov.ratio >= threshold
        mark = "✓ 通过" if ok else "✗ 未达标"
        print(
            f"{label:<12}{cov.covered:>10}{cov.total:>10}"
            f"{cov.ratio:>11.1%}{threshold:>10.0%}  {mark}"
        )
        if not ok:
            failures.append(
                f"{label} {cov.ratio:.1%} 低于下限 {threshold:.0%}"
            )

    # ---- 分模块判定 ----
    # 只看全局会被平均掉：一个 100% 覆盖的模块可以掩盖另一个 0% 覆盖的模块。
    # 分模块卡下限能防住这种情况。
    #
    # 注意聚合报告的 XML 结构：report-aggregate 会为每个模块生成一个 <group>，
    # <package> 是嵌在 <group> 里的。直接 findall("package") 会得到空列表 ——
    # 分包统计静默失效，而全局数字看起来完全正常，是个容易长期不被发现的坑。
    print(f"\n各模块行覆盖率（下限 {args.min_module_line:.0%}）")
    print("-" * 68)

    groups = root.findall("group")
    if not groups:
        print("  ⚠ 未找到 <group> 节点，分模块门禁被跳过")
        print("    若报告由 report-aggregate 生成，说明结构异常，请检查 JaCoCo 版本")

    for group in sorted(groups, key=lambda g: g.get("name", "")):
        name = group.get("name", "<unnamed>")
        cov = read_counters(group).get("LINE", Coverage())
        if not cov.total:
            print(f"  - {name:<24}{'（无代码）':>20}")
            continue
        ok = cov.ratio >= args.min_module_line
        mark = "✓" if ok else "✗"
        print(f"  {mark} {name:<24}{cov.covered:>6}/{cov.total:<6}{cov.ratio:>8.1%}")
        if not ok:
            failures.append(
                f"模块 {name} 行覆盖率 {cov.ratio:.1%} 低于下限 {args.min_module_line:.0%}"
            )

    print()
    if failures:
        for msg in failures:
            print(f"::error::{msg}")
        print(f"门禁未通过：{len(failures)} 项不达标")
        return 1

    print("门禁通过 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
