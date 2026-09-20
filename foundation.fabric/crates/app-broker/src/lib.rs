//! Bounded local demo broker used by the desktop applications.

use std::{collections::BTreeMap, sync::Arc, time::Duration};

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::{Mutex, mpsc, oneshot, watch},
};

pub const DEFAULT_BROKER_ADDRESS: &str = "127.0.0.1:44331";
pub const LOCAL_DEVICE_ID: &str = "local";
const MAX_FRAME_BYTES: usize = 1024 * 1024;
const MAX_ABILITY_BYTES: usize = 255;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Message {
    Register {
        ability: String,
    },
    Registered {
        ability: String,
    },
    Invoke {
        request_id: u64,
        target_device: String,
        ability: String,
        payload: Vec<u8>,
    },
    OpenStream {
        request_id: u64,
        target_device: String,
        ability: String,
        stream_name: String,
    },
    StreamOpen {
        stream_id: u64,
        ability: String,
        stream_name: String,
    },
    StreamReady {
        stream_id: u64,
    },
    AttachStream {
        stream_id: u64,
        role: StreamRole,
    },
    Invocation {
        request_id: u64,
        payload: Vec<u8>,
    },
    Complete {
        request_id: u64,
        payload: Vec<u8>,
    },
    Result {
        request_id: u64,
        payload: Vec<u8>,
    },
    Error {
        request_id: u64,
        message: String,
    },
    ListDevices,
    Devices {
        devices: Vec<DeviceInfo>,
    },
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum StreamRole {
    Requester,
    Provider,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct DeviceInfo {
    pub id: String,
    pub label: String,
    pub paired: bool,
    pub online: bool,
}

#[async_trait]
pub trait RemoteBackend: Send + Sync {
    async fn devices(&self) -> Result<Vec<DeviceInfo>, BrokerError>;

    async fn invoke(
        &self,
        target_device: &str,
        request_id: u64,
        ability: &str,
        payload: Vec<u8>,
    ) -> Result<Vec<u8>, BrokerError>;

    async fn open_stream(
        &self,
        target_device: &str,
        request_id: u64,
        ability: &str,
        stream_name: &str,
    ) -> Result<Box<dyn BrokerByteStream>, BrokerError>;
}

pub trait BrokerByteStream: AsyncRead + AsyncWrite + Unpin + Send {}

impl<T> BrokerByteStream for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

struct LocalOnlyBackend;

#[async_trait]
impl RemoteBackend for LocalOnlyBackend {
    async fn devices(&self) -> Result<Vec<DeviceInfo>, BrokerError> {
        Ok(Vec::new())
    }

    async fn invoke(
        &self,
        _target_device: &str,
        _request_id: u64,
        _ability: &str,
        _payload: Vec<u8>,
    ) -> Result<Vec<u8>, BrokerError> {
        Err(BrokerError::DeviceUnavailable)
    }

    async fn open_stream(
        &self,
        _target_device: &str,
        _request_id: u64,
        _ability: &str,
        _stream_name: &str,
    ) -> Result<Box<dyn BrokerByteStream>, BrokerError> {
        Err(BrokerError::DeviceUnavailable)
    }
}

#[derive(Debug, Error)]
pub enum BrokerError {
    #[error("broker I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("broker message is malformed")]
    InvalidMessage,
    #[error("broker frame exceeds the configured limit")]
    FrameTooLarge,
    #[error("ability provider is unavailable")]
    ProviderUnavailable,
    #[error("target device is unavailable")]
    DeviceUnavailable,
    #[error("broker request timed out")]
    Timeout,
    #[error("broker connection closed")]
    Closed,
    #[error("broker rejected the request: {0}")]
    Rejected(String),
}

#[derive(Default)]
struct State {
    providers: BTreeMap<String, Provider>,
    pending: BTreeMap<u64, Pending>,
    next_invocation_id: u64,
    pending_streams: BTreeMap<u64, PendingStream>,
    next_stream_id: u64,
}

#[derive(Clone)]
struct Provider {
    connection_id: u64,
    outbound: mpsc::Sender<Message>,
}

struct Pending {
    provider_connection_id: u64,
    completion: oneshot::Sender<Vec<u8>>,
}

struct PendingStream {
    requester: Option<Box<dyn BrokerByteStream>>,
    provider: Option<Box<dyn BrokerByteStream>>,
}

pub struct Broker {
    listener: TcpListener,
    state: Arc<Mutex<State>>,
    backend: Arc<dyn RemoteBackend>,
}

impl Broker {
    pub async fn bind(address: &str) -> Result<Self, BrokerError> {
        Self::bind_with_backend(address, Arc::new(LocalOnlyBackend)).await
    }

    pub async fn bind_with_backend(
        address: &str,
        backend: Arc<dyn RemoteBackend>,
    ) -> Result<Self, BrokerError> {
        Ok(Self {
            listener: TcpListener::bind(address).await?,
            state: Arc::new(Mutex::new(State::default())),
            backend,
        })
    }

    /// The address the broker is listening on (useful when bound to port 0).
    pub fn local_addr(&self) -> Result<std::net::SocketAddr, BrokerError> {
        Ok(self.listener.local_addr()?)
    }

    pub async fn run(self, mut shutdown: watch::Receiver<bool>) -> Result<(), BrokerError> {
        let mut next_connection_id = 1_u64;
        loop {
            let stream = tokio::select! {
                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow() { break; }
                    continue;
                }
                accepted = self.listener.accept() => accepted?.0,
            };
            let connection_id = next_connection_id;
            next_connection_id = next_connection_id
                .checked_add(1)
                .ok_or(BrokerError::Closed)?;
            let state = Arc::clone(&self.state);
            let backend = Arc::clone(&self.backend);
            tokio::spawn(async move {
                let _ = serve_connection(stream, connection_id, state, backend).await;
            });
        }
        Ok(())
    }
}

async fn serve_connection(
    mut stream: TcpStream,
    connection_id: u64,
    state: Arc<Mutex<State>>,
    backend: Arc<dyn RemoteBackend>,
) -> Result<(), BrokerError> {
    stream.set_nodelay(true)?;
    let first = read_message(&mut stream).await?;
    if let Message::AttachStream { stream_id, role } = first {
        attach_stream(stream_id, role, Box::new(stream), &state).await?;
        return Ok(());
    }
    let (mut reader, mut writer) = stream.into_split();
    let (outbound_tx, mut outbound_rx) = mpsc::channel::<Message>(64);
    let writer_task = tokio::spawn(async move {
        while let Some(message) = outbound_rx.recv().await {
            write_message(&mut writer, &message).await?;
        }
        Ok::<(), BrokerError>(())
    });
    let mut registered = Vec::new();
    let mut next_message = Some(first);
    loop {
        let message = if let Some(message) = next_message.take() {
            message
        } else {
            match read_message(&mut reader).await {
                Ok(message) => message,
                Err(BrokerError::Closed) => break,
                Err(error) => return Err(error),
            }
        };
        match message {
            Message::Register { ability } => {
                register_provider(
                    ability,
                    connection_id,
                    &state,
                    &outbound_tx,
                    &mut registered,
                )
                .await?;
            }
            Message::Invoke {
                request_id,
                target_device,
                ability,
                payload,
            } => {
                if target_device == LOCAL_DEVICE_ID {
                    dispatch_invocation(request_id, ability, payload, &state, &outbound_tx).await?;
                } else {
                    dispatch_remote_invocation(
                        request_id,
                        target_device,
                        ability,
                        payload,
                        Arc::clone(&backend),
                        outbound_tx.clone(),
                    )?;
                }
            }
            Message::OpenStream {
                request_id,
                target_device,
                ability,
                stream_name,
            } => {
                if target_device == LOCAL_DEVICE_ID {
                    open_local_stream(
                        request_id,
                        ability,
                        stream_name,
                        connection_id,
                        &state,
                        &outbound_tx,
                    )
                    .await?;
                } else {
                    open_remote_broker_stream(
                        request_id,
                        target_device,
                        ability,
                        stream_name,
                        Arc::clone(&backend),
                        &state,
                        &outbound_tx,
                    )?;
                }
            }
            Message::Complete {
                request_id,
                payload,
            } => {
                complete_invocation(request_id, payload, connection_id, &state).await?;
            }
            Message::ListDevices => {
                let mut devices = vec![DeviceInfo {
                    id: LOCAL_DEVICE_ID.into(),
                    label: "This device".into(),
                    paired: true,
                    online: true,
                }];
                devices.extend(backend.devices().await?);
                outbound_tx
                    .send(Message::Devices { devices })
                    .await
                    .map_err(|_| BrokerError::Closed)?;
            }
            _ => return Err(BrokerError::InvalidMessage),
        }
    }
    {
        let mut state = state.lock().await;
        for ability in registered {
            if state
                .providers
                .get(&ability)
                .is_some_and(|provider| provider.connection_id == connection_id)
            {
                state.providers.remove(&ability);
            }
        }
    }
    drop(outbound_tx);
    let _ = writer_task.await;
    Ok(())
}

fn dispatch_remote_invocation(
    request_id: u64,
    target_device: String,
    ability: String,
    payload: Vec<u8>,
    backend: Arc<dyn RemoteBackend>,
    requester: mpsc::Sender<Message>,
) -> Result<(), BrokerError> {
    validate_ability(&ability)?;
    validate_payload(&payload)?;
    tokio::spawn(async move {
        let message = match tokio::time::timeout(
            REQUEST_TIMEOUT,
            backend.invoke(&target_device, request_id, &ability, payload),
        )
        .await
        {
            Ok(Ok(payload)) => Message::Result {
                request_id,
                payload,
            },
            Ok(Err(error)) => Message::Error {
                request_id,
                message: error.to_string(),
            },
            Err(_) => Message::Error {
                request_id,
                message: "remote device timed out".into(),
            },
        };
        let _ = requester.send(message).await;
    });
    Ok(())
}

async fn register_provider(
    ability: String,
    connection_id: u64,
    state: &Arc<Mutex<State>>,
    outbound: &mpsc::Sender<Message>,
    registered: &mut Vec<String>,
) -> Result<(), BrokerError> {
    validate_ability(&ability)?;
    let mut locked = state.lock().await;
    if locked.providers.contains_key(&ability) {
        drop(locked);
        outbound
            .send(Message::Error {
                request_id: 0,
                message: "ability already has a provider".into(),
            })
            .await
            .map_err(|_| BrokerError::Closed)?;
        return Ok(());
    }
    locked.providers.insert(
        ability.clone(),
        Provider {
            connection_id,
            outbound: outbound.clone(),
        },
    );
    drop(locked);
    registered.push(ability.clone());
    outbound
        .send(Message::Registered { ability })
        .await
        .map_err(|_| BrokerError::Closed)
}

async fn dispatch_invocation(
    request_id: u64,
    ability: String,
    payload: Vec<u8>,
    state: &Arc<Mutex<State>>,
    requester: &mpsc::Sender<Message>,
) -> Result<(), BrokerError> {
    validate_payload(&payload)?;
    let (invocation_id, provider, result_rx) = {
        let mut locked = state.lock().await;
        let Some(provider) = locked.providers.get(&ability).cloned() else {
            drop(locked);
            requester
                .send(Message::Error {
                    request_id,
                    message: "ability provider is unavailable".into(),
                })
                .await
                .map_err(|_| BrokerError::Closed)?;
            return Ok(());
        };
        locked.next_invocation_id = locked
            .next_invocation_id
            .checked_add(1)
            .ok_or(BrokerError::Closed)?;
        let invocation_id = locked.next_invocation_id;
        let (result_tx, result_rx) = oneshot::channel();
        locked.pending.insert(
            invocation_id,
            Pending {
                provider_connection_id: provider.connection_id,
                completion: result_tx,
            },
        );
        (invocation_id, provider, result_rx)
    };
    provider
        .outbound
        .send(Message::Invocation {
            request_id: invocation_id,
            payload,
        })
        .await
        .map_err(|_| BrokerError::ProviderUnavailable)?;
    let outbound = requester.clone();
    let pending = Arc::clone(state);
    tokio::spawn(async move {
        let response = tokio::time::timeout(REQUEST_TIMEOUT, result_rx).await;
        pending.lock().await.pending.remove(&invocation_id);
        let message = match response {
            Ok(Ok(payload)) => Message::Result {
                request_id,
                payload,
            },
            _ => Message::Error {
                request_id,
                message: "provider timed out".into(),
            },
        };
        let _ = outbound.send(message).await;
    });
    Ok(())
}

async fn open_local_stream(
    request_id: u64,
    ability: String,
    stream_name: String,
    _requester_connection_id: u64,
    state: &Arc<Mutex<State>>,
    requester: &mpsc::Sender<Message>,
) -> Result<(), BrokerError> {
    validate_ability(&ability)?;
    validate_stream_name(&stream_name)?;
    let (stream_id, provider) = {
        let mut locked = state.lock().await;
        let Some(provider) = locked.providers.get(&ability).cloned() else {
            drop(locked);
            requester
                .send(Message::Error {
                    request_id,
                    message: "ability provider is unavailable".into(),
                })
                .await
                .map_err(|_| BrokerError::Closed)?;
            return Ok(());
        };
        locked.next_stream_id = locked
            .next_stream_id
            .checked_add(1)
            .ok_or(BrokerError::Closed)?;
        let stream_id = locked.next_stream_id;
        locked.pending_streams.insert(
            stream_id,
            PendingStream {
                requester: None,
                provider: None,
            },
        );
        (stream_id, provider)
    };
    provider
        .outbound
        .send(Message::StreamOpen {
            stream_id,
            ability,
            stream_name,
        })
        .await
        .map_err(|_| BrokerError::ProviderUnavailable)?;
    requester
        .send(Message::StreamReady { stream_id })
        .await
        .map_err(|_| BrokerError::Closed)
}

fn open_remote_broker_stream(
    request_id: u64,
    target_device: String,
    ability: String,
    stream_name: String,
    backend: Arc<dyn RemoteBackend>,
    state: &Arc<Mutex<State>>,
    requester: &mpsc::Sender<Message>,
) -> Result<(), BrokerError> {
    validate_ability(&ability)?;
    validate_stream_name(&stream_name)?;
    let state = Arc::clone(state);
    let requester = requester.clone();
    tokio::spawn(async move {
        let message = match backend
            .open_stream(&target_device, request_id, &ability, &stream_name)
            .await
        {
            Ok(stream) => {
                let stream_id = {
                    let mut locked = state.lock().await;
                    locked.next_stream_id = locked.next_stream_id.checked_add(1).unwrap_or(1);
                    let stream_id = locked.next_stream_id;
                    locked.pending_streams.insert(
                        stream_id,
                        PendingStream {
                            requester: None,
                            provider: Some(stream),
                        },
                    );
                    stream_id
                };
                Message::StreamReady { stream_id }
            }
            Err(error) => Message::Error {
                request_id,
                message: error.to_string(),
            },
        };
        let _ = requester.send(message).await;
    });
    Ok(())
}

async fn attach_stream(
    stream_id: u64,
    role: StreamRole,
    stream: Box<dyn BrokerByteStream>,
    state: &Arc<Mutex<State>>,
) -> Result<(), BrokerError> {
    let pair = {
        let mut locked = state.lock().await;
        let pending = locked
            .pending_streams
            .get_mut(&stream_id)
            .ok_or(BrokerError::DeviceUnavailable)?;
        match role {
            StreamRole::Requester => {
                if pending.requester.is_some() {
                    return Err(BrokerError::InvalidMessage);
                }
                pending.requester = Some(stream);
            }
            StreamRole::Provider => {
                if pending.provider.is_some() {
                    return Err(BrokerError::InvalidMessage);
                }
                pending.provider = Some(stream);
            }
        }
        if pending.requester.is_some() && pending.provider.is_some() {
            let mut pending = locked
                .pending_streams
                .remove(&stream_id)
                .ok_or(BrokerError::DeviceUnavailable)?;
            Some((
                pending
                    .requester
                    .take()
                    .ok_or(BrokerError::DeviceUnavailable)?,
                pending
                    .provider
                    .take()
                    .ok_or(BrokerError::DeviceUnavailable)?,
            ))
        } else {
            None
        }
    };
    if let Some((mut requester, mut provider)) = pair {
        tokio::spawn(async move {
            let _ = tokio::io::copy_bidirectional(&mut *requester, &mut *provider).await;
        });
    }
    Ok(())
}

async fn complete_invocation(
    request_id: u64,
    payload: Vec<u8>,
    connection_id: u64,
    state: &Arc<Mutex<State>>,
) -> Result<(), BrokerError> {
    validate_payload(&payload)?;
    let mut locked = state.lock().await;
    if locked
        .pending
        .get(&request_id)
        .is_some_and(|pending| pending.provider_connection_id != connection_id)
    {
        return Err(BrokerError::InvalidMessage);
    }
    if let Some(pending) = locked.pending.remove(&request_id) {
        let _ = pending.completion.send(payload);
    }
    Ok(())
}

pub struct EchoProvider {
    stream: TcpStream,
    broker_address: String,
}

impl EchoProvider {
    pub async fn connect(address: &str, ability: &str) -> Result<Self, BrokerError> {
        validate_ability(ability)?;
        let mut stream = TcpStream::connect(address).await?;
        stream.set_nodelay(true)?;
        write_message(
            &mut stream,
            &Message::Register {
                ability: ability.into(),
            },
        )
        .await?;
        match read_message(&mut stream).await? {
            Message::Registered {
                ability: registered,
            } if registered == ability => Ok(Self {
                stream,
                broker_address: address.into(),
            }),
            Message::Error { message, .. } => Err(BrokerError::Rejected(message)),
            _ => Err(BrokerError::InvalidMessage),
        }
    }

    pub async fn serve(mut self) -> Result<(), BrokerError> {
        loop {
            match read_message(&mut self.stream).await? {
                Message::Invocation {
                    request_id,
                    payload,
                } => {
                    write_message(
                        &mut self.stream,
                        &Message::Complete {
                            request_id,
                            payload,
                        },
                    )
                    .await?;
                }
                Message::StreamOpen { stream_id, .. } => {
                    let address = self.broker_address.clone();
                    tokio::spawn(async move {
                        if let Ok(stream) = attach_provider_stream(&address, stream_id).await {
                            let _ = echo_stream(stream).await;
                        }
                    });
                }
                _ => return Err(BrokerError::InvalidMessage),
            }
        }
    }
}

pub async fn invoke(
    address: &str,
    request_id: u64,
    ability: &str,
    payload: Vec<u8>,
) -> Result<Vec<u8>, BrokerError> {
    invoke_on(address, request_id, LOCAL_DEVICE_ID, ability, payload).await
}

pub async fn invoke_on(
    address: &str,
    request_id: u64,
    target_device: &str,
    ability: &str,
    payload: Vec<u8>,
) -> Result<Vec<u8>, BrokerError> {
    validate_ability(ability)?;
    validate_payload(&payload)?;
    let mut stream = TcpStream::connect(address).await?;
    stream.set_nodelay(true)?;
    write_message(
        &mut stream,
        &Message::Invoke {
            request_id,
            target_device: target_device.into(),
            ability: ability.into(),
            payload,
        },
    )
    .await?;
    match read_message(&mut stream).await? {
        Message::Result {
            request_id: response_id,
            payload,
        } if response_id == request_id => Ok(payload),
        Message::Error {
            request_id: response_id,
            message,
        } if response_id == request_id => Err(BrokerError::Rejected(message)),
        _ => Err(BrokerError::InvalidMessage),
    }
}

pub async fn open_stream_on(
    address: &str,
    request_id: u64,
    target_device: &str,
    ability: &str,
    stream_name: &str,
) -> Result<TcpStream, BrokerError> {
    validate_ability(ability)?;
    validate_stream_name(stream_name)?;
    let mut control = TcpStream::connect(address).await?;
    control.set_nodelay(true)?;
    write_message(
        &mut control,
        &Message::OpenStream {
            request_id,
            target_device: target_device.into(),
            ability: ability.into(),
            stream_name: stream_name.into(),
        },
    )
    .await?;
    let stream_id = match read_message(&mut control).await? {
        Message::StreamReady { stream_id } => stream_id,
        Message::Error {
            request_id: response_id,
            message,
        } if response_id == request_id => return Err(BrokerError::Rejected(message)),
        _ => return Err(BrokerError::InvalidMessage),
    };
    attach_stream_endpoint(address, stream_id, StreamRole::Requester).await
}

pub async fn attach_provider_stream(
    address: &str,
    stream_id: u64,
) -> Result<TcpStream, BrokerError> {
    attach_stream_endpoint(address, stream_id, StreamRole::Provider).await
}

async fn attach_stream_endpoint(
    address: &str,
    stream_id: u64,
    role: StreamRole,
) -> Result<TcpStream, BrokerError> {
    let mut stream = TcpStream::connect(address).await?;
    stream.set_nodelay(true)?;
    write_message(&mut stream, &Message::AttachStream { stream_id, role }).await?;
    Ok(stream)
}

pub async fn list_devices(address: &str) -> Result<Vec<DeviceInfo>, BrokerError> {
    let mut stream = TcpStream::connect(address).await?;
    stream.set_nodelay(true)?;
    write_message(&mut stream, &Message::ListDevices).await?;
    match read_message(&mut stream).await? {
        Message::Devices { devices } => Ok(devices),
        Message::Error { message, .. } => Err(BrokerError::Rejected(message)),
        _ => Err(BrokerError::InvalidMessage),
    }
}

pub async fn open_remote_stream<S>(
    stream: &mut S,
    request_id: u64,
    ability: &str,
    stream_name: &str,
) -> Result<(), BrokerError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    validate_ability(ability)?;
    validate_stream_name(stream_name)?;
    write_message(
        stream,
        &Message::OpenStream {
            request_id,
            target_device: LOCAL_DEVICE_ID.into(),
            ability: ability.into(),
            stream_name: stream_name.into(),
        },
    )
    .await?;
    match read_message(stream).await? {
        Message::StreamReady { .. } => Ok(()),
        Message::Error {
            request_id: response_id,
            message,
        } if response_id == request_id => Err(BrokerError::Rejected(message)),
        _ => Err(BrokerError::InvalidMessage),
    }
}

