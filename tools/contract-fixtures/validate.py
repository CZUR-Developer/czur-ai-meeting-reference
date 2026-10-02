#!/usr/bin/env python3
"""离线校验公开 Fixture 的 JSON、会议事件、瞬时 WSS 消息与音频校验字节域。"""
from __future__ import annotations

from pathlib import Path
import base64
import hashlib
import json
import re
from datetime import datetime


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts"
FIXTURES = CONTRACTS / "fixtures"
DURABLE_BODIES = {"sessionState", "transcriptUpsert", "translationUpsert", "speakerUpdate", "capabilityChanged", "finalization", "error", "audioContinuity"}
TRANSIENT_BODIES = {"audioAck", "heartbeat", "resumePageEnd"}
BASE_FIELDS = {"specVersion", "meetingId", "streamId", "eventSequence", "emittedAt"}


def proto_enums() -> dict[str, set[str]]:
    source = (CONTRACTS / "realtime.proto").read_text(encoding="utf-8")
    return {name: set(re.findall(r"\b([A-Z][A-Z_0-9]*)\s*=", body))
            for name, body in re.findall(r"enum\s+(\w+)\s*\{([^}]+)\}", source)}


def fail(message: str) -> None:
    raise ValueError(message)


def timestamp(value: str) -> None:
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})", value):
        fail("emittedAt must be RFC 3339")
    datetime.fromisoformat(value.replace("Z", "+00:00"))


def check_event(value: object, expected_sequence: int, enums: dict[str, set[str]], *, transient: bool = False, single_stream: bool = True) -> str:
    if not isinstance(value, dict): fail("event must be an object")
    required = BASE_FIELDS if transient else BASE_FIELDS | {"eventId"}
    missing = required - value.keys()
    if missing: fail(f"event missing {sorted(missing)}")
    if transient and "eventId" in value: fail("transient eventId must be absent")
    if not transient and (TRANSIENT_BODIES & value.keys()): fail("transient body in durable event")
    if transient and (DURABLE_BODIES & value.keys()): fail("durable body in transient event")
    bodies = (TRANSIENT_BODIES if transient else DURABLE_BODIES) & value.keys()
    if len(bodies) != 1: fail("event must contain exactly one body")
    if value.keys() - required - DURABLE_BODIES - TRANSIENT_BODIES: fail("unexpected envelope fields")
    if any(not isinstance(value[key], str) for key in required): fail("envelope fields must be strings")
    if not value["meetingId"] or not value["streamId"]: fail("meetingId/streamId must be nonempty")
    if not transient and not value["eventId"]: fail("durable eventId must be nonempty")
    if single_stream and value["streamId"] != value["meetingId"]: fail("P2 streamId must equal meetingId")
    if transient:
        if value["eventSequence"] != "0": fail("transient eventSequence must be 0")
    elif value["eventSequence"] != str(expected_sequence):
        fail(f"eventSequence expected {expected_sequence}, got {value['eventSequence']}")
    timestamp(value["emittedAt"])
    name = next(iter(bodies)); body = value[name]
    if not isinstance(body, dict): fail("event body must be an object")
    if name == "audioAck" and body.get("durable") is not True: fail("audioAck.durable must be true")
    if name == "resumePageEnd":
        if set(body) != {"lastEventSequence", "hasMore"}: fail("resumePageEnd fields differ")
        if not isinstance(body["hasMore"], bool): fail("resumePageEnd.hasMore must be boolean")
    uint64_fields = {
        "audioAck": ("acceptedStreamSequence", "acceptedAudioPositionMs"),
        "resumePageEnd": ("lastEventSequence",),
        "transcriptUpsert": ("startMs", "endMs"),
        "audioContinuity": ("afterStreamSequence", "sourceGeneration", "gapDurationMs"),
    }
    for field in uint64_fields.get(name, ()):
        if field in body and (not isinstance(body[field], str) or not re.fullmatch(r"[0-9]+", body[field])):
            fail(f"{name}.{field}: uint64 must use a decimal string in Proto JSON Mapping")
    enum_fields = {"sessionState": {"state": "MeetingState"}, "transcriptUpsert": {"operation": "UpsertOperation"}, "translationUpsert": {"operation": "UpsertOperation"}, "speakerUpdate": {"kind": "SpeakerUpdateKind"}, "capabilityChanged": {"availability": "CapabilityAvailability"}, "finalization": {"stage": "FinalizationStage", "inputState": "InputState"}, "error": {"domain": "ErrorDomain", "severity": "ErrorSeverity", "userAction": "UserAction"}, "audioContinuity": {"reason": "AudioDiscontinuityReason", "action": "AudioContinuityAction"}}
    for field, enum_name in enum_fields.get(name, {}).items():
        if body.get(field) not in enums[enum_name]: fail(f"{name}.{field}: unknown {enum_name} value {body.get(field)!r}")
    return name


