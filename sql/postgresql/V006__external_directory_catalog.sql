CREATE UNIQUE INDEX infra_files_external_directory_path
    ON infra_files (path)
    WHERE mime_type = 'inode/directory';
