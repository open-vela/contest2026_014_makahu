//! Application-facing asynchronous Device Fabric SDK contracts.

pub mod conformance;

use std::{pin::Pin, sync::Arc};

use async_trait::async_trait;
use bytes::Bytes;
use fabric_core::{
    AbilityInstanceId, AbilityOffer, AbilityRequirement, InvitationId, Participant, ParticipantId,
    PortId, RequirementId, SessionId, SessionPlan, SessionState,
};
use futures_core::Stream;
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncWrite};

pub type OfferDraft = AbilityOffer;
pub type RequirementDraft = AbilityRequirement;
pub type SessionProposal = SessionPlan;

#[derive(Debug, Error)]
pub enum FabricError {
    #[error("invalid request: {0}")]
    InvalidRequest(String),
    #[error("permission denied")]
    PermissionDenied,
    #[error("resource limit exceeded")]
    ResourceExhausted,
    #[error("Session is stale or no longer active")]
    StaleSession,
    #[error("transport disconnected")]
    Disconnected,
    #[error("operation is unsupported by this platform")]
    UnsupportedCapability,
}

#[derive(Clone, Debug)]
pub struct SessionInvitation {
    pub id: InvitationId,
    pub plan: SessionPlan,
}

#[derive(Clone, Debug, Default)]
pub struct SessionAcceptance {
    pub opaque_ability_config: Bytes,
}

#[derive(Clone, Debug)]
pub struct Candidate {
    pub offer: AbilityOffer,
}

pub type SessionInvitationStream = Pin<Box<dyn Stream<Item = SessionInvitation> + Send>>;
pub type CandidateStream = Pin<Box<dyn Stream<Item = Candidate> + Send>>;
pub type SessionStateStream = Pin<Box<dyn Stream<Item = SessionState> + Send>>;

#[async_trait]
pub trait FabricClient: Send + Sync {
    async fn publish_offer(
        &self,
        offer: OfferDraft,
    ) -> Result<Box<dyn PublishedOffer>, FabricError>;
    async fn register_requirement(
        &self,
        requirement: RequirementDraft,
    ) -> Result<Box<dyn RequirementHandle>, FabricError>;
    async fn propose_session(
        &self,
        proposal: SessionProposal,
    ) -> Result<Box<dyn SessionHandle>, FabricError>;
    fn invitations(&self) -> SessionInvitationStream;
    async fn accept_invitation(
        &self,
        invitation: InvitationId,
        acceptance: SessionAcceptance,
    ) -> Result<Box<dyn SessionHandle>, FabricError>;
}

#[async_trait]
pub trait PublishedOffer: Send + Sync {
    fn instance_id(&self) -> AbilityInstanceId;
    async fn update_properties(&self, patch: PropertyPatch) -> Result<(), FabricError>;
    async fn renew(&self) -> Result<(), FabricError>;
    async fn withdraw(self: Box<Self>) -> Result<(), FabricError>;
    fn incoming_sessions(&self) -> SessionInvitationStream;
}

#[derive(Clone, Debug, Default)]
pub struct PropertyPatch(pub Vec<(String, Option<fabric_core::PropertyValue>)>);

#[derive(Clone, Debug, Default)]
pub struct RequirementPatch {
    pub replacement: Option<AbilityRequirement>,
}

#[async_trait]
pub trait RequirementHandle: Send + Sync {
    fn id(&self) -> RequirementId;
    fn candidates(&self) -> CandidateStream;
    async fn update(&self, patch: RequirementPatch) -> Result<(), FabricError>;
    async fn cancel(self: Box<Self>) -> Result<(), FabricError>;
}

#[derive(Clone, Debug)]
pub enum SessionChange {
    ReplacePlan(SessionPlan),
}

#[derive(Clone, Debug)]
pub enum CloseReason {
    Completed,
    Cancelled,
    Error(String),
}

pub trait SessionClock: Send + Sync {
    fn group_to_local(&self, group_ns: i128) -> Result<u64, FabricError>;
    fn local_to_group(&self, local_ns: u64) -> Result<i128, FabricError>;
    fn uncertainty_ns(&self) -> u64;
}

#[async_trait]
pub trait SessionHandle: Send + Sync {
    fn id(&self) -> SessionId;
    fn epoch(&self) -> u64;
    fn state(&self) -> SessionStateStream;
    fn participants(&self) -> Vec<Participant>;
    fn clock(&self) -> Option<Arc<dyn SessionClock>>;
    async fn open_message_port(&self, port: &str) -> Result<Box<dyn MessagePort>, FabricError>;
    async fn open_stream_port(&self, port: &str) -> Result<StreamPort, FabricError>;
    async fn open_datagram_port(&self, port: &str) -> Result<Box<dyn DatagramPort>, FabricError>;
    async fn request_reconfiguration(&self, change: SessionChange) -> Result<u64, FabricError>;
    async fn close(self: Box<Self>, reason: CloseReason) -> Result<(), FabricError>;
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ReceivedMessage {
    pub sender: ParticipantId,
    pub port: PortId,
    pub payload: Bytes,
}

#[async_trait]
pub trait MessagePort: Send + Sync {
    async fn send(&self, payload: Bytes) -> Result<(), FabricError>;
    async fn receive(&self) -> Result<ReceivedMessage, FabricError>;
}

pub struct StreamPort {
    pub reader: Pin<Box<dyn AsyncRead + Send>>,
    pub writer: Pin<Box<dyn AsyncWrite + Send>>,
}

#[async_trait]
pub trait DatagramPort: Send + Sync {
    fn try_send(&self, payload: Bytes) -> Result<(), FabricError>;
    async fn receive(&self) -> Result<ReceivedMessage, FabricError>;
}