def check_checksum(value: object) -> None:
    if not isinstance(value, dict): fail("audio checksum vector must be an object")
    if set(value) != {"streamSequence", "startPositionMs", "durationMs", "audio", "checksum"}: fail("audio checksum vector fields differ")
    if not all(isinstance(value[key], str) and re.fullmatch(r"[0-9]+", value[key]) for key in ("streamSequence", "startPositionMs")): fail("audio uint64 fields must be decimal strings")
    if not isinstance(value["durationMs"], int) or isinstance(value["durationMs"], bool): fail("durationMs must be an integer")
    if not isinstance(value["checksum"], str) or not re.fullmatch(r"[0-9a-f]{64}", value["checksum"]): fail("checksum must be lowercase SHA-256 hex")
    try: audio = base64.b64decode(value["audio"], validate=True)
    except (ValueError, base64.binascii.Error): fail("audio must be base64 bytes")
    if hashlib.sha256(audio).hexdigest() != value["checksum"]: fail("audio checksum mismatch")


def rows(name: str) -> list[dict]:
    lines = (FIXTURES / name).read_text(encoding="utf-8").splitlines()
    if not lines: fail(f"{name}: JSONL must not be empty")
    return [json.loads(line) for line in lines]


def expect_invalid(name: str, expected: str, enums: dict[str, set[str]], *, transient: bool = False) -> None:
    value = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
    try: check_event(value, 1, enums, transient=transient)
    except ValueError as exc:
        if str(exc) != expected: fail(f"{name}: expected only {expected!r}, got {exc!s}")
    else: fail(f"expected invalid fixture to fail: {name}")


def main() -> None:
    if not CONTRACTS.is_dir():
        raise SystemExit("contracts directory is missing")
    count = 0
    for path in CONTRACTS.rglob("*.json"):
        json.loads(path.read_text(encoding="utf-8"))
        count += 1
    enums = proto_enums(); sequences: dict[str, int] = {}; durable: set[str] = set(); transient: set[str] = set(); durable_count = 0
    for name in ("realtime-events.jsonl", "capability-scenarios.jsonl"):
        for event in rows(name):
            meeting_id = event.get("meetingId", "")
            sequence = sequences.get(meeting_id, 0) + 1
            durable.add(check_event(event, sequence, enums)); sequences[meeting_id] = sequence; durable_count += 1
    for event in rows("realtime-wss-transient.jsonl"):
        transient.add(check_event(event, 0, enums, transient=True))
    if durable != DURABLE_BODIES: fail(f"durable body coverage missing: {sorted(DURABLE_BODIES - durable)}")
    if transient != TRANSIENT_BODIES: fail(f"transient body coverage missing: {sorted(TRANSIENT_BODIES - transient)}")
    expect_invalid("realtime-event-two-bodies.invalid.json", "event must contain exactly one body", enums)
    expect_invalid("realtime-event-missing-stream-id.invalid.json", "event missing ['streamId']", enums)
    expect_invalid("transient-in-durable.invalid.json", "transient body in durable event", enums)
    expect_invalid("wss-positive-sequence.invalid.json", "transient eventSequence must be 0", enums, transient=True)
    expect_invalid("wss-resume-page-end-negative.invalid.json", "resumePageEnd.lastEventSequence: uint64 must use a decimal string in Proto JSON Mapping", enums, transient=True)
    seen: dict[str, int] = {}
    for index, event in enumerate(rows("meeting-global-reset.invalid.jsonl"), 1):
        meeting_id = event.get("meetingId", "")
        sequence = seen.get(meeting_id, 0) + 1
        try: check_event(event, sequence, enums, single_stream=False)
        except ValueError as exc:
            if index != 2 or str(exc) != "eventSequence expected 2, got 1": fail(f"global reset fixture: unexpected reason {exc}")
            break
        seen[meeting_id] = sequence
    else: fail("expected global reset fixture to fail")
    check_checksum(json.loads((FIXTURES / "audio-frame-checksum.valid.json").read_text(encoding="utf-8")))
    try: check_checksum(json.loads((FIXTURES / "audio-frame-checksum.invalid.json").read_text(encoding="utf-8")))
    except ValueError as exc:
        if str(exc) != "audio checksum mismatch": fail(f"invalid checksum fixture: unexpected reason {exc}")
    else: fail("expected audio checksum fixture to fail")
    print(f"CONTRACT_FIXTURES_OK json={count} durable_events={durable_count} transient_messages={len(transient)} negatives=7")


if __name__ == "__main__":
    main()