pub async fn invoke_remote_stream<S>(
    stream: &mut S,
    request_id: u64,
    ability: &str,
    payload: Vec<u8>,
) -> Result<Vec<u8>, BrokerError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    validate_ability(ability)?;
    validate_payload(&payload)?;
    write_message(
        stream,
        &Message::Invoke {
            request_id,
            target_device: LOCAL_DEVICE_ID.into(),
            ability: ability.into(),
            payload,
        },
    )
    .await?;
    match read_message(stream).await? {
        Message::Result {
            request_id: response_id,
            payload,
        } if response_id == request_id => Ok(payload),
        Message::Error {
            request_id: response_id,
            message,
        } if response_id == request_id => Err(BrokerError::Rejected(message)),
        _ => Err(BrokerError::InvalidMessage),
    }
}

pub async fn serve_remote_stream<S>(mut stream: S, broker_address: &str) -> Result<(), BrokerError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    match read_message(&mut stream).await? {
        Message::Invoke {
            request_id,
            target_device,
            ability,
            payload,
        } if target_device == LOCAL_DEVICE_ID => {
            validate_ability(&ability)?;
            validate_payload(&payload)?;
            let result = invoke(broker_address, request_id, &ability, payload)
                .await
                .map_err(|error| error.to_string());
            write_remote_result(&mut stream, request_id, result).await
        }
        Message::OpenStream {
            request_id,
            target_device,
            ability,
            stream_name,
        } if target_device == LOCAL_DEVICE_ID => {
            validate_ability(&ability)?;
            validate_stream_name(&stream_name)?;
            let mut local = open_stream_on(
                broker_address,
                request_id,
                LOCAL_DEVICE_ID,
                &ability,
                &stream_name,
            )
            .await?;
            write_message(
                &mut stream,
                &Message::StreamReady {
                    stream_id: request_id,
                },
            )
            .await?;
            tokio::io::copy_bidirectional(&mut stream, &mut local).await?;
            Ok(())
        }
        _ => Err(BrokerError::InvalidMessage),
    }
}

