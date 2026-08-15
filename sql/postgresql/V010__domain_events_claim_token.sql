-- 领取令牌（fencing token）。
--
-- 行锁只覆盖领取那一条语句，监听者执行期间早已释放。领取者 A 超时后被 B 重领，
-- A 迟到的 markDelivered / markFailed 若只按 id 匹配，会把 B 已经完成的记录改回
-- 待重试或终态失败。每次领取换新令牌、落定时校验，旧领取者的落定自动作废。
ALTER TABLE infra_domain_events ADD COLUMN IF NOT EXISTS claim_token VARCHAR(64);
