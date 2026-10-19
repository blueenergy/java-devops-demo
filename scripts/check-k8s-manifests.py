#!/usr/bin/env python3
"""K8s manifests 的跨资源引用一致性检查。

与 kubeconform 的分工：
  kubeconform 校验「单个资源是否符合 schema」——字段名拼错、类型不对、必填缺失。
  本脚本校验「资源之间的关系是否成立」——这类错误 schema 完全查不出来，
  因为每个资源单看都是合法的。

  CI 里两者都跑：kubeconform 管格式，本脚本管关系。

覆盖的检查项：
  1. Service 的 selector 能否真的选中某个工作负载的 Pod 标签
     —— 最常见的坑：改了 Pod 标签忘了同步 Service，服务「部署成功」但零流量。
  2. envFrom / env 引用的 ConfigMap、Secret 是否存在
     —— 少一个 ConfigMap，Pod 会卡在 CreateContainerConfigError，
        而 Deployment 状态看起来只是「副本没起来」。
  3. Rollout 的 stableService / canaryService 是否存在
  4. Rollout 引用的 AnalysisTemplate 是否存在
  5. 探针路径对应的健康分组是否真的在 application.yml 里定义过
     —— 路径写错时 Pod 永远不 Ready，但报错信息只有 404，排查方向容易跑偏。
"""

from __future__ import annotations

import pathlib
import sys
from typing import Any

import yaml

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
K8S_DIR = REPO_ROOT / "k8s"
APP_YML = REPO_ROOT / "demo-api" / "src" / "main" / "resources" / "application.yml"

WORKLOAD_KINDS = {"Deployment", "Rollout", "StatefulSet", "DaemonSet", "Job"}


def kind(doc: dict[str, Any]) -> str:
    return doc.get("kind", "")


def name(doc: dict[str, Any]) -> str:
    return doc.get("metadata", {}).get("name", "<unnamed>")


def pod_labels(workload: dict[str, Any]) -> dict[str, str]:
    return (
        workload.get("spec", {})
        .get("template", {})
        .get("metadata", {})
        .get("labels", {})
        or {}
    )


def containers(workload: dict[str, Any]) -> list[dict[str, Any]]:
    spec = workload.get("spec", {}).get("template", {}).get("spec", {})
    return list(spec.get("containers") or []) + list(spec.get("initContainers") or [])


def load_documents() -> list[dict[str, Any]]:
    docs: list[dict[str, Any]] = []
    for path in sorted(K8S_DIR.rglob("*.yaml")):
        # kustomization.yaml 不是 k8s 资源，是 kustomize 的指令文件
        if path.name == "kustomization.yaml":
            continue
        try:
            for doc in yaml.safe_load_all(path.read_text(encoding="utf-8")):
                if doc:
                    docs.append(doc)
        except yaml.YAMLError as exc:
            print(f"::error file={path}::YAML 解析失败: {exc}")
            sys.exit(1)
    return docs


