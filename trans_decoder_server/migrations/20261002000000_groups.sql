-- Groups table
CREATE TABLE IF NOT EXISTS groups (
    group_id VARCHAR(36) PRIMARY KEY,
    group_code VARCHAR(6) UNIQUE NOT NULL,
    group_name VARCHAR(100) NOT NULL,
    created_by VARCHAR(36) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Group members table
CREATE TABLE IF NOT EXISTS group_members (
    group_id VARCHAR(36) NOT NULL REFERENCES groups(group_id) ON DELETE CASCADE,
    device_id VARCHAR(36) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (group_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_groups_code ON groups(group_code);
CREATE INDEX IF NOT EXISTS idx_group_members_device ON group_members(device_id);
