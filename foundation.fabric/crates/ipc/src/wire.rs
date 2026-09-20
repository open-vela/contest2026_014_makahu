use prost::{Enumeration, Message};

pub const IPC_PROTOCOL_VERSION: u32 = 1;
pub const MAX_IPC_VERSIONS: usize = 16;
pub const MAX_IPC_FEATURES: usize = 32;
pub const MAX_IPC_STRING_BYTES: usize = 255;

#[derive(Clone, PartialEq, Message)]
pub struct ClientHello {
    #[prost(uint32, repeated, tag = "1")]
    pub protocol_versions: Vec<u32>,
    #[prost(string, tag = "2")]
    pub sdk_version: String,
    #[prost(string, repeated, tag = "3")]
    pub requested_features: Vec<String>,
}

#[derive(Clone, PartialEq, Message)]
pub struct ServerHello {
    #[prost(uint32, tag = "1")]
    pub selected_version: u32,
    #[prost(bytes = "vec", tag = "2")]
    pub connection_id: Vec<u8>,
    #[prost(uint32, tag = "3")]
    pub maximum_offers: u32,
    #[prost(uint32, tag = "4")]
    pub maximum_requirements: u32,
    #[prost(uint32, tag = "5")]
    pub maximum_sessions: u32,
    #[prost(uint64, tag = "6")]
    pub maximum_buffered_bytes: u64,
    #[prost(string, repeated, tag = "7")]
    pub hub_features: Vec<String>,
}

#[derive(Clone, PartialEq, Message)]
pub struct BindApplication {
    #[prost(string, optional, tag = "1")]
    pub declared_app_id: Option<String>,
}

#[derive(Clone, PartialEq, Message)]
pub struct ApplicationBound {
    #[prost(string, tag = "1")]
    pub stable_app_id: String,
    #[prost(string, optional, tag = "2")]
    pub publisher_id: Option<String>,
    #[prost(bytes = "vec", optional, tag = "3")]
    pub signing_digest: Option<Vec<u8>>,
    #[prost(string, tag = "4")]
    pub os_subject: String,
}

#[derive(Clone, PartialEq, Message)]
pub struct Request {
    #[prost(oneof = "request::Body", tags = "1, 2, 3, 4, 5")]
    pub body: Option<request::Body>,
}

pub mod request {
    use prost::Oneof;

    #[derive(Clone, PartialEq, Oneof)]
    pub enum Body {
        #[prost(bytes, tag = "1")]
        PublishOffer(Vec<u8>),
        #[prost(bytes, tag = "2")]
        RegisterRequirement(Vec<u8>),
        #[prost(bytes, tag = "3")]
        ProposeSession(Vec<u8>),
        #[prost(bytes, tag = "4")]
        AcceptSessionId(Vec<u8>),
        #[prost(bool, tag = "5")]
        Ping(bool),
    }
}

#[derive(Clone, PartialEq, Message)]
pub struct Response {
    #[prost(enumeration = "ResponseCode", tag = "1")]
    pub code: i32,
    #[prost(bytes = "vec", tag = "2")]
    pub body: Vec<u8>,
    #[prost(string, tag = "3")]
    pub safe_message: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Enumeration)]
#[repr(i32)]
pub enum ResponseCode {
    Unspecified = 0,
    Ok = 1,
    InvalidArgument = 2,
    UnsupportedMessage = 3,
    PermissionDenied = 4,
    ResourceExhausted = 5,
    NotFound = 6,
    Internal = 7,
}
