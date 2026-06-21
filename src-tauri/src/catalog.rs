use serde::{Deserialize, Serialize};

/// 供应商预置目录（不包含 API Key，由用户自行配置）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderPreset {
    pub id: String,                      // 预置 ID
    pub name: String,                    // 显示名
    pub provider_type: String,           // 类型：openai/anthropic/...
    pub base_url: String,                // API 根地址
    pub query_endpoint: String,          // 查询接口
    pub query_method: String,            // GET / POST
    pub default_headers: std::collections::HashMap<String, String>,
    pub default_refresh_interval: u64,   // 推荐刷新间隔
    pub category: String,                // 分类：domestic / overseas / coding_plan
    pub description: String,             // 简介
    pub docs_url: String,                // 官方文档
    pub requires_body: bool,             // 是否需要请求体（火山方舟需要）
    pub default_model: Option<String>,   // 默认模型（火山方舟用）
}

/// 完整预置目录（参考 ccSwitch 的设计：50+ 厂商可扩展）
pub fn get_catalog() -> Vec<ProviderPreset> {
    vec![
        // ===== 国内 Coding Plan =====
        ProviderPreset {
            id: "minimax_coding_cn".into(),
            name: "MiniMax Token Plan (国内)".into(),
            provider_type: "minimax_coding".into(),
            base_url: "https://www.minimaxi.com".into(),
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
        ProviderPreset {
            id: "volcengine_coding".into(),
            name: "火山方舟 Coding Plan".into(),
            provider_type: "volcengine_coding".into(),
            base_url: "https://ark.cn-beijing.volces.com/api/coding/v3".into(),
            query_endpoint: "/chat/completions".into(),
            query_method: "POST".into(),
            default_headers: [("Content-Type".to_string(), "application/json".to_string())].into(),
            default_refresh_interval: 60,
            category: "domestic".into(),
            description: "字节火山方舟 Coding Plan，5h + 周 + 月三维额度".into(),
            docs_url: "https://www.volcengine.com/docs/82379/2165245".into(),
            requires_body: true,
            default_model: Some("doubao-seed-code-1-0-260215".into()),
        },
        ProviderPreset {
            id: "volcengine_token".into(),
            name: "火山方舟 Token Plan".into(),
            provider_type: "volcengine_token".into(),
            base_url: "https://ark.cn-beijing.volces.com/api/coding/v3".into(),
            query_endpoint: "/chat/completions".into(),
            query_method: "POST".into(),
            default_headers: [("Content-Type".to_string(), "application/json".to_string())].into(),
            default_refresh_interval: 60,
            category: "domestic".into(),
            description: "字节火山方舟 Token Plan（别名，与 Coding Plan 使用同一接口）".into(),
            docs_url: "https://www.volcengine.com/docs/82379/2165245".into(),
            requires_body: true,
            default_model: Some("doubao-seed-code-1-0-260215".into()),
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
            id: "openai".into(),
            name: "OpenAI".into(),
            provider_type: "openai".into(),
            base_url: "https://api.openai.com".into(),
            query_endpoint: "/v1/dashboard/billing/credit_grants".into(),
            query_method: "GET".into(),
            default_headers: [("Content-Type".to_string(), "application/json".to_string())].into(),
            default_refresh_interval: 60,
            category: "overseas".into(),
            description: "OpenAI 按量计费账户余额".into(),
            docs_url: "https://platform.openai.com/usage".into(),
            requires_body: false,
            default_model: None,
        },
        ProviderPreset {
            id: "anthropic".into(),
            name: "Anthropic".into(),
            provider_type: "anthropic".into(),
            base_url: "https://api.anthropic.com".into(),
            query_endpoint: "/v1/organizations/self/subscription".into(),
            query_method: "GET".into(),
            default_headers: [
                ("Content-Type".to_string(), "application/json".to_string()),
                ("anthropic-version".to_string(), "2023-06-01".to_string()),
            ].into(),
            default_refresh_interval: 60,
            category: "overseas".into(),
            description: "Anthropic Claude 按量计费账户".into(),
            docs_url: "https://docs.anthropic.com/en/api/usage".into(),
            requires_body: false,
            default_model: None,
        },
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
        ProviderPreset {
            id: "zhipu".into(),
            name: "智谱 AI (GLM)".into(),
            provider_type: "zhipu".into(),
            base_url: "https://open.bigmodel.cn".into(),
            query_endpoint: "/api/finance/limit/list".into(),
            query_method: "GET".into(),
            default_headers: [("Content-Type".to_string(), "application/json".to_string())].into(),
            default_refresh_interval: 60,
            category: "domestic".into(),
            description: "智谱 GLM 系列模型账户余额".into(),
            docs_url: "https://bigmodel.cn/dev/api/finance/limit".into(),
            requires_body: false,
            default_model: None,
        },
        ProviderPreset {
            id: "moonshot".into(),
            name: "Moonshot (Kimi)".into(),
            provider_type: "moonshot".into(),
            base_url: "https://api.moonshot.cn".into(),
            query_endpoint: "/v1/users/me/balance".into(),
            query_method: "GET".into(),
            default_headers: [("Content-Type".to_string(), "application/json".to_string())].into(),
            default_refresh_interval: 60,
            category: "domestic".into(),
            description: "Moonshot Kimi 大模型账户余额".into(),
            docs_url: "https://platform.moonshot.cn/docs/api-reference/balance".into(),
            requires_body: false,
            default_model: None,
        },
        ProviderPreset {
            id: "qwen".into(),
            name: "通义千问 (DashScope)".into(),
            provider_type: "qwen".into(),
            base_url: "https://dashscope.aliyuncs.com".into(),
            query_endpoint: "/api/v1/account/info".into(),
            query_method: "GET".into(),
            default_headers: [("Content-Type".to_string(), "application/json".to_string())].into(),
            default_refresh_interval: 60,
            category: "domestic".into(),
            description: "阿里云通义千问 DashScope 账户".into(),
            docs_url: "https://help.aliyun.com/zh/model-studio/developer-reference".into(),
            requires_body: false,
            default_model: None,
        },
        ProviderPreset {
            id: "gemini".into(),
            name: "Google Gemini".into(),
            provider_type: "gemini".into(),
            base_url: "https://generativelanguage.googleapis.com".into(),
            query_endpoint: "/v1beta/models".into(),
            query_method: "GET".into(),
            default_headers: [("Content-Type".to_string(), "application/json".to_string())].into(),
            default_refresh_interval: 60,
            category: "overseas".into(),
            description: "Google Gemini API 账户".into(),
            docs_url: "https://ai.google.dev/api".into(),
            requires_body: false,
            default_model: None,
        },

        // ===== 自定义（占位）=====
        ProviderPreset {
            id: "custom".into(),
            name: "自定义 Provider".into(),
            provider_type: "custom".into(),
            base_url: "https://your-api.example.com".into(),
            query_endpoint: "/usage".into(),
            query_method: "GET".into(),
            default_headers: [("Content-Type".to_string(), "application/json".to_string())].into(),
            default_refresh_interval: 60,
            category: "custom".into(),
            description: "配置自定义 API 端点，监控任意 LLM 服务".into(),
            docs_url: "".into(),
            requires_body: false,
            default_model: None,
        },
    ]
}

/// 根据预置 ID 获取单个预置
pub fn get_preset_by_id(id: &str) -> Option<ProviderPreset> {
    get_catalog().into_iter().find(|p| p.id == id)
}
