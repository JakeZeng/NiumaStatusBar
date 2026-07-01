use crate::logging::{redact_json_keys_string, AppLogger, LogCategory, LogLevel};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderConfig {
    pub id: String,
    pub name: String,
    pub provider: String,
    pub base_url: String,
    pub api_key: String,
    pub query_endpoint: String,
    pub query_method: String,
    pub query_headers: HashMap<String, String>,
    pub query_params: serde_json::Value,
    pub refresh_interval: u64,
    pub is_enabled: bool,
    pub status: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct UsageStatus {
    pub provider_id: String,
    pub timestamp: i64,
    pub balance: Option<f64>,
    pub balance_used: Option<f64>,
    pub balance_limit: Option<f64>,
    pub requests_today: Option<i64>,
    pub error_rate: Option<f64>,
    pub avg_latency: Option<i64>,
    pub last_error: Option<String>,

    // === 多周期额度（MiniMax / Coding Plan）===
    /// 5 小时窗口：剩余
    pub quota_5h_remaining: Option<f64>,
    /// 5 小时窗口：剩余百分比（国内 minimax 用此字段）
    pub quota_5h_remaining_percent: Option<f64>,
    /// 5 小时窗口：总额
    pub quota_5h_total: Option<f64>,
    /// 5 小时窗口：已用
    pub quota_5h_used: Option<f64>,

    /// 周窗口：剩余
    pub quota_week_remaining: Option<f64>,
    /// 周窗口：剩余百分比（国内 minimax 用此字段）
    pub quota_week_remaining_percent: Option<f64>,
    /// 周窗口：总额
    pub quota_week_total: Option<f64>,
    /// 周窗口：已用
    pub quota_week_used: Option<f64>,

    /// 月窗口：剩余（仅 Coding Plan Pro/Lite）
    pub quota_month_remaining: Option<f64>,
    /// 月窗口：总额
    pub quota_month_total: Option<f64>,
    /// 月窗口：已用
    pub quota_month_used: Option<f64>,
}

pub struct ProviderManager {
    client: reqwest::Client,
    providers: RwLock<Vec<ProviderConfig>>,
    statuses: RwLock<HashMap<String, UsageStatus>>,
    /// 可选的应用日志器；由 lib.rs setup 阶段通过 set_logger 注入
    logger: std::sync::Mutex<Option<Arc<AppLogger>>>,
}

impl ProviderManager {
    pub fn new() -> Self {
        Self {
            client: reqwest::Client::builder()
                .timeout(std::time::Duration::from_secs(15))
                .build()
                .unwrap_or_default(),
            providers: RwLock::new(Vec::new()),
            statuses: RwLock::new(HashMap::new()),
            logger: std::sync::Mutex::new(None),
        }
    }

    /// 注入日志器（lib.rs setup 阶段调用）。可重复调用覆盖。
    pub fn set_logger(&self, logger: Arc<AppLogger>) {
        *self.logger.lock().unwrap() = Some(logger);
    }

    /// 取日志器的便捷方法，无日志器时静默返回 None
    fn logger_ref(&self) -> Option<Arc<AppLogger>> {
        self.logger.lock().unwrap().clone()
    }

    pub async fn get_providers(&self) -> Vec<ProviderConfig> {
        self.providers.read().await.clone()
    }

    pub async fn set_providers(&self, providers: Vec<ProviderConfig>) {
        *self.providers.write().await = providers;
    }

    pub async fn add_provider(&self, provider: ProviderConfig) {
        self.providers.write().await.push(provider);
    }

    pub async fn update_provider(&self, id: String, provider: ProviderConfig) {
        let mut providers = self.providers.write().await;
        if let Some(p) = providers.iter_mut().find(|p| p.id == id) {
            *p = provider;
        }
    }

    pub async fn delete_provider(&self, id: String) {
        self.providers.write().await.retain(|p| p.id != id);
        self.statuses.write().await.remove(&id);
    }

    pub async fn update_status(&self, provider_id: String, status: UsageStatus) {
        self.statuses.write().await.insert(provider_id, status);
    }

    pub async fn get_status(&self, provider_id: &str) -> Option<UsageStatus> {
        self.statuses.read().await.get(provider_id).cloned()
    }

    pub async fn fetch_usage(&self, provider: &ProviderConfig) -> Result<UsageStatus, String> {
        let url = format!(
            "{}{}",
            provider.base_url.trim_end_matches('/'),
            provider.query_endpoint
        );

        let logger = self.logger_ref();
        let log_helper = |lv: LogLevel, cat: LogCategory, msg: &str, det: Option<serde_json::Value>| {
            if let Some(l) = &logger {
                l.log(lv, cat, Some(provider.id.clone()), msg.to_string(), det);
            }
        };

        // 阶段 A 入口埋点
        log_helper(
            LogLevel::Info,
            LogCategory::Http,
            "request start",
            Some(serde_json::json!({
                "provider": provider.provider,
                "method": provider.query_method,
                "url_no_query": strip_url_query(&url),
                "headers_count": provider.query_headers.len() + 1, // +1 for Authorization
            })),
        );

        let start = std::time::Instant::now();

        let mut request = match provider.query_method.as_str() {
            "GET" => self.client.get(&url),
            "POST" => self.client.post(&url),
            _ => return Err("Unsupported method".to_string()),
        };

        request = request.header("Authorization", format!("Bearer {}", provider.api_key));

        // 是否会注入 JSON body（决定要不要在这里手动塞 Content-Type）
        let url_lower = url.to_lowercase();
        let is_volc_coding = provider.provider == "volcengine_coding"
            || provider.provider == "volcengine_token";
        let is_chat_completions = url_lower.contains("/chat/completions");
        let is_ark_coding_endpoint = url_lower.contains("/api/coding/v3");
        let is_ark_cn_host = url_lower.contains("ark.cn-beijing.volces.com")
            || url_lower.contains("ark.volces.com");
        let is_ark_endpoint = is_ark_cn_host
            || url_lower.contains("volces.com/api/")
            || is_ark_coding_endpoint;
        let is_post = provider.query_method == "POST";
        let needs_body = is_post
            && (is_volc_coding || is_chat_completions || is_ark_endpoint);

        // 注入用户自定义 headers——若马上要 .json() 则跳过 Content-Type，避免
        // reqwest 内部追加导致重复头（火山方舟网关会因此返回 400 "缺少 message"）。
        for (key, value) in &provider.query_headers {
            if needs_body && key.eq_ignore_ascii_case("content-type") {
                continue;
            }
            request = request.header(key.as_str(), value.as_str());
        }
        // GET 请求显式补一个 Content-Type，POST 走 .json() 自动设置。
        if !needs_body {
            request = request.header("Content-Type", "application/json");
        }

        // 把 query_params 中的 model 项从 URL 查询串里剥离出来（避免 `?model=...` 出现在 URL 上）
        // 同时收集剩余的 query 项
        let mut extra_query: Vec<(String, String)> = Vec::new();
        if let Some(params) = provider.query_params.as_object() {
            for (key, value) in params {
                if key == "model" { continue; }
                if let Some(s) = value.as_str() {
                    extra_query.push((key.clone(), s.to_string()));
                }
            }
        }
        for (k, v) in &extra_query {
            request = request.query(&[(k.as_str(), v.as_str())]);
        }

        if needs_body {
            // 优先使用 provider.query_params 里的 model，否则用默认 model
            let model = provider
                .query_params
                .get("model")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string())
                .or_else(|| {
                    provider
                        .query_headers
                        .get("x-ark-model")
                        .cloned()
                })
                .unwrap_or_else(|| "doubao-seed-code-1-0-260215".to_string());

            let minimal_body = serde_json::json!({
                "model": model,
                "messages": [{"role": "user", "content": "ping"}],
                "max_tokens": 1,
                "stream": false
            });
            log_helper(
                LogLevel::Debug,
                LogCategory::Provider,
                "injected body",
                Some(serde_json::json!({
                    "provider": provider.provider,
                    "url": strip_url_query(&url),
                    "model": model,
                    "body": serde_json::Value::String(redact_json_keys_string(
                        &minimal_body.to_string(),
                    )),
                })),
            );
            request = request.json(&minimal_body);
        } else {
            // 同步记录 body 注入决策，便于排查 "为什么 POST 但没发 body"
            log_helper(
                LogLevel::Debug,
                LogCategory::Provider,
                "body injection decision",
                Some(serde_json::json!({
                    "is_post": is_post,
                    "is_volc": is_volc_coding,
                    "is_chat_completions": is_chat_completions,
                    "is_ark": is_ark_endpoint,
                    "needs_body": needs_body,
                })),
            );
        }

        if !needs_body && is_post && (is_chat_completions || is_ark_endpoint) {
            log_helper(
                LogLevel::Warn,
                LogCategory::Provider,
                "POST hits Ark/chat-completions but body not injected",
                Some(serde_json::json!({
                    "provider": provider.provider,
                    "url": strip_url_query(&url),
                })),
            );
        }

        // 阶段 B：HTTP 发送 + 错误埋点
        let response = request.send().await.map_err(|e| {
            let latency = start.elapsed().as_millis() as i64;
            log_helper(
                LogLevel::Error,
                LogCategory::Http,
                "network error",
                Some(serde_json::json!({
                    "error_message": e.to_string(),
                    "latency_ms": latency,
                })),
            );
            e.to_string()
        })?;
        let latency = start.elapsed().as_millis() as i64;

        // 提取所有响应头（用于 Coding Plan 等基于响应头的额度）
        let mut headers_map = HashMap::new();
        for (k, v) in response.headers() {
            if let Ok(v_str) = v.to_str() {
                headers_map.insert(k.as_str().to_lowercase(), v_str.to_string());
            }
        }

        let status_code = response.status().as_u16();
        let body = response.text().await.map_err(|e| e.to_string())?;

        let mut status = UsageStatus {
            provider_id: provider.id.clone(),
            timestamp: chrono::Utc::now().timestamp(),
            avg_latency: Some(latency),
            ..Default::default()
        };

        // ===== 解析逻辑 =====

        // 1) Coding Plan（火山方舟）通过响应头返回
        parse_coding_plan_headers(&headers_map, &mut status);

        // 2) 通用 JSON 解析
        let parsed_json: Option<serde_json::Value> = serde_json::from_str(&body).ok();
        if let Some(ref json) = parsed_json {
            parse_generic_json(json, &mut status);

            // 3) MiniMax Coding Plan / Token Plan
            if provider.provider == "minimax_coding" || provider.provider == "minimax_token" {
                let hits = parse_minimax_remains(json, &mut status);
                log_helper(
                    LogLevel::Debug,
                    LogCategory::Provider,
                    "minimax parse result",
                    Some(serde_json::json!({
                        "provider": provider.provider,
                        "hits": hits,
                        "has_5h_percent": status.quota_5h_remaining_percent.is_some(),
                        "has_5h_remaining": status.quota_5h_remaining.is_some(),
                        "has_week_percent": status.quota_week_remaining_percent.is_some(),
                        "has_week_remaining": status.quota_week_remaining.is_some(),
                        "model_count": parsed_json
                            .as_ref()
                            .and_then(|j| j.get("model_remains"))
                            .and_then(|v| v.as_array())
                            .map(|a| a.len())
                            .unwrap_or(0),
                    })),
                );
            }
        }

        // 4) 错误处理：HTTP 错误码 → 显式错误，让前端能看到原因而不是静默"等待数据"
        if status_code >= 400 {
            let body_excerpt = truncate(&body, 300);
            log_helper(
                LogLevel::Error,
                LogCategory::Http,
                "http error response",
                Some(serde_json::json!({
                    "status": status_code,
                    "url": strip_url_query(&url),
                    "body_truncated": body_excerpt,
                })),
            );
            return Err(format!(
                "HTTP {} 来自 {}：{}",
                status_code,
                url,
                truncate(&body, 300)
            ));
        }

        // 5) MiniMax 业务错误码（HTTP 200 但 base_resp.status_code != 0）
        if (provider.provider == "minimax_coding" || provider.provider == "minimax_token")
            && status.quota_5h_remaining.is_none()
            && status.quota_week_remaining.is_none()
        {
            if let Some(ref json) = parsed_json {
                if let Some(base_resp) = json.get("base_resp") {
                    let code = base_resp.get("status_code").and_then(|v| v.as_i64());
                    let msg = base_resp
                        .get("status_msg")
                        .and_then(|v| v.as_str())
                        .unwrap_or("");
                    if code.unwrap_or(0) != 0 || !msg.is_empty() && msg != "success" {
                        log_helper(
                            LogLevel::Warn,
                            LogCategory::Provider,
                            "minimax business error",
                            Some(serde_json::json!({
                                "status_code": code,
                                "message": msg,
                            })),
                        );
                        return Err(format!(
                            "MiniMax 业务错误 code={:?} msg={} body={}",
                            code,
                            msg,
                            truncate(&body, 200)
                        ));
                    }
                }
                log_helper(
                    LogLevel::Warn,
                    LogCategory::Provider,
                    "parse warning",
                    Some(serde_json::json!({
                        "reason": "minimax_missing_model_remains",
                        "raw_excerpt": truncate(&body, 200),
                    })),
                );
                return Err(format!(
                    "MiniMax 响应未包含 model_remains。原始响应：{}",
                    truncate(&body, 300)
                ));
            } else {
                log_helper(
                    LogLevel::Warn,
                    LogCategory::Provider,
                    "parse warning",
                    Some(serde_json::json!({
                        "reason": "minimax_not_json",
                        "raw_excerpt": truncate(&body, 200),
                    })),
                );
                return Err(format!(
                    "MiniMax 响应不是 JSON。原始响应：{}",
                    truncate(&body, 300)
                ));
            }
        }

        // 6) 火山方舟 Coding Plan：POST 成功但没拿到额度头时，显式说明
        if (provider.provider == "volcengine_coding" || provider.provider == "volcengine_token")
            && status.quota_5h_remaining.is_none()
            && status.quota_week_remaining.is_none()
            && status.quota_month_remaining.is_none()
        {
            log_helper(
                LogLevel::Warn,
                LogCategory::Provider,
                "volcengine missing rate-limit headers",
                Some(serde_json::json!({
                    "headers": headers_map.keys().collect::<Vec<_>>(),
                })),
            );
            return Err(format!(
                "火山方舟未返回 X-RateLimit-* 响应头。请确认 API Key 已开通 Coding Plan / Token Plan 套餐。响应头：{:?}，body：{}",
                headers_map.keys().collect::<Vec<_>>(),
                truncate(&body, 200)
            ));
        }

        Ok(status)
    }
}

