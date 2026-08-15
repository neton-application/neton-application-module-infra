-- 回收滞留在"投递中"的事件记录所需的索引。
--
-- 领取之后、标记完成/失败之前进程崩溃，记录会卡在 status=1。原先没有任何机制把它改回
-- 待投递，于是这条记录永远不会被再次领取、也不会报错——事件静默永久丢失，而"不丢"
-- 正是选用事务性 outbox 的全部理由。
--
-- 现在 claimDue 额外捞 status=1 且 updated_at 超过可见性超时的行，本索引服务该条件。
CREATE INDEX IF NOT EXISTS idx_infra_domain_events_stale
    ON infra_domain_events (status, updated_at);

-- 历史数据修复：V008 时期总线恒传 createdAt=0，落库的时间戳全是 epoch 0。
-- 把它们对齐到当前时刻，否则这些行会被上面的滞留判定立刻误判为超时。
UPDATE infra_domain_events
SET created_at = (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    updated_at = (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
WHERE created_at = 0;
