// 应用日志系统
//
// - 内存环形缓冲（最近 BUFFER_CAPACITY 条）+ SQLite 持久化（默认 7 天）
// - 写入路径异步化：调用方 AppLogger::log 只做 tx.send()，不阻塞业务
// - 后台消费 task：push buffer → save_log → emit("app-log")
// - 关键脱敏：在调用方 / 持久化前就 redact，绝不依赖前端掩码
//
// 配合事件 (`APP_LOG_EVENT`) 由前端 LogViewer 通过 listen 实时订阅。

use rusqlite::Result as SqlResult;
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter};

use crate::storage::Database;

/// 内存环形缓冲容量
pub const BUFFER_CAPACITY: usize = 200;
/// 实时事件名（前端 listen 名称）
pub const APP_LOG_EVENT: &str = "app-log";
/// 敏感字段白名单（值会被替换为 `"***"`），大小写不敏感匹配
pub const REDACT_KEYS: &[&str] = &[
    "api_key",
    "apiKey",
    "authorization",
    "Authorization",
    "x-api-key",
    "X-Api-Key",
];

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum LogLevel {
    Debug,
    Info,
    Warn,
    Error,
}

impl std::fmt::Display for LogLevel {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            LogLevel::Debug => "debug",
            LogLevel::Info => "info",
            LogLevel::Warn => "warn",
            LogLevel::Error => "error",
        })
    }
}

impl std::str::FromStr for LogLevel {
    type Err = String;
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            "debug" => Ok(LogLevel::Debug),
            "info" => Ok(LogLevel::Info),
            "warn" => Ok(LogLevel::Warn),
            "error" => Ok(LogLevel::Error),
            other => Err(format!("unknown log level: {}", other)),
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum LogCategory {
    Http,
    Provider,
    Poller,
    Database,
    Command,
    Setup,
    Tray,
    System,
}

impl std::fmt::Display for LogCategory {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            LogCategory::Http => "http",
            LogCategory::Provider => "provider",
            LogCategory::Poller => "poller",
            LogCategory::Database => "database",
            LogCategory::Command => "command",
            LogCategory::Setup => "setup",
            LogCategory::Tray => "tray",
            LogCategory::System => "system",
        })
    }
}

impl std::str::FromStr for LogCategory {
    type Err = String;
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            "http" => Ok(LogCategory::Http),
            "provider" => Ok(LogCategory::Provider),
            "poller" => Ok(LogCategory::Poller),
            "database" => Ok(LogCategory::Database),
            "command" => Ok(LogCategory::Command),
            "setup" => Ok(LogCategory::Setup),
            "tray" => Ok(LogCategory::Tray),
            "system" => Ok(LogCategory::System),
            other => Err(format!("unknown log category: {}", other)),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogEntry {
    pub id: i64,
    pub timestamp: i64,
    pub level: LogLevel,
    pub category: LogCategory,
    pub source: Option<String>,
    pub message: String,
    pub details: Option<String>,
}

/// 前端传过来的日志查询条件（支持任意字段为 None）
/// camelCase 让前端可以按 idiomatic 风格 `{ keyword, levels, categories, since, until, limit }` 传参
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LogQuery {
    pub keyword: Option<String>,
    pub levels: Option<Vec<LogLevel>>,
    pub categories: Option<Vec<LogCategory>>,
    pub since: Option<i64>,
    pub until: Option<i64>,
    pub limit: Option<i64>,
}

/// 内存环形缓冲（线程安全）
pub struct LogBuffer {
    inner: Mutex<VecDeque<LogEntry>>,
}

impl LogBuffer {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(VecDeque::with_capacity(BUFFER_CAPACITY)),
        }
    }

    pub fn push(&self, entry: LogEntry) {
        let mut guard = self.inner.lock().unwrap();
        if guard.len() >= BUFFER_CAPACITY {
            guard.pop_front();
        }
        guard.push_back(entry);
    }

    #[allow(dead_code)]
    pub fn snapshot(&self) -> Vec<LogEntry> {
        let guard = self.inner.lock().unwrap();
        guard.iter().cloned().collect()
    }

    pub fn since(&self, since_id: Option<i64>) -> Vec<LogEntry> {
        let guard = self.inner.lock().unwrap();
        guard
            .iter()
            .filter(|e| match since_id {
                Some(id) => e.id > id,
                None => true,
            })
            .cloned()
            .collect()
    }

    pub fn clear(&self) {
        self.inner.lock().unwrap().clear();
    }
}

/// 应用日志器：调用方入口
///
/// 写入走 mpsc channel，生产者零阻塞；后台消费 task 负责 buffer + DB + emit
pub struct AppLogger {
    db: Arc<Database>,
    buffer: Arc<LogBuffer>,
    tx: tokio::sync::mpsc::UnboundedSender<LogEntry>,
}

