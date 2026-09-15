#!/usr/bin/env python3
"""对公开契约 Fixture 做最小的可重复校验。

脚本只检查 JSON 可解析性和目录存在性，不访问网络、不读取真实服务，便于
在本地、Gitee CI 和发布前扫描中复用。具体协议语义由各模块测试负责。
"""
from pathlib import Path
import json


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts"


def main() -> None:
    if not CONTRACTS.is_dir():
        raise SystemExit("contracts directory is missing")
    count = 0
    for path in CONTRACTS.rglob("*.json"):
        json.loads(path.read_text(encoding="utf-8"))
        count += 1
    print(f"CONTRACT_FIXTURES_OK json={count}")


if __name__ == "__main__":
    main()