pub struct RemoteInvocation {
    pub request_id: u64,
    pub ability: String,
    pub payload: Vec<u8>,
}

pub struct RemoteStreamOpen {
    pub request_id: u64,
    pub ability: String,
    pub stream_name: String,
}

pub enum RemoteRequest {
    Invocation(RemoteInvocation),
    StreamOpen(RemoteStreamOpen),
}

pub async fn read_remote_request<S>(stream: &mut S) -> Result<RemoteRequest, BrokerError>
where
    S: AsyncRead + Unpin,
{
    match read_message(stream).await? {
        Message::Invoke {
            request_id,
            target_device,
            ability,
            payload,
        } if target_device == LOCAL_DEVICE_ID => {
            validate_ability(&ability)?;
            validate_payload(&payload)?;
            Ok(RemoteRequest::Invocation(RemoteInvocation {
                request_id,
                ability,
                payload,
            }))
        }
        Message::OpenStream {
            request_id,
            target_device,
            ability,
            stream_name,
        } if target_device == LOCAL_DEVICE_ID => {
            validate_ability(&ability)?;
            validate_stream_name(&stream_name)?;
            Ok(RemoteRequest::StreamOpen(RemoteStreamOpen {
                request_id,
                ability,
                stream_name,
            }))
        }
        _ => Err(BrokerError::InvalidMessage),
    }
}

