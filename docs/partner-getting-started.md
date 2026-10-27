# Partner Getting-Started Guide

[English](partner-getting-started.md) | [简体中文](partner-getting-started.zh-CN.md)

> First integration · Part 1/4 | Previous ← [Preparation checklist](preparation-checklist.md) | Next → [Model and capability guide](model-and-capability-guide.md)

This document is for Android/backend developers new to this project. After reading it you can
prepare the required resources and complete "login → real speech transcript → stop → Web replay" in
your own environment, then add translation, summary, speaker recognition and demo capture as needed.
Deployment addresses are not committed to the repository; they come from local files (see section 3.3).
When they are missing, the build fails immediately.

Suggested reading order:

1. This document: understand the components, prepare the environment, get the first meeting running.
2. [Model and capability guide](model-and-capability-guide.md): third-party platforms, resource requests, feature mapping and optional capability configuration in one place.
3. [Integration guide](partner-integration-guide.md): replacing login, models, storage, result actions and SDK tools.

## 1. What this reference project can do

This project provides a reference implementation of AI meeting transcripts, minutes and bilingual
translation: the Android side captures speech and displays text in real time, and after the meeting
ends the results are viewed on the default Web page. The backend manages identity, meetings,
available capabilities, model connection material, usage and results in one place. Partners can build
their own products on top of it.

| Component | Role | How to obtain |
| --- | --- | --- |
| Android reference App | Login, pre-meeting options, transcript/translation, floating window, stop and result entry | `android-reference-app/` in this repository, Kotlin First |
| Device AAR (closed source) | Audio source detection, audio capture, screenshots, native and SubtitleTextView; does not include core/contracts | `android-reference-app/meeting-android/libs/ai-meeting-device.aar` in this repository (0.5.0, SHA-256 `150f5a52294036b7e273a91d4d59afa7df212021bb6825e9199384023cd28053`, actually containing arm64-v8a/armeabi-v7a/x86/x86_64 while the product guarantees only the two ARM ABIs) |
| Business AAR (source) | Model connection, data normalization and the shared meeting facade | `android-reference-app/meeting-android/` in this repository; the device AAR is already in the repository at `meeting-android/libs/ai-meeting-device.aar` |
| Subtitle display widget | `com.czur.aimeeting.ui.SubtitleTextView` built into the device AAR | Delivered with the device AAR; no separate UI AAR is provided any more |
| Public backend core | Meeting flow, standard contracts, capability negotiation, persistence and extension ports | `backend-core/` in this repository, built as a plain JAR |
| Reference backend | A startable HTTP service wiring sample login, model issuers and result storage | `backend-reference/` in this repository |
| Web results page | Shows transcript, translation, summary, audio, images and person info according to actual results | `web-reference/` in this repository, built into static files |
| Database initialization and deployment example | The only MySQL initialization entry `deploy/migrate.sh`, the backend Dockerfile and Compose | `deploy/` in this repository and the root `Dockerfile` |

An AAR is an Android library package. The reference App is always built from the device AAR plus the
business AAR in this repository; subtitle/placeholder display uses the `SubtitleTextView` inside the
device AAR, and no separate UI AAR is pulled in. The device AAR is version 0.5.0, SHA-256
`150f5a52294036b7e273a91d4d59afa7df212021bb6825e9199384023cd28053`; its ABI actually contains
`arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`, while the product guarantees only the two ARM ABIs.
The business AAR verifies the device AAR version during initialization.
When a host uses the bare business AAR directly, it must also provide OkHttp 5.3.2 and
kotlinx-coroutines-android 1.11.0 explicitly.

The reference solution is deployed independently and does not depend on CZUR accounts, business
services, internal domains or pre-provisioned model quota.
Third-party service accounts and resources are requested and maintained by the deployer; real keys
are not included in the source code or the SDK.

## 2. Understand the data flow first

The diagram below shows the Android direct-to-model path used in this guide:

```text
                    Log in, discover capabilities, create/stop meetings
Android App/SDK   <-------------------------------->  Reference backend
       |                                               |
       | Realtime audio direct, transcript/translation +--> MySQL: meeting and result metadata
       v                                               +--> File directory/object storage: result content
    Realtime speech model                              |
       |                                               +--> Optional voiceprint service: post-meeting match/register
       v
SDK normalization --> App shows transcript/translation
       |
       +--> Optional summary/standalone translation model: SDK calls with backend-issued material
       |
       +--> Standard text, usage, optional recording/screenshot --> Reference backend
                                                       |
                                     After results are ready --> Web results page
```

The backend hands out the model address, short-lived connection material and capabilities, so the App
never has to assemble vendor requests itself. The realtime audio stream is sent straight to the model;
a retention-enabled sealed recording is still uploaded to the backend for playback and optional
person processing. The default Web is for post-meeting review; subtitles during the meeting are shown
in the App.

