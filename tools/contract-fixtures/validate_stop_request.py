#!/usr/bin/env python3
"""验证公开 Stop 请求体与当前路由一致：可省略，存在时只能是 {}。"""

from pathlib import Path

from jsonschema import Draft202012Validator
from ruamel.yaml import YAML


ROOT = Path(__file__).resolve().parents[2]
OPENAPI = ROOT / "contracts" / "ai-meeting-openapi.yaml"


def main() -> None:
    yaml = YAML(typ="safe", pure=True)
    yaml.version = (1, 2)
    openapi = yaml.load(OPENAPI.read_text(encoding="utf-8"))
    operation = openapi["paths"]["/v1/meetings/{meetingId}:stop"]["post"]
    assert operation["requestBody"]["required"] is False
    assert "400" in operation["responses"]
    assert operation["requestBody"]["content"]["application/json"]["schema"] == {
        "$ref": "#/components/schemas/StopMeetingRequest"
    }

    schema = openapi["components"]["schemas"]["StopMeetingRequest"]
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    assert validator.is_valid({})
    for rejected in (
        {"lastStreamSequence": 1},
        {"lastAudioPositionMs": 10},
        {"recordingChecksum": "checksum"},
        {"unexpected": True},
        [],
        None,
    ):
        assert not validator.is_valid(rejected), "nonempty/invalid Stop body accepted"
    print("STOP_REQUEST_OPENAPI_OK positive=1 negative=6 optional_body=true")


if __name__ == "__main__":
    main()
