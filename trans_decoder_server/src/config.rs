use std::env;

#[derive(Clone)]
pub struct Config {
    pub database_url: String,
    pub server_port: u16,
}

impl Config {
    pub fn from_env() -> Self {
        dotenvy::dotenv().ok();
        
        let database_url = env::var("DATABASE_URL")
            .unwrap_or_else(|_| "postgres://postgres:postgres@127.0.0.1:5434/trans_decoder".to_string());
            
        let server_port = env::var("PORT")
            .unwrap_or_else(|_| "0".to_string())
            .parse::<u16>()
            .unwrap_or(0);

        Self {
            database_url,
            server_port,
        }
    }
}
