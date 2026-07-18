use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::IntoResponse,
    Json,
    body::Body,
};
use sqlx::PgPool;
use uuid::Uuid;
use serde_json::json;
use tracing::{error, info};
use tokio::sync::mpsc;
use std::sync::Arc;
use futures_util::StreamExt;
use futures_util::Stream;
use std::pin::Pin;
use std::task::{Context, Poll};
use tokio::time::{sleep, Duration};

use crate::models::SessionStatus;
use crate::signaling::{SignalingState, RelaySession};

pub struct RelayStream {
    pub rx: mpsc::Receiver<Result<axum::body::Bytes, axum::Error>>,
}

impl Stream for RelayStream {
    type Item = Result<axum::body::Bytes, axum::Error>;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        self.get_mut().rx.poll_recv(cx)
    }
}

pub async fn health_check() -> impl IntoResponse {
    (StatusCode::OK, "OK")
}

pub async fn get_transfer_status(
    Path(session_id): Path<Uuid>,
    State((pool, _)): State<(PgPool, SignalingState)>,
) -> impl IntoResponse {
    // Fetch session details
    let session_status_res = sqlx::query!(
        "SELECT status as \"status: SessionStatus\" FROM transfer_sessions WHERE session_id = $1",
        session_id
    )
    .fetch_optional(&pool)
    .await;

    match session_status_res {
        Ok(Some(session)) => {
            // Fetch all files associated with the session
            let files_res = sqlx::query_as!(
                crate::models::TransferFile,
                "SELECT file_id, session_id, file_name, file_path, file_size, file_hash, bytes_transferred, status as \"status: _\", updated_at FROM transfer_files WHERE session_id = $1",
                session_id
            )
            .fetch_all(&pool)
            .await;

            match files_res {
                Ok(files) => {
                    (
                        StatusCode::OK,
                        Json(json!({
                            "session_id": session_id,
                            "status": session.status,
                            "files": files
                        })),
                    )
                }
                Err(e) => {
                    error!("Database error fetching transfer files: {}", e);
                    (
                        StatusCode::INTERNAL_SERVER_ERROR,
                        Json(json!({ "error": "Internal database error" })),
                    )
                }
            }
        }
        Ok(None) => (
            StatusCode::NOT_FOUND,
            Json(json!({ "error": "Transfer session not found" })),
        ),
        Err(e) => {
            error!("Database error fetching session: {}", e);
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": "Internal database error" })),
            )
        }
    }
}

pub async fn relay_upload(
    Path(file_id): Path<String>,
    State((_, signaling)): State<(PgPool, SignalingState)>,
    body: Body,
) -> impl IntoResponse {
    info!("Relay Upload: Initiated for file {}", file_id);
    let tx = {
        let mut sessions = signaling.relay_sessions.write().await;
        if let Some(session) = sessions.get(&file_id) {
            session.tx.clone()
        } else {
            let (tx, rx) = mpsc::channel(16);
            let session = RelaySession {
                tx: Some(tx.clone()),
                rx: Arc::new(tokio::sync::Mutex::new(Some(rx))),
            };
            sessions.insert(file_id.clone(), session);
            Some(tx)
        }
    };

    let mut stream = body.into_data_stream();
    if let Some(tx) = tx {
        while let Some(chunk_res) = stream.next().await {
            match chunk_res {
                Ok(bytes) => {
                    if tx.send(Ok(bytes)).await.is_err() {
                        error!("Relay Upload: Receiver disconnected for file {}", file_id);
                        break;
                    }
                }
                Err(e) => {
                    error!("Relay Upload: Error reading body for file {}: {}", file_id, e);
                    let _ = tx.send(Err(e)).await;
                    break;
                }
            }
        }
    } else {
        error!("Relay Upload: No sender available for file {}", file_id);
    }

    info!("Relay Upload: Completed/terminated for file {}", file_id);
    let sessions_weak = signaling.relay_sessions.clone();
    // Drop the stored sender so the mpsc channel closes naturally.
    {
        let mut sessions = signaling.relay_sessions.write().await;
        if let Some(session) = sessions.get_mut(&file_id) {
            session.tx = None;
        }
    }
    // Clean up the session after 60s so orphaned sessions don't leak
    tokio::spawn(async move {
        sleep(Duration::from_secs(60)).await;
        let mut sessions = sessions_weak.write().await;
        sessions.remove(&file_id);
        info!("Relay Upload: Cleaned up stale session for file {}", file_id);
    });

    StatusCode::OK
}

pub async fn relay_download(
    Path(file_id): Path<String>,
    State((_, signaling)): State<(PgPool, SignalingState)>,
) -> impl IntoResponse {
    info!("Relay Download: Initiated for file {}", file_id);
    let rx_opt = {
        let mut sessions = signaling.relay_sessions.write().await;
        if let Some(session) = sessions.get(&file_id) {
            let mut rx_lock = session.rx.lock().await;
            rx_lock.take()
        } else {
            let (tx, rx) = mpsc::channel(16);
            let session = RelaySession {
                tx: Some(tx),
                rx: Arc::new(tokio::sync::Mutex::new(None)),
            };
            sessions.insert(file_id.clone(), session);
            Some(rx)
        }
    };

    match rx_opt {
        Some(rx) => {
            let stream = RelayStream { rx };
            info!("Relay Download: Starting stream response for file {}", file_id);
            axum::response::Response::builder()
                .header("content-type", "application/octet-stream")
                .body(Body::from_stream(stream))
                .unwrap()
        }
        None => {
            error!("Relay Download: Conflict/Already occupied for file {}", file_id);
            axum::response::Response::builder()
                .status(StatusCode::CONFLICT)
                .body(Body::from("Download channel already occupied or invalid"))
                .unwrap()
        }
    }
}