pub async fn read_remote_invocation<S>(stream: &mut S) -> Result<RemoteInvocation, BrokerError>
where
    S: AsyncRead + Unpin,
{
    match read_remote_request(stream).await? {
        RemoteRequest::Invocation(request) => Ok(request),
        RemoteRequest::StreamOpen(_) => Err(BrokerError::InvalidMessage),
    }
}

async fn echo_stream(mut stream: TcpStream) -> Result<(), BrokerError> {
    let mut buffer = vec![0; 64 * 1024];
    loop {
        let read = stream.read(&mut buffer).await?;
        if read == 0 {
            return Ok(());
        }
        stream.write_all(&buffer[..read]).await?;
        stream.flush().await?;
    }
}

pub async fn write_remote_result<S>(
    stream: &mut S,
    request_id: u64,
    result: Result<Vec<u8>, String>,
) -> Result<(), BrokerError>
where
    S: AsyncWrite + Unpin,
{
    let message = match result {
        Ok(payload) => {
            validate_payload(&payload)?;
            Message::Result {
                request_id,
                payload,
            }
        }
        Err(message) => Message::Error {
            request_id,
            message,
        },
    };
    write_message(stream, &message).await
}

pub async fn accept_remote_stream<S>(stream: &mut S, request_id: u64) -> Result<(), BrokerError>
where
    S: AsyncWrite + Unpin,
{
    write_message(
        stream,
        &Message::StreamReady {
            stream_id: request_id,
        },
    )
    .await
}

