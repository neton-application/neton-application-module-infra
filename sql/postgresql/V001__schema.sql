-- infra: 1.0.0-beta fresh-install schema. Not an upgrade path.
-- Apply through the migration runner; never edit after this baseline is released.
SET search_path = public;
--
--


SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: infra_api_access_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_api_access_logs (
    id bigint NOT NULL,
    user_id bigint,
    user_type smallint DEFAULT 0 NOT NULL,
    application_name character varying(128),
    request_method character varying(16),
    request_url character varying(512) NOT NULL,
    request_params text,
    response_body text,
    user_ip character varying(64),
    user_agent character varying(512),
    operate_module character varying(64),
    operate_name character varying(64),
    operate_type smallint DEFAULT 0 NOT NULL,
    begin_time bigint DEFAULT 0 NOT NULL,
    end_time bigint DEFAULT 0 NOT NULL,
    duration integer DEFAULT 0 NOT NULL,
    result_code integer DEFAULT 0 NOT NULL,
    result_msg text,
    created_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: infra_api_access_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_api_access_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_api_access_logs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.infra_api_access_logs_id_seq OWNED BY public.infra_api_access_logs.id;


--
-- Name: infra_api_error_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_api_error_logs (
    id bigint NOT NULL,
    user_id bigint,
    user_type smallint DEFAULT 0 NOT NULL,
    application_name character varying(128),
    request_method character varying(16),
    request_url character varying(512) NOT NULL,
    request_params text,
    user_ip character varying(64),
    user_agent character varying(512),
    exception_name character varying(255),
    exception_message text,
    exception_stack_trace text,
    process_status smallint DEFAULT 0 NOT NULL,
    process_user_id bigint,
    process_time bigint,
    created_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: infra_api_error_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_api_error_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_api_error_logs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.infra_api_error_logs_id_seq OWNED BY public.infra_api_error_logs.id;


--
-- Name: infra_domain_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_domain_events (
    id bigint NOT NULL,
    event_type character varying(128) NOT NULL,
    listener_id character varying(128) NOT NULL,
    payload text NOT NULL,
    status smallint DEFAULT 0 NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at bigint DEFAULT 0 NOT NULL,
    last_error text,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL,
    claim_token character varying(64)
);


--
-- Name: infra_domain_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_domain_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_domain_events_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.infra_domain_events_id_seq OWNED BY public.infra_domain_events.id;


--
-- Name: infra_file_configs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_file_configs (
    id bigint NOT NULL,
    name character varying(128) NOT NULL,
    storage smallint DEFAULT 0 NOT NULL,
    config text DEFAULT ''::text NOT NULL,
    master smallint DEFAULT 0 NOT NULL,
    remark character varying(512),
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: infra_file_configs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_file_configs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_file_configs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.infra_file_configs_id_seq OWNED BY public.infra_file_configs.id;


--
-- Name: infra_files; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_files (
    id bigint NOT NULL,
    config_id bigint,
    name character varying(255) NOT NULL,
    path character varying(512) NOT NULL,
    url character varying(512),
    mime_type character varying(128),
    size bigint DEFAULT 0 NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    file_id character varying(64),
    owner_uid bigint,
    business_type character varying(64),
    sha256 character varying(64),
    status smallint DEFAULT 1 NOT NULL,
    lifecycle character varying(16) DEFAULT 'attached'::character varying NOT NULL,
    lifecycle_at bigint,
    purged_at bigint,
    admin_uploader_id bigint,
    width integer,
    height integer,
    CONSTRAINT ck_infra_files_lifecycle CHECK (((lifecycle)::text = ANY ((ARRAY['temporary'::character varying, 'attached'::character varying, 'orphaned'::character varying, 'deleted'::character varying])::text[])))
);


--
-- Name: COLUMN infra_files.lifecycle; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_files.lifecycle IS '媒体生命周期：temporary/attached/orphaned/deleted';


--
-- Name: COLUMN infra_files.lifecycle_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_files.lifecycle_at IS '生命周期最近变化时刻（epoch ms），宽限期起点';


--
-- Name: COLUMN infra_files.purged_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_files.purged_at IS '物理对象清理完成时刻（epoch ms）；deleted 且为空表示待清理';


--
-- Name: COLUMN infra_files.admin_uploader_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_files.admin_uploader_id IS '后台上传者（system_users.id）；有值时 owner_uid 为空';


--
-- Name: COLUMN infra_files.width; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_files.width IS '图片原始宽度（像素，已按 EXIF 方向校正）；非图片或未识别为空';


--
-- Name: COLUMN infra_files.height; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_files.height IS '图片原始高度（像素，已按 EXIF 方向校正）；非图片或未识别为空';


--
-- Name: infra_files_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_files_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_files_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.infra_files_id_seq OWNED BY public.infra_files.id;


--
-- Name: infra_job_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_job_logs (
    id bigint NOT NULL,
    job_id bigint NOT NULL,
    handler_name character varying(128) NOT NULL,
    handler_param character varying(512),
    execute_index integer DEFAULT 1 NOT NULL,
    begin_time bigint DEFAULT 0 NOT NULL,
    end_time bigint,
    duration integer,
    status smallint DEFAULT 0 NOT NULL,
    result_msg text,
    created_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: infra_job_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_job_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_job_logs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.infra_job_logs_id_seq OWNED BY public.infra_job_logs.id;


--
-- Name: infra_jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_jobs (
    id bigint NOT NULL,
    name character varying(128) NOT NULL,
    handler_name character varying(128) NOT NULL,
    handler_param character varying(512),
    cron_expression character varying(64),
    retry_count integer DEFAULT 0 NOT NULL,
    retry_interval integer DEFAULT 0 NOT NULL,
    status smallint DEFAULT 0 NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: infra_jobs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_jobs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_jobs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.infra_jobs_id_seq OWNED BY public.infra_jobs.id;


--
-- Name: infra_media_references; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_media_references (
    id bigint NOT NULL,
    file_id character varying(64) NOT NULL,
    owner_type character varying(64) NOT NULL,
    owner_id character varying(128) NOT NULL,
    usage character varying(64) NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE infra_media_references; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.infra_media_references IS '媒体引用登记（infra-media §6.2）';


--
-- Name: COLUMN infra_media_references.file_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_media_references.file_id IS '对外文件 ID（infra_files.file_id）';


--
-- Name: COLUMN infra_media_references.owner_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_media_references.owner_type IS '引用对象类型，如 content.item';


--
-- Name: COLUMN infra_media_references.owner_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_media_references.owner_id IS '引用对象 ID';


--
-- Name: COLUMN infra_media_references.usage; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_media_references.usage IS '用途，须在文件业务类型声明的 usages 内';


--
-- Name: COLUMN infra_media_references.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_media_references.created_at IS '登记时间（epoch ms）';


--
-- Name: infra_media_references_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.infra_media_references ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.infra_media_references_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: infra_api_access_logs id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_api_access_logs ALTER COLUMN id SET DEFAULT nextval('public.infra_api_access_logs_id_seq'::regclass);


--
-- Name: infra_api_error_logs id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_api_error_logs ALTER COLUMN id SET DEFAULT nextval('public.infra_api_error_logs_id_seq'::regclass);


--
-- Name: infra_domain_events id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_domain_events ALTER COLUMN id SET DEFAULT nextval('public.infra_domain_events_id_seq'::regclass);


--
-- Name: infra_file_configs id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_file_configs ALTER COLUMN id SET DEFAULT nextval('public.infra_file_configs_id_seq'::regclass);


--
-- Name: infra_files id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_files ALTER COLUMN id SET DEFAULT nextval('public.infra_files_id_seq'::regclass);


--
-- Name: infra_job_logs id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_job_logs ALTER COLUMN id SET DEFAULT nextval('public.infra_job_logs_id_seq'::regclass);


--
-- Name: infra_jobs id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_jobs ALTER COLUMN id SET DEFAULT nextval('public.infra_jobs_id_seq'::regclass);


--
-- Name: infra_api_access_logs infra_api_access_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_api_access_logs
    ADD CONSTRAINT infra_api_access_logs_pkey PRIMARY KEY (id);


--
-- Name: infra_api_error_logs infra_api_error_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_api_error_logs
    ADD CONSTRAINT infra_api_error_logs_pkey PRIMARY KEY (id);


--
-- Name: infra_domain_events infra_domain_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_domain_events
    ADD CONSTRAINT infra_domain_events_pkey PRIMARY KEY (id);


--
-- Name: infra_file_configs infra_file_configs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_file_configs
    ADD CONSTRAINT infra_file_configs_pkey PRIMARY KEY (id);


--
-- Name: infra_files infra_files_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_files
    ADD CONSTRAINT infra_files_pkey PRIMARY KEY (id);


--
-- Name: infra_job_logs infra_job_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_job_logs
    ADD CONSTRAINT infra_job_logs_pkey PRIMARY KEY (id);


--
-- Name: infra_jobs infra_jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_jobs
    ADD CONSTRAINT infra_jobs_pkey PRIMARY KEY (id);


--
-- Name: infra_media_references infra_media_references_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_media_references
    ADD CONSTRAINT infra_media_references_pkey PRIMARY KEY (id);


--
-- Name: infra_media_references uk_infra_media_references; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_media_references
    ADD CONSTRAINT uk_infra_media_references UNIQUE (file_id, owner_type, owner_id, usage);


--
-- Name: idx_infra_domain_events_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_infra_domain_events_due ON public.infra_domain_events USING btree (status, next_attempt_at);


--
-- Name: idx_infra_domain_events_stale; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_infra_domain_events_stale ON public.infra_domain_events USING btree (status, updated_at);


--
-- Name: idx_infra_files_admin_uploader; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_infra_files_admin_uploader ON public.infra_files USING btree (admin_uploader_id, business_type, sha256) WHERE (admin_uploader_id IS NOT NULL);


--
-- Name: idx_infra_files_file_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_infra_files_file_id ON public.infra_files USING btree (file_id) WHERE (file_id IS NOT NULL);


--
-- Name: idx_infra_files_lifecycle; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_infra_files_lifecycle ON public.infra_files USING btree (lifecycle, lifecycle_at) WHERE ((lifecycle)::text <> 'attached'::text);


--
-- Name: idx_infra_files_owner_biz_sha; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_infra_files_owner_biz_sha ON public.infra_files USING btree (owner_uid, business_type, sha256);


--
-- Name: idx_infra_files_path; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_infra_files_path ON public.infra_files USING btree (path);


--
-- Name: idx_infra_job_logs_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_infra_job_logs_job ON public.infra_job_logs USING btree (job_id);


--
-- Name: idx_infra_media_references_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_infra_media_references_owner ON public.infra_media_references USING btree (owner_type, owner_id);


--
--


SET search_path = public;
