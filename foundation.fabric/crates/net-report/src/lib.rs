//! Network conditions probing ("netcheck") for fabric links.
//!
//! Modeled on iroh's `net_report` module but reimplemented for fabric on top of
//! vanilla `quinn`. The job of this crate is to answer, quickly and repeatedly:
//!
//! - What is our server-reflexive (public) transport address, on IPv4 and IPv6?
//! - Does our NAT map the same local socket to a stable public `ip:port` across
//!   different destinations, or does the mapping vary (symmetric NAT)? The answer
//!   decides whether UDP hole punching can succeed or whether we must relay.
//! - Which relay/STUN server is closest by round-trip time?
//!
//! The report this produces feeds the magic socket's path selection and the
//! hole-punch coordinator.
//!
//! ## Build phases
//! - **Phase 0 (this file's [`stun`] module):** the STUN binding request/response
//!   wire codec — just enough to learn a reflexive address from a server.
//! - Phase 1: the probe engine + [`NetReport`] assembly.
//! - Later phases live in the magic socket crate.

pub mod probe;
pub mod report;
pub mod stun;

pub use report::NetReport;
