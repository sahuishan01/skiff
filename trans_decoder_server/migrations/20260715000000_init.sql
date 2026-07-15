-- Create enums if they do not exist
DO $$ 
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'session_status') THEN
        CREATE TYPE session_status AS ENUM ('pending', 'active', 'completed', 'failed', 'paused');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'file_status') THEN
        CREATE TYPE file_status AS ENUM ('pending', 'transferring', 'completed', 'failed', 'paused');
    END IF;
END $$;

-- Devices registry
CREATE TABLE IF NOT EXISTS devices (
    device_id VARCHAR(36) PRIMARY KEY,
    device_code VARCHAR(6) UNIQUE NOT NULL,
    public_ip VARCHAR(45),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Active or historical P2P sessions
CREATE TABLE IF NOT EXISTS transfer_sessions (
    session_id UUID PRIMARY KEY,
    sender_device_id VARCHAR(36) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    receiver_device_id VARCHAR(36) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    status session_status NOT NULL DEFAULT 'pending',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- File-level status for resume capability
CREATE TABLE IF NOT EXISTS transfer_files (
    file_id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES transfer_sessions(session_id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    file_path TEXT NOT NULL,
    file_size BIGINT NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    bytes_transferred BIGINT NOT NULL DEFAULT 0,
    status file_status NOT NULL DEFAULT 'pending',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for fast queries
CREATE INDEX IF NOT EXISTS idx_devices_code ON devices(device_code);
CREATE INDEX IF NOT EXISTS idx_files_session ON transfer_files(session_id);
CREATE INDEX IF NOT EXISTS idx_files_hash ON transfer_files(file_hash);
