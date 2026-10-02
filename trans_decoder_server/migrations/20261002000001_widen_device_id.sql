-- Widen device_id columns to VARCHAR(64) to support prefixed IDs (e.g. web-uuid)
ALTER TABLE devices ALTER COLUMN device_id TYPE VARCHAR(64);
ALTER TABLE transfer_sessions ALTER COLUMN sender_device_id TYPE VARCHAR(64);
ALTER TABLE transfer_sessions ALTER COLUMN receiver_device_id TYPE VARCHAR(64);
ALTER TABLE chat_messages ALTER COLUMN sender_device_id TYPE VARCHAR(64);
ALTER TABLE chat_messages ALTER COLUMN receiver_device_id TYPE VARCHAR(64);
ALTER TABLE groups ALTER COLUMN created_by TYPE VARCHAR(64);
ALTER TABLE group_members ALTER COLUMN device_id TYPE VARCHAR(64);
