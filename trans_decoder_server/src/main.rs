mod config;
mod db;
mod handlers;
mod models;
mod signaling;

use std::net::SocketAddr;
use axum::{
    routing::get,
    Router,
};
use config::Config;
use signaling::SignalingState;
use tower_http::cors::{Any, CorsLayer};
use tower_http::trace::TraceLayer;
use tracing::info;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

#[tokio::main]
async fn main() {
    // Initialize logging
    tracing_subscriber::registry()
        .with(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .with(tracing_subscriber::fmt::layer())
        .init();

    info!("Starting Skiff signaling server...");

    let config = Config::from_env();

    // Initialize database
    let pool = match db::init_db(&config.database_url).await {
        Ok(p) => p,
        Err(e) => {
            eprintln!("Failed to initialize database: {}", e);
            std::process::exit(1);
        }
    };

    // Initialize signaling state
    let signaling_state = SignalingState::default();
    let app_state = (pool, signaling_state);

    // Setup CORS
    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    // Build router
    let app = Router::new()
        .route("/health", get(handlers::api::health_check))
        .route("/ws", get(handlers::ws::ws_handler))
        .route("/api/transfers/:session_id", get(handlers::api::get_transfer_status))
        .layer(cors)
        .layer(TraceLayer::new_for_http())
        .with_state(app_state);

    // Bind address and run server
    let addr = SocketAddr::from(([0, 0, 0, 0], config.server_port));
    let listener = tokio::net::TcpListener::bind(&addr).await.unwrap();
    let bound_addr = listener.local_addr().unwrap();
    info!("Server listening on http://{}", bound_addr);

    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await
    .unwrap();
}
