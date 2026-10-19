#!/usr/bin/env python3
"""对渲染后的 K8s 清单做关键字段断言。

为什么需要这个脚本，而不是只靠 kubeconform：

  kubeconform 校验的是「单个资源是否符合 schema」。但有两类问题它抓不到：

  1. **CRD 直接被跳过**。我们跑 kubeconform 时必须加
     `-ignore-missing-schemas`（Rollout / AnalysisTemplate 是 CRD，
     kubeconform 没有它们的 schema），于是这些资源**完全没被校验**。

  2. **渲染期字段丢失**。Kustomize 对 CRD 的 patch 使用 JSON merge 语义，
     `containers:` 数组是整体替换而非按 name 合并。补丁里只写了
     `containers: [{name, resources}]`，结果 image、三类探针、
     securityContext 全部被抹掉 —— 渲染"成功"，schema 校验也"通过"，
     直到 apply 到集群才报
     `InvalidSpec: containers[0].image: Required value`。

  本脚本补上这两块：对渲染后的最终产物断言「必须存在的字段」。

用法：
    python3 scripts/check-rendered-manifests.py /tmp/render-ci.yaml [...]
"""

from __future__ import annotations

import sys

import yaml

# 每个容器都必须有的探针。少一个都意味着 k8s 无法正确判断它的状态：
# 没有 startupProbe，启动慢会被 liveness 反复杀死；
# 没有 readinessProbe，未就绪就会被切流量；
# 没有 livenessProbe，进程卡死也不会被重启。
REQUIRED_PROBES = ("livenessProbe", "readinessProbe", "startupProbe")


def check_workload(doc: dict, path: str) -> list[str]:
    errors: list[str] = []
    kind = doc.get("kind")
    name = doc.get("metadata", {}).get("name", "<unnamed>")
    pod_spec = doc.get("spec", {}).get("template", {}).get("spec", {})
    containers = pod_spec.get("containers") or []

    if not containers:
        errors.append(f"{path}: {kind}/{name} 没有任何容器")
        return errors

    for container in containers:
        cname = container.get("name", "<unnamed>")
        prefix = f"{path}: {kind}/{name} 容器 {cname}"

        # 最关键的一条：image 为空是 CRD 被错误 patch 的典型症状
        if not container.get("image"):
            errors.append(
                f"{prefix} 缺少 image —— 通常是 kustomize 对 CRD 的 "
                f"containers 数组做了整体替换，把 image 抹掉了"
            )

        # 探针：分析类工作负载（Job）不需要，这里只检查长期运行的
        if kind in ("Rollout", "Deployment", "StatefulSet"):
            for probe in REQUIRED_PROBES:
                if probe not in container:
                    errors.append(f"{prefix} 缺少 {probe}")

            if not container.get("resources", {}).get("requests"):
                errors.append(f"{prefix} 缺少 resources.requests（HPA 与调度都依赖它）")

            if not container.get("securityContext"):
                errors.append(f"{prefix} 缺少 securityContext")

    return errors


def main() -> int:
    if len(sys.argv) < 2:
        print("用法: check-rendered-manifests.py <rendered.yaml> [...]", file=sys.stderr)
        return 2

    errors: list[str] = []
    checked = 0

    for path in sys.argv[1:]:
        try:
            docs = [d for d in yaml.safe_load_all(open(path, encoding="utf-8")) if d]
        except (OSError, yaml.YAMLError) as exc:
            errors.append(f"{path}: 无法解析 —— {exc}")
            continue

        for doc in docs:
            if doc.get("kind") in ("Rollout", "Deployment", "StatefulSet"):
                checked += 1
                errors.extend(check_workload(doc, path))

            # Service 的 selector 为空意味着它永远不会关联到任何 Pod
            if doc.get("kind") == "Service":
                checked += 1
                if not doc.get("spec", {}).get("selector"):
                    errors.append(
                        f"{path}: Service/{doc.get('metadata', {}).get('name')} 没有 selector"
                    )

    print(f"检查了 {checked} 个工作负载/Service")
    if errors:
        for err in errors:
            print(f"::error::{err}")
        print(f"\n发现 {len(errors)} 处问题")
        return 1

    print("渲染后关键字段检查全部通过 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
