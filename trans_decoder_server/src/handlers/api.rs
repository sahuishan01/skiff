use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use sqlx::PgPool;
use uuid::Uuid;
use serde_json::json;
use tracing::error;

use crate::models::SessionStatus;
use crate::signaling::SignalingState;

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
