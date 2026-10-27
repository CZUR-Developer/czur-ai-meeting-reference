# Partner Integration Guide

[English](partner-integration-guide.md) | [简体中文](partner-integration-guide.zh-CN.md)

> Integration main line | Module-level notes in the [docs index](README.md) | Integration order in the [getting-started guide](partner-getting-started.md)

This document is for partners who need to do secondary development. It explains how to start a
replaceable Android App, a Java 21 backend and a static Web results page from this repository, and
where each capability is extended. It only describes the public boundary of the reference
implementation; it does not bring CZUR accounts, model keys, private addresses or system services
into the reference project.

If you are new to the project, read the [getting-started guide](partner-getting-started.md) first and
complete a real-transcript loop with the preparation checklist. All third-party platforms, resources,
capabilities and missing-capability behavior are collected in the
[model and capability guide](model-and-capability-guide.md).
This document focuses on the existing reference implementation and extension interfaces; partners are
not expected to know internal development stages or CZUR private projects.

## 1. Delivery scope

The reference project contains the following parts, each of which can be opened and built on its own:

| Directory | Role | Entry |
| --- | --- | --- |
| `backend-core/` | Meeting state, contracts, capability plans, persistence ports and artifact ports | Plain Maven JAR |
| `backend-reference/` | Reference HTTP process, demo login, Mock and default assembly | `ReferenceClientMeetingMain` |
| `android-reference-app/` | Kotlin First login, pre-meeting settings, in-meeting transcript/translation, stop and result actions | Android Studio project |
| `web-reference/` | Reads the standard Manifest and displays transcript, translation, summary and available media | Static build output |
| `contracts/` | OpenAPI, JSON Schema, realtime events and fixtures | Protocol reference |
| `deploy/` | Delivery-stage Dockerfile, Compose and the single V001 initialization script | Product deployment example |

The reference App provides the Web result action by default. E-mail, local PDF, QR code/mini-program
or a partner's own result renderer all go through result action extensions and are not hard-coded in
the core meeting state machine. The reference App already integrates the SDK screen-capture black-box
session; the built-in processing is optional, output is a Bitmap or a File, callers never touch
internal algorithms, and the interval, final frame and window exclusion remain decided by the App.
For integration details see "SDK screen-capture extension" below.

## 2. Local development and short meetings on the LAN

Development verification uses the local Java 21, Maven, Python 3, Node.js/npm and Android SDK
directly; Docker is not used as a check tool. The Dockerfile and Compose are only for delivery
deployment; if the development machine has no isolated database you can temporarily run `mysql:5.7`,
but the container, data volume and account are not deliverables.
When using the delivery Docker image, follow the [deployment guide](../deploy/README.md) to load
configuration explicitly, initialize external MySQL and start the app; the same page explains the
relationships between the container upstream, Android HTTPS and the static Web.

```bash
cd czur-ai-meeting-reference
mkdir -p ~/.config/ai-meeting
cp tools/reference-client.env.example ~/.config/ai-meeting/reference-client.env
export REFERENCE_ENV_FILE=~/.config/ai-meeting/reference-client.env
# Edit this out-of-repo file: database, demo account, model Adapter and a writable artifact directory
bash tools/migrate-reference-client.sh
bash tools/run-reference-client-backend.sh
```

Keep the runtime configuration outside the repository (you can also use another out-of-repo directory
and point `REFERENCE_ENV_FILE` at it).
Do not copy it to `tools/reference-client.env` in the repository: the public boundary check scans by
workspace, and a configuration file with a filled-in password inside the repository makes
`python3 tools/check-public-boundary.py` fail.

Open another terminal to check the backend and the Web:

```bash
bash tools/check-reference-backend.sh
REFERENCE_HTTP_BASE_URL='http://192.0.2.10:8080' \
REFERENCE_WEB_BIND=0.0.0.0 REFERENCE_WEB_PORT=8090 \
bash tools/run-reference-web.sh
```

