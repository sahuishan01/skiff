use std::net::SocketAddr;
use axum::{
    extract::{
        connect_info::ConnectInfo,
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    response::IntoResponse,
};
use futures_util::{SinkExt, StreamExt};
use rand::{distributions::Alphanumeric, Rng};
use sqlx::PgPool;
use tokio::sync::mpsc;
use tracing::{error, info, warn};

use crate::{
    models::{FileStatus, GroupInfo, GroupMemberInfo, WsMessage},
    signaling::SignalingState,
};

pub async fn ws_handler(
    ws: WebSocketUpgrade,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    State((pool, signaling)): State<(PgPool, SignalingState)>,
) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_socket(socket, addr, pool, signaling))
}

async fn handle_socket(
    socket: WebSocket,
    addr: SocketAddr,
    pool: PgPool,
    signaling: SignalingState,
) {
    info!("New WebSocket connection from {}", addr);
    let (mut ws_sender, mut ws_receiver) = socket.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<WsMessage>();

    // Spawn a writer task to send outgoing WebSocket messages
    let write_task = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            match serde_json::to_string(&msg) {
                Ok(json) => {
                    if let Err(e) = ws_sender.send(Message::Text(json)).await {
                        error!("Error sending WS message: {}", e);
                        break;
                    }
                }
                Err(e) => {
                    error!("Error serializing WS message: {}", e);
                }
            }
        }
    });

    let mut client_device_id: Option<String> = None;
    let mut client_device_code: Option<String> = None;

    // Read incoming messages from client
    while let Some(result) = ws_receiver.next().await {
        let msg = match result {
            Ok(msg) => msg,
            Err(e) => {
                error!("WebSocket receive error from {}: {}", addr, e);
                break;
            }
        };

        let text = match msg {
            Message::Text(t) => t,
            Message::Close(_) => break,
            _ => continue,
        };

        let ws_msg: WsMessage = match serde_json::from_str(&text) {
            Ok(m) => m,
            Err(e) => {
                warn!("Invalid message payload: {}. Error: {}", text, e);
                let _ = tx.send(WsMessage::Error {
                    message: "Invalid JSON format".to_string(),
                });
                continue;
            }
        };

        match ws_msg {
            WsMessage::Register { device_id } => {
                let code: String = rand::thread_rng()
                    .sample_iter(&Alphanumeric)
                    .take(6)
                    .map(char::from)
                    .collect::<String>()
                    .to_uppercase();

                // Save/update device in DB
                let public_ip = addr.ip().to_string();
                let db_res = sqlx::query!(
                    "INSERT INTO devices (device_id, device_code, public_ip, last_seen) 
                     VALUES ($1, $2, $3, NOW()) 
                     ON CONFLICT (device_id) 
                     DO UPDATE SET public_ip = $3, last_seen = NOW() 
                     RETURNING device_code",
                    device_id,
                    code,
                    public_ip
                )
                .fetch_one(&pool)
                .await;

                match db_res {
                    Ok(row) => {
                        let final_code = row.device_code;
                        client_device_id = Some(device_id.clone());
                        client_device_code = Some(final_code.clone());

                        signaling
                            .register_device(device_id.clone(), final_code.clone(), tx.clone())
                            .await;

                        let _ = tx.send(WsMessage::Registered {
                            device_code: final_code,
                        });

                        // Deliver pending chat messages stored in server PostgreSQL DB
                        let pending_chats = sqlx::query!(
                            "SELECT message_id, sender_device_id, content, created_at
                             FROM chat_messages
                             WHERE receiver_device_id = $1 AND status = 'PENDING'
                             ORDER BY created_at ASC",
                            device_id
                        )
                        .fetch_all(&pool)
                        .await;

                        if let Ok(chats) = pending_chats {
                            for chat in chats {
                                let _ = tx.send(WsMessage::ChatReceived {
                                    message_id: chat.message_id,
                                    sender_device_id: chat.sender_device_id,
                                    content: chat.content,
                                    created_at: chat.created_at,
                                });

                                let _ = sqlx::query!(
                                    "UPDATE chat_messages SET status = 'DELIVERED', delivered_at = NOW() WHERE message_id = $1",
                                    chat.message_id
                                )
                                .execute(&pool)
                                .await;
                            }
                        }
                    }
                    Err(e) => {
                        error!("Failed to register device in database: {}", e);
                        let _ = tx.send(WsMessage::Error {
                            message: "Database registration failure".to_string(),
                        });
                    }
                }
            }

            WsMessage::SendChat {
                message_id,
                receiver_device_id,
                content,
            } => {
                let sender_id = match &client_device_id {
                    Some(id) => id,
                    None => {
                        let _ = tx.send(WsMessage::Error {
                            message: "Unregistered device".to_string(),
                        });
                        continue;
                    }
                };

                let is_receiver_online = signaling.is_device_connected(&receiver_device_id).await;
                let initial_status = if is_receiver_online { "DELIVERED" } else { "PENDING" };

                // Save message into DB
                let save_res = sqlx::query!(
                    "INSERT INTO chat_messages (message_id, sender_device_id, receiver_device_id, content, status, delivered_at)
                     VALUES ($1, $2, $3, $4, $5, $6) ON CONFLICT DO NOTHING",
                    message_id,
                    sender_id,
                    receiver_device_id,
                    content,
                    initial_status,
                    if is_receiver_online { Some(chrono::Utc::now()) } else { None }
                )
                .execute(&pool)
                .await;

                if let Err(e) = save_res {
                    error!("Failed to store chat message: {}", e);
                    let _ = tx.send(WsMessage::Error {
                        message: "Failed to store chat message".to_string(),
                    });
                    continue;
                }

                if is_receiver_online {
                    let delivered = signaling
                        .send_to_device(
                            &receiver_device_id,
                            WsMessage::ChatReceived {
                                message_id,
                                sender_device_id: sender_id.clone(),
                                content: content.clone(),
                                created_at: chrono::Utc::now(),
                            },
                        )
                        .await;

                    if delivered {
                        let _ = tx.send(WsMessage::ChatDelivered { message_id });
                    } else {
                        // Mark as PENDING if websocket send failed
                        let _ = sqlx::query!(
                            "UPDATE chat_messages SET status = 'PENDING', delivered_at = NULL WHERE message_id = $1",
                            message_id
                        )
                        .execute(&pool)
                        .await;
                    }
                }
            }

            WsMessage::RequestConnection { target_code } => {
                let sender_id = match &client_device_id {
                    Some(id) => id,
                    None => {
                        let _ = tx.send(WsMessage::Error {
                            message: "Unregistered device".to_string(),
                        });
                        continue;
                    }
                };

                let sender_code = client_device_code.clone().unwrap_or_default();

                if let Some(target_id) = signaling.get_device_id_by_code(&target_code.to_uppercase()).await {
                    let routed = signaling
                        .send_to_device(
                            &target_id,
                            WsMessage::IncomingRequest {
                                sender_device_id: sender_id.clone(),
                                sender_code,
                            },
                        )
                        .await;

                    if !routed {
                        let _ = tx.send(WsMessage::RequestRejected {
                            reason: "Target offline".to_string(),
                        });
                    }
                } else {
                    let _ = tx.send(WsMessage::RequestRejected {
                        reason: "Code not found".to_string(),
                    });
                }
            }

            WsMessage::RequestConnectionById { target_device_id } => {
                let sender_id = match &client_device_id {
                    Some(id) => id,
                    None => {
                        let _ = tx.send(WsMessage::Error {
                            message: "Unregistered device".to_string(),
                        });
                        continue;
                    }
                };

                let sender_code = client_device_code.clone().unwrap_or_default();

                if signaling.is_device_connected(&target_device_id).await {
                    let routed = signaling
                        .send_to_device(
                            &target_device_id,
                            WsMessage::IncomingRequest {
                                sender_device_id: sender_id.clone(),
                                sender_code,
                            },
                        )
                        .await;

                    if !routed {
                        let _ = tx.send(WsMessage::RequestRejected {
                            reason: "Target offline".to_string(),
                        });
                    }
                } else {
                    let _ = tx.send(WsMessage::RequestRejected {
                        reason: "Target offline".to_string(),
                    });
                }
            }

            WsMessage::AcceptRequest { sender_device_id } => {
                let receiver_id = match &client_device_id {
                    Some(id) => id,
                    None => continue,
                };

                let receiver_endpoint = Some(addr.ip().to_string());

                signaling
                    .send_to_device(
                        &sender_device_id,
                        WsMessage::RequestAccepted {
                            receiver_device_id: receiver_id.clone(),
                            receiver_endpoint,
                        },
                    )
                    .await;
            }

            WsMessage::RejectRequest { sender_device_id } => {
                signaling
                    .send_to_device(
                        &sender_device_id,
                        WsMessage::RequestRejected {
                            reason: "Rejected by peer".to_string(),
                        },
                    )
                    .await;
            }

            WsMessage::IceCandidate {
                target_device_id,
                candidate,
            } => {
                let sender_id = match &client_device_id {
                    Some(id) => id,
                    None => continue,
                };

                signaling
                    .send_to_device(
                        &target_device_id,
                        WsMessage::RelayedIceCandidate {
                            sender_device_id: sender_id.clone(),
                            candidate,
                        },
                    )
                    .await;
            }

            WsMessage::InitiateTransfer {
                session_id,
                receiver_device_id,
                files,
            } => {
                let sender_id = match &client_device_id {
                    Some(id) => id,
                    None => continue,
                };

                // Requirement: Media transfer allowed ONLY if recipient is actively connected
                if !signaling.is_device_connected(&receiver_device_id).await {
                    let _ = tx.send(WsMessage::Error {
                        message: "Target device is offline. Media transfer requires both devices to be online.".to_string(),
                    });
                    continue;
                }

                // Create transfer session in PostgreSQL
                let session_res = sqlx::query!(
                    "INSERT INTO transfer_sessions (session_id, sender_device_id, receiver_device_id, status)
                     VALUES ($1, $2, $3, 'active') ON CONFLICT DO NOTHING",
                    session_id,
                    sender_id,
                    receiver_device_id
                )
                .execute(&pool)
                .await;

                if let Err(e) = session_res {
                    error!("Failed to create transfer session: {}", e);
                    let _ = tx.send(WsMessage::Error {
                        message: "Failed to create transfer session".to_string(),
                    });
                    continue;
                }

                // Insert files
                let mut db_error = false;
                for file in &files {
                    let file_res = sqlx::query!(
                        "INSERT INTO transfer_files (file_id, session_id, file_name, file_path, file_size, file_hash, bytes_transferred, status)
                         VALUES ($1, $2, $3, $4, $5, $6, 0, 'pending') ON CONFLICT DO NOTHING",
                        file.file_id,
                        session_id,
                        file.file_name,
                        file.file_path,
                        file.file_size,
                        file.file_hash
                    )
                    .execute(&pool)
                    .await;

                    if let Err(e) = file_res {
                        error!("Failed to insert file record: {}", e);
                        db_error = true;
                        break;
                    }
                }

                if db_error {
                    let _ = tx.send(WsMessage::Error {
                        message: "Failed to save file transfer metadata".to_string(),
                    });
                } else {
                    let _ = tx.send(WsMessage::TransferInitiated { session_id });
                    // Relay transfer details to the receiver so they are notified of the incoming files
                    signaling
                        .send_to_device(
                            &receiver_device_id,
                            WsMessage::IncomingTransfer {
                                session_id,
                                sender_device_id: sender_id.clone(),
                                files: files.clone(),
                            },
                        )
                        .await;
                }
            }

            WsMessage::UpdateProgress {
                file_id,
                bytes_transferred,
                status,
            } => {
                let progress_res = sqlx::query!(
                    "UPDATE transfer_files 
                     SET bytes_transferred = $1, status = $2, updated_at = NOW() 
                     WHERE file_id = $3",
                    bytes_transferred,
                    status as FileStatus,
                    file_id
                )
                .execute(&pool)
                .await;

                match progress_res {
                    Ok(_) => {
                        let _ = tx.send(WsMessage::ProgressUpdated {
                            file_id,
                            bytes_transferred,
                        });

                        // Relay progress update to the peer device in real-time
                        let session_query = sqlx::query!(
                            "SELECT sender_device_id, receiver_device_id FROM transfer_sessions s
                             JOIN transfer_files f ON s.session_id = f.session_id
                             WHERE f.file_id = $1 LIMIT 1",
                            file_id
                        )
                        .fetch_one(&pool)
                        .await;

                        if let Ok(session) = session_query {
                            let peer_id = if Some(&session.sender_device_id) == client_device_id.as_ref() {
                                session.receiver_device_id
                            } else {
                                session.sender_device_id
                            };
                            
                            signaling
                                .send_to_device(
                                    &peer_id,
                                    WsMessage::ProgressUpdated {
                                        file_id,
                                        bytes_transferred,
                                    },
                                )
                                .await;
                        }
                    }
                    Err(e) => {
                        error!("Failed to update file progress in DB: {}", e);
                    }
                }
            }

            WsMessage::CreateGroup { group_name } => {
                let sender_id = match &client_device_id {
                    Some(id) => id,
                    None => {
                        let _ = tx.send(WsMessage::Error {
                            message: "Unregistered device".to_string(),
                        });
                        continue;
                    }
                };

                let group_id = uuid::Uuid::new_v4().to_string();
                let group_code: String = rand::thread_rng()
                    .sample_iter(&Alphanumeric)
                    .take(6)
                    .map(char::from)
                    .collect::<String>()
                    .to_uppercase();

                let res = sqlx::query!(
                    "INSERT INTO groups (group_id, group_code, group_name, created_by) VALUES ($1, $2, $3, $4)",
                    group_id,
                    group_code,
                    group_name,
                    sender_id
                )
                .execute(&pool)
                .await;

                if let Err(e) = res {
                    error!("Failed to create group: {}", e);
                    let _ = tx.send(WsMessage::Error {
                        message: "Failed to create group".to_string(),
                    });
                    continue;
                }

                // Add creator as first member
                let _ = sqlx::query!(
                    "INSERT INTO group_members (group_id, device_id) VALUES ($1, $2) ON CONFLICT DO NOTHING",
                    group_id,
                    sender_id
                )
                .execute(&pool)
                .await;

                let group_info = GroupInfo {
                    group_id: group_id.clone(),
                    group_code: group_code.clone(),
                    group_name: group_name.clone(),
                    created_by: sender_id.clone(),
                    members: vec![GroupMemberInfo {
                        device_id: sender_id.clone(),
                        is_online: true,
                    }],
                };

                let _ = tx.send(WsMessage::GroupCreated {
                    group: group_info,
                });
            }

            WsMessage::JoinGroup { group_code } => {
                let sender_id = match &client_device_id {
                    Some(id) => id,
                    None => {
                        let _ = tx.send(WsMessage::Error {
                            message: "Unregistered device".to_string(),
                        });
                        continue;
                    }
                };

                let code = group_code.trim().to_uppercase();
                let group_rec = sqlx::query!(
                    "SELECT group_id, group_code, group_name, created_by FROM groups WHERE group_code = $1",
                    code
                )
                .fetch_optional(&pool)
                .await;

                match group_rec {
                    Ok(Some(grp)) => {
                        let _ = sqlx::query!(
                            "INSERT INTO group_members (group_id, device_id) VALUES ($1, $2) ON CONFLICT DO NOTHING",
                            grp.group_id,
                            sender_id
                        )
                        .execute(&pool)
                        .await;

                        // Fetch all members
                        let members_rec = sqlx::query!(
                            "SELECT device_id FROM group_members WHERE group_id = $1",
                            grp.group_id
                        )
                        .fetch_all(&pool)
                        .await
                        .unwrap_or_default();

                        let mut member_infos = Vec::new();
                        for m in members_rec {
                            let is_online = signaling.is_device_connected(&m.device_id).await;
                            member_infos.push(GroupMemberInfo {
                                device_id: m.device_id,
                                is_online,
                            });
                        }

                        let group_info = GroupInfo {
                            group_id: grp.group_id.clone(),
                            group_code: grp.group_code.clone(),
                            group_name: grp.group_name.clone(),
                            created_by: grp.created_by.clone(),
                            members: member_infos,
                        };

                        let _ = tx.send(WsMessage::GroupJoined {
                            group: group_info,
                        });

                        // Broadcast to existing online members that this member joined
                        let all_members = sqlx::query!(
                            "SELECT device_id FROM group_members WHERE group_id = $1 AND device_id != $2",
                            grp.group_id,
                            sender_id
                        )
                        .fetch_all(&pool)
                        .await
                        .unwrap_or_default();

                        for m in all_members {
                            signaling
                                .send_to_device(
                                    &m.device_id,
                                    WsMessage::GroupMemberJoined {
                                        group_id: grp.group_id.clone(),
                                        member: GroupMemberInfo {
                                            device_id: sender_id.clone(),
                                            is_online: true,
                                        },
                                    },
                                )
                                .await;
                        }
                    }
                    Ok(None) => {
                        let _ = tx.send(WsMessage::Error {
                            message: "Group code not found".to_string(),
                        });
                    }
                    Err(e) => {
                        error!("Database error querying group: {}", e);
                        let _ = tx.send(WsMessage::Error {
                            message: "Failed to join group".to_string(),
                        });
                    }
                }
            }

            WsMessage::LeaveGroup { group_id } => {
                let sender_id = match &client_device_id {
                    Some(id) => id,
                    None => continue,
                };

                let _ = sqlx::query!(
                    "DELETE FROM group_members WHERE group_id = $1 AND device_id = $2",
                    group_id,
                    sender_id
                )
                .execute(&pool)
                .await;

                let _ = tx.send(WsMessage::GroupLeft {
                    group_id: group_id.clone(),
                });

                // Notify other members
                let remaining = sqlx::query!(
                    "SELECT device_id FROM group_members WHERE group_id = $1",
                    group_id
                )
                .fetch_all(&pool)
                .await
                .unwrap_or_default();

                for m in remaining {
                    signaling
                        .send_to_device(
                            &m.device_id,
                            WsMessage::GroupMemberLeft {
                                group_id: group_id.clone(),
                                device_id: sender_id.clone(),
                            },
                        )
                        .await;
                }
            }

            WsMessage::GetMyGroups => {
                let sender_id = match &client_device_id {
                    Some(id) => id,
                    None => continue,
                };

                let groups_rec = sqlx::query!(
                    "SELECT g.group_id, g.group_code, g.group_name, g.created_by
                     FROM groups g
                     JOIN group_members gm ON g.group_id = gm.group_id
                     WHERE gm.device_id = $1",
                    sender_id
                )
                .fetch_all(&pool)
                .await
                .unwrap_or_default();

                let mut group_list = Vec::new();
                for g in groups_rec {
                    let members_rec = sqlx::query!(
                        "SELECT device_id FROM group_members WHERE group_id = $1",
                        g.group_id
                    )
                    .fetch_all(&pool)
                    .await
                    .unwrap_or_default();

                    let mut member_infos = Vec::new();
                    for m in members_rec {
                        let is_online = signaling.is_device_connected(&m.device_id).await;
                        member_infos.push(GroupMemberInfo {
                            device_id: m.device_id,
                            is_online,
                        });
                    }

                    group_list.push(GroupInfo {
                        group_id: g.group_id,
                        group_code: g.group_code,
                        group_name: g.group_name,
                        created_by: g.created_by,
                        members: member_infos,
                    });
                }

                let _ = tx.send(WsMessage::GroupList {
                    groups: group_list,
                });
            }

            WsMessage::SendGroupChat {
                message_id,
                group_id,
                content,
            } => {
                let sender_id = match &client_device_id {
                    Some(id) => id,
                    None => continue,
                };

                let members = sqlx::query!(
                    "SELECT device_id FROM group_members WHERE group_id = $1 AND device_id != $2",
                    group_id,
                    sender_id
                )
                .fetch_all(&pool)
                .await
                .unwrap_or_default();

                let now = chrono::Utc::now();
                for m in members {
                    signaling
                        .send_to_device(
                            &m.device_id,
                            WsMessage::GroupChatReceived {
                                message_id,
                                group_id: group_id.clone(),
                                sender_device_id: sender_id.clone(),
                                content: content.clone(),
                                created_at: now,
                            },
                        )
                        .await;
                }
            }

            WsMessage::InitiateGroupTransfer {
                group_id,
                session_id,
                files,
            } => {
                let sender_id = match &client_device_id {
                    Some(id) => id,
                    None => continue,
                };

                let members = sqlx::query!(
                    "SELECT device_id FROM group_members WHERE group_id = $1 AND device_id != $2",
                    group_id,
                    sender_id
                )
                .fetch_all(&pool)
                .await
                .unwrap_or_default();

                let mut target_recipients = Vec::new();
                for m in members {
                    if signaling.is_device_connected(&m.device_id).await {
                        target_recipients.push(m.device_id);
                    }
                }

                if target_recipients.is_empty() {
                    let _ = tx.send(WsMessage::Error {
                        message: "No other group members are currently online".to_string(),
                    });
                    continue;
                }

                let _ = tx.send(WsMessage::TransferInitiated { session_id });

                for receiver_id in target_recipients {
                    // Create session record for each recipient
                    let target_session_id = uuid::Uuid::new_v4();
                    let _ = sqlx::query!(
                        "INSERT INTO transfer_sessions (session_id, sender_device_id, receiver_device_id, status)
                         VALUES ($1, $2, $3, 'active') ON CONFLICT DO NOTHING",
                        target_session_id,
                        sender_id,
                        receiver_id
                    )
                    .execute(&pool)
                    .await;

                    for file in &files {
                        let _ = sqlx::query!(
                            "INSERT INTO transfer_files (file_id, session_id, file_name, file_path, file_size, file_hash, bytes_transferred, status)
                             VALUES ($1, $2, $3, $4, $5, $6, 0, 'pending') ON CONFLICT DO NOTHING",
                            file.file_id,
                            target_session_id,
                            file.file_name,
                            file.file_path,
                            file.file_size,
                            file.file_hash
                        )
                        .execute(&pool)
                        .await;
                    }

                    signaling
                        .send_to_device(
                            &receiver_id,
                            WsMessage::IncomingTransfer {
                                session_id: target_session_id,
                                sender_device_id: sender_id.clone(),
                                files: files.clone(),
                            },
                        )
                        .await;
                }
            }

            _ => {
                warn!("Received unhandled or server-side only message over WS: {:?}", ws_msg);
            }
        }
    }

    // Connection teardown
    if let (Some(id), Some(code)) = (client_device_id, client_device_code) {
        signaling.remove_device(&id, &code).await;

        // Mark active sessions involving this device as failed/paused
        let update_sessions = sqlx::query!(
            "UPDATE transfer_sessions 
             SET status = 'paused', updated_at = NOW() 
             WHERE (sender_device_id = $1 OR receiver_device_id = $1) AND status = 'active'",
            id
        )
        .execute(&pool)
        .await;

        if let Err(e) = update_sessions {
            error!("Failed to clean up active transfer sessions for disconnected device: {}", e);
        }
    }

    write_task.abort();
}
