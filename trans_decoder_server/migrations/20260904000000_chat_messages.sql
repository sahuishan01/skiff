-- Chat messages table for store-and-forward messaging
CREATE TABLE IF NOT EXISTS chat_messages (
    message_id UUID PRIMARY KEY,
    sender_device_id VARCHAR(36) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    receiver_device_id VARCHAR(36) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_chat_receiver_status ON chat_messages(receiver_device_id, status);
CREATE INDEX IF NOT EXISTS idx_chat_sender_receiver ON chat_messages(sender_device_id, receiver_device_id);
