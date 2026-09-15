#!/usr/bin/env python3
"""发布前快速检查明显的私有路径或凭据；不能替代完整人工审查。"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SKIP = {".git", "target", "build", "dist", ".gradle"}
PATTERNS = {
    "private AOSP path": re.compile(r"(?:frameworks/(?:base|av)|vendor/|device/rockchip/)", re.I),
    # 只匹配疑似真实值；`${VAR:-placeholder}`、`<REPLACE_ME>` 等示例允许出现。
    "credential assignment": re.compile(r"(?:password|secret|api[_-]?key)\s*(?:=|:\s*(?!-))\s*[\"']?(?!\$\{|\$\(|<|CHANGE_ME|change-me)[A-Za-z0-9+/=_-]{8,}", re.I),
}


def main() -> int:
    violations = []
    for path in ROOT.rglob("*"):
        if (not path.is_file() or any(part in SKIP for part in path.parts)
                or path.name in {"README.md", "SOURCE.md", "check-public-boundary.py"}):
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for label, pattern in PATTERNS.items():
            # 设计文档可以描述被禁止的源码位置；只对可执行/配置文件判定该路径。
            if label == "private AOSP path" and path.suffix.lower() in {".md", ".txt"}:
                continue
            if pattern.search(content):
                violations.append(f"{label}: {path}")
    if violations:
        print("PUBLIC_BOUNDARY_FAILED")
        print("\n".join(violations))
        return 1
    print("PUBLIC_BOUNDARY_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