Replace the documentation placeholder address `192.0.2.10` with a device-reachable PC LAN address.
For the complete real-model, HTTPS and SDK build order see
[section 4 of the getting-started guide](partner-getting-started.md#4-complete-the-first-real-meeting-step-by-step);
the JSON model endpoint that the template defaults to must be served separately, so copying the
template alone does not give real recognition capability.

The reference client entry starts only the HTTP business API; Android gets short-lived material
through `POST /v1/meetings/{meetingId}:modelSession` and then connects to the model directly, so audio
does not pass through the backend. Developers inject a device-reachable HTTPS backend address and a
separate results page address into the App build on the PC; end users only log in normally and never
configure an IP or URL. The device's own `127.0.0.1` does not mean the development computer.
If the development computer has no HTTPS entry yet, follow
[local LAN HTTPS integration](local-lan-https.md) to generate an out-of-repo certificate, start the
Node streaming TLS entry and put the PEM at `app/src/debug/res/raw/reference_debug_ca.pem` so the Debug
APK trusts that CA.
Production Release builds do not carry the development CA and use the deployer's official HTTPS
certificate; `adb reverse` is for diagnostics only.

The Android deployment parameters live in the out-of-repo local file
`android-reference-app/deployment.local.properties` (already Git-ignored; the template is the
`.example` in the same directory): the two keys `backendUrl` and `resultWebUrl` are injected into
`BuildConfig` at build time. A missing file or key fails the build immediately and never produces a
package pointing at the author's local address; keep one file each for test and production and switch
with `REFERENCE_DEPLOYMENT_FILE`. There is no address input UI and no address fallback source on the
device.
For build and device start commands see the [tool notes](../tools/android-device/README.md).

### Database initialization

The database is always MySQL 5.7 and only `jdbc:mysql://` addresses are supported (scope in the
[backend notes](../backend-reference/README.md#数据库范围)). The deployer creates the isolated
database first, then runs the repository's current `V001__initial_schema.sql` through the only entry
`bash deploy/migrate.sh` (the local template goes through `bash tools/migrate-reference-client.sh`).
The script registers `V001__initial_schema` only after the whole SQL succeeds; the readiness check at
application startup uses the registration table as the source of truth and never creates or alters
tables automatically.
Running the SQL file by hand does not write the registration, and the backend refuses to start with
the migration unregistered (`MIGRATION_MISSING`); if you really must run it by hand, you must
additionally run `INSERT INTO ai_trans_schema_migrations(version) VALUES ('V001__initial_schema')`
after the whole SQL succeeds, and this cannot be skipped. The script only adds the dedicated
`ai_trans_*` tables; it does not create databases/accounts and does not modify old business tables.
Details are in the [database baseline](../deploy/migrations/README.md). If the development machine has
no isolated database, only Mock/module tests can be run, and they must not be recorded as real LAN
meeting acceptance.

### Background workers for the MySQL client-model

The MySQL 5.7 path of the client-model now also supports the Outbox and expired-Reservation cleanup,
but it does not start threads automatically. The host calls `JdbcClientModelWorkers` periodically from
its own scheduler:

- `outboxDispatcher(context, handlerRegistrations, config)`: creates the post-commit Outbox dispatcher.
  Each event type must be registered explicitly with a Handler; unregistered types are never picked
  up. MySQL 5.7 uses in-transaction `SELECT ... FOR UPDATE` plus a conditional UPDATE and does not rely
  on `SKIP LOCKED` or `RETURNING`.
- `expiredReservationCleanupWorker(context, canceller, ids, config)`: creates the expired-Reservation
  cleanup Worker; `canceller` must be this assembly's lease-aware entitlement port and must not be
  replaced by a plain cancel.

Both reuse the single `JdbcDatabaseContext` connection pool and `runOnce()` never creates threads; the
host is responsible for scheduling, the lease owner, batch limits and stopping the calls before the
context is closed. The wait window of the frozen-usage timeout scan must be derived with
`JdbcFrozenUsageTimeoutSweeper.Config.derive(heartbeatIntervalMs, reservationTtl)` and must not use a
constant unrelated to the reservation TTL. The gate command for real MySQL 5.7:

```bash
REFERENCE_TEST_MYSQL_DATABASE_URL='jdbc:mysql://127.0.0.1:3306/ai_meeting_test' \
REFERENCE_TEST_MYSQL_DATABASE_USER=... \
REFERENCE_TEST_MYSQL_DATABASE_PASSWORD=... \
mvn -pl backend-reference -am -Dtest=JdbcMysqlWorkerIntegrationTest -DfailIfNoTests=false test
```

### Model configuration

`tools/reference-client.env.example` provides placeholder values only. The smallest deterministic
wiring can use `sample-json-ws`; switch to an approved Provider only for real verification, injecting
the key through untracked local configuration or a secret:

```dotenv
AI_MEETING_MODEL_ADAPTER=tingwu-realtime
AI_MEETING_MODEL_LANGUAGES=zh-CN,en-US,ja-JP
AI_MEETING_MODEL_CAPABILITIES=transcript.realtime,translation.realtime
```

The server intersects Provider, Product Profile, entitlement and deployment limits to obtain the
available capabilities; the deployer fills `AI_MEETING_MODEL_LANGUAGES` with the languages the actual
Provider supports, and `/v1/capabilities` returns that declaration directly. When it is not
configured the value is `zh-CN,en-US`; there is currently no automatic intersection with a Provider/Profile
language list and no query to the model for its languages. The reference UI does not fix a vendor list.
The current Tingwu implementation already maps seven standard languages — Chinese, English, Japanese,
Korean, German, French and Russian — and deployments only declare the subset actually enabled.

The ASR issuer of the default command-line entry supports Tingwu and `sample-json-ws`, and can
optionally combine the standalone text-translation sample described below. Changing the endpoint and
declarations of an existing implementation is a configuration change; to integrate a new model you
need to inject a `ModelSessionIssuer` in your own backend host, register the matching `ModelAdapter` on
Android, and declare the languages/capabilities that implementation actually supports. Merely setting
`AI_MEETING_MODEL_ADAPTER` to an arbitrary string does not generate a vendor implementation
automatically, and the meeting state machine does not need to change.
Real third-party keys must not be written into source code, templates, the APK, logs or this manual.

## 3. Login and authentication extensions

Login is assembled through App-layer plugins, and the meeting core consumes an already verified
identity:

1. The App uses `IdentityProvider` to read, save, restore and clear login sessions.
2. The reference implementation's `PersistentReferenceIdentityProvider` saves only restorable session
   material and never the password; logging out clears the local session.
3. The backend verifies the Bearer credential through `IdentityProvider`, and the meeting core only
   receives an already verified `VerifiedIdentity`.

Partners can replace the App Provider in their own `MeetingAppAssembly`, or assemble their own identity
Provider in the backend. The replacement point does not require changes to `MeetingCoordinator`, the
meeting state machine, model Adapters or the results page. CZUR's `ContentProvider/UserHandler` belongs
to product-side implementations and is not a default dependency of the reference project.

The login interaction is replaced separately through the App's `AppLoginEntry`: when not logged in the
account button calls `open`, when logged in it calls `signOut`. The default entry opens the reference
form when logged out and logs out of the reference account when logged in; cancelling or a failed login
keeps the original session, only a successful new login replaces it, and only an explicit logout clears
it. Partners assemble their own `IdentityProvider` and `registerLoginEntry(...)` after
`MeetingAppAssembly.initialize(context)` and before creating the meeting page:

```kotlin
MeetingAppAssembly.registerIdentityProvider(partnerIdentityProvider)
MeetingAppAssembly.registerLoginEntry(object : AppLoginEntry {
    override fun open(activity: Activity, defaultEntry: () -> Unit, onIdentityChanged: () -> Unit) {
        // Open the partner's own login UI; its success callback updates partnerIdentityProvider and then notifies the page.
        partnerLogin.open(activity) { onIdentityChanged() }
        // If you use the reference username/password flow, call defaultEntry() instead.
    }

    override fun signOut(activity: Activity, defaultSignOut: () -> Unit, onIdentityChanged: () -> Unit) {
        // Logout or account switching for a system account/SSO is up to the plugin; it may also call defaultSignOut to keep the reference logout.
        partnerLogin.signOut(activity) { onIdentityChanged() }
    }
})
```

Registering only an `open` lambda still compiles, and `signOut` falls back to the default
implementation (clearing the reference session) when logged in.
The reference App only has the two actions "log in / log out" by default and provides no account
switching menu; after replacing the entry it is up to the partner to implement logout or switching,
and the page does not run the default logout first. Session save/restore is the partner Provider's
responsibility, and the callback only triggers a re-read of identity and capabilities; the SDK does
not request accounts, show login UI or reference system accounts.
The default plugin pairs the session with the build-time backend: upgrading/restarting the same backend
restores a valid login, while an APK for a different backend does not reuse the original backend
session. Partner identity plugins should keep this ownership; there is no runtime entry to switch the
deployment address.

## 4. Model and capability extensions

### Realtime model

On Android, `ModelAdapter` receives Provider data and normalizes transcript, translation, revisions,
speakers and end signals into public events. To add a Provider:

1. implement `ModelAdapter` and its lifecycle;
2. register the Adapter ID in `ModelAdapterRegistry`;
3. implement the matching `ModelSessionIssuer` in the backend, issuing only short-lived connection
   material;
4. declare the supported languages and capabilities in the deployment configuration;
5. verify partial/final, revisions and stop timing with the existing Adapter contract tests.

For the complete skeleton, registration code, time base and common errors see the
[custom model Adapter tutorial](custom-model-adapter.md).

Raw model JSON and vendor short codes stay inside the corresponding Adapter/Issuer; permanent keys are
held only by the backend issuer's external configuration and never placed in the Android Adapter, the
APK or the Web. When the device connects to the model directly, the backend receives normalized
standard events and usage facts, and separately receives retained recordings/screenshots according to
this meeting's policy.

`ModelHttpRequest` is a neutral request description handed to the SDK for execution, supporting a
custom `method`, `headers`, `contentType` and text `body`; the address comes from this meeting's Server
Grant. Realtime Adapters describe the handshake through `connectionRequest(grant)`, and
summary/text-translation Adapters describe requests through `request(...)`. For example a partner can
use `x-api-key` or a pre-signed URL, and the SDK does not append an extra Bearer.
The previous default behavior — realtime GET, summary POST/JSON and Bearer for a non-empty token —
is preserved.
Threading, timing and extension interfaces are in the
[public Android core notes](../android-reference-app/meeting-client-core/README.md#模型请求描述与独立文本翻译).

### Active heartbeat and abandoned-meeting closure

This section is the authoritative description of heartbeats and abandoned-meeting closure; other
documents only summarize and link here.

The reference deployment uses a "server-side heartbeat" to decide whether a meeting is still alive.
`limits.heartbeatIntervalMs` in the Create response is the interval the server expects (default
120000; `0` means an older server does not require heartbeats). The reference App has a foreground
Service call `MeetingSession.heartbeat()` at that interval: it starts after entering `onStarted` and is
cancelled on the `READY`/`ENDED`/`FAILED`/`CLOSED` terminal states or when the Service is destroyed;
after the user confirms a stop it keeps sending until the server accepts the Stop. Inside the SDK it
is sent on a dedicated single thread, not shared with the transcript/translation business IO queues,
with at most one in flight at a time. Partners writing their own host must follow the same start/stop
timing.

The server only records the server-side times when each meeting "really started" and "was last
active". **The first heartbeat** both freezes the "really started" time and is the dividing line at
which the meeting counts as started; having no first heartbeat and losing contact after the first
heartbeat lead to different outcomes:

- No first heartbeat was ever received and the meeting is still in `CREATED`/`STARTING`: 15 minutes
  after creation it is cancelled as "never started", settled with 0 usage, ending in `CANCELLED` with
  no result manifest. Meetings that already submitted frozen audio usage (`audio_duration_ms`) do not
  take part in this kind of cancellation and continue to normal Stop closure.
- Contact was lost after the first heartbeat: when `now - last_active_at` exceeds
  `max(3 × heartbeatIntervalMs, 10 分钟)`, closure is by abandonment: first the abandonment decision is
  persisted (frozen settlement duration, target terminal state and decision time), then the
  entitlement port is called to settle, and finally results and the `READY_WITH_WARNINGS` terminal
  state are written. The settlement duration is the last-active time minus the real start, truncated to
  the deployment's maximum meeting duration; audio usage already frozen by a normal Stop takes
  precedence, and those meetings do not go through abandonment. Once the decision is persisted,
  heartbeats, transcripts, translations, usage and minutes are all rejected and the meeting cannot go
  back to being a normal active meeting; if settlement fails only the decision is kept and the next
  round retries settlement only, without double charging.

The two closure paths differ in result availability: `CANCELLED` has no result manifest;
`READY_WITH_WARNINGS` can still read the result manifest, but it is closed on heartbeat facts and lacks
the complete wrap-up of a normal Stop (minutes the client never submitted, sealed audio, etc. are not
filled in). Heartbeats do not drive meeting state and do not directly trigger settlement; they only
decide the abandonment window and settlement duration — but "whether there was a first heartbeat"
decides whether closure takes the cancellation or the abandonment path, so the host must not skip the
first heartbeat. The reference implementation already includes the scanner; partners only need to make
sure the host calls it at the timing above and do not have to write their own timed closure.

Accepting a normal Stop and closing out its results are also separate. The server first commits
`STOPPING` and the "pending wrap-up" facts in the same transaction, then tries result closure
synchronously; a failed synchronous closure does not make the Stop report failure and the client does
not need to resend the Stop. The reference process's 30-second scheduler retries the same meeting within
the grace window, settling with the same stable key as a normal Stop; repeated scans only replay
committed results and never charge twice or publish artifacts twice; on success the meeting goes to
`READY` and the pending item is deleted.

There is another recovery chain for the case where the client crashes after `usage:submit` froze audio
usage but before it sent the Stop: if no Stop arrives within `max(3 × heartbeatIntervalMs, 10 分钟)`,
the server completes normal settlement and result publication with the frozen usage, ending in `READY`
or `READY_WITH_WARNINGS` depending on this meeting's result capabilities. This chain does not settle 0
and does not write the `meeting.abandoned` abandonment warning; it is mutually exclusive with the
abandonment scan, so each meeting is closed by exactly one chain. After recovering, the client must not
submit another `usage:submit` with a different usage; only the first frozen value may be replayed for
the same meeting.

The wait window must not exceed the entitlement reservation lifetime: the effective value is
`min(max(3 × heartbeatIntervalMs, 10 分钟), AI_MEETING_ENTITLEMENT_RESERVATION_TTL_MS - 60 秒)`.
When the TTL cannot hold the derived window, the startup log prints
`derivedMs/effectiveMs/reservationTtlMs/marginMs` and the window is compressed to the TTL; if the TTL
cannot leave even a 60-second margin plus one 30-second scheduling period, assembly fails outright. The
example configuration sets the reservation TTL to 12 minutes and the Grant TTL to 15 minutes to keep
the full 10-minute wait; shortening the TTL shortens the recovery wait accordingly, but never makes the
backend fail permanently after expiry.

For a final submission of a bound reservation, `bound_meeting_id` ownership is the only gate: an
expired `expires_at` does not block settlement, and a missing or stale `usage_expected_version`
recorded at freeze time does not block it either — the Provider locks the reservation and submits with
the current version. Settlement and abandonment share the same idempotent operation ledger, so repeated
scans only replay the first deduction. The expired-reservation cleanup Worker only processes records
that have neither `bound_meeting_id` nor `bound_at`: any binding evidence, terminal state or single-row
persisted fact that is damaged is never cleaned up; damaged rows are skipped and counted and never make
a whole scan batch throw on every round.

### Optional standalone text translation

When only ASR is provided, you can combine `ModelSessionIssuer` and `TranslationSessionIssuer` on the
server through `TextTranslationModelSessionIssuer`, and register the matching `TranslationAdapter` on
Android. The reference command-line entry already has an optional JSON sample, configured externally as
follows:

```dotenv
AI_MEETING_SAMPLE_JSON_TRANSLATION_ENDPOINT=https://translation.example.invalid/translate
AI_MEETING_PROFILE_OPTIONAL_CAPABILITIES=translation.realtime
AI_MEETING_ENTITLEMENT_SUPPORTED_CAPABILITIES=transcript.realtime,translation.realtime
AI_MEETING_MODEL_CAPABILITIES=transcript.realtime,translation.realtime
```

The deployer must provide that HTTP service and declare the languages ASR and translation actually
support. The sample Adapter ID is `sample-json-translation`; requests contain
`sourceSegmentId/sourceRevision/sourceLanguage/targetLanguage/text`, and the response is
`{"text":"译文","sourceLanguage":"实际源语种（可省略）"}`. The reference issuer carries no token and is
valid for five minutes; it is for protocol wiring, contains no real translation model, and passing with
the sample cannot be used to claim model quality is verified. Production vendors implement their own
issuer and Adapter; long-term keys are never handed to the App.

The first material comes back with the same meeting's `:modelSession` as the optional
`translationSession`; the SDK calls it segment by segment for confirmed transcript and chooses the
target language from the two pre-meeting languages according to the actual source language. When less
than 30 seconds remain before expiry and another segment is requested, only the same meeting's
`POST /v1/meetings/{meetingId}:translationSession` is called to renew, without rebuilding ASR or the
audio source and without background polling. After combining, ASR's native translation is turned off
while bilingual recognition is kept; when not configured, Tingwu's all-in-one native translation path
stays. Stop stops the source first, and translations already accepted still close along the original
standard events and confirmation flow without a separate meeting state machine.

### Optional capabilities

When a capability is missing, the remaining chain stays usable and no empty artifacts or fake entries
are produced:

| Capability | App behavior when missing | Web behavior when missing |
| --- | --- | --- |
| `translation.realtime` | Hides the translation entry, keeps the transcript only | Hides the translation area when there is no translation artifact |
| `summary.structured` | Does not show the summary switch, does not request summary material, and the text is still readable after Stop | Hides the summary area when there is no summary artifact |
| `speaker.diarization.session` | Does not show a speaker list and never treats an empty ID as a speaker | Shows the transcript per actual data and never invents separated results |
| `speaker.identity.cross_meeting` | Does not request cross-meeting identity; a provisional session Speaker never impersonates a cross-meeting identity | Hides the automatic-recognition option; manual renaming still works while session numbers exist, with no separate person column |

`transcript.realtime` is the mandatory capability of the reference meeting; when it is not declared the
App keeps the start button disabled and the backend rejects Create. The behaviors above are driven
jointly by capability declarations and the Create decision for this meeting; capabilities must not be
inferred from a fixed "Chinese/English" setting or from "Tingwu is configured".

### Audio retention policy

`audio.original` is a result artifact type; whether to keep audio uses the existing Create field
`artifactPolicy.retainAudio` and is not put into the model capability list. The default reference App
retains audio at SDK runtime and does not retain it in Mock runs. Partners can reuse the existing API
in their own pre-meeting assembly to turn it off:

```kotlin
val withoutRecording = options.withCreateParameters(
    title = options.title(),
    timezone = options.timezone(),
    retainAudio = false,
)
// Pass withoutRecording to the existing meeting start flow; it still uses the same SDK and result chain.
```

This option does not disable realtime audio capture or transcript/translation; it only controls
retention and upload of this meeting's audio. When the Manifest after stop contains no audio the Web
hides the player and the text/summary still deliver normally. The reference cross-meeting identity
implementation depends on post-meeting audio and does not request that optional capability when
retention is off. The reference page currently adds no separate audio switch; partners can decide the
parameter in their own product flow, and no new Server capability or result state machine is needed.

Demo capture likewise uses the existing Create field `artifactPolicy.presentationCapture`;
`presentation.capture` is its result artifact type and is not model-capability configuration. When it is
not enabled no capture starts and the Web shows no demo area. SDK builds already integrate the black-box
session with built-in processing; the clean Mock provides no capture engine and keeps its demo switch
disabled.

### SDK screen-capture extension

System authorization and the foreground Service are still managed by the App; after obtaining a
`ScreenCaptureClient`, create an independent session for each meeting. The reference App explicitly
enables built-in processing; partners may also choose `enableProcessing = false` to get unprocessed
originals. The public interface returns only images, a normal empty result or a technical failure and
never provides algorithm-based reasons.

```kotlin
// Create once per meeting; create the directory on IO beforehand and read the newest window handle on the main thread each time.
val images = captureClient.createSession(enableProcessing = true)
suspend fun captureOneFrame() {
    val image = images.captureFile(screenshotDirectory, currentWindowTokens()) ?: return
    try {
        // The partner's own business method: it must return only after the save succeeded or a real server save ACK arrived.
        saveOrUploadAndAwaitAck(image.value)
        image.confirmAccepted()
    } finally {
        // The sample chooses not to keep the temporary File; cancellation also completes IO cleanup without blocking the main thread.
        withContext(NonCancellable + Dispatchers.IO) { image.value.delete() }
    }
}
```

The save method above is an illustration of partner business logic, not the SDK's upload entry. The
corresponding wiring in the reference App uses the existing three-argument
`MeetingSession.submitPresentation` and confirms only after `onSuccess`; the two-argument entry without
a callback only means enqueued and cannot be used as save confirmation. A failure confirms nothing and
the image can still be obtained later; `null` continues normally.

Use `captureBitmap(...)` when you need a Bitmap. With processing enabled the File is JPEG, with
processing disabled it is PNG; delivered Bitmaps/Files belong to the caller. Confirming neither recycles
the Bitmap nor deletes the File nor performs network requests. SDK processing, encoding and file output
never block the main thread, and the App's own reads and writes must also go to IO.

The App decides the interval, the final frame, which windows to exclude and the meeting-relative time.
Each meeting keeps at most one capture/submit in flight and only starts the final frame after the
periodic frame ACK finishes; a successful final frame, no new image or a failure must all continue the
original meeting stop flow. Close `images` after stopping to release this meeting's state;
`captureClient.close()` separately releases the original capture authorization.
For the complete reference wiring see the
[Android screen-capture notes](../android-reference-app/README.md#独立sdk截图与参考演示帧);
for the public API, threading constraints, resource ownership and close order see
[SDK screen-capture public entry and resource constraints](../android-reference-app/docs/SCREEN-CAPTURE.md).

## 5. Result actions and Web extensions

Result delivery is registered through `ResultActionPlugin`. The default `WebResultActionPlugin`
generates a unified result link; a plugin only prepares, executes and presents its own action, never
copies the meeting state machine and never modifies the Manifest.

The default Web plugin only keeps the full original link of the most recent meeting, and the
pre-meeting screen no longer has a separate "view last meeting result" button; historical review always
goes through the reference App's meeting list (`GET /v1/meetings` lists this account's meetings, and
the result link is generated from the current time when one is opened). Both paths keep the original
`genTime` semantics and do not reopen finished meetings.

Partners can add:

- a local offline HTML or PDF renderer: first read the content asynchronously through the action
  context, then generate your own file and hand it to the App;
- an e-mail action: send the same result link or follow the partner's own attachment policy;
- a mini-program/QR code action: integrate the partner's receive end and Hook in the App;
- a NAS, enterprise IM or other result receiver: implement the same action interface.

### Local result plugins must read content on demand

On a normal entry into `READY`, `context.result()` provides this meeting's ID, title, state, `genTime`
and artifact type list, and `content()` is usually `null`. That does not mean the content is empty. A
plugin that needs local rendering must call `context.access().readContent(...)` to obtain the actual
READY content — transcript, translation, summary, demo frames and so on — of the same meeting; the
default Web action only generates a link and does not need to download that content in advance.

Below is the smallest Kotlin example that can be assembled into the result action registry. `writeHtml`
and `openHtml` are file-generation and page-display functions injected by the partner; the example does
not include an offline HTML renderer:

```kotlin
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingOptions
import com.czur.aimeeting.client.MeetingResult
import com.czur.aimeeting.client.MeetingResultContent
import com.czur.aimeeting.client.MeetingError
import com.czur.aimeeting.client.result.ResultActionOutcome
import com.czur.aimeeting.client.result.ResultActionPlugin
import java.io.File

class LocalHtmlAction(
    private val writeHtml: (MeetingResult, MeetingResultContent) -> File,
    private val openHtml: (File) -> Unit,
) : ResultActionPlugin {
    override fun id() = "local.html"
    override fun displayName() = "本地查看"
    override fun requiredArtifactTypes() = setOf("transcript.final")

    override fun prepare(options: MeetingOptions) =
        ResultActionPlugin.Preparation.allow { context, callback ->
            context.access().readContent(object : MeetingClient.Callback<MeetingResultContent?> {
                override fun onSuccess(content: MeetingResultContent?) {
                    if (content == null) {
                        callback.onError("本场正文不可用")
                        return
                    }
                    context.workExecutor().execute {
                        runCatching { writeHtml(context.result(), content) }.fold(
                            onSuccess = { file ->
                                context.uiExecutor().execute {
                                    runCatching { openHtml(file) }.fold(
                                        onSuccess = {
                                            callback.onSuccess(ResultActionOutcome.success(id(), "已打开本地结果"))
                                        },
                                        onFailure = { callback.onError("无法打开本地结果") },
                                    )
                                }
                            },
                            onFailure = { callback.onError("生成本地结果失败") },
                        )
                    }
                }
                override fun onError(error: MeetingError) {
                    // The SDK returns only stable error codes; the host maps its own text.
                    callback.onError("读取本场正文失败")
                }
            })
        }
}
```

The Chinese string literals in this sample are the reference App's display/error texts; replace them
with your own localized strings when you assemble your own plugin.

The stable error entry of `MeetingClient.Callback` is `onError(error: MeetingError)`; the host maps its
own text by `MeetingErrorCode`, and the SDK returns no end-user sentences. `onError(message: String)` is
kept only for old host plugins and the SDK itself never calls it.

File reads/writes and rendering go to `workExecutor()`, page operations go to `uiExecutor()`; injected
functions must not capture a destroyed Activity, and an asynchronous action finally calls back exactly
once with success or failure. A failure to read content, generate a file or present it is a result
action failure; retrying that action is enough and you must not stop the meeting again or settle twice.

When rendering, decide the areas from `MeetingResultContent.artifactTypes()` and the actual content of
each field; when there is no translation, summary or image, do not create empty areas. `readContent`
does not package retained audio files; a complete offline media solution also needs to stream the audio
separately per the Manifest's `ArtifactRef` and then reference it through local relative paths.
For actual PDF wiring see
[LocalPdfResultActionPlugin](../android-reference-app/app/src/main/java/com/czur/aimeeting/reference/LocalPdfResultActionPlugin.kt),
and for interface lifecycle see the
[public client core notes](../android-reference-app/meeting-client-core/README.md).

The Web reads a `READY` or `READY_WITH_WARNINGS` Manifest; the latter shows a degradation warning, but
write entries that only accept `READY`, such as name editing, are not shown. Transcript, translation,
structured summary and audio each decide display from real `ArtifactRef`s; audio is streamed with
`GET /v1/artifacts/{artifactId}?genTime=...` and long audio is not stuffed into JSON. When partners
replace the results page they should still reuse the Manifest, artifact types and validity rules.

The default results page provides a large-screen transcript/summary split, small-screen tabs,
source-and-translation side by side, a bottom player, click-to-locate and follow in the transcript, a
large demo image and copy. The browser can also export text/audio and print a demo PDF; the App WebView
hides entries that require downloading or printing on the host by default. The page depends entirely on
standard artifacts and collapses the corresponding area when summary, translation or audio is missing;
person data is used only for transcript names and does not occupy a separate right column. For where to
replace fields, styles and interactions see the [Web results page](../web-reference/README.md).

When audio is retained, the default HTTP upload and the file/S3 Store use the same budget function:
`max(64 MiB, AI_MEETING_MAX_MEETING_DURATION_MS × 32 + 1 MiB)`.
It is computed from the standard 16 kHz/mono/PCM16 normal fallback and does not require the device to
have Opus encoding; the actual duration still comes from the frozen audio facts, and headroom is not
billed as usage. Partners building their own HTTP transport, Artifact Store or reverse proxy should
align with that longest-meeting budget so that a failed upload does not block backend wrap-up.

The client's final content (`transcript.final` and other JSON artifacts) uses another unified budget:
`max(2 MiB, ceil(AI_MEETING_MAX_MEETING_DURATION_MS / 60000) × 16 KiB + 64 KiB)`.
Publication, the read port and HTTP responses must use the same derived value; when content exceeds the
budget the publisher refuses to write it, so you never get the unreachable outcome of "Stop is READY but
the results page cannot read it back because of a fixed 1 MiB limit". Custom transports, reverse proxies
or artifact gateways should also keep that response limit and must not truncate published content to
1 MiB or a server default.

The same budget is also checked at `events:append` acceptance against the "final aggregated content": the
server projects the last revision, translation and minutes of the same segment into the JSON that will
finally be published, and allows it when the accumulated UTF-8 byte count is exactly the budget, while
rejecting it with `413 CONTENT_TOO_LARGE` as soon as it exceeds by 1 byte. Clients should handle that
error during the append stage (ask the user to shorten/end the meeting or clean up content) instead of
discovering it after Stop; rejected events are not written and already accepted content can still Stop
to `READY` normally. The request-body limit of a single append still applies separately, and the two
limits cannot replace each other.

The Web speaker rename and the optional cross-meeting registration use a valid result link by default
and do not require logging in again; `READY_WITH_WARNINGS` shows a warning but hides the name-editing
entry that only accepts `READY`. For edit authorization, name precedence, `remember` semantics and the
replaceable `SpeakerResultAccessPolicy.Factory` see
[Web speaker editing](../web-reference/README.md#发言人改名与可选跨会议记录). Registration ownership
uses the account the meeting really belongs to and never fakes a guest identity; when no Provider is
registered, session renaming still works, automatic recognition options are hidden, and
transcript/translation/summary and audio are still delivered. `speaker.identity` is a separate standard
artifact, and session speaker numbers are separate from cross-meeting `personId`; for the iFlytek
example, FFmpeg dependency and optional environment items see
[backend notes](../backend-reference/README.md#可选跨会议声纹参考插件) and the
[local configuration template](../tools/reference-client.env.example). The business scope of
cross-meeting recognition is up to partners: the reference implementation provides only the interface
and a minimal example (off by default), does not define the boundary of "cross-meeting" and does not
guarantee the example's retry behavior after a partial publication failure.

## 6. Pre-delivery checks

Run these before submitting reference code:

```bash
bash tools/check-reference-delivery.sh
bash tools/verify-local.sh
cd android-reference-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

`check-reference-delivery.sh` only checks the directories, scripts, templates, untracked-key boundary
and Git diff format required for public delivery; it does not replace acceptance on an isolated
database, a real Provider, an Android device or cross-meeting voiceprint. Final acceptance should
record the following four kinds of evidence, which cannot replace each other:

- static checks and protocol fixtures;
- Maven/Gradle/Web module builds;
- a real short meeting on the local LAN with database, Provider and device;
- cross-meeting voiceprint results from two real meetings (when that capability is enabled).

Partners should record the above results item by item using their own deployment, devices and models;
when optional capabilities are not configured, accept them per the actual degraded behavior. If
translation or summary selected before the meeting is not authorized for this meeting, the App prompts
and does not start that meeting, and never silently substitutes the selected business. For model
configuration and the minimum acceptance checklist see the
[getting-started guide](partner-getting-started.md).

## 7. Reference repository and CZUR product boundary

This repository is a modifiable open-source reference implementation; the closed-source SDK binary and
its license are provided with the companion delivery package, and CZUR system implementations,
production account systems and private cloud services are not part of this repository's delivery.
Partners extend their own implementations through public interfaces; real keys, certificates,
production addresses and user data are not published with the reference source.

The public meeting capability maintains a single copy of the source: the server side is provided by the
plain `backend-core` JAR, and Android is provided in layers by the public core/contracts and the
closed-source SDK. Partner hosts consume the matching JAR/AAR builds and connect their own account,
model material, settlement and result publication plugins at the product assembly layer; they do not
copy the state machine or depend on the whole reference app. New vendor interfaces are adapted by the
corresponding Adapter/Provider, and the reference default implementations stay independent from
product-private implementations.
