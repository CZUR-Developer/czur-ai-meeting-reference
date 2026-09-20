# Public contracts

Versioned OpenAPI, JSON Schema, and realtime event definitions belong here.
Every contract change must include positive and negative fixtures plus a
backward-compatibility note.

## Meeting event replay compatibility

`GET /v1/meetings/{meetingId}/events` uses one meeting-global
`eventSequence`; it never filters the page by `streamId`. Every returned
`MeetingEvent` still requires `streamId` as ServerEnvelope metadata and must
contain exactly one body selected by the schema `oneOf`. Implementations of an
earlier unpublished draft that paged each stream independently must migrate to
the meeting-global cursor before publishing the contract.
