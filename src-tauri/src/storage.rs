use crate::providers::ProviderConfig;
use rusqlite::{params, Connection, Result};
use std::path::PathBuf;
use std::sync::Mutex;

pub struct Database {
    conn: Mutex<Connection>,
}

impl Database {
    pub fn new() -> Result<Self> {
        let db_path = Self::get_db_path()?;
        
        // 确保目录存在
        if let Some(parent) = db_path.parent() {
            std::fs::create_dir_all(parent).ok();
        }
        
        let conn = Connection::open(db_path)?;
        
        // 创建表
        conn.execute(
            "CREATE TABLE IF NOT EXISTS providers (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                provider TEXT NOT NULL,
                base_url TEXT NOT NULL,
                api_key TEXT NOT NULL,
                query_endpoint TEXT NOT NULL,
                query_method TEXT NOT NULL,
                query_headers TEXT NOT NULL,
                query_params TEXT NOT NULL,
                refresh_interval INTEGER NOT NULL,
                is_enabled INTEGER NOT NULL,
                status TEXT NOT NULL
            )",
            [],
        )?;
        
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }
    
    fn get_db_path() -> Result<PathBuf> {
        let data_dir = dirs::data_dir()
            .ok_or_else(|| rusqlite::Error::SqliteFailure(
                rusqlite::ffi::Error::new(1),
                Some("Failed to get data directory".to_string())
            ))?
            .join("ai-model-monitor");
        
        Ok(data_dir.join("data.db"))
    }
    
    pub fn load_providers(&self) -> Result<Vec<ProviderConfig>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, name, provider, base_url, api_key, query_endpoint, 
                    query_method, query_headers, query_params, refresh_interval, 
                    is_enabled, status
             FROM providers"
        )?;
        
        let providers = stmt.query_map([], |row| {
            let headers_json: String = row.get(7)?;
            let params_json: String = row.get(8)?;
            let is_enabled_int: i32 = row.get(10)?;
            
            Ok(ProviderConfig {
                id: row.get(0)?,
                name: row.get(1)?,
                provider: row.get(2)?,
                base_url: row.get(3)?,
                api_key: row.get(4)?,
                query_endpoint: row.get(5)?,
                query_method: row.get(6)?,
                query_headers: serde_json::from_str(&headers_json).unwrap_or_default(),
                query_params: serde_json::from_str(&params_json).unwrap_or(serde_json::Value::Null),
                refresh_interval: row.get(9)?,
                is_enabled: is_enabled_int != 0,
                status: row.get(11)?,
            })
        })?;
        
        providers.collect()
    }
    
    pub fn save_provider(&self, provider: &ProviderConfig) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let headers_json = serde_json::to_string(&provider.query_headers)
            .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?;
        let params_json = serde_json::to_string(&provider.query_params)
            .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?;
        let is_enabled_int = if provider.is_enabled { 1 } else { 0 };
        
        conn.execute(
            "INSERT OR REPLACE INTO providers 
             (id, name, provider, base_url, api_key, query_endpoint, 
              query_method, query_headers, query_params, refresh_interval, 
              is_enabled, status)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)",
            params![
                provider.id,
                provider.name,
                provider.provider,
                provider.base_url,
                provider.api_key,
                provider.query_endpoint,
                provider.query_method,
                headers_json,
                params_json,
                provider.refresh_interval,
                is_enabled_int,
                provider.status,
            ],
        )?;
        
        Ok(())
    }
    
    pub fn delete_provider(&self, id: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute("DELETE FROM providers WHERE id = ?1", params![id])?;
        Ok(())
    }
}
