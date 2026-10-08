# Public contracts

Versioned OpenAPI, JSON Schema, and realtime event definitions belong here.
Every contract change must include positive and negative fixtures plus a
backward-compatibility note.

## Model session compatibility

`POST /v1/meetings/{meetingId}:modelSession` is a separate authenticated
operation. It returns the configured Adapter ID, a temporary model endpoint,
an activation deadline and Adapter initialization values. The Tingwu example
uses `initParams.taskId`; its JoinUrl already contains the connection credential,
so the optional `token` field is absent. See `model-session.valid.json` and
`model-session.missing-endpoint.invalid.json`. This addition does not change
the existing Create v1 `realtime` response or `/v1/realtime/{streamId}` WSS
audio contract; older clients can continue using that path.

## App transcript append compatibility

The new `POST /v1/meetings/{meetingId}:startClient` and
`POST /v1/meetings/{meetingId}/events:append` routes apply only to meetings
explicitly started with client event input. The first append version accepts
one `transcriptUpsert` per request; the server assigns the canonical
`ServerEnvelope` event ID, meeting-global sequence, and timestamp. Meetings
without a client-input marker keep the existing WSS audio behavior. See the
`client-transcript-append` valid and invalid fixtures.

## Client input usage compatibility

`POST /v1/meetings/{meetingId}/usage:submit` freezes actual captured audio
duration before the existing Stop operation. For standard 16 kHz mono PCM16,
`audioDurationMs` is captured bytes divided by 32, truncated to whole
milliseconds. Silence counts; retransmission and summary waiting do not.
The server persists the first duration and Reservation version; same-value
replay returns 200 and a different value returns a conflict. Measured zero
is valid; missing usage remains unknown. Stop commits this same frozen usage
before publishing READY, keeping the existing empty or `{}` Stop body.
Older clients without this submission retain their result display behavior
and do not create a zero-usage settlement. See `client-input-usage.valid.json`
and `client-input-usage.negative.invalid.json`; the HTTP integration test
consumes these fixtures directly.

## Meeting event replay compatibility

`GET /v1/meetings/{meetingId}/events` uses one meeting-global
`eventSequence`; it never filters the page by `streamId`. Every returned
`MeetingEvent` still requires `streamId` as ServerEnvelope metadata and must
contain exactly one body selected by the schema `oneOf`. Implementations of an
earlier unpublished draft that paged each stream independently must migrate to
the meeting-global cursor before publishing the contract.

## Manifest `createdAt` compatibility

`ArtifactRef.createdAt` is required by `meeting-manifest.schema.json`. An
earlier unpublished fixture omitted this field even though the Java contract
already required it. Implementations based on that draft must populate every
Artifact creation instant before producing a Manifest; consumers must reject an
Artifact that omits it. This is a pre-release contract correction, not a
compatibility path for stored production data.

Manifest timestamps use the documented strict RFC 3339 subset and canonicalize
to UTC `Z` within four-digit years `0000..9999`. Boundary years `0000` and
`9999` accept only `Z`; years `0001..9998` may use the declared offset range.
Unpublished producers that emitted `24:00`, leap seconds, more than nine
fractional digits, or values outside this range must normalize them before
publication. String fields carrying the Schema `\S`
pattern use ECMAScript whitespace semantics, including NBSP and BOM.
