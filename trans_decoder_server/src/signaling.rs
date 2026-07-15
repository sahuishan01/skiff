use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{mpsc, RwLock};
use tracing::{info, warn};
use crate::models::WsMessage;

#[derive(Default, Clone)]
pub struct SignalingState {
    // Maps device_id -> Tx channel of the WebSocket task
    pub active_connections: Arc<RwLock<HashMap<String, mpsc::UnboundedSender<WsMessage>>>>,
    // Maps device_code -> device_id
    pub code_mappings: Arc<RwLock<HashMap<String, String>>>,
}

impl SignalingState {
    pub async fn register_device(
        &self,
        device_id: String,
        device_code: String,
        tx: mpsc::UnboundedSender<WsMessage>,
    ) {
        info!("Registering connection for device_id: {}, code: {}", device_id, device_code);
        self.active_connections.write().await.insert(device_id.clone(), tx);
        self.code_mappings.write().await.insert(device_code, device_id);
    }

    pub async fn remove_device(&self, device_id: &str, device_code: &str) {
        info!("Removing connection for device_id: {}", device_id);
        self.active_connections.write().await.remove(device_id);
        self.code_mappings.write().await.remove(device_code);
    }

    pub async fn get_device_id_by_code(&self, code: &str) -> Option<String> {
        self.code_mappings.read().await.get(code).cloned()
    }

    pub async fn send_to_device(&self, device_id: &str, msg: WsMessage) -> bool {
        if let Some(tx) = self.active_connections.read().await.get(device_id) {
            if let Err(e) = tx.send(msg) {
                warn!("Failed to send WebSocket message to device {}: {}", device_id, e);
                false
            } else {
                true
            }
        } else {
            warn!("Device connection not found for ID: {}", device_id);
            false
        }
    }
}
