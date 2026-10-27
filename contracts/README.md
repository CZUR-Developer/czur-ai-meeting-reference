# Public contracts

This directory contains the current public OpenAPI, JSON Schema, and realtime
event definitions. Every contract change must update the corresponding positive
and negative fixtures and document its effect on consumers.

The OpenAPI labels operations the reference implementation does not serve:
`x-implementation-status: reserved` together with a "（预留，未实现）" summary means
the reference HTTP dispatcher has no route and returns 404. Operations marked
"（可选扩展）" require a partner implementation and are not part of the default
process; the reference dispatcher has no route for them either and returns 404.

## Optional post-meeting speaker identity

`speaker-identity.schema.json` defines the optional `speaker.identity` JSON
Artifact. Each row references a real final transcript `sessionSpeakerId` and
contains either a stable cross-meeting `personId` plus `displayName`, or two
null values for an unmatched speaker. Provider feature IDs never substitute
for standard person IDs. See the valid and incomplete-person invalid fixtures.

`POST /v1/meetings/{meetingId}/speakers/{sessionSpeakerId}:enroll` accepts
`{"displayName":"..."}` with the existing user Bearer authentication and
returns `personId`, `displayName`, and `manifestRevision`. It applies only to
owned READY client meetings with the capability and retained recording.
Registration publishes an immutable identity Artifact and updates the
client Manifest revision, keeping original timing, other Artifacts,
and the original read-only genTime link. Anonymous result links do not grant
write access. Assemblies without a registered identity Provider leave this extension disabled;
without a registered Provider there is no cross-meeting capability or identity
Artifact; transcript/translation/summary/audio behavior is independent.

## Model session

`POST /v1/meetings/{meetingId}:modelSession` is an authenticated
operation. It returns the configured Adapter ID, a temporary model endpoint,
an activation deadline and Adapter initialization values. The Tingwu example
uses `initParams.taskId`; its JoinUrl already contains the connection credential,
so the optional `token` field is absent. See `model-session.valid.json` and
`model-session.missing-endpoint.invalid.json`. This is the default Android
client-direct model path. The Create v1 `realtime` response and
`/v1/realtime/{streamId}` WSS audio contract describe a separate server-audio
path; they do not mean the default reference process starts a WSS listener.

## Client transcript append

`POST /v1/meetings/{meetingId}:startClient` and
`POST /v1/meetings/{meetingId}/events:append` routes apply only to meetings
explicitly started with client event input. The first append version accepts
one `transcriptUpsert` per request; the server assigns the canonical
`ServerEnvelope` event ID, meeting-global sequence, and timestamp. Meetings
without a client-input marker use the separate server-audio path. See the
`client-transcript-append` valid and invalid fixtures.

## Client input usage

`POST /v1/meetings/{meetingId}/usage:submit` freezes actual captured audio
duration before Stop. For standard 16 kHz mono PCM16,
`audioDurationMs` is captured bytes divided by 32, truncated to whole
milliseconds. Silence counts; retransmission and summary waiting do not.
The server persists the first duration and Reservation version; same-value
replay returns 200 and a different value returns a conflict. Measured zero
is valid; missing usage remains unknown. Stop commits this same frozen usage
before publishing READY; the Stop body is empty or `{}`.
Missing usage is not treated as measured zero and must not create a zero-usage
settlement. See `client-input-usage.valid.json`
and `client-input-usage.negative.invalid.json`; the HTTP integration test
consumes these fixtures directly.

## Meeting event replay

`GET /v1/meetings/{meetingId}/events` uses one meeting-global
`eventSequence`; it never filters the page by `streamId`. Every returned
`MeetingEvent` still requires `streamId` as ServerEnvelope metadata and must
contain exactly one body selected by the schema `oneOf`. Clients use the
meeting-global cursor to continue pagination.

## Manifest timestamps and strings

`ArtifactRef.createdAt` is required by `meeting-manifest.schema.json`.
Producers populate every Artifact creation instant before producing a Manifest;
consumers reject an Artifact that omits it.

Manifest timestamps use the documented strict RFC 3339 subset and canonicalize
to UTC `Z` within four-digit years `0000..9999`. Boundary years `0000` and
`9999` accept only `Z`; years `0001..9998` may use the declared offset range.
`24:00`, leap seconds, more than nine fractional digits, and values outside this
range are invalid. String fields carrying the Schema `\S`
pattern use ECMAScript whitespace semantics, including NBSP and BOM.