The SDK probes device capabilities: when a compatible system shared-audio-source interface exists it
uses that interface; plain Android or old firmware without the new interface falls back to the standard
microphone and reports the actual mode to the App. Shared audio capture, orientation angles, screenshot
window exclusion and similar capabilities on regular systems are limited by the system and cannot be
obtained just by installing the reference App.

## 3. What to prepare before you start

The full checklist (including optional dependencies, alternatives and the "model not ready yet" path)
is in the [preparation checklist](preparation-checklist.md), which is authoritative; this section
lists only what the first meeting requires.

### 3.1 Required resources for the first real-transcript meeting

| Resource | What to prepare | Purpose |
| --- | --- | --- |
| Project source code | The complete directory of the current reference project | Build the backend, App and Web together |
| SDK delivery package | The device AAR is already in the repository (0.5.0, SHA-256 `150f5a52294036b7e273a91d4d59afa7df212021bb6825e9199384023cd28053`); the business AAR is built from this repository's source | Real audio and model operation, subtitle widget |
| Development computer / server host | Reachable from the Android device, with a writable results directory | Run the backend and the static Web |
| MySQL | MySQL 5.7-compatible instance, dedicated database and an account that can initialize/read/write | Store meeting state and metadata |
| Optional realtime speech service | The `sample-json-ws` sample bundled with the repository by default; for real transcripts use your own Alibaba Cloud Tingwu AppKey, AccessKey ID/Secret and an activated service | Turn real speech into transcripts |
| Sample login credentials | Reference username, password and session configuration set by the deployer | Let end users log in to the App normally |
| Network and HTTPS | The device can reach the backend, Web and model; the App login entry has trusted HTTPS | Device connectivity and model interaction |
| Android test device | Prefer StarryHub series or other compatible-firmware devices; a regular Android 14 device only guarantees the basic transcript pipeline | Run the App and verify real speech |

