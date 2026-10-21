-- V009：图片原始宽高（infra-media §7.3 / client-content 缺口 G7）
--
-- 上传与服务端转存时从文件头读出（PNG / GIF / JPEG（含 EXIF 方向）/ WebP / AVIF），不解码像素。
-- 网页端据此输出 width / height 与固定宽高比占位，避免布局偏移（CLS）；CDN 派生规格据宽度省掉放大的档位。
-- 历史行为空：读不到时照常展示，只是没有宽高。
ALTER TABLE infra_files ADD COLUMN IF NOT EXISTS width INTEGER;
ALTER TABLE infra_files ADD COLUMN IF NOT EXISTS height INTEGER;

COMMENT ON COLUMN infra_files.width IS '图片原始宽度（像素，已按 EXIF 方向校正）；非图片或未识别为空';
COMMENT ON COLUMN infra_files.height IS '图片原始高度（像素，已按 EXIF 方向校正）；非图片或未识别为空';
