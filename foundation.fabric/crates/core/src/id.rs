//! Strongly typed identifiers.

use uuid::Uuid;

macro_rules! uuid_id {
    ($name:ident) => {
        #[repr(transparent)]
        #[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
        #[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
        pub struct $name(pub Uuid);

        impl $name {
            #[must_use]
            pub fn new() -> Self {
                Self(Uuid::new_v4())
            }
        }

        impl Default for $name {
            fn default() -> Self {
                Self::new()
            }
        }
    };
}

uuid_id!(SessionId);
uuid_id!(AbilityInstanceId);
uuid_id!(RequirementId);
uuid_id!(ParticipantId);
uuid_id!(ChannelId);
uuid_id!(PortId);
uuid_id!(PolicySnapshotId);
uuid_id!(ClockDomainId);
uuid_id!(BarrierId);
uuid_id!(InvitationId);
uuid_id!(ConnectionId);
uuid_id!(OperationId);
uuid_id!(CapabilityGrantId);

#[repr(transparent)]
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct DeviceId(pub [u8; 32]);