For the first run, enabling only the transcript is enough; Bailian summary, iFlytek voiceprint, object
storage, e-mail and mini-programs are all optional.
For all model resources and the effects of missing ones, see the
[unified model capability table](model-and-capability-guide.md#1-第三方平台与业务功能总表).

### 3.2 Local tools

The local paths below use Bash 4+/Linux-style commands; the source code can be opened in Android
Studio and in a Maven-capable development environment respectively.

| Environment | Notes |
| --- | --- |
| Java Development Kit (JDK) 21, Maven 3.9.x | Backend build and run; the Android project bundles its Gradle Wrapper |
| Android Studio, Android SDK Platform 35, ADB | App build, install and device debugging; Android 14/API 34 minimum |
| Node.js 18+, npm; 22+ recommended | Web requires 18+; the optional WebView result check script uses built-in capabilities from 22+ |
| Python 3 | Local Web startup, LAN build and device scripts |
| MySQL command-line client, curl | Run initialization, check services |
| OpenSSL 1.1.1+ | Generate development certificates for local integration; no self-signing needed if trusted HTTPS is already available |
| FFmpeg, optional | Extract recording segments in the backend when the reference cross-meeting voiceprint is enabled |

Local development checks use these tools directly. The backend Docker image is a separate delivery
deployment path, see section 6; if you have no MySQL instance you can run a temporary test container
yourself, but the database container is not part of this project's deliverables.

### 3.3 Three local configuration files (none of them in the repository)

Deployment addresses and runtime configuration are not committed: one build-time file each for
Android and Web, and one runtime configuration file for the backend kept outside the repository.
None of the three travels with the repository; when either of the first two is missing the
**build fails immediately**, and the third is read from a path outside the repository by default,
where the scripts fail with an explicit error if it cannot be found.

| File | Where it comes from | Purpose | What happens when missing |
| --- | --- | --- | --- |
| `android-reference-app/deployment.local.properties` | Copy the `.example` in the same directory | Backend address and results-page address for the APK, injected into `BuildConfig` at build time | `./gradlew` fails immediately, asking for `backendUrl`/`resultWebUrl` |
| `web-reference/deployment.local.json` | Copy the `.example` in the same directory | Backend address for the results page, generating `dist/config.json` at build time | `npm run build` fails immediately, asking for `apiBaseUrl` |
| `~/.config/ai-meeting/reference-client.env` | Copy `tools/reference-client.env.example`, **copying it outside the repository** | Backend listen address, database, sample login, model Adapter and artifact directory | The startup script reports `未找到配置文件` (configuration file not found) and does not start with default values |

Do not put the backend runtime configuration back into the repository's `tools/` directory:
`tools/check-public-boundary.py` scans workspace files rather than Git-tracked files, so a single
`reference-client.env` with a filled-in password lying in the repository makes the delivery check
print `PUBLIC_BOUNDARY_FAILED`. To use another location, point
`export REFERENCE_ENV_FILE=<仓外绝对路径>` at it; both local scripts honor that variable.

To separate test and production, keep more than one file and select it with environment variables:

```bash
(cd android-reference-app && REFERENCE_DEPLOYMENT_FILE=deployment.prod.properties ./gradlew :app:assembleDebug)
REFERENCE_WEB_DEPLOYMENT_FILE=deployment.prod.json npm --prefix web-reference run build
```

Copying the template without editing it is also caught: both builds treat the placeholder domains in
the template (such as `your-backend.example`) as unconfigured and fail immediately. Recreate them after
switching machines, cloning again or cleaning the workspace; they are just one or two fields each and
do not travel with the repository.

## 4. Complete the first real meeting step by step

All relative paths below start from the repository root unless a command says otherwise.
Enable only Chinese/English transcripts, file result storage and sample login at first, so you do not
have to debug several external services at the same time.

### Step 1: Create the local runtime configuration (outside the repository)

```bash
cd czur-ai-meeting-reference
mkdir -p ~/.config/ai-meeting
cp tools/reference-client.env.example ~/.config/ai-meeting/reference-client.env
export REFERENCE_ENV_FILE=~/.config/ai-meeting/reference-client.env
```

**Do not** copy it to `tools/reference-client.env` as before. `tools/check-public-boundary.py` scans
by workspace and does not care whether Git ignores the file; a configuration file with a filled-in
password in the repository makes the delivery check print `PUBLIC_BOUNDARY_FAILED`. Both local scripts
read `~/.config/ai-meeting/reference-client.env` by default; when the path differs, re-run
`export REFERENCE_ENV_FILE=<仓外路径>` in every new terminal.

Edit the file you just copied outside the repository, not the `.example` template. The template
defaults to the `sample-json-ws` protocol sample, whose address does not start a real model with the
project. **Change the model endpoint first**: it is the address the device connects to directly, and
the template's default `127.0.0.1` points at the device itself on real hardware, so the device only
connects to itself — the meeting starts but no transcript appears.

```dotenv
# The development machine's LAN IP, not 127.0.0.1
AI_MEETING_SAMPLE_JSON_WS_ENDPOINT=ws://192.168.1.20:19091/model
```

Keeping `ws://127.0.0.1:19091/model` only works for single-machine self-tests where the App, backend
and sample model all run on the same machine and `adb reverse` maps port 19091 to the device; for
real-device integration always switch to the LAN IP.

Start the sample model (from the repository root, in a separate terminal; the port matches the
endpoint above):

```bash
python3 tools/sample-json-ws-model.py --host 0.0.0.0 --port 19091
```

When using Tingwu you must change the following settings to your own values and keep the other
required configuration from the template:

```dotenv
DATABASE_URL='jdbc:mysql://127.0.0.1:3306/meeting_reference?useSSL=false&allowPublicKeyRetrieval=true'
DATABASE_USER=replace-with-database-user
DATABASE_PASSWORD=replace-with-database-password

REFERENCE_DEV_IDENTITY_ENABLED=true
REFERENCE_DEV_LOGIN_USERNAME=meeting-user
REFERENCE_DEV_LOGIN_PASSWORD=replace-with-login-password
REFERENCE_DEV_IDENTITY_BEARER_TOKEN=replace-with-generated-session-token
AI_MEETING_PAGE_TOKEN_SECRET=replace-with-generated-page-secret

AI_MEETING_CREATE_ENABLED=true
AI_MEETING_PROFILE_REQUIRED_CAPABILITIES=transcript.realtime
AI_MEETING_ENTITLEMENT_SUPPORTED_CAPABILITIES=transcript.realtime
AI_MEETING_MODEL_CAPABILITIES=transcript.realtime
AI_MEETING_MODEL_LANGUAGES=zh-CN,en-US

AI_MEETING_MODEL_ADAPTER=tingwu-realtime
AI_MEETING_TINGWU_ENABLED=true
AI_MEETING_TINGWU_APP_KEY=replace-with-tingwu-app-key
AI_MEETING_TINGWU_ACCESS_KEY_ID=replace-with-access-key-id
AI_MEETING_TINGWU_ACCESS_KEY_SECRET=replace-with-access-key-secret

AI_MEETING_ARTIFACT_STORE=file
AI_MEETING_ARTIFACT_ROOT=.local/reference-artifacts
AI_MEETING_SPEAKER_IDENTITY_PROVIDER=none
```

The `DATABASE_URL` line must keep its single quotes: without quotes, the `&` in the value is treated
by Bash as a background operator and the following arguments are truncated or produce errors. Quote
with single quotes any value containing spaces, `$`, `&`, `?` or `#`.

Change each setting to a single effective assignment, so that an old value later in the file does not
override the new one. For the first run you do not need to set
`AI_MEETING_PROFILE_OPTIONAL_CAPABILITIES` and do not need to enable summary.
The local file is `source`d by Bash directly and parsed as `KEY=VALUE` under shell rules; quote values
containing spaces, `$`, `&` or other special characters with single quotes.

Also check:

- Generate the session token and the page-token secret as separate random values of at least 32 UTF-8
  bytes each, for example by running `openssl rand -hex 32` once for each; the placeholder values in
  the sample cannot be used to log in.
- Change `REFERENCE_DEV_IDENTITY_ISSUED_AT`/`REFERENCE_DEV_IDENTITY_EXPIRES_AT` to UTC times covering
  the current deployment moment, meaning issued time and expiry time respectively; do not copy the
  template year long-term.
- `AI_MEETING_PROFILE_ID=reference-asr` and `AI_MEETING_PROFILE_REVISION=1` match the current reference
  App; keep them for the first integration.
- For the first run, use the template's `0.0.0.0:8080` HTTP listener; changing the port means updating
  five places (backend listener, health check, `REFERENCE_TLS_UPSTREAM` of the HTTPS entry, Web
  `apiBaseUrl`, App `backendUrl`), see
  [deployment guide](../deploy/README.md#改端口和地址要同步哪些地方).
- `127.0.0.1` for MySQL only means the database runs on the development computer.
- Store permanent model credentials only in the local backend configuration; never write them into
  Android deployment files, the APK, Web configuration or Git.

Configuration is read when the backend starts. After changing it, restart the backend process and
verify in the next meeting.

### Step 2: Prepare the database and run the one-time initialization script

Have the database administrator create a dedicated database and account first, for example database
`meeting_reference` with `utf8mb4` encoding; make sure the connection information from step 1 matches.

For convenience during development you may create the database and account directly with the local
root account and run the migration once; **delivery and production require least privilege**: the
migration account needs only one-time DDL (`CREATE/ALTER/INDEX/REFERENCES` plus read/write), the
application account keeps only runtime `SELECT/INSERT/UPDATE/DELETE`, the two are requested separately
and never reuse an old business database. For the full create-database/create-account SQL and the
boundaries, see the [deployment guide](../deploy/README.md#数据库).

```bash
bash tools/migrate-reference-client.sh
```

The script reads `~/.config/ai-meeting/reference-client.env` by default; if you moved it in step 1,
run `export REFERENCE_ENV_FILE=<仓外路径>` first. It delegates to the only initialization entry
[deploy/migrate.sh](../deploy/migrate.sh), which executes
[`V001__initial_schema.sql`](../deploy/migrations/V001__initial_schema.sql), creates the `ai_trans_*`
tables and registers `V001__initial_schema`. It does not create databases or accounts and does not
migrate other business tables. Application startup only checks the registration result and never
creates tables automatically; running the SQL file by hand does not write the registration, so the
backend refuses to start. If you really must run it by hand, you must additionally run
`INSERT INTO ai_trans_schema_migrations(version) VALUES ('V001__initial_schema')` after the whole SQL
succeeds; steps and boundaries are in the
[integration guide's database initialization section](partner-integration-guide.md#database-initialization).
A first integration never needs to look for other version scripts by hand.

### Step 3: Start the backend and confirm health

In terminal A, from the repository root:

```bash
bash tools/run-reference-client-backend.sh
```

The script builds and starts the backend entry where clients connect to models directly. Keep this
terminal running. In terminal B, check:

```bash
bash tools/check-reference-backend.sh
curl --fail http://127.0.0.1:8080/v1/capabilities
```

Expected: the liveness and database readiness checks both pass, and the capabilities endpoint returns
the capabilities and languages of the current deployment. This step confirms that the backend and
database started; real model calls are confirmed separately in a device meeting.

### Step 4: Prepare device-reachable HTTPS and Web

First determine the development computer's LAN IP. `192.0.2.10` below is a documentation placeholder;
replace it with the real value before running anything.

1. Follow sections 1 and 2 of the
   [local LAN HTTPS guide](local-lan-https.md#1-在仓外生成本机-ca-和-ip-san-证书) to generate a
   development CA/server certificate and start the TLS entry: devices visit
   `https://<PC_LAN_IP>:8443`, which forwards upstream to the local `http://127.0.0.1:8080`.
   If `8443` is taken by another service, add `REFERENCE_TLS_PORT=18443` to the entry (use an actually
   free port) and change the App's `backendUrl` in step 5 to `https://<PC_LAN_IP>:18443`; the
   certificate SAN contains the IP, so no re-signing is needed. If you already have a trusted
   domain/HTTPS proxy, you can use it directly.
2. Open another terminal C and start the post-meeting Web:

```bash
REFERENCE_HTTP_BASE_URL='http://192.0.2.10:8080' \
REFERENCE_WEB_BIND=0.0.0.0 REFERENCE_WEB_PORT=8090 \
bash tools/run-reference-web.sh
```

Here the Web API points at a PC address the device browser can reach; the App's backend login address
still uses HTTPS. A LAN results page may use HTTP to avoid making the browser trust the development CA;
a production HTTPS results page requires an HTTPS API. Also make sure the computer's firewall and
wireless network allow the device to reach the ports in use.

| Address | Who accesses it | Local integration example |
| --- | --- | --- |
| Database JDBC address | Backend process | `jdbc:mysql://127.0.0.1:3306/meeting_reference` |
| HTTP upstream | TLS entry and health check on the PC | `http://127.0.0.1:8080` |
| App backend address | Android SDK/login | `https://<PC_LAN_IP>:8443` |
| Results page address | Device WebView/browser | `http://<PC_LAN_IP>:8090/index.html` |
| Web API address | Device WebView/browser | `http://<PC_LAN_IP>:8080` |

The device's own `127.0.0.1` points at the device itself. The model WebSocket address must also be
reachable from the device. A complete LAN test does not rely on `adb reverse`.

### Step 5: Build and install the real SDK build of the App

The device AAR ships with the reference repository at `meeting-android/libs/ai-meeting-device.aar`;
the business AAR is built by `meeting-android` in this repository and the build needs no arguments.

Set the addresses first: copy the template and fill it in (it is already Git-ignored), and it is
injected into the APK at build time:

```bash
cp android-reference-app/deployment.local.properties.example \
   android-reference-app/deployment.local.properties
# Edit android-reference-app/deployment.local.properties:
#   backendUrl=https://<PC_LAN_IP>:8443
#   resultWebUrl=http://<PC_LAN_IP>:8090/index.html
```

Debug integration also requires the APK to trust the development CA generated in step 4: name the PEM
`reference_debug_ca.pem` and put it in `android-reference-app/app/src/debug/res/raw/` (already
Git-ignored; see the [local LAN HTTPS guide](local-lan-https.md) for how it is generated); without it,
only system CAs are trusted.
When this configuration file is absent, `android-reference-app/settings.gradle.kts` does not include
`:app` in the build and `./gradlew :app:assembleDebug` reports `project ':app' not found`; only when
the file exists but a key is missing do you get messages like `缺少部署配置：backendUrl`. Neither
produces a package pointing at the author's local address.
Keep one file each for test and production and switch with `REFERENCE_DEPLOYMENT_FILE`.

Then build and install (from the repository root; the `cd` inside the parentheses applies only to that
one command):

```bash
(cd android-reference-app && ./gradlew :app:assembleDebug)
adb install -r android-reference-app/app/build/outputs/apk/debug/app-debug.apk
```

On a freshly cloned machine Gradle must also find the Android SDK: set `ANDROID_HOME`, or write
`sdk.dir=<SDK 路径>` in `android-reference-app/local.properties` (that file is not committed).
Without this step the build reports `SDK location not found`, which is unrelated to the deployment
configuration.

Addresses are fixed at packaging time; end users never type an IP. The App keeps a valid login for the
current backend; after a data-preserving reinstall or a restart the login is restored, logging out
clears the session, and an APK for a different backend does not reuse the old identity.

### Step 6: Log in, start a meeting, stop, and view results

1. Open the reference App on the device and log in with the reference username/password set in step 1.
2. Grant the recording permission as prompted and enable the floating-window permission; when demo
   capture is off, no screen capture authorization is needed.
3. Select AI transcript/minutes and a declared language and use the default Web result action; for the
   first run, leave summary and demo capture off.
4. Tap start; the pre-meeting settings close and the cross-app subtitle floating window appears. Speak
   into the microphone and confirm real transcripts. Open another app and confirm the subtitles keep
   running and the area outside the window remains operable; you can collapse it to Mini and restore it.
5. Tap a normal stop and confirm the floating window exits and the audio source is released; open the
   Web once results have been generated.
6. Check the final transcript of this meeting; when the SDK retains recordings by default, playback and
   seeking must also work.
7. Go back to the App and start another meeting to confirm it starts normally. Restart the App and
   confirm the valid login is restored.

After these steps you own your own real-transcript loop. A `READY` result means the standard artifacts
of this meeting are published; optional capabilities that are not enabled do not produce empty result
areas. Do not kill the process or clear data instead of stopping normally.

When opening the results page manually, do not shorten the URL: both the `meetingId` and `genTime`
parameters are mandatory:

```text
http://<PC_LAN_IP>:8090/index.html?meetingId=mtg_xxxxxxxxxxxx&genTime=<毫秒时间戳>
```

| Parameter | Meaning | Where it comes from |
| --- | --- | --- |
| `meetingId` | This meeting's ID, in the form `mtg_...` | Create response or this meeting's Manifest |
| `genTime` | Unix millisecond timestamp when this link was generated | Use the current time, e.g. `date +%s%3N`; `-1` means never expires, for local diagnostics only |

The server uses `genTime + AI_MEETING_RESULT_LINK_TTL_MS` to decide whether the link has expired; after
expiry the page shows that the result cannot be read, and a fresh current `genTime` opens it again. With
only `meetingId` the page has no validity context and fails immediately.

For automatic start, normal stop and Web checks you can use the
[existing scripts](../tools/android-device/README.md#日常会议流程); they go through the original App
entry and need no changes to the meeting core.

### Run a full meeting over HTTP only (without installing the App)

To confirm the backend itself is fine before integrating Android, you can run the same meeting with
curl only. The order below is the real call order of the reference App: login → Create → startClient →
events → usage → stop → manifest. Authoritative route and field definitions are in the
[public contracts](../contracts/ai-meeting-openapi.yaml); here we only give the smallest snippet you
can copy and run. First initialize the database and start the backend as in steps 2 and 3, then open
another terminal. The `profile.id=reference-asr` and `languages` in the snippet must match
`AI_MEETING_PROFILE_ID` and `AI_MEETING_MODEL_LANGUAGES` from step 1; update them together if you
changed them. `python3` is only used to pick fields out of JSON; no extra dependency is needed if the
command line already has it.

```bash
BASE='http://127.0.0.1:8080'

# 1. Log in: exchange for the Bearer token issued by the reference implementation
TOKEN=$(curl -sS -X POST "$BASE/v1/reference/session:login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"<第 1 步的 REFERENCE_DEV_LOGIN_USERNAME>","password":"<第 1 步的 REFERENCE_DEV_LOGIN_PASSWORD>"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')

# 2. Create: Idempotency-Key is required (16–128 characters); retrying with the same key returns the same meeting.
#    clientSessionId must not duplicate an existing meeting either; append a timestamp so repeated runs do not conflict.
RUN_ID="$(date +%s%3N)"
CREATE_BODY=$(cat <<JSON
{
  "clientSessionId": "curl-demo-$RUN_ID",
  "profile": {"id": "reference-asr", "revision": 1},
  "client": {"appId": "curl-demo", "appVersion": "1", "contractVersion": "v1", "deviceId": "curl-demo-device"},
  "timezone": "Asia/Shanghai",
  "languages": {"source": "zh-CN", "targets": []},
  "audioProfile": {"encoding": "PCM_S16LE", "sampleRateHz": 16000, "channelCount": 1, "frameDurationMs": 100},
  "requestedCapabilities": ["transcript.realtime"],
  "artifactPolicy": {"retainAudio": false, "presentationCapture": false}
}
JSON
)
CREATE=$(curl -sS -X POST "$BASE/v1/meetings" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: curl-demo-create-$RUN_ID" \
  -d "$CREATE_BODY")
MEETING=$(printf '%s' "$CREATE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["meetingId"])')
echo "meeting=$MEETING"

# 3. startClient: establish the client event input; only then are events:append calls accepted
curl -sS -X POST "$BASE/v1/meetings/$MEETING:startClient" -H "Authorization: Bearer $TOKEN"

# 4. Submit 2–3 standard transcript events; clientEventId must be unique, clientSequence increases from 1
for n in 1 2 3; do
  curl -sS -X POST "$BASE/v1/meetings/$MEETING/events:append" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"clientEventId\":\"curl-evt-$n\",\"clientSequence\":$n,\"transcriptUpsert\":{\"segmentId\":\"curl-seg-$n\",\"revision\":1,\"operation\":\"UPSERT_OPERATION_UPSERT\",\"startMs\":$((n*1000-1000)),\"endMs\":$((n*1000)),\"language\":\"zh-CN\",\"text\":\"第 $n 句 curl 提交的原文\",\"final\":true}}" \
    > /dev/null
done

# 5. Freeze usage (milliseconds) after audio capture stops; Stop uses it to settle
curl -sS -X POST "$BASE/v1/meetings/$MEETING/usage:submit" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"audioDurationMs":3000}'

# 6. Stop: Idempotency-Key is required here as well; generate it per run like RUN_ID above
curl -sS -X POST "$BASE/v1/meetings/$MEETING:stop" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: curl-demo-stop-$RUN_ID"

# 7. Read the Manifest: genTime is required, in milliseconds; -1 means never expires
GEN_TIME=$(date +%s%3N)
curl -sS "$BASE/v1/meetings/$MEETING/manifest?genTime=$GEN_TIME"
```

Values inside angle brackets are placeholders to replace, and the Chinese sentence in the sample
payload is example content; the request shape is what matters.

Success criteria — check them in order and stop to investigate as soon as one does not match:

| Step | Expectation |
| --- | --- |
| Login | HTTP 200, the response contains `accessToken` |
| Create | HTTP 201, returns `meetingId` (`mtg_...`) and `state=CREATED` |
| startClient | HTTP 200, `state` is `STARTING`, `inputSource=CLIENT_EVENTS` |
| events:append | HTTP 201 for each event, the response carries `eventSequence` |
| usage:submit | HTTP 201, `accepted=true` (replaying the same value returns 200) |
| stop | HTTP 200, `state=READY`, `manifestState=READY` (`READY_WITH_WARNINGS` means degraded, the content is still readable) |
| manifest | HTTP 200, `status=READY`, `artifacts` is non-empty; right after stop it may briefly return 202, just retry |

Finally confirm the artifact is readable by taking the first `artifactId` from the Manifest:

```bash
ARTIFACT=$(curl -sS "$BASE/v1/meetings/$MEETING/manifest?genTime=$GEN_TIME" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["artifacts"][0]["artifactId"])')
curl -sS "$BASE/v1/artifacts/$ARTIFACT?genTime=$GEN_TIME"
```

This returns the final transcript JSON of this meeting, proving that login, meeting creation, events,
usage, closure and artifact publication all work end to end. Real speech transcripts still need App
verification in steps 5 and 6: this HTTP path goes through neither the microphone nor the model and
only proves that the control plane and database work. Never write session tokens, `Idempotency-Key` or
`genTime` into repository files.

## 5. Extend the transcript loop one capability at a time

Add one configuration/implementation at a time, restart the backend, fetch capabilities again and
verify with a new meeting.

| Next item | Preparation and configuration | Result you should see |
| --- | --- | --- |
| Bilingual translation | Declare translation and the required languages and confirm the selected model has translation enabled; a standalone translation model can also be assembled | Transcript/translation in the App; actual translation on the Web |
| AI minutes | Prepare a Bailian application and key, or your own summary Adapter/issuer | Optional summary before the meeting; title, overview, key points and action items afterwards |
| Session speaker diarization | The model supports and declares the session speaker capability | Session speaker numbers/groups; this is not recognizing a person across meetings |
| Cross-meeting speaker identity | iFlytek voiceprint resources or a custom Provider, session speaker diarization, retained recordings and FFmpeg | Person info on the Web, renaming through a valid result link with registration by default, recognition in later meetings |
| Demo capture | SDK build, system screenshot capability or standard screen capture authorization; select demo mode | Screenshots of changing pages and a Web demo area; the reference App does not select translation and demo at the same time |
| Replace accounts/entitlements/storage/result channels | Implement a plugin at the corresponding assembly point | Your own business systems in use while the shared meeting flow stays shared |

For full configuration combinations and "what happens when this is missing", see the
[model and capability guide](model-and-capability-guide.md).
Screenshots and their optional deduplication run inside the SDK, so no separate image model has to be
requested; for the interval, the final frame, excluded windows and save-confirmation wiring, see the
[screen capture extension](partner-integration-guide.md#sdk-screen-capture-extension).

## 6. Docker delivery deployment

Once short meetings work locally, you can deploy the same reference backend with the delivery image.
The deployer needs Docker/Compose, external MySQL, a persistent results directory or object storage, an
HTTPS proxy/certificate and a static Web server.
CZUR microservices, configuration centers or deployment platforms are not required.

From the repository root:

```bash
cp .env.example deploy/.env
# Edit deploy/.env, then run:
(cd deploy && bash migrate.sh --env-file .env)
(cd deploy && bash healthcheck-db.sh --env-file .env)
(cd deploy && docker compose up --build -d)
```

`deploy/.env` is this deployment's environment configuration file. The Dockerfile builds the code and
Compose's `env_file` injects that file at runtime, so real keys are not baked into the image. Note that
the root template disables identity and meeting creation by default; fill in/enable login, token,
page-token secret, validity period, meeting creation, model and capabilities explicitly as in section 4.
Copying the template alone does not give you a fully configured real model service.

Compose maps container port `8080` to the host `API_PORT` (default `8080`) and mounts a persistent volume
for file artifacts. The database address inside the container must be able to reach external MySQL;
`127.0.0.1` inside the container does not mean the host. The same `.env` is also needed by the host-side
initialization, so prefer a database address reachable from both sides.

The Web is built separately; one command produces both the page and the configuration:

```bash
cp web-reference/deployment.local.example.json web-reference/deployment.local.json   # once; fill in a browser-reachable apiBaseUrl
npm --prefix web-reference run build
```

Fill `apiBaseUrl` in `deployment.local.json` with a browser-reachable API; a production HTTPS site uses
an HTTPS API with the corresponding cross-origin access or same-origin routing configured. Then rebuild
the App with the production backend and results site addresses.

If this file is missing or not filled in, `npm --prefix web-reference run build` fails immediately and
says which file is missing; it never produces a page that reports "缺少后端配置" (missing backend
configuration) when opened. Keep one file each for test and production and switch with
`REFERENCE_WEB_DEPLOYMENT_FILE=deployment.prod.json`; for a quick check you can also pass values
directly without writing a file:

```bash
REFERENCE_API_BASE_URL='https://192.0.2.10:8443' npm --prefix web-reference run build
```
Production Release builds use the deployer's official certificate and do not carry the local Debug CA.
Full instructions are in the [deployment guide](../deploy/README.md).

The database delivery has only one MySQL V001; the repository ships no MySQL image/data volume and no
Web image either.

## 7. What partners usually replace

| What you want to replace | Extension seam | Basic example and boundary provided by this repository |
| --- | --- | --- |
| App login and login persistence | `IdentityProvider`, `AppLoginEntry`, registered in `MeetingAppAssembly` | Single-account form, session save/restore, login/logout; no registration or password-recovery platform |
| Backend authentication | Backend `IdentityProvider` | Verifies the Bearer credential and produces a verified identity; integrate your own login system |
| Meeting eligibility, plans/billing | `EntitlementPolicy` and the usage settlement seam | A fixed entitlement/duration example plus actual meeting usage facts; does not connect to payment channels or preset partner pricing |
| Storage | `ArtifactStore` | Local file and S3-compatible examples; metadata is stored separately in the database; user-space quota is up to the partner's business |
| Realtime/translation/summary models | Backend material issuer + the matching Android Adapter | Normalized transcript, translation and summary; no vendor JSON branches inside App pages |
| Cross-meeting voiceprint | `SpeakerIdentityProvider` | Optional iFlytek plugin and person mapping; the rest of the chain works when it is disabled |
| Result delivery/pages | `ResultActionPlugin`, Manifest/Artifact rendering | Default Web; local offline HTML, e-mail, mini-programs, etc. are developed/assembled by partners |
| Pre-meeting checks | App pre-meeting hooks | Your own conditions can be injected; the reference code provides no QR receive end/mini-program |
| Subtitle widget and floating window | The device AAR provides `SubtitleTextView`; the floating window, Mini/large window, permissions and Service are implemented by the reference App's `basic-ui` | Window interaction is an editable default implementation, not a fixed SDK capability |

For concrete interfaces, threading and lifecycle, see the [integration guide](partner-integration-guide.md),
the [Android notes](../android-reference-app/README.md) and the
[public core notes](../backend-core/README.md).
The SDK and the backend core should use matching versions; reuse the public core JAR instead of
depending on the whole reference app or copying the state machine.

## 8. First-integration acceptance checklist and FAQ

Minimum acceptance: backend/database ready → normal account login → fetch real capabilities and
languages → physical microphone transcript → normal stop → same-meeting Web transcript/usable recording
→ start again after stopping → login preserved after restart.

| Symptom | Check first |
| --- | --- |
| The App says to contact the app provider | Whether the APK was built with the backend and results page addresses injected; whether it was rebuilt after editing the files |
| Login fails/cannot connect | Device-reachable HTTPS, certificate host name, account configuration, session validity; the meaning of `127.0.0.1` on the device |
| Backend fails to start or become ready | MySQL connection/account, the single V001, identity enabled, page-token secret, model issuer configuration |
| No translation, summary or person entry | Whether the implementation is assembled and whether the capability is declared in the corresponding configuration; hiding based on actual capabilities is expected behavior |
| UI present but no real transcript | Whether the APK is the `sdk` build, recording authorization, model resources and connectivity; the protocol sample provides no real recognition |
| Summary area empty/hidden | Whether summary is configured and requested and whether the final Manifest actually contains a summary artifact; no model means no empty summary is filled in |
| Web cannot read results | Whether the Web `apiBaseUrl` is reachable from the device, cross-origin/protocol configuration, whether the link is still valid |
| Only session numbers, no cross-meeting names | Session speaker diarization and cross-meeting recognition are configured separately; recordings, a voiceprint Provider and registered persons are also required |

When troubleshooting you can provide the build version, selected capabilities, the failing step and
redacted error information; do not submit permanent keys, login tokens, private keys or real meeting
content. Keep real local configuration in ignored files.
