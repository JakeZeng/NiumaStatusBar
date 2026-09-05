use serde::{Deserialize, Serialize};

/// 供应商预置目录（不包含 API Key，由用户自行配置）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderPreset {
    pub id: String,             // 预置 ID
    pub name: String,           // 显示名
    pub provider_type: String,  // 类型：openai/anthropic/...
    pub base_url: String,       // API 根地址
    pub query_endpoint: String, // 查询接口
    pub query_method: String,   // GET / POST
    pub default_headers: std::collections::HashMap<String, String>,
    pub default_refresh_interval: u64, // 推荐刷新间隔
    pub category: String,              // 分类：domestic / overseas / coding_plan
    pub description: String,           // 简介
    pub docs_url: String,              // 官方文档
    pub requires_body: bool,           // 是否需要请求体（火山方舟需要）
    pub default_model: Option<String>, // 默认模型（火山方舟用）
}

/// 完整预置目录（参考 ccSwitch 的设计：50+ 厂商可扩展）
///
/// **当前发布范围**：仅展示已完成适配并验证的供应商。
/// 火山方舟 / openai / anthropic / zhipu / moonshot / qwen / gemini / custom 等
/// 未完成端到端验证的厂商暂未列出，待后续补完解析逻辑后再放回。
pub fn get_catalog() -> Vec<ProviderPreset> {
    vec![
        // ===== 国内 Coding Plan =====
        ProviderPreset {
            id: "minimax_coding_cn".into(),
            name: "MiniMax Token Plan (国内)".into(),
            provider_type: "minimax_coding".into(),
            base_url: "https://api.minimaxi.com".into(),
            query_endpoint: "/v1/token_plan/remains".into(),
            query_method: "GET".into(),
            default_headers: [("Content-Type".to_string(), "application/json".to_string())].into(),
            default_refresh_interval: 60,
            category: "domestic".into(),
            description: "MiniMax MiniMax 国内 Token Plan，5小时滚动窗口 + 周额度".into(),
            docs_url: "https://platform.minimaxi.com/docs/token-plan/faq".into(),
            requires_body: false,
            default_model: None,
        },
        // ===== 海外 Coding Plan =====
        ProviderPreset {
            id: "minimax_coding_overseas".into(),
            name: "MiniMax Coding Plan (海外)".into(),
            provider_type: "minimax_token".into(),
            base_url: "https://api.minimax.io".into(),
            query_endpoint: "/v1/api/openplatform/coding_plan/remains".into(),
            query_method: "GET".into(),
            default_headers: [("Content-Type".to_string(), "application/json".to_string())].into(),
            default_refresh_interval: 60,
            category: "overseas".into(),
            description: "MiniMax MiniMax 海外 Coding Plan API".into(),
            docs_url: "https://platform.minimaxi.com/docs/token-plan/quickstart".into(),
            requires_body: false,
            default_model: None,
        },
        // ===== 通用 API（按量计费）=====
        ProviderPreset {
            id: "deepseek".into(),
            name: "DeepSeek".into(),
            provider_type: "deepseek".into(),
            base_url: "https://api.deepseek.com".into(),
            query_endpoint: "/v1/user/balance".into(),
            query_method: "GET".into(),
            default_headers: [("Content-Type".to_string(), "application/json".to_string())].into(),
            default_refresh_interval: 60,
            category: "domestic".into(),
            description: "DeepSeek 开放平台账户余额".into(),
            docs_url: "https://api-docs.deepseek.com/zh-cn/api/get-user-balance".into(),
            requires_body: false,
            default_model: None,
        },
    ]
}

/// 根据预置 ID 获取单个预置
pub fn get_preset_by_id(id: &str) -> Option<ProviderPreset> {
    get_catalog().into_iter().find(|p| p.id == id)
}