/// 火山方舟 Coding Plan：从响应头读取
/// 字段: X-RateLimit-Remaining-5H, X-RateLimit-Limit-5H
///       X-RateLimit-Remaining-Week, X-RateLimit-Limit-Week
///       X-RateLimit-Remaining-Month, X-RateLimit-Limit-Month
fn parse_coding_plan_headers(headers: &HashMap<String, String>, status: &mut UsageStatus) {
    let get = |k: &str| headers.get(k).and_then(|v| v.parse::<f64>().ok());

    if let Some(remaining) = get("x-ratelimit-remaining-5h") {
        status.quota_5h_remaining = Some(remaining);
        if let Some(total) = get("x-ratelimit-limit-5h") {
            status.quota_5h_total = Some(total);
            status.quota_5h_used = Some((total - remaining).max(0.0));
        }
    }

    if let Some(remaining) = get("x-ratelimit-remaining-week") {
        status.quota_week_remaining = Some(remaining);
        if let Some(total) = get("x-ratelimit-limit-week") {
            status.quota_week_total = Some(total);
            status.quota_week_used = Some((total - remaining).max(0.0));
        }
    }

    if let Some(remaining) = get("x-ratelimit-remaining-month") {
        status.quota_month_remaining = Some(remaining);
        if let Some(total) = get("x-ratelimit-limit-month") {
            status.quota_month_total = Some(total);
            status.quota_month_used = Some((total - remaining).max(0.0));
        }
    }
}

