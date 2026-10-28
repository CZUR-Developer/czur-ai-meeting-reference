# CZUR AI Meeting Reference

[English](README.md) | [简体中文](README.zh-CN.md)

An open-source reference implementation of the AI meeting pipeline: the Android app, the backend,
the results web page, public contracts and test tools live in one repository. You can run the complete
flow end to end, then replace modules with your own; model accounts, storage, identity and billing are
provided by your own services.

## What this project is

| Component | Responsibility |
| --- | --- |
| Android app (`android-reference-app/`) | Meeting capture and UI: login, options, live transcript, floating window, result replay; includes the public client core and the device AAR |
| Backend (`backend-core/`, `backend-reference/`) | Client-facing control plane, meeting state machine, artifact storage, manifest, entitlement boundary and background workers |
| Results web page (`web-reference/`) | Static reader for transcript, translation, summary, speaker names and result links |
| Contracts (`contracts/`) | Control-plane OpenAPI, manifest schema, realtime protocol and conformance fixtures |
| Tools (`tools/`) | Local launch scripts, device helpers, contract validators and delivery checks |
| Deployment (`deploy/`) | The single MySQL 5.7 baseline and reference deployment examples |

Typical baseline: an Android 14 (API 34) device, a JDK 21 backend, MySQL 5.7 and a static web host. The
main pipeline is login → Create → realtime model session → transcript events → Stop → manifest → results
page; translation, structured summary and session speaker diarization are optional capabilities.

## Documentation map

| What you want to do | Read this | Approx. |
| --- | --- | --- |
| See what to prepare before you start | [Preparation checklist](docs/preparation-checklist.md) | 10 minutes |
| Get a real transcript running for the first time | [Getting-started guide](docs/partner-getting-started.md) | 30–60 minutes |
| Connect your own model, languages or capability mix | [Model and capability guide](docs/model-and-capability-guide.md) | 20 minutes |
| Replace login, store models, add result actions | [Integration guide](docs/partner-integration-guide.md) | 40 minutes |
| Deliver to your own servers | [Deployment guide](deploy/README.md) | 30 minutes |
| Embed only the meeting results page | [Web results page](web-reference/README.md) | 15 minutes |
| See what has been verified and what has not | [Support scope and acceptance checklist](docs/validation-scope.md) | 10 minutes |

For a first integration there is only one path: go through the preparation checklist, then steps 1–6 of
the getting-started guide; the commands can be pasted as-is. The device AAR ships with the repository;
once both the Android and the Web local deployment configurations exist, everything can be rebuilt from a
clean checkout. All other documents (module READMEs, tools, public contracts) are listed in the
**[docs index](docs/README.md)**.

## What is in the repository

| Directory | Contents |
| --- | --- |
| `backend-core/`, `backend-reference/` | Meeting core and reference backend (Java 21) |
| `android-reference-app/` | Reference app, client core, business AAR (including the device AAR) |
| `web-reference/` | Static source of the results page |
| `contracts/`, `system-audio-contract/` | Public contracts and the audio source interface |
| `deploy/` | Backend Docker Compose example (connecting to an external MySQL) |
| `tools/` | Local verification, contract validation, device-side tools |

## A few rules

- Do not commit production addresses, keys, certificates, user data or device signing material; keep
  local configuration outside the repository or in untracked files.
- Before releasing, go through the [acceptance checklist](docs/validation-scope.md#目标环境的最小验收)
  yourself, with your own signing and your own deployment environment.
- The device AAR binary ships with this reference repository; its closed-source SDK source is not
  published with this repository and is used under the delivery license.

Development environment, verification commands, contribution workflow and release boundaries are in
[CONTRIBUTING.md](CONTRIBUTING.md) and [local development verification](tools/README.md).
