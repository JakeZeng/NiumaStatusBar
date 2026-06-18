use serde::{Deserialize, Serialize};
use std::collections::HashMap;
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

#[derive(Debug, Clone, Serialize, Deserialize)]
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
}

pub struct ProviderManager {
    client: reqwest::Client,
    providers: RwLock<Vec<ProviderConfig>>,
}

impl ProviderManager {
    pub fn new() -> Self {
        Self {
            client: reqwest::Client::builder()
                .timeout(std::time::Duration::from_secs(10))
                .build()
                .unwrap_or_default(),
            providers: RwLock::new(Vec::new()),
        }
    }

    pub async fn get_providers(&self) -> Vec<ProviderConfig> {
        self.providers.read().await.clone()
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
    }

    pub async fn fetch_usage(&self, provider: &ProviderConfig) -> Result<UsageStatus, String> {
        let url = format!(
            "{}{}",
            provider.base_url.trim_end_matches('/'),
            provider.query_endpoint
        );

        let start = std::time::Instant::now();

        let mut request = match provider.query_method.as_str() {
            "GET" => self.client.get(&url),
            "POST" => self.client.post(&url),
            _ => return Err("Unsupported method".to_string()),
        };

        request = request
            .header("Authorization", format!("Bearer {}", provider.api_key))
            .header("Content-Type", "application/json");

        for (key, value) in &provider.query_headers {
            request = request.header(key.as_str(), value.as_str());
        }

        if let Some(params) = provider.query_params.as_object() {
            for (key, value) in params {
                if let Some(s) = value.as_str() {
                    request = request.query(&[(key, s)]);
                }
            }
        }

        let response = request.send().await.map_err(|e| e.to_string())?;

        let latency = start.elapsed().as_millis() as i64;

        let body = response.text().await.map_err(|e| e.to_string())?;
        let json: serde_json::Value = serde_json::from_str(&body).map_err(|e| e.to_string())?;

        Ok(UsageStatus {
            provider_id: provider.id.clone(),
            timestamp: chrono::Utc::now().timestamp(),
            balance: json.get("balance").and_then(|v| v.as_f64()),
            balance_used: json
                .get("used")
                .or(json.get("balance_used"))
                .and_then(|v| v.as_f64()),
            balance_limit: json
                .get("limit")
                .or(json.get("balance_limit"))
                .and_then(|v| v.as_f64()),
            requests_today: json
                .get("requests_today")
                .or(json.get("total_requests"))
                .and_then(|v| v.as_i64()),
            error_rate: json.get("error_rate").and_then(|v| v.as_f64()),
            avg_latency: Some(latency),
            last_error: None,
        })
    }
}