// AppLogger 的方法都需要 Arc 包裹以共享给后台 task
impl AppLogger {
    pub fn new(db: Arc<Database>, app: AppHandle) -> Arc<Self> {
        let buffer = Arc::new(LogBuffer::new());
        let (tx, rx) = tokio::sync::mpsc::unbounded_channel::<LogEntry>();

        let logger = Arc::new(Self {
            db,
            buffer: buffer.clone(),
            tx,
        });

        // 后台消费 task：buffer push → DB save → emit
        let db_for_task = logger.db.clone();
        let buffer_for_task = logger.buffer.clone();
        tauri::async_runtime::spawn(async move {
            let mut rx = rx;
            while let Some(mut entry) = rx.recv().await {
                // 1) 写 DB（先写获取 id，保证 buffer 与 DB id 对齐）
                match db_for_task.save_log(&entry) {
                    Ok(id) => {
                        entry.id = id;
                    }
                    Err(e) => {
                        // DB 写入失败：降级用时间戳 + 负号作为临时 id，保证 buffer 中条目可追
                        // 仍然 push 进 buffer 与 emit，便于调试
                        entry.id = -(chrono::Utc::now().timestamp_nanos_opt().unwrap_or(0));
                        let _ = e; // 故意忽略，避免在 logging 通道里再打日志（可能递归）
                    }
                }
                // 2) push buffer
                buffer_for_task.push(entry.clone());
                // 3) 实时推送给前端
                let _ = app.emit(APP_LOG_EVENT, &entry);
            }
        });

        logger
    }

    /// 主调用入口：写入一条日志（异步，调用方零阻塞）
    ///
    /// `details` 传入 `serde_json::Value`，**内部不脱敏**——调用方负责 redact 后再传。
    /// 失败场景下我们也兜底走 redact，确保落库字段安全。
    pub fn log(
        &self,
        level: LogLevel,
        category: LogCategory,
        source: Option<String>,
        message: impl Into<String>,
        details: Option<serde_json::Value>,
    ) {
        let message = message.into();

        // 兜底脱敏：调用方应该主动 redact，但万一传了 raw string 也尽量过滤一遍
        let details_str = details.and_then(|v| {
            let raw = v.to_string();
            Some(redact_json_keys_string(&raw))
        });

        let entry = LogEntry {
            id: 0, // 0 占位，由后台 task 写库后回填真实 id
            timestamp: chrono::Utc::now().timestamp(),
            level,
            category,
            source,
            message: mask_bearer_token(&message),
            details: details_str.map(|s| mask_bearer_token(&s)),
        };

        let _ = self.tx.send(entry);
    }

    /// 同步从 DB 查询（供前端 query_logs 命令调用）
    pub fn query(&self, q: LogQuery) -> SqlResult<Vec<LogEntry>> {
        self.db.query_logs(&q)
    }

    /// 从内存 buffer 取 since_id 之后的条目，供前端拉取遗漏事件（兜底）
    #[allow(dead_code)]
    pub fn recent_from_buffer(&self, since_id: Option<i64>) -> Vec<LogEntry> {
        self.buffer.since(since_id)
    }

    /// 清空 DB + buffer
    pub fn clear(&self) -> SqlResult<usize> {
        self.buffer.clear();
        self.db.clear_logs()
    }
}

// ============================================================================
//  脱敏工具（模块私有）
// ============================================================================

/// 替换 `Bearer xxx` 字串为 `Bearer ****`，大小写不敏感
///
/// 仅在 `Bearer` 后面紧跟 `[A-Za-z0-9._-]+` token 时才 redact；
/// 其它情况（如已经是 `Bearer ****` 的占位）保持原文不变。
fn mask_bearer_token(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < bytes.len() {
        if i + 6 <= bytes.len() {
            let candidate = &s[i..i + 6];
            if candidate.eq_ignore_ascii_case("bearer") {
                let prev_ok = i == 0 || !is_word_byte(bytes[i - 1]);
                if prev_ok {
                    // 探测 Bearer 后面是否紧跟有效 token
                    let mut j = i + 6;
                    while j < bytes.len() && bytes[j].is_ascii_whitespace() {
                        j += 1;
                    }
                    let token_start = j;
                    if j < bytes.len()
                        && (bytes[j].is_ascii_alphanumeric()
                            || bytes[j] == b'.'
                            || bytes[j] == b'_'
                            || bytes[j] == b'-')
                    {
                        while j < bytes.len()
                            && (bytes[j].is_ascii_alphanumeric()
                                || bytes[j] == b'.'
                                || bytes[j] == b'_'
                                || bytes[j] == b'-')
                        {
                            j += 1;
                        }
                        if j > token_start {
                            // 确实有 token，整段替换
                            out.push_str("Bearer ****");
                            i = j;
                            continue;
                        }
                    }
                    // 没有有效 token（如已是 "Bearer ****" 的占位），走普通字符流
                }
            }
        }
        out.push(bytes[i] as char);
        i += 1;
    }
    out
}

fn is_word_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_' || b == b'-'
}

