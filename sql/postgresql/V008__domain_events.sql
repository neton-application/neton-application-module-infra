-- 领域事件投递表（framework 的 DomainEventStore 端口在 infra 的落地实现）。
--
-- 事件在发布方的事务内写入本表，提交后由调度任务异步投递、失败退避重试。
-- 与业务写入同事务是关键：发布方回滚记录随之消失，发布方提交记录必然存在，
-- 不存在"主流程成功了事件却丢了"或"投递了并未发生的事件"。
--
-- 表归 infra 而不是框架：用什么载体投递（数据库 / 线程 / 中间件）是应用层的选择，
-- 框架只定义端口。

CREATE TABLE IF NOT EXISTS infra_domain_events (
    id              BIGSERIAL PRIMARY KEY,
    -- 事件类型标识，投递时据此还原事件对象
    event_type      VARCHAR(128) NOT NULL,
    -- 目标监听者；同一事件的多个监听者各一行，互不影响重试
    listener_id     VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,
    -- 0=待投递 1=投递中 2=已投递 3=终态失败
    status          SMALLINT     NOT NULL DEFAULT 0,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at BIGINT       NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      BIGINT       NOT NULL DEFAULT 0,
    updated_at      BIGINT       NOT NULL DEFAULT 0
);

-- 投递查询固定按 (status, next_attempt_at) 过滤，缺这个索引在积压时会全表扫
CREATE INDEX IF NOT EXISTS idx_infra_domain_events_due
    ON infra_domain_events (status, next_attempt_at);
