use crate::logging::{LogEntry, LogQuery};
use crate::providers::{ProviderConfig, UsageStatus};
use rusqlite::types::Value as SqlValue;
use rusqlite::{params, params_from_iter, Connection, Result};
use std::path::PathBuf;
use std::sync::Mutex;

pub struct Database {
    conn: Mutex<Connection>,
}

impl Database {
    pub fn new(data_dir: PathBuf) -> Result<Self> {
        let db_path = Self::get_db_path(&data_dir)?;

        // 确保目录存在
        if let Some(parent) = db_path.parent() {
            std::fs::create_dir_all(parent).ok();
        }

        let conn = Connection::open(db_path)?;

        // 创建 Provider 配置表
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
                is_enabled INTEGER NOT NULL DEFAULT 1,
                status TEXT NOT NULL
            )",
            [],
        )?;

        // 创建使用历史表
        conn.execute(
            "CREATE TABLE IF NOT EXISTS usage_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                provider_id TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                balance REAL,
                balance_used REAL,
                balance_limit REAL,
                requests_today INTEGER,
                error_rate REAL,
                avg_latency INTEGER,
                last_error TEXT,
                FOREIGN KEY (provider_id) REFERENCES providers(id) ON DELETE CASCADE
            )",
            [],
        )?;

        // 升级旧库：补 quota_* 列（仅在缺列时 ADD COLUMN，幂等）
        let existing_columns: std::collections::HashSet<String> = conn
            .prepare("PRAGMA table_info(usage_history)")?
            .query_map([], |row| row.get::<_, String>(1))?
            .filter_map(Result::ok)
            .collect();
        let quota_columns = [
            ("quota_5h_remaining", "REAL"),
            ("quota_5h_remaining_percent", "REAL"),
            ("quota_5h_total", "REAL"),
            ("quota_5h_used", "REAL"),
            ("quota_week_remaining", "REAL"),
            ("quota_week_remaining_percent", "REAL"),
            ("quota_week_total", "REAL"),
            ("quota_week_used", "REAL"),
            ("quota_month_remaining", "REAL"),
            ("quota_month_total", "REAL"),
            ("quota_month_used", "REAL"),
        ];
        for (name, ty) in quota_columns {
            if !existing_columns.contains(name) {
                conn.execute(
                    &format!("ALTER TABLE usage_history ADD COLUMN {} {}", name, ty),
                    [],
                )?;
            }
        }

        // 创建索引加速查询
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_usage_history_provider_time
             ON usage_history(provider_id, timestamp DESC)",
            [],
        )?;

        // 设置/偏好（KV）
        conn.execute(
            "CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )",
            [],
        )?;

        // 应用日志表（Key 不入库；categories 与 levels 由 enum 字符串约束）
        conn.execute(
            "CREATE TABLE IF NOT EXISTS app_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                level TEXT NOT NULL,
                category TEXT NOT NULL,
                source TEXT,
                message TEXT NOT NULL,
                details TEXT
            )",
            [],
        )?;
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_app_logs_time
             ON app_logs(timestamp DESC)",
            [],
        )?;
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_app_logs_level_cat
             ON app_logs(level, category)",
            [],
        )?;

        // 一次性 migrate：把历史 is_enabled=0 的 Provider 设为 1。
        // 背景：早期 schema 没设 DEFAULT 1，且 enable_preset 之外的路径
        // 可能让 is_enabled=0。重启后 poller 才能拉到。
        // 该操作幂等：is_enabled=1 的记录保持原状，0 的记录更新为 1。
        let updated = conn.execute(
            "UPDATE providers SET is_enabled = 1 WHERE is_enabled = 0",
            [],
        )?;
        if updated > 0 {
            eprintln!(
                "[storage migrate] re-enabled {} provider(s) with is_enabled=0",
                updated
            );
        }

        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    fn get_db_path(data_dir: &PathBuf) -> Result<PathBuf> {
        let dir = data_dir.join("ai-model-monitor");
        Ok(dir.join("data.db"))
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
        conn.execute("DELETE FROM usage_history WHERE provider_id = ?1", params![id])?;
        conn.execute("DELETE FROM providers WHERE id = ?1", params![id])?;
        Ok(())
    }
    
    // ===== 使用历史相关方法 =====
    
    pub fn save_usage_history(&self, status: &UsageStatus) -> Result<()> {
        let mut status = status.clone();
        if status.balance.is_none() {
            if let (Some(limit), Some(used)) = (status.balance_limit, status.balance_used) {
                status.balance = Some((limit - used).max(0.0));
            }
        }
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT INTO usage_history
             (provider_id, timestamp, balance, balance_used, balance_limit,
              requests_today, error_rate, avg_latency, last_error,
              quota_5h_remaining, quota_5h_remaining_percent, quota_5h_total, quota_5h_used,
              quota_week_remaining, quota_week_remaining_percent, quota_week_total, quota_week_used,
              quota_month_remaining, quota_month_total, quota_month_used)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9,
                     ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20)",
            params![
                status.provider_id,
                status.timestamp,
                status.balance,
                status.balance_used,
                status.balance_limit,
                status.requests_today,
                status.error_rate,
                status.avg_latency,
                status.last_error,
                status.quota_5h_remaining,
                status.quota_5h_remaining_percent,
                status.quota_5h_total,
                status.quota_5h_used,
                status.quota_week_remaining,
                status.quota_week_remaining_percent,
                status.quota_week_total,
                status.quota_week_used,
                status.quota_month_remaining,
                status.quota_month_total,
                status.quota_month_used,
            ],
        )?;
        Ok(())
    }
    
    pub fn get_usage_history(
        &self,
        provider_id: &str,
        limit: i64,
        since: Option<i64>,
    ) -> Result<Vec<UsageStatus>> {
        let conn = self.conn.lock().unwrap();
        let since_ts = since.unwrap_or(0);
        
        let mut stmt = conn.prepare(
            "SELECT provider_id, timestamp, balance, balance_used, balance_limit,
                    requests_today, error_rate, avg_latency, last_error,
                    quota_5h_remaining, quota_5h_remaining_percent, quota_5h_total, quota_5h_used,
                    quota_week_remaining, quota_week_remaining_percent, quota_week_total, quota_week_used,
                    quota_month_remaining, quota_month_total, quota_month_used
             FROM usage_history
             WHERE provider_id = ?1 AND timestamp >= ?2
             ORDER BY timestamp DESC
             LIMIT ?3"
        )?;

        let rows = stmt.query_map(params![provider_id, since_ts, limit], |row| {
            Ok(UsageStatus {
                provider_id: row.get(0)?,
                timestamp: row.get(1)?,
                balance: row.get(2)?,
                balance_used: row.get(3)?,
                balance_limit: row.get(4)?,
                requests_today: row.get(5)?,
                error_rate: row.get(6)?,
                avg_latency: row.get(7)?,
                last_error: row.get(8)?,
                quota_5h_remaining: row.get(9)?,
                quota_5h_remaining_percent: row.get(10)?,
                quota_5h_total: row.get(11)?,
                quota_5h_used: row.get(12)?,
                quota_week_remaining: row.get(13)?,
                quota_week_remaining_percent: row.get(14)?,
                quota_week_total: row.get(15)?,
                quota_week_used: row.get(16)?,
                quota_month_remaining: row.get(17)?,
                quota_month_total: row.get(18)?,
                quota_month_used: row.get(19)?,
                ..Default::default()
            })
        })?;
        
        rows.collect()
    }
    
    pub fn cleanup_old_history(&self) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let cutoff = chrono::Utc::now().timestamp() - 7 * 24 * 3600;
        conn.execute(
            "DELETE FROM usage_history WHERE timestamp < ?1",
            params![cutoff],
        )?;
        Ok(())
    }

    // ===== 设置（KV）=====

    pub fn get_setting(&self, key: &str) -> Result<Option<String>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare("SELECT value FROM settings WHERE key = ?1")?;
        let mut rows = stmt.query(params![key])?;
        if let Some(row) = rows.next()? {
            Ok(Some(row.get(0)?))
        } else {
            Ok(None)
        }
    }

    pub fn set_setting(&self, key: &str, value: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT OR REPLACE INTO settings (key, value) VALUES (?1, ?2)",
            params![key, value],
        )?;
        Ok(())
    }

    pub fn delete_setting(&self, key: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute("DELETE FROM settings WHERE key = ?1", params![key])?;
        Ok(())
    }

    // ===== 应用日志 (app_logs) =====

    pub fn save_log(&self, entry: &LogEntry) -> Result<i64> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT INTO app_logs (timestamp, level, category, source, message, details)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                entry.timestamp,
                entry.level.to_string(),
                entry.category.to_string(),
                entry.source,
                entry.message,
                entry.details,
            ],
        )?;
        Ok(conn.last_insert_rowid())
    }

    /// 动态拼 WHERE 子句；所有值都用 `?` 绑定（防注入）。
    /// 关键字 `'` 转义为 `''` 后拼进 LIKE pattern。
    pub fn query_logs(&self, q: &LogQuery) -> Result<Vec<LogEntry>> {
        let conn = self.conn.lock().unwrap();
        let mut sql = String::from(
            "SELECT id, timestamp, level, category, source, message, details
             FROM app_logs WHERE 1=1",
        );
        let mut bind: Vec<SqlValue> = Vec::new();

        if let Some(keyword) = &q.keyword {
            if !keyword.is_empty() {
                let escaped = keyword.replace('\'', "''");
                let pat = format!("%{}%", escaped);
                sql.push_str(" AND (message LIKE ?1 OR details LIKE ?1)");
                bind.push(SqlValue::Text(pat));
            }
        }

        if let Some(levels) = &q.levels {
            if !levels.is_empty() {
                let placeholders: Vec<String> = (0..levels.len())
                    .map(|i| format!("?{}", bind.len() + i + 1))
                    .collect();
                sql.push_str(&format!(" AND level IN ({})", placeholders.join(",")));
                for lv in levels {
                    bind.push(SqlValue::Text(lv.to_string()));
                }
            }
        }

        if let Some(cats) = &q.categories {
            if !cats.is_empty() {
                let placeholders: Vec<String> = (0..cats.len())
                    .map(|i| format!("?{}", bind.len() + i + 1))
                    .collect();
                sql.push_str(&format!(" AND category IN ({})", placeholders.join(",")));
                for cat in cats {
                    bind.push(SqlValue::Text(cat.to_string()));
                }
            }
        }

        if let Some(since) = q.since {
            sql.push_str(&format!(" AND timestamp >= ?{}", bind.len() + 1));
            bind.push(SqlValue::Integer(since));
        }
        if let Some(until) = q.until {
            sql.push_str(&format!(" AND timestamp <= ?{}", bind.len() + 1));
            bind.push(SqlValue::Integer(until));
        }

        sql.push_str(" ORDER BY timestamp DESC");
        let limit = q.limit.unwrap_or(200).clamp(1, 1000);
        sql.push_str(&format!(" LIMIT ?{}", bind.len() + 1));
        bind.push(SqlValue::Integer(limit));

        let mut stmt = conn.prepare(&sql)?;
        let rows = stmt.query_map(params_from_iter(bind.iter()), |row| {
            let level_str: String = row.get(2)?;
            let cat_str: String = row.get(3)?;
            Ok(LogEntry {
                id: row.get(0)?,
                timestamp: row.get(1)?,
                level: parse_log_level(&level_str, 2)?,
                category: parse_log_category(&cat_str, 3)?,
                source: row.get(4)?,
                message: row.get(5)?,
                details: row.get(6)?,
            })
        })?;
        rows.collect()
    }

    pub fn clear_logs(&self) -> Result<usize> {
        let conn = self.conn.lock().unwrap();
        let n = conn.execute("DELETE FROM app_logs", [])?;
        Ok(n)
    }

    pub fn cleanup_old_logs(&self, retain_days: i64) -> Result<usize> {
        let conn = self.conn.lock().unwrap();
        let cutoff = chrono::Utc::now().timestamp() - retain_days * 24 * 3600;
        let n = conn.execute(
            "DELETE FROM app_logs WHERE timestamp < ?1",
            params![cutoff],
        )?;
        Ok(n)
    }
}

/// 从字符串解析 LogLevel；解析失败转为 rusqlite::Error (InvalidColumnType)
fn parse_log_level(s: &str, col_idx: usize) -> Result<crate::logging::LogLevel> {
    use std::str::FromStr;
    crate::logging::LogLevel::from_str(s).map_err(|e| {
        rusqlite::Error::InvalidColumnType(col_idx, format!("LogLevel({})", e), rusqlite::types::Type::Text)
    })
}

fn parse_log_category(s: &str, col_idx: usize) -> Result<crate::logging::LogCategory> {
    use std::str::FromStr;
    crate::logging::LogCategory::from_str(s).map_err(|e| {
        rusqlite::Error::InvalidColumnType(col_idx, format!("LogCategory({})", e), rusqlite::types::Type::Text)
    })
}
