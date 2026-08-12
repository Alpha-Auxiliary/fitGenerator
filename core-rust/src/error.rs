#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorCode {
    Ok = 0,
    InvalidInput = 100,
    UnsupportedSchema = 101,
    ResourceLimit = 102,
    SimulationFailed = 200,
    FitEncodingFailed = 300,
    InternalError = 900,
}

#[derive(Debug, thiserror::Error)]
pub enum CoreError {
    #[error("{0}")]
    InvalidInput(String),
    #[error("不支持的数据版本：{0}")]
    UnsupportedSchema(u32),
    #[error("{0}")]
    ResourceLimit(String),
    #[error("{0}")]
    SimulationFailed(String),
    #[error("{0}")]
    FitEncodingFailed(String),
    #[error("{0}")]
    InternalError(String),
}

impl CoreError {
    pub fn code(&self) -> ErrorCode {
        match self {
            Self::InvalidInput(_) => ErrorCode::InvalidInput,
            Self::UnsupportedSchema(_) => ErrorCode::UnsupportedSchema,
            Self::ResourceLimit(_) => ErrorCode::ResourceLimit,
            Self::SimulationFailed(_) => ErrorCode::SimulationFailed,
            Self::FitEncodingFailed(_) => ErrorCode::FitEncodingFailed,
            Self::InternalError(_) => ErrorCode::InternalError,
        }
    }
}
