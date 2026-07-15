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
    models::{FileStatus, WsMessage},
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
                            .register_device(device_id, final_code.clone(), tx.clone())
                            .await;

                        let _ = tx.send(WsMessage::Registered {
                            device_code: final_code,
                        });
                    }
                    Err(e) => {
                        error!("Failed to register device in database: {}", e);
                        let _ = tx.send(WsMessage::Error {
                            message: "Database registration failure".to_string(),
                        });
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