/// 递归替换 JSON Object 中白名单 key 的 String 值为 `"***"`
///
/// 故意"宁可漏报也不误删"：非法 JSON 原样返回；非 Object/非 String 类型的值不替换
pub(crate) fn redact_json_keys_string(json: &str) -> String {
    match serde_json::from_str::<serde_json::Value>(json) {
        Ok(mut value) => {
            redact_value(&mut value);
            value.to_string()
        }
        Err(_) => json.to_string(),
    }
}

fn redact_value(value: &mut serde_json::Value) {
    match value {
        serde_json::Value::Object(map) => {
            for (k, v) in map.iter_mut() {
                if is_sensitive_key(k) {
                    if v.is_string() {
                        *v = serde_json::Value::String("***".to_string());
                    } else {
                        // 即便值是嵌套对象 / 数组，也递归 redact（防御性）
                        redact_value(v);
                        if let serde_json::Value::Object(inner) = v {
                            for (_, inner_v) in inner.iter_mut() {
                                if inner_v.is_string() {
                                    *inner_v = serde_json::Value::String("***".to_string());
                                }
                            }
                        }
                    }
                } else {
                    redact_value(v);
                }
            }
        }
        serde_json::Value::Array(arr) => {
            for v in arr {
                redact_value(v);
            }
        }
        _ => {}
    }
}

fn is_sensitive_key(k: &str) -> bool {
    REDACT_KEYS.iter().any(|redact| redact.eq_ignore_ascii_case(k))
}

// ============================================================================
//  单元测试
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mask_bearer_token_basic() {
        let input = "Authorization: Bearer abc.def-ghi";
        let output = mask_bearer_token(input);
        assert!(output.contains("Bearer ****"));
        assert!(!output.contains("abc.def-ghi"));
    }

    #[test]
    fn mask_bearer_token_keeps_other_text() {
        let input = "Bearer token expired";
        let output = mask_bearer_token(input);
        // "Bearer token expired" 会被识别为 Bearer + token="token"，变成 "Bearer **** expired"
        assert!(!output.contains("Bearer token"));
    }

    #[test]
    fn mask_bearer_token_already_masked() {
        let input = "Authorization: Bearer ****";
        let output = mask_bearer_token(input);
        // 已经是 **** 不应二次处理
        assert_eq!(output, input);
    }

    #[test]
    fn redact_json_keys_removes_api_key() {
        let input = r#"{"api_key":"sk-xxx","model":"m"}"#;
        let output = redact_json_keys_string(input);
        assert!(output.contains(r#""api_key":"***""#));
        assert!(output.contains(r#""model":"m""#));
        assert!(!output.contains("sk-xxx"));
    }

    #[test]
    fn redact_json_keys_recursive() {
        let input = r#"{"headers":{"Authorization":"Bearer foo","x":"y"}}"#;
        let output = redact_json_keys_string(input);
        assert!(output.contains(r#""Authorization":"***""#));
        assert!(output.contains(r#""x":"y""#));
        assert!(!output.contains("Bearer foo"));
    }

    #[test]
    fn redact_json_keys_invalid_input_returns_original() {
        let input = "not a json {";
        let output = redact_json_keys_string(input);
        assert_eq!(output, input);
    }

    #[test]
    fn redact_json_keys_case_insensitive() {
        let input = r#"{"AUTHORIZATION":"Bearer foo","model":"m"}"#;
        let output = redact_json_keys_string(input);
        assert!(output.contains(r#""AUTHORIZATION":"***""#));
        assert!(!output.contains("Bearer foo"));
    }

    #[test]
    fn redact_json_keys_does_not_touch_unrelated_fields() {
        let input = r#"{"balance":100,"api_key":"sk-secret"}"#;
        let output = redact_json_keys_string(input);
        assert!(output.contains(r#""balance":100"#));
        assert!(output.contains(r#""api_key":"***""#));
    }

    #[test]
    fn log_buffer_push_pops_oldest_at_capacity() {
        let buf = LogBuffer::new();
        for i in 0..(BUFFER_CAPACITY + 5) {
            buf.push(LogEntry {
                id: i as i64,
                timestamp: i as i64,
                level: LogLevel::Info,
                category: LogCategory::System,
                source: None,
                message: format!("m{}", i),
                details: None,
            });
        }
        let snap = buf.snapshot();
        assert_eq!(snap.len(), BUFFER_CAPACITY);
        // 最早 5 条被淘汰
        assert_eq!(snap.first().unwrap().id, 5);
        assert_eq!(snap.last().unwrap().id, (BUFFER_CAPACITY + 4) as i64);
    }

    #[test]
    fn log_buffer_since_filters_by_id() {
        let buf = LogBuffer::new();
        for i in 0..10 {
            buf.push(LogEntry {
                id: i,
                timestamp: i,
                level: LogLevel::Info,
                category: LogCategory::System,
                source: None,
                message: String::new(),
                details: None,
            });
        }
        let after5 = buf.since(Some(5));
        // id > 5 => 6,7,8,9
        assert_eq!(after5.len(), 4);
        assert_eq!(after5.first().unwrap().id, 6);
        assert_eq!(after5.last().unwrap().id, 9);

        let all = buf.since(None);
        assert_eq!(all.len(), 10);
    }
}
