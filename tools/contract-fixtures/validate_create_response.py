#!/usr/bin/env python3
"""用公开 OpenAPI 3.1 schema 验证 Create 响应正反例，不调用服务。

先安装 tools/contract-fixtures/requirements.txt 中锁定的测试依赖，再运行本脚本。
Java 投影测试会逐字节比较同一份正例 fixture，防止两侧样例独立漂移。
"""

from copy import deepcopy
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from ruamel.yaml import YAML


ROOT = Path(__file__).resolve().parents[2]
OPENAPI = ROOT / "contracts" / "ai-meeting-openapi.yaml"
FIXTURE = ROOT / "contracts" / "fixtures" / "create-meeting-response.v1.json"


def expect_valid(validator, payload, name):
    failures = list(validator.iter_errors(payload))
    if failures:
        raise AssertionError(f"{name} must match CreateMeetingResponse schema")


def expect_invalid(validator, payload, name):
    if not list(validator.iter_errors(payload)):
        raise AssertionError(f"{name} must be rejected by CreateMeetingResponse schema")


def main():
    yaml = YAML(typ="safe", pure=True)
    yaml.version = (1, 2)
    openapi = yaml.load(OPENAPI.read_text(encoding="utf-8"))
    # 保留 components 路径，让 CreateMeetingResponse 内部的 $ref 按原文解析。
    schema = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$ref": "#/components/schemas/CreateMeetingResponse",
        "components": openapi["components"],
    }
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    response = json.loads(FIXTURE.read_text(encoding="utf-8"))
    expect_valid(validator, response, "public v1 response")

    extra_internal = deepcopy(response)
    extra_internal["schemaVersion"] = 1
    expect_invalid(validator, extra_internal, "internal V013 field")

    extra_decision = deepcopy(response)
    extra_decision["capabilityDecisions"][0]["action"] = "DISABLE"
    expect_invalid(validator, extra_decision, "internal decision field")

    null_optional = deepcopy(response)
    null_optional["limits"]["maxReplayAudioMs"] = None
    expect_invalid(validator, null_optional, "null optional limit")

    missing_required = deepcopy(response)
    del missing_required["meetingId"]
    expect_invalid(validator, missing_required, "missing meetingId")
    print("CREATE_RESPONSE_OPENAPI_OK positive=1 negative=4")


if __name__ == "__main__":
    main()
