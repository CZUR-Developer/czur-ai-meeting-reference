-- 首次正式交付前的唯一建库基线。
-- 本文件从已验证的开发迁移终态导出；只创建当前所需结构，不包含中间升级、
-- 历史数据回填、默认用户或业务数据。deploy/migrate.sh 将本文件与版本登记放入同一事务。
-- PostgreSQL 16；使用当前 search_path 指向的目标 schema。

CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE artifact (
    artifact_id text NOT NULL,
    meeting_id text NOT NULL,
    artifact_type text NOT NULL,
    media_type text NOT NULL,
    language text,
    revision integer NOT NULL,
    intent_id text NOT NULL,
    storage_binding text NOT NULL,
    content_size bigint NOT NULL,
    content_checksum text NOT NULL,
    recorded_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT artifact_content_checksum_ck CHECK ((content_checksum ~ '^sha256:[0-9a-f]{64}$'::text)),
    CONSTRAINT artifact_content_size_ck CHECK ((content_size >= 0)),
    CONSTRAINT artifact_language_ck CHECK (((language IS NULL) OR ((length(translate(language, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0) AND (octet_length(convert_to(language, 'UTF8'::name)) <= 4096)))),
    CONSTRAINT artifact_revision_ck CHECK ((revision > 0)),
    CONSTRAINT artifact_text_ck CHECK (((length(translate(artifact_id, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0) AND (octet_length(convert_to(artifact_id, 'UTF8'::name)) <= 4096) AND (length(translate(meeting_id, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0) AND (octet_length(convert_to(meeting_id, 'UTF8'::name)) <= 4096) AND (length(translate(artifact_type, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0) AND (octet_length(convert_to(artifact_type, 'UTF8'::name)) <= 4096) AND (length(translate(media_type, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0) AND (octet_length(convert_to(media_type, 'UTF8'::name)) <= 4096) AND (length(translate(intent_id, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0) AND (octet_length(convert_to(intent_id, 'UTF8'::name)) <= 4096) AND (length(translate(storage_binding, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0) AND (octet_length(convert_to(storage_binding, 'UTF8'::name)) <= 4096) AND (length(translate(content_checksum, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0) AND (octet_length(convert_to(content_checksum, 'UTF8'::name)) <= 4096)))
);


--
-- Name: artifact_deletion_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE artifact_deletion_item (
    meeting_id text NOT NULL,
    ordinal integer NOT NULL,
    artifact_id text NOT NULL,
    artifact_type text NOT NULL,
    revision integer NOT NULL,
    status text NOT NULL,
    storage_binding text,
    deleted_at timestamp with time zone,
    CONSTRAINT artifact_deletion_item_ordinal_ck CHECK (((ordinal >= 0) AND (ordinal < 256))),
    CONSTRAINT artifact_deletion_item_revision_ck CHECK ((revision > 0)),
    CONSTRAINT artifact_deletion_item_status_ck CHECK ((((status = 'PENDING'::text) AND (storage_binding IS NULL) AND (deleted_at IS NULL)) OR ((status = 'DELETED'::text) AND (storage_binding IS NOT NULL) AND (deleted_at IS NOT NULL)))),
    CONSTRAINT artifact_deletion_item_text_ck CHECK (((length(translate(artifact_id, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0) AND (octet_length(convert_to(artifact_id, 'UTF8'::name)) <= 4096) AND (length(translate(artifact_type, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0) AND (octet_length(convert_to(artifact_type, 'UTF8'::name)) <= 4096) AND ((storage_binding IS NULL) OR ((length(translate(storage_binding, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0) AND (octet_length(convert_to(storage_binding, 'UTF8'::name)) <= 4096)))))
);


--
-- Name: durable_input_fact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE durable_input_fact (
    meeting_id text NOT NULL,
    input_binding text NOT NULL,
    last_accepted_sequence bigint NOT NULL,
    audio_duration_ms bigint NOT NULL,
    source_language text NOT NULL,
    target_languages text[] NOT NULL,
    accepted_at timestamp with time zone NOT NULL,
    content_digest text NOT NULL,
    CONSTRAINT durable_input_fact_content_digest_ck CHECK ((length(btrim(content_digest)) > 0)),
    CONSTRAINT durable_input_fact_duration_ck CHECK ((audio_duration_ms >= 0)),
    CONSTRAINT durable_input_fact_input_binding_ck CHECK ((length(btrim(input_binding)) > 0)),
    CONSTRAINT durable_input_fact_sequence_ck CHECK ((last_accepted_sequence >= 0)),
    CONSTRAINT durable_input_fact_source_language_ck CHECK ((length(btrim(source_language)) > 0)),
    CONSTRAINT durable_input_fact_target_languages_ck CHECK (((cardinality(target_languages) >= 0) AND ((cardinality(target_languages) = 0) OR (array_ndims(target_languages) = 1)) AND (array_position(target_languages, NULL::text) IS NULL) AND (array_position(target_languages, ''::text) IS NULL)))
);


--
-- Name: final_usage_fact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE final_usage_fact (
    meeting_id text NOT NULL,
    actual_unit text NOT NULL,
    actual_amount bigint NOT NULL,
    source_input_binding text NOT NULL,
    source_input_digest text NOT NULL,
    source_accepted_at timestamp with time zone NOT NULL,
    CONSTRAINT final_usage_fact_actual_amount_ck CHECK ((actual_amount >= 0)),
    CONSTRAINT final_usage_fact_actual_unit_ck CHECK ((actual_unit = 'MEETING_DURATION_MS'::text)),
    CONSTRAINT final_usage_fact_source_input_binding_ck CHECK ((length(btrim(source_input_binding)) > 0)),
    CONSTRAINT final_usage_fact_source_input_digest_ck CHECK ((length(btrim(source_input_digest)) > 0))
);


--
-- Name: finalization_execution_fact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE finalization_execution_fact (
    meeting_id text NOT NULL,
    canonical_json text NOT NULL,
    execution_fact_json jsonb NOT NULL,
    content_sha256 bytea NOT NULL,
    CONSTRAINT finalization_execution_fact_document_ck CHECK (((jsonb_typeof(execution_fact_json) = 'object'::text) AND (execution_fact_json ?& ARRAY['specVersion'::text, 'meetingId'::text, 'startedAt'::text, 'title'::text, 'productProfile'::text, 'capabilityPlan'::text]) AND ((execution_fact_json - ARRAY['specVersion'::text, 'meetingId'::text, 'startedAt'::text, 'title'::text, 'productProfile'::text, 'capabilityPlan'::text]) = '{}'::jsonb) AND (jsonb_typeof((execution_fact_json -> 'specVersion'::text)) = 'string'::text) AND ((execution_fact_json ->> 'specVersion'::text) = 'finalization-execution-fact/v1'::text) AND (jsonb_typeof((execution_fact_json -> 'meetingId'::text)) = 'string'::text) AND ((execution_fact_json ->> 'meetingId'::text) = meeting_id) AND (jsonb_typeof((execution_fact_json -> 'startedAt'::text)) = 'string'::text) AND ((execution_fact_json ->> 'startedAt'::text) ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\.[0-9]{1,6})?Z$'::text) AND (jsonb_typeof((execution_fact_json -> 'title'::text)) = ANY (ARRAY['string'::text, 'null'::text])) AND (jsonb_typeof((execution_fact_json -> 'productProfile'::text)) = 'object'::text) AND (jsonb_typeof((execution_fact_json -> 'capabilityPlan'::text)) = 'object'::text) AND ((canonical_json)::jsonb = execution_fact_json))),
    CONSTRAINT finalization_execution_fact_hash_ck CHECK (((octet_length(content_sha256) = 32) AND (content_sha256 = sha256(convert_to(canonical_json, 'UTF8'::name)))))
);


--
-- Name: idempotency_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE idempotency_record (
    subject_scope text NOT NULL,
    idempotency_key text NOT NULL,
    request_hash text NOT NULL,
    response_status integer,
    response_body jsonb,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone,
    CONSTRAINT idempotency_expiry_ck CHECK (((expires_at IS NULL) OR (expires_at > created_at)))
);


--
-- Name: meeting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE meeting (
    meeting_id text NOT NULL,
    deployment_scope text NOT NULL,
    client_session_id text NOT NULL,
    subject_scope text NOT NULL,
    profile_id text NOT NULL,
    profile_revision integer NOT NULL,
    state text NOT NULL,
    aggregate_version bigint DEFAULT 0 NOT NULL,
    finalization_receipt_id text,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    grant_id text,
    grant_expires_at timestamp with time zone,
    usage_reservation_id text,
    input_state text DEFAULT 'INPUT_OPEN'::text NOT NULL,
    manifest_state text DEFAULT 'NOT_STARTED'::text NOT NULL,
    CONSTRAINT meeting_aggregate_version_check CHECK ((aggregate_version >= 0)),
    CONSTRAINT meeting_deleted_at_equivalence_ck CHECK ((((state = 'DELETED'::text) AND (manifest_state = 'DELETED'::text) AND (deleted_at IS NOT NULL) AND (deleted_at = updated_at)) OR ((state <> 'DELETED'::text) AND (manifest_state <> 'DELETED'::text) AND (deleted_at IS NULL)))),
    CONSTRAINT meeting_finalization_receipt_ck CHECK ((((input_state = 'INPUT_ACCEPTED'::text) AND (finalization_receipt_id IS NOT NULL) AND (length(btrim(finalization_receipt_id)) > 0)) OR ((input_state <> 'INPUT_ACCEPTED'::text) AND (finalization_receipt_id IS NULL)))),
    CONSTRAINT meeting_id_text_ck CHECK (((length(btrim(meeting_id)) > 0) AND (octet_length(meeting_id) <= 1024))),
    CONSTRAINT meeting_input_state_ck CHECK ((input_state = ANY (ARRAY['INPUT_OPEN'::text, 'INPUT_COMPLETE_PENDING'::text, 'INPUT_ACCEPTED'::text, 'INPUT_CANCELLED'::text]))),
    CONSTRAINT meeting_manifest_state_ck CHECK ((manifest_state = ANY (ARRAY['NOT_STARTED'::text, 'GENERATING'::text, 'READY'::text, 'READY_WITH_WARNINGS'::text, 'FAILED'::text, 'DELETION_PENDING'::text, 'DELETED'::text]))),
    CONSTRAINT meeting_profile_revision_check CHECK ((profile_revision > 0)),
    CONSTRAINT meeting_state_ck CHECK ((state = ANY (ARRAY['CREATED'::text, 'AUTHORIZED'::text, 'STARTING'::text, 'RUNNING'::text, 'STOPPING'::text, 'FINALIZING'::text, 'READY'::text, 'READY_WITH_WARNINGS'::text, 'FAILED'::text, 'CANCELLED'::text, 'DELETION_PENDING'::text, 'DELETED'::text]))),
    CONSTRAINT meeting_updated_after_created CHECK ((updated_at >= created_at))
);


--
-- Name: meeting_audio_frame; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE meeting_audio_frame (
    meeting_id text NOT NULL,
    stream_sequence bigint NOT NULL,
    start_position_ms bigint NOT NULL,
    duration_ms integer NOT NULL,
    audio bytea NOT NULL,
    source_generation bigint NOT NULL,
    format_revision integer NOT NULL,
    gap_duration_ms_before bigint NOT NULL,
    flags integer NOT NULL,
    digest_version smallint NOT NULL,
    content_digest bytea NOT NULL,
    CONSTRAINT meeting_audio_frame_shape_ck CHECK ((((stream_sequence > 0) AND (start_position_ms >= 0) AND ((duration_ms >= 1) AND (duration_ms <= 200)) AND (octet_length(audio) = (duration_ms * 32)) AND (octet_length(audio) <= 6400) AND (source_generation >= 0) AND (format_revision >= 0) AND (gap_duration_ms_before >= 0) AND (flags >= 0) AND ((flags & (~ 14)) = 0) AND (digest_version = 1) AND (octet_length(content_digest) = 32)) IS TRUE))
);


--
-- Name: meeting_audio_input; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE meeting_audio_input (
    meeting_id text NOT NULL,
    writer_id uuid NOT NULL,
    writer_epoch bigint NOT NULL,
    accepted_stream_sequence bigint DEFAULT 0 NOT NULL,
    accepted_audio_position_ms bigint DEFAULT 0 NOT NULL,
    complete_last_stream_sequence bigint,
    complete_last_audio_position_ms bigint,
    complete_recording_checksum text,
    complete_recording_size_bytes bigint,
    complete_request_id text,
    CONSTRAINT meeting_audio_input_complete_ck CHECK (((((complete_last_stream_sequence IS NULL) AND (complete_last_audio_position_ms IS NULL) AND (complete_recording_checksum IS NULL) AND (complete_recording_size_bytes IS NULL) AND (complete_request_id IS NULL)) OR ((complete_last_stream_sequence > 0) AND (complete_last_stream_sequence >= accepted_stream_sequence) AND (complete_last_audio_position_ms >= accepted_audio_position_ms) AND (((complete_recording_checksum = ''::text) AND (complete_recording_size_bytes = 0)) OR ((complete_recording_checksum ~ '^[0-9a-f]{64}$'::text) AND (complete_recording_size_bytes > 0))) AND ((length(complete_request_id) >= 1) AND (length(complete_request_id) <= 128)) AND (length(btrim(complete_request_id)) > 0) AND (strpos(complete_request_id, chr(13)) = 0) AND (strpos(complete_request_id, chr(10)) = 0))) IS TRUE)),
    CONSTRAINT meeting_audio_input_cursor_ck CHECK ((((writer_epoch > 0) AND (accepted_stream_sequence >= 0) AND (accepted_audio_position_ms >= 0) AND ((accepted_stream_sequence > 0) OR (accepted_audio_position_ms = 0))) IS TRUE))
);


--
-- Name: meeting_create_fact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE meeting_create_fact (
    scope text NOT NULL,
    idempotency_key text NOT NULL,
    request_schema_version integer NOT NULL,
    request_digest text NOT NULL,
    request_json text NOT NULL,
    response_schema_version integer NOT NULL,
    response_status integer NOT NULL,
    response_type text NOT NULL,
    response_json text NOT NULL,
    response_sha256 bytea NOT NULL,
    meeting_id text,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT meeting_create_fact_key_ck CHECK ((((length(btrim(scope)) > 0) AND (length(btrim(idempotency_key)) > 0)) IS TRUE)),
    CONSTRAINT meeting_create_fact_outcome_ck CHECK (((((response_type = 'SUCCESS'::text) AND (response_status = 201) AND (meeting_id IS NOT NULL) AND (((response_json)::jsonb ->> 'meetingId'::text) = meeting_id) AND (((response_json)::jsonb ->> 'state'::text) = 'CREATED'::text)) OR ((response_type = 'FAILURE'::text) AND ((response_status >= 400) AND (response_status <= 599)) AND (meeting_id IS NULL))) IS TRUE)),
    CONSTRAINT meeting_create_fact_request_ck CHECK ((((request_schema_version = 1) AND (request_digest ~ '^[0-9a-f]{64}$'::text) AND (jsonb_typeof((request_json)::jsonb) = 'object'::text)) IS TRUE)),
    CONSTRAINT meeting_create_fact_response_ck CHECK ((((response_schema_version = 1) AND (jsonb_typeof((response_json)::jsonb) = 'object'::text) AND (octet_length(response_sha256) = 32) AND (response_sha256 = sha256(convert_to(response_json, 'UTF8'::name)))) IS TRUE))
);


--
-- Name: meeting_deletion_plan; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE meeting_deletion_plan (
    meeting_id text NOT NULL,
    accepted_at timestamp with time zone NOT NULL,
    source_manifest_revision integer,
    source_workflow_step_type text,
    source_workflow_step_key text,
    source_workflow_checkpoint_type text,
    source_workflow_content_digest text,
    target_count integer NOT NULL,
    canonical_json text NOT NULL,
    deletion_plan_json jsonb NOT NULL,
    content_sha256 bytea NOT NULL,
    status text NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT meeting_deletion_plan_document_ck CHECK ((((jsonb_typeof(deletion_plan_json) = 'object'::text) AND (deletion_plan_json ?& ARRAY['specVersion'::text, 'meetingId'::text, 'acceptedAt'::text, 'sourceManifestRevision'::text, 'sourceFinalizationWorkflow'::text, 'targets'::text]) AND ((deletion_plan_json - ARRAY['specVersion'::text, 'meetingId'::text, 'acceptedAt'::text, 'sourceManifestRevision'::text, 'sourceFinalizationWorkflow'::text, 'targets'::text]) = '{}'::jsonb) AND (jsonb_typeof((deletion_plan_json -> 'specVersion'::text)) = 'string'::text) AND ((deletion_plan_json ->> 'specVersion'::text) = 'artifact-deletion-plan/v1'::text) AND (jsonb_typeof((deletion_plan_json -> 'meetingId'::text)) = 'string'::text) AND ((deletion_plan_json ->> 'meetingId'::text) = meeting_id) AND (jsonb_typeof((deletion_plan_json -> 'acceptedAt'::text)) = 'string'::text) AND ((deletion_plan_json ->> 'acceptedAt'::text) ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\.[0-9]{1,6})?Z$'::text) AND (((deletion_plan_json ->> 'acceptedAt'::text))::timestamp with time zone = accepted_at) AND (((source_manifest_revision IS NULL) AND (jsonb_typeof((deletion_plan_json -> 'sourceManifestRevision'::text)) = 'null'::text)) OR ((source_manifest_revision IS NOT NULL) AND (jsonb_typeof((deletion_plan_json -> 'sourceManifestRevision'::text)) = 'number'::text) AND ((deletion_plan_json ->> 'sourceManifestRevision'::text) ~ '^[1-9][0-9]*$'::text) AND (((deletion_plan_json ->> 'sourceManifestRevision'::text))::numeric <= (2147483647)::numeric) AND (((deletion_plan_json ->> 'sourceManifestRevision'::text))::numeric = (source_manifest_revision)::numeric))) AND (((source_workflow_step_type IS NULL) AND (source_workflow_step_key IS NULL) AND (source_workflow_checkpoint_type IS NULL) AND (source_workflow_content_digest IS NULL) AND (jsonb_typeof((deletion_plan_json -> 'sourceFinalizationWorkflow'::text)) = 'null'::text)) OR ((source_workflow_step_type IS NOT NULL) AND (source_workflow_step_key IS NOT NULL) AND (source_workflow_checkpoint_type IS NOT NULL) AND (source_workflow_content_digest IS NOT NULL) AND (jsonb_typeof((deletion_plan_json -> 'sourceFinalizationWorkflow'::text)) = 'object'::text) AND ((deletion_plan_json -> 'sourceFinalizationWorkflow'::text) ?& ARRAY['meetingId'::text, 'stepType'::text, 'stepKey'::text, 'checkpointType'::text, 'contentDigest'::text]) AND (((deletion_plan_json -> 'sourceFinalizationWorkflow'::text) - ARRAY['meetingId'::text, 'stepType'::text, 'stepKey'::text, 'checkpointType'::text, 'contentDigest'::text]) = '{}'::jsonb) AND (jsonb_typeof((deletion_plan_json #> '{sourceFinalizationWorkflow,meetingId}'::text[])) = 'string'::text) AND (jsonb_typeof((deletion_plan_json #> '{sourceFinalizationWorkflow,stepType}'::text[])) = 'string'::text) AND (jsonb_typeof((deletion_plan_json #> '{sourceFinalizationWorkflow,stepKey}'::text[])) = 'string'::text) AND (jsonb_typeof((deletion_plan_json #> '{sourceFinalizationWorkflow,checkpointType}'::text[])) = 'string'::text) AND (jsonb_typeof((deletion_plan_json #> '{sourceFinalizationWorkflow,contentDigest}'::text[])) = 'string'::text) AND ((deletion_plan_json #>> '{sourceFinalizationWorkflow,meetingId}'::text[]) = meeting_id) AND ((deletion_plan_json #>> '{sourceFinalizationWorkflow,stepType}'::text[]) = source_workflow_step_type) AND ((deletion_plan_json #>> '{sourceFinalizationWorkflow,stepKey}'::text[]) = source_workflow_step_key) AND ((deletion_plan_json #>> '{sourceFinalizationWorkflow,checkpointType}'::text[]) = source_workflow_checkpoint_type) AND ((deletion_plan_json #>> '{sourceFinalizationWorkflow,contentDigest}'::text[]) = source_workflow_content_digest))) AND (jsonb_typeof((deletion_plan_json -> 'targets'::text)) = 'array'::text) AND (jsonb_array_length((deletion_plan_json -> 'targets'::text)) = target_count) AND ((canonical_json)::jsonb = deletion_plan_json)) IS TRUE)),
    CONSTRAINT meeting_deletion_plan_hash_ck CHECK (((octet_length(content_sha256) = 32) AND (content_sha256 = sha256(convert_to(canonical_json, 'UTF8'::name))))),
    CONSTRAINT meeting_deletion_plan_source_ck CHECK (((((source_workflow_step_type IS NULL) AND (source_workflow_step_key IS NULL) AND (source_workflow_checkpoint_type IS NULL) AND (source_workflow_content_digest IS NULL)) OR ((source_workflow_step_type IS NOT NULL) AND (source_workflow_step_key IS NOT NULL) AND (source_workflow_checkpoint_type IS NOT NULL) AND (source_workflow_content_digest IS NOT NULL) AND (source_workflow_step_type = 'mock-finalization'::text) AND (length(translate(source_workflow_step_key, concat(chr(9), chr(10), chr(11), chr(12), chr(13), chr(28), chr(29), chr(30), chr(31), chr(32), chr(5760), chr(8192), chr(8193), chr(8194), chr(8195), chr(8196), chr(8197), chr(8198), chr(8200), chr(8201), chr(8202), chr(8232), chr(8233), chr(8287), chr(12288)), ''::text)) > 0) AND (octet_length(convert_to(source_workflow_step_key, 'UTF8'::name)) <= 4096) AND (source_workflow_checkpoint_type = 'mock-finalization-checkpoint/v1'::text) AND (source_workflow_content_digest ~ '^sha256:[0-9a-f]{64}$'::text))) IS TRUE)),
    CONSTRAINT meeting_deletion_plan_status_ck CHECK ((((status = 'PENDING'::text) AND (completed_at IS NULL)) OR ((status = 'COMPLETED'::text) AND (completed_at IS NOT NULL) AND (completed_at >= accepted_at)))),
    CONSTRAINT meeting_deletion_plan_target_count_ck CHECK (((target_count >= 0) AND (target_count <= 256)))
);


--
-- Name: meeting_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE meeting_event (
    meeting_id text NOT NULL,
    event_sequence bigint NOT NULL,
    event_id text NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    spec_version text,
    stream_id text,
    canonical_payload text,
    CONSTRAINT meeting_event_envelope_group_ck CHECK ((((spec_version IS NULL) AND (stream_id IS NULL) AND (canonical_payload IS NULL)) OR ((spec_version IS NOT NULL) AND (stream_id IS NOT NULL) AND (canonical_payload IS NOT NULL) AND (length(btrim(spec_version)) > 0) AND (length(btrim(stream_id)) > 0) AND
CASE
    WHEN (length(btrim(canonical_payload)) > 0) THEN ((canonical_payload)::jsonb = payload)
    ELSE false
END))),
    CONSTRAINT meeting_event_event_sequence_check CHECK ((event_sequence > 0))
);

-- P4 Android 客户端事件链路。无记录的历史会议继续使用后端音频输入。
-- 客户端身份/顺序与服务端 event_sequence 分开保存；统一事件正文仍只有 meeting_event 一份。
CREATE TABLE meeting_client_input (
    meeting_id text PRIMARY KEY,
    started_at timestamp with time zone NOT NULL
);

CREATE TABLE meeting_client_event (
    meeting_id text NOT NULL REFERENCES meeting_client_input(meeting_id),
    client_event_id text NOT NULL,
    client_sequence bigint NOT NULL CHECK (client_sequence > 0),
    event_id text NOT NULL UNIQUE,
    PRIMARY KEY (meeting_id, client_event_id),
    UNIQUE (meeting_id, client_sequence)
);


--
-- Name: meeting_manifest_revision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE meeting_manifest_revision (
    meeting_id text NOT NULL,
    manifest_revision integer NOT NULL,
    previous_revision integer,
    canonical_json text NOT NULL,
    manifest_json jsonb NOT NULL,
    content_sha256 bytea NOT NULL,
    recorded_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT meeting_manifest_revision_chain_ck CHECK ((((manifest_revision = 1) AND (previous_revision IS NULL)) OR ((manifest_revision > 1) AND (previous_revision IS NOT NULL) AND (previous_revision = (manifest_revision - 1))))),
    CONSTRAINT meeting_manifest_revision_document_ck CHECK (((jsonb_typeof(manifest_json) = 'object'::text) AND (manifest_json ?& ARRAY['specVersion'::text, 'meetingId'::text, 'manifestRevision'::text, 'profile'::text, 'title'::text, 'status'::text, 'startedAt'::text, 'endedAt'::text, 'durationMs'::text, 'languages'::text, 'artifacts'::text, 'warnings'::text]) AND ((manifest_json - ARRAY['specVersion'::text, 'meetingId'::text, 'manifestRevision'::text, 'profile'::text, 'title'::text, 'status'::text, 'startedAt'::text, 'endedAt'::text, 'durationMs'::text, 'languages'::text, 'artifacts'::text, 'warnings'::text]) = '{}'::jsonb) AND (jsonb_typeof((manifest_json -> 'meetingId'::text)) = 'string'::text) AND ((manifest_json ->> 'meetingId'::text) = meeting_id) AND (jsonb_typeof((manifest_json -> 'manifestRevision'::text)) = 'number'::text) AND ((manifest_json ->> 'manifestRevision'::text) ~ '^[1-9][0-9]*$'::text) AND (((manifest_json ->> 'manifestRevision'::text))::numeric <= (2147483647)::numeric) AND (((manifest_json ->> 'manifestRevision'::text))::numeric = (manifest_revision)::numeric) AND (jsonb_typeof((manifest_json -> 'status'::text)) = 'string'::text) AND ((manifest_json ->> 'status'::text) = ANY (ARRAY['READY'::text, 'READY_WITH_WARNINGS'::text, 'FAILED'::text, 'DELETION_PENDING'::text, 'DELETED'::text])) AND ((canonical_json)::jsonb = manifest_json))),
    CONSTRAINT meeting_manifest_revision_hash_ck CHECK (((octet_length(content_sha256) = 32) AND (content_sha256 = sha256(convert_to(canonical_json, 'UTF8'::name)))))
);


--
-- Name: meeting_realtime_token_grant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE meeting_realtime_token_grant (
    meeting_id text NOT NULL,
    idempotency_key text NOT NULL,
    grant_json text NOT NULL,
    grant_sha256 bytea NOT NULL,
    token_sha256 bytea NOT NULL,
    issued_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT meeting_realtime_token_grant_digest_ck CHECK ((((jsonb_typeof((grant_json)::jsonb) = 'object'::text) AND (((grant_json)::jsonb ->> 'meetingId'::text) = meeting_id) AND (((grant_json)::jsonb ->> 'streamId'::text) = meeting_id) AND (length(((grant_json)::jsonb ->> 'sessionToken'::text)) > 0) AND ((((grant_json)::jsonb ->> 'expiresAt'::text))::timestamp with time zone = expires_at) AND (octet_length(grant_sha256) = 32) AND (grant_sha256 = sha256(convert_to(grant_json, 'UTF8'::name))) AND (octet_length(token_sha256) = 32) AND (token_sha256 = sha256(convert_to(((grant_json)::jsonb ->> 'sessionToken'::text), 'UTF8'::name)))) IS TRUE)),
    CONSTRAINT meeting_realtime_token_grant_key_ck CHECK ((((length(btrim(meeting_id)) > 0) AND ((length(idempotency_key) >= 16) AND (length(idempotency_key) <= 128))) IS TRUE)),
    CONSTRAINT meeting_realtime_token_grant_time_ck CHECK (((expires_at > issued_at) IS TRUE))
);


--
-- Name: meeting_stream; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE meeting_stream (
    meeting_id text NOT NULL,
    stream_id text NOT NULL,
    last_client_seq bigint DEFAULT 0 NOT NULL,
    last_server_seq bigint DEFAULT 0 NOT NULL,
    input_state text DEFAULT 'OPEN'::text NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT meeting_stream_last_client_seq_check CHECK ((last_client_seq >= 0)),
    CONSTRAINT meeting_stream_last_server_seq_check CHECK ((last_server_seq >= 0))
);


--
-- Name: outbox_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE outbox_event (
    event_id text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    available_at timestamp with time zone NOT NULL,
    status text DEFAULT 'PENDING'::text NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    lease_owner text,
    lease_until timestamp with time zone,
    published_at timestamp with time zone,
    last_error text,
    lease_token text,
    lease_version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT outbox_event_attempt_count_check CHECK ((attempt_count >= 0)),
    CONSTRAINT outbox_lease_token_ck CHECK (((lease_token IS NULL) OR (length(btrim(lease_token)) > 0))),
    CONSTRAINT outbox_lease_version_ck CHECK ((lease_version >= 0)),
    CONSTRAINT outbox_status_ck CHECK ((status = ANY (ARRAY['PENDING'::text, 'LEASED'::text, 'PUBLISHED'::text, 'DEAD'::text])))
);


--
-- Name: plugin_execution; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE plugin_execution (
    execution_id text NOT NULL,
    meeting_id text,
    plugin_id text NOT NULL,
    plugin_version text NOT NULL,
    operation text NOT NULL,
    status text NOT NULL,
    request_id text,
    started_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone,
    error_code text,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL
);


--
-- Name: usage_operation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE usage_operation (
    deployment_scope text NOT NULL,
    subject_scope text NOT NULL,
    operation_scope text NOT NULL,
    idempotency_key text NOT NULL,
    request_digest text NOT NULL,
    reservation_id text NOT NULL,
    result_body jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT usage_operation_scope_ck CHECK ((operation_scope = ANY (ARRAY['usage.reserve.v1'::text, 'usage.commit.v1'::text, 'usage.cancel.v1'::text]))),
    CONSTRAINT usage_operation_text_ck CHECK (((length(btrim(deployment_scope)) > 0) AND (length(btrim(subject_scope)) > 0) AND (length(btrim(idempotency_key)) > 0) AND (length(btrim(request_digest)) > 0)))
);


--
-- Name: usage_reservation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE usage_reservation (
    reservation_id text NOT NULL,
    meeting_id text,
    usage_type text,
    status text NOT NULL,
    quantity numeric(20,6),
    provider_ref text,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    record_format text NOT NULL,
    deployment_scope text NOT NULL,
    subject_scope text NOT NULL,
    grant_id text,
    expected_meeting_id text NOT NULL,
    bound_meeting_id text,
    bound_at timestamp with time zone,
    usage_unit text NOT NULL,
    reserved_amount numeric(20,6) NOT NULL,
    actual_amount numeric(20,6),
    expires_at timestamp with time zone,
    version bigint NOT NULL,
    committed_at timestamp with time zone,
    cancelled_at timestamp with time zone,
    cancellation_reason_code text,
    lease_owner text,
    lease_token text,
    lease_until timestamp with time zone,
    lease_version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT usage_reservation_quantity_check CHECK ((quantity >= (0)::numeric)),
    CONSTRAINT usage_reservation_v3_ck CHECK (((record_format = ANY (ARRAY['LEGACY_V1'::text, 'V3'::text])) AND (length(btrim(deployment_scope)) > 0) AND (length(btrim(subject_scope)) > 0) AND (length(btrim(expected_meeting_id)) > 0) AND (length(btrim(usage_unit)) > 0) AND (reserved_amount >= (0)::numeric) AND (version >= 0) AND (lease_version >= 0) AND (((bound_meeting_id IS NULL) AND (bound_at IS NULL)) OR ((bound_meeting_id = expected_meeting_id) AND (bound_at IS NOT NULL))) AND (((record_format = 'LEGACY_V1'::text) AND (subject_scope = 'LEGACY_UNOWNED'::text) AND (grant_id IS NULL) AND (expires_at IS NULL) AND (meeting_id IS NOT NULL) AND (expected_meeting_id = meeting_id) AND (bound_meeting_id = meeting_id) AND (bound_at = created_at) AND (usage_unit = usage_type) AND (reserved_amount = quantity) AND (lease_owner IS NULL) AND (lease_token IS NULL) AND (lease_until IS NULL) AND (lease_version = 0) AND (((status = 'RESERVED'::text) AND (actual_amount IS NULL) AND (committed_at IS NULL) AND (cancelled_at IS NULL) AND (cancellation_reason_code IS NULL) AND (version = 1)) OR ((status = 'COMMITTED'::text) AND (actual_amount = quantity) AND (committed_at = updated_at) AND (cancelled_at IS NULL) AND (cancellation_reason_code IS NULL) AND (version = 2)) OR ((status = 'CANCELLED'::text) AND (actual_amount IS NULL) AND (committed_at IS NULL) AND (cancelled_at = updated_at) AND (cancellation_reason_code = 'LEGACY_MIGRATION'::text) AND (version = 1)))) OR ((record_format = 'V3'::text) AND (grant_id IS NOT NULL) AND (length(btrim(grant_id)) > 0) AND (usage_unit = 'MEETING_DURATION_MS'::text) AND (reserved_amount > (0)::numeric) AND (expires_at IS NOT NULL) AND (expires_at > created_at) AND (updated_at >= created_at) AND ((bound_at IS NULL) OR ((bound_at >= created_at) AND (bound_at < expires_at))) AND (((status = 'RESERVED'::text) AND (actual_amount IS NULL) AND (committed_at IS NULL) AND (cancelled_at IS NULL) AND (cancellation_reason_code IS NULL) AND (((bound_meeting_id IS NULL) AND (version = 0)) OR ((bound_meeting_id IS NOT NULL) AND (version >= 1)))) OR ((status = 'COMMITTED'::text) AND (bound_meeting_id IS NOT NULL) AND (actual_amount IS NOT NULL) AND (actual_amount >= (0)::numeric) AND (committed_at IS NOT NULL) AND (committed_at >= bound_at) AND (cancelled_at IS NULL) AND (cancellation_reason_code IS NULL) AND (version >= 2)) OR ((status = 'CANCELLED'::text) AND (bound_meeting_id IS NULL) AND (actual_amount IS NULL) AND (committed_at IS NULL) AND (cancelled_at IS NOT NULL) AND (cancelled_at >= created_at) AND (cancellation_reason_code ~ '^[A-Z0-9_]+$'::text) AND (version >= 1))) AND (((lease_owner IS NULL) AND (lease_token IS NULL) AND (lease_until IS NULL)) OR ((status = 'RESERVED'::text) AND (bound_meeting_id IS NULL) AND (length(btrim(lease_owner)) > 0) AND (length(btrim(lease_token)) > 0) AND (lease_until IS NOT NULL) AND (lease_version > 0)))))))
);


--
-- Name: workflow_step; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE workflow_step (
    step_id bigint NOT NULL,
    meeting_id text NOT NULL,
    step_type text NOT NULL,
    step_key text NOT NULL,
    status text NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    lease_owner text,
    lease_until timestamp with time zone,
    input_artifact_id text,
    output_artifact_id text,
    error_code text,
    error_message text,
    updated_at timestamp with time zone NOT NULL,
    checkpoint_type text NOT NULL,
    canonical_payload text NOT NULL,
    content_digest text NOT NULL,
    initial_available_at timestamp with time zone NOT NULL,
    next_attempt_at timestamp with time zone,
    lease_token text,
    lease_version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT workflow_step_attempt_count_check CHECK ((attempt_count >= 0)),
    CONSTRAINT workflow_step_definition_ck CHECK (((length(btrim(checkpoint_type)) > 0) AND (length(btrim(canonical_payload)) > 0) AND (length(btrim(content_digest)) > 0))),
    CONSTRAINT workflow_step_facts_ck CHECK (((lease_version >= 0) AND (((status = 'PENDING'::text) AND (next_attempt_at IS NOT NULL) AND (lease_owner IS NULL) AND (lease_until IS NULL) AND (lease_token IS NULL)) OR ((status = 'LEASED'::text) AND (next_attempt_at IS NULL) AND (lease_owner IS NOT NULL) AND (length(btrim(lease_owner)) > 0) AND (lease_until IS NOT NULL) AND (lease_token IS NOT NULL) AND (lease_version > 0) AND (lease_until > updated_at)) OR ((status = 'COMPLETED'::text) AND (next_attempt_at IS NULL) AND (lease_owner IS NULL) AND (lease_until IS NULL) AND (lease_token IS NULL) AND (error_code IS NULL) AND (error_message IS NULL)) OR ((status = 'DEAD'::text) AND (next_attempt_at IS NULL) AND (lease_owner IS NULL) AND (lease_until IS NULL) AND (lease_token IS NULL) AND (error_code IS NOT NULL) AND (error_message IS NOT NULL))) AND (((error_code IS NULL) AND (error_message IS NULL)) OR ((error_code IS NOT NULL) AND (error_message IS NOT NULL) AND (length(btrim(error_code)) > 0) AND (length(btrim(error_message)) > 0))))),
    CONSTRAINT workflow_step_lease_token_ck CHECK (((lease_token IS NULL) OR (length(btrim(lease_token)) > 0))),
    CONSTRAINT workflow_step_status_ck CHECK ((status = ANY (ARRAY['PENDING'::text, 'LEASED'::text, 'COMPLETED'::text, 'DEAD'::text])))
);


--
-- Name: workflow_step_step_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE workflow_step ALTER COLUMN step_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME workflow_step_step_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: artifact_deletion_item artifact_deletion_item_artifact_id_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY artifact_deletion_item
    ADD CONSTRAINT artifact_deletion_item_artifact_id_uq UNIQUE (artifact_id);


--
-- Name: artifact_deletion_item artifact_deletion_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY artifact_deletion_item
    ADD CONSTRAINT artifact_deletion_item_pkey PRIMARY KEY (meeting_id, ordinal);


--
-- Name: artifact_deletion_item artifact_deletion_item_revision_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY artifact_deletion_item
    ADD CONSTRAINT artifact_deletion_item_revision_uq UNIQUE (meeting_id, artifact_type, revision);


--
-- Name: artifact artifact_intent_id_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY artifact
    ADD CONSTRAINT artifact_intent_id_uq UNIQUE (intent_id);


--
-- Name: artifact artifact_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY artifact
    ADD CONSTRAINT artifact_pkey PRIMARY KEY (artifact_id);


--
-- Name: artifact artifact_revision_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY artifact
    ADD CONSTRAINT artifact_revision_uq UNIQUE (meeting_id, artifact_type, revision);


--
-- Name: artifact artifact_storage_binding_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY artifact
    ADD CONSTRAINT artifact_storage_binding_uq UNIQUE (storage_binding);


--
-- Name: durable_input_fact durable_input_fact_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY durable_input_fact
    ADD CONSTRAINT durable_input_fact_pkey PRIMARY KEY (meeting_id);


--
-- Name: final_usage_fact final_usage_fact_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY final_usage_fact
    ADD CONSTRAINT final_usage_fact_pkey PRIMARY KEY (meeting_id);


--
-- Name: finalization_execution_fact finalization_execution_fact_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY finalization_execution_fact
    ADD CONSTRAINT finalization_execution_fact_pkey PRIMARY KEY (meeting_id);


--
-- Name: idempotency_record idempotency_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY idempotency_record
    ADD CONSTRAINT idempotency_record_pkey PRIMARY KEY (subject_scope, idempotency_key);


--
-- Name: meeting_audio_frame meeting_audio_frame_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_audio_frame
    ADD CONSTRAINT meeting_audio_frame_pkey PRIMARY KEY (meeting_id, stream_sequence);


--
-- Name: meeting_audio_input meeting_audio_input_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_audio_input
    ADD CONSTRAINT meeting_audio_input_pkey PRIMARY KEY (meeting_id);


--
-- Name: meeting_create_fact meeting_create_fact_meeting_id_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_create_fact
    ADD CONSTRAINT meeting_create_fact_meeting_id_uq UNIQUE (meeting_id);


--
-- Name: meeting_create_fact meeting_create_fact_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_create_fact
    ADD CONSTRAINT meeting_create_fact_pkey PRIMARY KEY (scope, idempotency_key);


--
-- Name: meeting_deletion_plan meeting_deletion_plan_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_deletion_plan
    ADD CONSTRAINT meeting_deletion_plan_pkey PRIMARY KEY (meeting_id);


--
-- Name: meeting_event meeting_event_id_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_event
    ADD CONSTRAINT meeting_event_id_uq UNIQUE (event_id);


--
-- Name: meeting_event meeting_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_event
    ADD CONSTRAINT meeting_event_pkey PRIMARY KEY (meeting_id, event_sequence);


--
-- Name: meeting_manifest_revision meeting_manifest_revision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_manifest_revision
    ADD CONSTRAINT meeting_manifest_revision_pkey PRIMARY KEY (meeting_id, manifest_revision);


--
-- Name: meeting meeting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting
    ADD CONSTRAINT meeting_pkey PRIMARY KEY (meeting_id);


--
-- Name: meeting_realtime_token_grant meeting_realtime_token_grant_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_realtime_token_grant
    ADD CONSTRAINT meeting_realtime_token_grant_pkey PRIMARY KEY (meeting_id, idempotency_key);


--
-- Name: meeting_realtime_token_grant meeting_realtime_token_grant_token_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_realtime_token_grant
    ADD CONSTRAINT meeting_realtime_token_grant_token_uq UNIQUE (token_sha256);


--
-- Name: meeting_stream meeting_stream_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_stream
    ADD CONSTRAINT meeting_stream_pkey PRIMARY KEY (meeting_id, stream_id);


--
-- Name: outbox_event outbox_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY outbox_event
    ADD CONSTRAINT outbox_event_pkey PRIMARY KEY (event_id);


--
-- Name: plugin_execution plugin_execution_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY plugin_execution
    ADD CONSTRAINT plugin_execution_pkey PRIMARY KEY (execution_id);


--
-- Name: usage_operation usage_operation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY usage_operation
    ADD CONSTRAINT usage_operation_pkey PRIMARY KEY (deployment_scope, subject_scope, operation_scope, idempotency_key);


--
-- Name: usage_reservation usage_reservation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY usage_reservation
    ADD CONSTRAINT usage_reservation_pkey PRIMARY KEY (reservation_id);


--
-- Name: workflow_step workflow_step_key_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY workflow_step
    ADD CONSTRAINT workflow_step_key_uq UNIQUE (meeting_id, step_type, step_key);


--
-- Name: workflow_step workflow_step_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY workflow_step
    ADD CONSTRAINT workflow_step_pkey PRIMARY KEY (step_id);


--
-- Name: idempotency_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idempotency_expiry_idx ON idempotency_record USING btree (expires_at);


--
-- Name: meeting_event_stream_sequence_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX meeting_event_stream_sequence_idx ON meeting_event USING btree (meeting_id, stream_id, event_sequence);


--
-- Name: meeting_finalization_receipt_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX meeting_finalization_receipt_uq ON meeting USING btree (finalization_receipt_id) WHERE (finalization_receipt_id IS NOT NULL);


--
-- Name: meeting_realtime_token_grant_active_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX meeting_realtime_token_grant_active_idx ON meeting_realtime_token_grant USING btree (meeting_id, expires_at);


--
-- Name: meeting_scope_session_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX meeting_scope_session_uq ON meeting USING btree (deployment_scope, client_session_id);


--
-- Name: meeting_scope_state_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX meeting_scope_state_idx ON meeting USING btree (deployment_scope, state, updated_at);


--
-- Name: meeting_scope_subject_created_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX meeting_scope_subject_created_idx ON meeting USING btree (deployment_scope, subject_scope, created_at DESC, meeting_id DESC);


--
-- Name: outbox_claim_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX outbox_claim_idx ON outbox_event USING btree (status, available_at, lease_until);


--
-- Name: outbox_leased_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX outbox_leased_expiry_idx ON outbox_event USING btree (lease_until, lease_version) WHERE (status = 'LEASED'::text);


--
-- Name: usage_operation_reservation_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX usage_operation_reservation_idx ON usage_operation USING btree (reservation_id, created_at);


--
-- Name: usage_reservation_expired_unbound_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX usage_reservation_expired_unbound_idx ON usage_reservation USING btree (expires_at, lease_until, reservation_id) WHERE ((record_format = 'V3'::text) AND (status = 'RESERVED'::text) AND (bound_meeting_id IS NULL));


--
-- Name: usage_reservation_scope_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX usage_reservation_scope_uq ON usage_reservation USING btree (deployment_scope, subject_scope, reservation_id);


--
-- Name: workflow_step_claim_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX workflow_step_claim_idx ON workflow_step USING btree (status, lease_until, updated_at);


--
-- Name: workflow_step_claim_v010_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX workflow_step_claim_v010_idx ON workflow_step USING btree (step_type, status, next_attempt_at, lease_until, meeting_id, step_key);


--
-- Name: artifact_deletion_item artifact_deletion_item_plan_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY artifact_deletion_item
    ADD CONSTRAINT artifact_deletion_item_plan_fk FOREIGN KEY (meeting_id) REFERENCES meeting_deletion_plan(meeting_id) ON DELETE RESTRICT;


--
-- Name: artifact artifact_meeting_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY artifact
    ADD CONSTRAINT artifact_meeting_fk FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT;


--
-- Name: durable_input_fact durable_input_fact_meeting_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY durable_input_fact
    ADD CONSTRAINT durable_input_fact_meeting_fk FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT;


--
-- Name: final_usage_fact final_usage_fact_meeting_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY final_usage_fact
    ADD CONSTRAINT final_usage_fact_meeting_fk FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT;


--
-- Name: finalization_execution_fact finalization_execution_fact_meeting_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY finalization_execution_fact
    ADD CONSTRAINT finalization_execution_fact_meeting_fk FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT;


--
-- Name: meeting_audio_frame meeting_audio_frame_input_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_audio_frame
    ADD CONSTRAINT meeting_audio_frame_input_fk FOREIGN KEY (meeting_id) REFERENCES meeting_audio_input(meeting_id) ON DELETE RESTRICT;


--
-- Name: meeting_audio_input meeting_audio_input_meeting_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_audio_input
    ADD CONSTRAINT meeting_audio_input_meeting_fk FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT;


--
-- Name: meeting_create_fact meeting_create_fact_meeting_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_create_fact
    ADD CONSTRAINT meeting_create_fact_meeting_fk FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT;


--
-- Name: meeting_deletion_plan meeting_deletion_plan_manifest_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_deletion_plan
    ADD CONSTRAINT meeting_deletion_plan_manifest_fk FOREIGN KEY (meeting_id, source_manifest_revision) REFERENCES meeting_manifest_revision(meeting_id, manifest_revision) ON DELETE RESTRICT;


--
-- Name: meeting_deletion_plan meeting_deletion_plan_meeting_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_deletion_plan
    ADD CONSTRAINT meeting_deletion_plan_meeting_fk FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT;


--
-- Name: meeting_deletion_plan meeting_deletion_plan_workflow_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_deletion_plan
    ADD CONSTRAINT meeting_deletion_plan_workflow_fk FOREIGN KEY (meeting_id, source_workflow_step_type, source_workflow_step_key) REFERENCES workflow_step(meeting_id, step_type, step_key) ON DELETE RESTRICT;


--
-- Name: meeting_event meeting_event_meeting_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_event
    ADD CONSTRAINT meeting_event_meeting_id_fkey FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id);

ALTER TABLE ONLY meeting_client_input
    ADD CONSTRAINT meeting_client_input_meeting_id_fkey FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id);

ALTER TABLE ONLY meeting_client_event
    ADD CONSTRAINT meeting_client_event_event_id_fkey FOREIGN KEY (event_id) REFERENCES meeting_event(event_id);


--
-- Name: meeting_manifest_revision meeting_manifest_revision_meeting_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_manifest_revision
    ADD CONSTRAINT meeting_manifest_revision_meeting_fk FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT;


--
-- Name: meeting_manifest_revision meeting_manifest_revision_previous_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_manifest_revision
    ADD CONSTRAINT meeting_manifest_revision_previous_fk FOREIGN KEY (meeting_id, previous_revision) REFERENCES meeting_manifest_revision(meeting_id, manifest_revision) ON DELETE RESTRICT;


--
-- Name: meeting_realtime_token_grant meeting_realtime_token_grant_meeting_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_realtime_token_grant
    ADD CONSTRAINT meeting_realtime_token_grant_meeting_fk FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id) ON DELETE RESTRICT;


--
-- Name: meeting_stream meeting_stream_meeting_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting_stream
    ADD CONSTRAINT meeting_stream_meeting_id_fkey FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id);


--
-- Name: meeting meeting_usage_reservation_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY meeting
    ADD CONSTRAINT meeting_usage_reservation_fk FOREIGN KEY (usage_reservation_id) REFERENCES usage_reservation(reservation_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: plugin_execution plugin_execution_meeting_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY plugin_execution
    ADD CONSTRAINT plugin_execution_meeting_id_fkey FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id);


--
-- Name: usage_operation usage_operation_reservation_scope_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY usage_operation
    ADD CONSTRAINT usage_operation_reservation_scope_fk FOREIGN KEY (deployment_scope, subject_scope, reservation_id) REFERENCES usage_reservation(deployment_scope, subject_scope, reservation_id);


--
-- Name: usage_reservation usage_reservation_bound_meeting_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY usage_reservation
    ADD CONSTRAINT usage_reservation_bound_meeting_fk FOREIGN KEY (bound_meeting_id) REFERENCES meeting(meeting_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: workflow_step workflow_step_meeting_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY workflow_step
    ADD CONSTRAINT workflow_step_meeting_id_fkey FOREIGN KEY (meeting_id) REFERENCES meeting(meeting_id);


--
-- PostgreSQL database dump complete
--
