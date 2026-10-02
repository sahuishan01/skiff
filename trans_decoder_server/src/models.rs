use serde::{Deserialize, Serialize};
use sqlx::Type;
use uuid::Uuid;
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, Type)]
#[sqlx(type_name = "session_status", rename_all = "lowercase")]
pub enum SessionStatus {
    Pending,
    Active,
    Completed,
    Failed,
    Paused,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, Type)]
#[sqlx(type_name = "file_status", rename_all = "lowercase")]
pub enum FileStatus {
    Pending,
    Transferring,
    Completed,
    Failed,
    Paused,
}

#[derive(Debug, Serialize, Deserialize, sqlx::FromRow)]
#[allow(dead_code)]
pub struct Device {
    pub device_id: String,
    pub device_code: String,
    pub public_ip: Option<String>,
    pub created_at: DateTime<Utc>,
    pub last_seen: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, sqlx::FromRow)]
#[allow(dead_code)]
pub struct TransferSession {
    pub session_id: Uuid,
    pub sender_device_id: String,
    pub receiver_device_id: String,
    pub status: SessionStatus,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, sqlx::FromRow)]
pub struct TransferFile {
    pub file_id: Uuid,
    pub session_id: Uuid,
    pub file_name: String,
    pub file_path: String,
    pub file_size: i64,
    pub file_hash: String,
    pub bytes_transferred: i64,
    pub status: FileStatus,
    pub updated_at: DateTime<Utc>,
}

// Request and response payloads for WebSockets
#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WsMessage {
    // Client -> Server
    Register {
        device_id: String,
    },
    RequestConnection {
        target_code: String,
    },
    RequestConnectionById {
        target_device_id: String,
    },
    AcceptRequest {
        sender_device_id: String,
    },
    RejectRequest {
        sender_device_id: String,
    },
    IceCandidate {
        target_device_id: String,
        candidate: serde_json::Value,
    },
    RegisterStunEndpoint {
        port: u16,
    },
    InitiateTransfer {
        session_id: Uuid,
        receiver_device_id: String,
        files: Vec<FileMetadataInput>,
    },
    UpdateProgress {
        file_id: Uuid,
        bytes_transferred: i64,
        status: FileStatus,
    },
    SendChat {
        message_id: Uuid,
        receiver_device_id: String,
        content: String,
    },
    CreateGroup {
        group_name: String,
    },
    JoinGroup {
        group_code: String,
    },
    LeaveGroup {
        group_id: String,
    },
    GetMyGroups,
    SendGroupChat {
        message_id: Uuid,
        group_id: String,
        content: String,
    },
    InitiateGroupTransfer {
        group_id: String,
        session_id: Uuid,
        files: Vec<FileMetadataInput>,
    },
    
    // Server -> Client
    Registered {
        device_code: String,
    },
    IncomingRequest {
        sender_device_id: String,
        sender_code: String,
    },
    RequestAccepted {
        receiver_device_id: String,
        receiver_endpoint: Option<String>,
    },
    RequestRejected {
        reason: String,
    },
    RelayedIceCandidate {
        sender_device_id: String,
        candidate: serde_json::Value,
    },
    TransferInitiated {
        session_id: Uuid,
    },
    IncomingTransfer {
        session_id: Uuid,
        sender_device_id: String,
        files: Vec<FileMetadataInput>,
    },
    ProgressUpdated {
        file_id: Uuid,
        bytes_transferred: i64,
    },
    ChatReceived {
        message_id: Uuid,
        sender_device_id: String,
        content: String,
        created_at: DateTime<Utc>,
    },
    ChatDelivered {
        message_id: Uuid,
    },
    GroupCreated {
        group: GroupInfo,
    },
    GroupJoined {
        group: GroupInfo,
    },
    GroupLeft {
        group_id: String,
    },
    GroupList {
        groups: Vec<GroupInfo>,
    },
    GroupMemberJoined {
        group_id: String,
        member: GroupMemberInfo,
    },
    GroupMemberLeft {
        group_id: String,
        device_id: String,
    },
    GroupChatReceived {
        message_id: Uuid,
        group_id: String,
        sender_device_id: String,
        content: String,
        created_at: DateTime<Utc>,
    },
    Error {
        message: String,
    },
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct GroupMemberInfo {
    pub device_id: String,
    pub is_online: bool,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct GroupInfo {
    pub group_id: String,
    pub group_code: String,
    pub group_name: String,
    pub created_by: String,
    pub members: Vec<GroupMemberInfo>,
}

#[derive(Debug, Serialize, Deserialize, sqlx::FromRow, Clone)]
pub struct ChatMessage {
    pub message_id: Uuid,
    pub sender_device_id: String,
    pub receiver_device_id: String,
    pub content: String,
    pub status: String,
    pub created_at: DateTime<Utc>,
    pub delivered_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct FileMetadataInput {
    pub file_id: Uuid,
    pub file_name: String,
    pub file_path: String,
    pub file_size: i64,
    pub file_hash: String,
}
