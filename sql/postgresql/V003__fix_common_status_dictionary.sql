-- Align the shared status dictionary with the platform-wide status convention.
UPDATE system_dict_data
SET label = '启用', sort = 1
WHERE dict_type = 'common_status' AND value = '1';

UPDATE system_dict_data
SET label = '停用', sort = 2
WHERE dict_type = 'common_status' AND value = '0';