/// Refuse a remote stream-open request with an explicit error frame, so the
/// requester's `open_remote_stream` fails with [`BrokerError::Rejected`]
/// instead of receiving a pipe that silently EOFs.
pub async fn reject_remote_stream<S>(
    stream: &mut S,
    request_id: u64,
    message: &str,
) -> Result<(), BrokerError>
where
    S: AsyncWrite + Unpin,
{
    write_message(
        stream,
        &Message::Error {
            request_id,
            message: message.into(),
        },
    )
    .await
}

async fn read_message<R: AsyncRead + Unpin>(reader: &mut R) -> Result<Message, BrokerError> {
    let length = reader.read_u32().await.map_err(|error| {
        if error.kind() == std::io::ErrorKind::UnexpectedEof {
            BrokerError::Closed
        } else {
            BrokerError::Io(error)
        }
    })? as usize;
    if length == 0 || length > MAX_FRAME_BYTES {
        return Err(BrokerError::FrameTooLarge);
    }
    let mut encoded = vec![0; length];
    reader.read_exact(&mut encoded).await?;
    serde_json::from_slice(&encoded).map_err(|_| BrokerError::InvalidMessage)
}

async fn write_message<W: AsyncWrite + Unpin>(
    writer: &mut W,
    message: &Message,
) -> Result<(), BrokerError> {
    let encoded = serde_json::to_vec(message).map_err(|_| BrokerError::InvalidMessage)?;
    if encoded.len() > MAX_FRAME_BYTES {
        return Err(BrokerError::FrameTooLarge);
    }
    writer
        .write_u32(u32::try_from(encoded.len()).map_err(|_| BrokerError::FrameTooLarge)?)
        .await?;
    writer.write_all(&encoded).await?;
    writer.flush().await?;
    Ok(())
}