def main() -> int:
    if not K8S_DIR.exists():
        print(f"::error::找不到 {K8S_DIR}")
        return 1

    docs = load_documents()
    workloads = [d for d in docs if kind(d) in WORKLOAD_KINDS]
    services = [d for d in docs if kind(d) == "Service"]
    config_maps = {name(d) for d in docs if kind(d) == "ConfigMap"}
    secrets = {name(d) for d in docs if kind(d) == "Secret"}
    service_names = {name(d) for d in services}
    template_names = {name(d) for d in docs if kind(d) == "AnalysisTemplate"}

    errors: list[str] = []

    # ---- 1. Service selector 必须能选中某个工作负载 ----
    for svc in services:
        selector = svc.get("spec", {}).get("selector") or {}
        if not selector:
            continue
        if not any(
            all(pod_labels(w).get(k) == v for k, v in selector.items()) for w in workloads
        ):
            errors.append(
                f"Service/{name(svc)} 的 selector {selector} 匹配不到任何工作负载的 Pod 标签。"
                f" 服务会创建成功但永远没有 Endpoints，表现为「部署成功但零流量」。"
            )

    # ---- 2. ConfigMap / Secret 引用必须存在 ----
    for workload in workloads:
        for container in containers(workload):
            for ref in container.get("envFrom") or []:
                if "configMapRef" in ref:
                    ref_name = ref["configMapRef"].get("name")
                    if ref_name not in config_maps:
                        errors.append(
                            f"{kind(workload)}/{name(workload)} 的容器 {container.get('name')}"
                            f" 引用不存在的 ConfigMap: {ref_name}"
                        )
                if "secretRef" in ref:
                    ref_name = ref["secretRef"].get("name")
                    if ref_name not in secrets:
                        errors.append(
                            f"{kind(workload)}/{name(workload)} 的容器 {container.get('name')}"
                            f" 引用不存在的 Secret: {ref_name}"
                        )

            # 逐个 env 的 valueFrom 引用（secretKeyRef / configMapKeyRef）。
            # 这两种引用比 envFrom 更隐蔽：envFrom 引用整个 Secret，
            # 而这里引用的是一个具体的 key —— key 写错时 Pod 同样会
            # CreateContainerConfigError，但报错只提到 key 名。
            for env_var in container.get("env") or []:
                value_from = env_var.get("valueFrom") or {}
                for ref_kind, known in (
                    ("secretKeyRef", secrets),
                    ("configMapKeyRef", config_maps),
                ):
                    ref = value_from.get(ref_kind)
                    if not ref:
                        continue
                    ref_name = ref.get("name")
                    if ref_name not in known:
                        errors.append(
                            f"{kind(workload)}/{name(workload)} 的容器 {container.get('name')}"
                            f" 的 env {env_var.get('name')} 引用了不存在的 {ref_kind}: {ref_name}"
                        )

            # volume 挂载引用的 ConfigMap / Secret
            for vol in workload.get("spec", {}).get("template", {}).get("spec", {}).get("volumes") or []:
                if "configMap" in vol and vol["configMap"].get("name") not in config_maps:
                    errors.append(
                        f"{kind(workload)}/{name(workload)} 的 volume {vol.get('name')}"
                        f" 引用不存在的 ConfigMap: {vol['configMap'].get('name')}"
                    )
                if "secret" in vol and vol["secret"].get("secretName") not in secrets:
                    errors.append(
                        f"{kind(workload)}/{name(workload)} 的 volume {vol.get('name')}"
                        f" 引用不存在的 Secret: {vol['secret'].get('secretName')}"
                    )

    # ---- 3 & 4. Rollout 的 Service 与 AnalysisTemplate 引用 ----
    for workload in docs:
        if kind(workload) != "Rollout":
            continue
        canary = workload.get("spec", {}).get("strategy", {}).get("canary") or {}

        for key in ("stableService", "canaryService"):
            ref = canary.get(key)
            if ref and ref not in service_names:
                errors.append(f"Rollout/{name(workload)} 的 {key}={ref} 指向不存在的 Service")

        for step in canary.get("steps") or []:
            for template in (step.get("analysis") or {}).get("templates") or []:
                ref = template.get("templateName")
                if ref not in template_names:
                    errors.append(
                        f"Rollout/{name(workload)} 引用了不存在的 AnalysisTemplate: {ref}"
                    )

    # ---- 5. 探针路径必须与 application.yml 的健康分组一致 ----
    if APP_YML.exists():
        app_config = APP_YML.read_text(encoding="utf-8")
        for workload in workloads:
            for container in containers(workload):
                for probe_key in ("livenessProbe", "readinessProbe", "startupProbe"):
                    path = (container.get(probe_key) or {}).get("httpGet", {}).get("path")
                    if not path or not path.startswith("/actuator/health/"):
                        continue
                    group = path.rsplit("/", 1)[-1]
                    # application.yml 里健康分组以 "<group>:" 的形式定义
                    if f"{group}:" not in app_config:
                        errors.append(
                            f"{kind(workload)}/{name(workload)} 的 {probe_key} 指向 {path}，"
                            f"但 application.yml 中未定义健康分组 '{group}'。"
                            f" Pod 将永远不 Ready。"
                        )

    print(
        f"检查了 {len(docs)} 个资源：{len(services)} 个 Service、"
        f"{len(workloads)} 个工作负载、{len(template_names)} 个 AnalysisTemplate"
    )

    if errors:
        print()
        for err in errors:
            print(f"::error::{err}")
        print(f"\n发现 {len(errors)} 处引用不一致")
        return 1

    print("跨资源引用一致性检查全部通过 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
