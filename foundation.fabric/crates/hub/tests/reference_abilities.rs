use bytes::Bytes;
use fabric_clock::{Barrier, BarrierPolicy, BarrierResult, BarrierSpec};
use fabric_core::{BarrierId, DeviceId, GroupInstant, ParticipantId, SessionId};
use fabric_link::{FabricLink, FaultConfig, IncomingStream, MemoryLink, StreamOpen};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

fn stream_header() -> StreamOpen {
    StreamOpen {
        session_id: SessionId::new(),
        epoch: 1,
        channel_id: fabric_core::ChannelId::new(),
        sender: ParticipantId::new(),
        destination_binding: [0; 16],
        flags: 0,
        e2ee_header: Bytes::new(),
    }
}

#[tokio::test]
async fn echo_reliable_message_and_byte_stream_transfer() {
    let (left, right) = MemoryLink::pair(
        DeviceId([1; 32]),
        DeviceId([2; 32]),
        FaultConfig::default(),
        FaultConfig::default(),
    );
    left.send_control(Bytes::from_static(b"echo"))
        .await
        .unwrap();
    assert_eq!(
        right.receive_control().await.unwrap(),
        Bytes::from_static(b"echo")
    );

    let mut writer = left.open_uni(stream_header()).await.unwrap();
    writer.write_all(b"opaque byte stream").await.unwrap();
    writer.shutdown().await.unwrap();
    let IncomingStream::Uni(_, mut reader) = right.accept_stream().await.unwrap() else {
        panic!("uni stream expected")
    };
    let mut received = Vec::new();
    reader.read_to_end(&mut received).await.unwrap();
    assert_eq!(received, b"opaque byte stream");
}

#[tokio::test]
async fn latest_state_datagram_and_timed_tick_barrier() {
    let (left, right) = MemoryLink::pair(
        DeviceId([1; 32]),
        DeviceId([2; 32]),
        FaultConfig::default(),
        FaultConfig::default(),
    );
    left.try_send_datagram(Bytes::from_static(b"latest-state"))
        .unwrap();
    assert_eq!(
        right.receive_datagram().await.unwrap(),
        Bytes::from_static(b"latest-state")
    );

    let participants: Vec<_> = (0..4).map(|_| ParticipantId::new()).collect();
    let mut barrier = Barrier::new(BarrierSpec {
        id: BarrierId::new(),
        session_id: SessionId::new(),
        epoch: 1,
        participants: participants.clone(),
        activate_at: GroupInstant(10_000),
        ready_deadline: GroupInstant(9_000),
        context: b"timed-tick".to_vec(),
        policy: BarrierPolicy::AllRequired,
        max_lateness_ns: 100,
    });
    barrier.prepare(1, 10, 50).unwrap();
    for participant in participants {
        barrier.ready(participant, 10).unwrap();
    }
    assert_eq!(
        barrier.decide(GroupInstant(9_000)).unwrap(),
        BarrierResult::Commit {
            activate_at: GroupInstant(10_000)
        }
    );
}