/// 通用 JSON 解析（兼容 OpenAI/Claude 等）
fn parse_generic_json(json: &serde_json::Value, status: &mut UsageStatus) {
    if status.balance.is_none() {
        status.balance = json
            .get("balance")
            .or(json.get("total_available"))
            .or(json.get("credit"))
            .and_then(|v| v.as_f64());
    }
    if status.balance_used.is_none() {
        status.balance_used = json
            .get("used")
            .or(json.get("balance_used"))
            .or(json.get("total_used"))
            .and_then(|v| v.as_f64());
    }
    if status.balance_limit.is_none() {
        status.balance_limit = json
            .get("limit")
            .or(json.get("balance_limit"))
            .or(json.get("hard_limit_usd"))
            .and_then(|v| v.as_f64());
    }
    if status.requests_today.is_none() {
        status.requests_today = json
            .get("requests_today")
            .or(json.get("total_requests"))
            .and_then(|v| v.as_i64());
    }
}

/// MiniMax Coding Plan / Token Plan 专用解析
///
/// 响应示例（国内）：
/// {
///   "base_resp": { "status_code": 0, "status_msg": "success" },
///   "model_remains": [{
///     "model_name": "MiniMax-M2.7",
///     "current_interval_remaining_percent": 84,
///     "current_weekly_remaining_percent": 85,
///     "current_interval_total_count": 0,
///     "current_interval_usage_count": 0,
///     "current_weekly_total_count": 0,
///     "current_weekly_usage_count": 0
///   }]
/// }
///
/// 海外响应字段：interval_total_count / interval_usage_count / weekly_total_count /
/// weekly_usage_count / week_total_count / week_usage_count。
///
/// 解析策略：percent 字段优先（国内默认走这条）；保留 total / used fallback 链
/// 兼容海外接口。返回命中的字段名列表，供调用方打 Debug 日志。
fn parse_minimax_remains(json: &serde_json::Value, status: &mut UsageStatus) -> Vec<&'static str> {
    let model_remains = json
        .get("model_remains")
        .or(json.get("data"))
        .and_then(|v| v.as_array());

    let mut hits: Vec<&'static str> = Vec::new();
    let Some(arr) = model_remains else { return hits; };
    let Some(first) = arr.first() else { return hits; };

    // ===== 5 小时窗口 =====
    if status.quota_5h_remaining_percent.is_none() {
        if let Some(v) = first
            .get("current_interval_remaining_percent")
            .and_then(|v| v.as_f64())
        {
            status.quota_5h_remaining_percent = Some(v);
            hits.push("5h:current_interval_remaining_percent");
        }
    }
    if status.quota_5h_total.is_none() {
        if let Some(v) = first
            .get("current_interval_total_count")
            .or(first.get("interval_total_count"))
            .and_then(|v| v.as_f64())
        {
            status.quota_5h_total = Some(v);
            hits.push("5h:current_interval_total_count");
        }
    }
    if status.quota_5h_used.is_none() {
        if let Some(v) = first
            .get("current_interval_usage_count")
            .or(first.get("interval_usage_count"))
            .and_then(|v| v.as_f64())
        {
            status.quota_5h_used = Some(v);
            hits.push("5h:current_interval_usage_count");
        }
    }
    if status.quota_5h_remaining.is_none() {
        if let (Some(t), Some(u)) = (status.quota_5h_total, status.quota_5h_used) {
            status.quota_5h_remaining = Some((t - u).max(0.0));
        }
    }

    // ===== 周窗口 =====
    if status.quota_week_remaining_percent.is_none() {
        if let Some(v) = first
            .get("current_weekly_remaining_percent")
            .and_then(|v| v.as_f64())
        {
            status.quota_week_remaining_percent = Some(v);
            hits.push("week:current_weekly_remaining_percent");
        }
    }
    if status.quota_week_total.is_none() {
        if let Some(v) = first
            .get("current_weekly_total_count")
            .or(first.get("weekly_total_count"))
            .or(first.get("week_total_count"))
            .and_then(|v| v.as_f64())
        {
            status.quota_week_total = Some(v);
            hits.push("week:current_weekly_total_count");
        }
    }
    if status.quota_week_used.is_none() {
        if let Some(v) = first
            .get("current_weekly_usage_count")
            .or(first.get("weekly_usage_count"))
            .or(first.get("week_usage_count"))
            .and_then(|v| v.as_f64())
        {
            status.quota_week_used = Some(v);
            hits.push("week:current_weekly_usage_count");
        }
    }
    if status.quota_week_remaining.is_none() {
        if let (Some(t), Some(u)) = (status.quota_week_total, status.quota_week_used) {
            status.quota_week_remaining = Some((t - u).max(0.0));
        }
    }

    hits
}

fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() > max {
        let mut t: String = s.chars().take(max).collect();
        t.push_str("...");
        t
    } else {
        s.to_string()
    }
}

/// 截断 URL 上的 query string（避免 token 等敏感参数进日志）
fn strip_url_query(url: &str) -> &str {
    match url.find('?') {
        Some(idx) => &url[..idx],
        None => url,
    }
}