fn validate_ability(ability: &str) -> Result<(), BrokerError> {
    if ability.is_empty()
        || ability.len() > MAX_ABILITY_BYTES
        || !ability
            .bytes()
            .all(|value| value.is_ascii_alphanumeric() || matches!(value, b'.' | b'-' | b'_'))
    {
        return Err(BrokerError::InvalidMessage);
    }
    Ok(())
}

fn validate_stream_name(stream_name: &str) -> Result<(), BrokerError> {
    if stream_name.is_empty()
        || stream_name.len() > MAX_ABILITY_BYTES
        || !stream_name
            .bytes()
            .all(|value| value.is_ascii_alphanumeric() || matches!(value, b'.' | b'-' | b'_'))
    {
        return Err(BrokerError::InvalidMessage);
    }
    Ok(())
}

fn validate_payload(payload: &[u8]) -> Result<(), BrokerError> {
    if payload.len() > MAX_FRAME_BYTES / 2 {
        return Err(BrokerError::FrameTooLarge);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn provider_round_trip_uses_broker() {
        let broker = Broker::bind("127.0.0.1:0").await.unwrap();
        let address = broker.listener.local_addr().unwrap().to_string();
        let (shutdown, shutdown_rx) = watch::channel(false);
        let broker_task = tokio::spawn(broker.run(shutdown_rx));
        let provider = EchoProvider::connect(&address, "com.example.echo")
            .await
            .unwrap();
        let provider_task = tokio::spawn(provider.serve());
        let response = invoke(&address, 7, "com.example.echo", b"hello".to_vec())
            .await
            .unwrap();
        assert_eq!(response, b"hello");
        shutdown.send(true).unwrap();
        broker_task.await.unwrap().unwrap();
        provider_task.abort();
    }

    #[tokio::test]
    async fn local_stream_uses_data_connection_not_binder_sized_frames() {
        let broker = Broker::bind("127.0.0.1:0").await.unwrap();
        let address = broker.listener.local_addr().unwrap().to_string();
        let (shutdown, shutdown_rx) = watch::channel(false);
        let broker_task = tokio::spawn(broker.run(shutdown_rx));
        let provider = EchoProvider::connect(&address, "com.example.echo")
            .await
            .unwrap();
        let provider_task = tokio::spawn(provider.serve());

        let mut stream = open_stream_on(&address, 8, LOCAL_DEVICE_ID, "com.example.echo", "bulk")
            .await
            .unwrap();
        let payload = vec![7; 768 * 1024];
        stream.write_all(&payload).await.unwrap();
        stream.shutdown().await.unwrap();
        let mut echoed = Vec::new();
        stream.read_to_end(&mut echoed).await.unwrap();

        assert_eq!(echoed, payload);
        shutdown.send(true).unwrap();
        broker_task.await.unwrap().unwrap();
        provider_task.abort();
    }

    #[tokio::test]
    async fn remote_stream_round_trip_preserves_request_identity() {
        let (mut client, mut server) = tokio::io::duplex(64 * 1024);
        let service = tokio::spawn(async move {
            let request = read_remote_invocation(&mut server).await.unwrap();
            assert_eq!(request.request_id, 77);
            assert_eq!(request.ability, "com.example.echo");
            write_remote_result(&mut server, request.request_id, Ok(request.payload))
                .await
                .unwrap();
        });
        let response = invoke_remote_stream(
            &mut client,
            77,
            "com.example.echo",
            b"through-quic".to_vec(),
        )
        .await
        .unwrap();
        assert_eq!(response, b"through-quic");
        service.await.unwrap();
    }
}
