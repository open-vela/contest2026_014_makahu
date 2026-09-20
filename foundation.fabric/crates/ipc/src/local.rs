use std::{path::PathBuf, sync::Arc};

use bytes::Bytes;
use fabric_core::{ConnectionId, SessionId};
#[cfg(unix)]
use fabric_core::{OsSubject, Platform};
use fabric_identity::{OsCredentialAdapter, PeerCredentials};
use prost::Message;
use thiserror::Error;
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    sync::{Semaphore, watch},
};

use crate::{
    ApplicationBound, BindApplication, ClientHello, ConnectionQuota, Frame, FrameKind,
    IPC_PROTOCOL_VERSION, IpcError, IpcServer, MAX_CONTROL_FRAME_BYTES, MAX_IPC_FEATURES,
    MAX_IPC_STRING_BYTES, MAX_IPC_VERSIONS, Request, Response, ResponseCode, ServerHello, request,
};

#[derive(Debug, Error)]
pub enum LocalIpcError {
    #[error("local IPC I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("local IPC frame failed validation: {0}")]
    Frame(#[from] IpcError),
    #[error("local IPC protobuf payload is invalid")]
    InvalidPayload,
    #[error("local IPC handshake is invalid")]
    InvalidHandshake,
    #[error("local IPC endpoint is invalid")]
    InvalidEndpoint,
    #[error("local IPC platform credential lookup failed: {0}")]
    Credential(String),
}

pub struct LocalIpcListener<A> {
    server: Arc<IpcServer<A>>,
    quota: ConnectionQuota,
    maximum_connections: usize,
    #[cfg(windows)]
    pipe_name: PathBuf,
    #[cfg(windows)]
    next_pipe: tokio::net::windows::named_pipe::NamedPipeServer,
    #[cfg(unix)]
    socket_path: PathBuf,
    #[cfg(unix)]
    listener: tokio::net::UnixListener,
}

impl<A: OsCredentialAdapter + 'static> LocalIpcListener<A> {
    pub fn bind(
        endpoint: PathBuf,
        server: Arc<IpcServer<A>>,
        quota: ConnectionQuota,
        maximum_connections: usize,
    ) -> Result<Self, LocalIpcError> {
        if maximum_connections == 0 {
            return Err(LocalIpcError::InvalidEndpoint);
        }
        #[cfg(windows)]
        {
            let next_pipe = create_pipe(endpoint.as_os_str(), true)?;
            Ok(Self {
                server,
                quota,
                maximum_connections,
                pipe_name: endpoint,
                next_pipe,
            })
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;

            if endpoint.as_os_str().is_empty() || endpoint.exists() {
                return Err(LocalIpcError::InvalidEndpoint);
            }
            let listener = tokio::net::UnixListener::bind(&endpoint)?;
            std::fs::set_permissions(&endpoint, std::fs::Permissions::from_mode(0o600))?;
            Ok(Self {
                server,
                quota,
                maximum_connections,
                socket_path: endpoint,
                listener,
            })
        }
        #[cfg(not(any(windows, unix)))]
        {
            let _ = (endpoint, server, quota);
            Err(LocalIpcError::InvalidEndpoint)
        }
    }

    #[cfg_attr(not(windows), allow(unused_mut))]
    pub async fn run(mut self, mut shutdown: watch::Receiver<bool>) -> Result<(), LocalIpcError> {
        let permits = Arc::new(Semaphore::new(self.maximum_connections));
        loop {
            #[cfg(windows)]
            let accepted = tokio::select! {
                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow() { break; }
                    continue;
                }
                result = self.next_pipe.connect() => {
                    result?;
                    let connected = std::mem::replace(
                        &mut self.next_pipe,
                create_pipe(self.pipe_name.as_os_str(), false)?,
                    );
                    let credentials = windows_peer_credentials(&connected)?;
                    (connected, credentials)
                }
            };

            #[cfg(unix)]
            let accepted = tokio::select! {
                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow() { break; }
                    continue;
                }
                result = self.listener.accept() => {
                    let (stream, _) = result?;
                    let credentials = unix_peer_credentials(&stream)?;
                    (stream, credentials)
                }
            };

            let Ok(permit) = Arc::clone(&permits).try_acquire_owned() else {
                drop(accepted);
                continue;
            };
            let server = Arc::clone(&self.server);
            let quota = self.quota;
            let mut connection_shutdown = shutdown.clone();
            tokio::spawn(async move {
                let _permit = permit;
                tokio::select! {
                    _ = connection_shutdown.changed() => {}
                    _ = serve_connection(accepted.0, accepted.1, server, quota) => {}
                }
            });
        }
        #[cfg(unix)]
        if self.socket_path.exists() {
            std::fs::remove_file(&self.socket_path)?;
        }
        Ok(())
    }
}

#[must_use]
pub fn default_local_ipc_endpoint(device_tag: &str, state_directory: &std::path::Path) -> PathBuf {
    #[cfg(windows)]
    {
        let _ = state_directory;
        PathBuf::from(format!(r"\\.\pipe\device-fabric-{device_tag}"))
    }
    #[cfg(unix)]
    {
        let _ = device_tag;
        state_directory.join("fabric.sock")
    }
    #[cfg(not(any(windows, unix)))]
    {
        let _ = device_tag;
        state_directory.join("fabric.ipc")
    }
}

async fn serve_connection<S, A>(
    mut stream: S,
    credentials: PeerCredentials,
    server: Arc<IpcServer<A>>,
    quota: ConnectionQuota,
) -> Result<(), LocalIpcError>
where
    S: AsyncRead + AsyncWrite + Unpin,
    A: OsCredentialAdapter,
{
    let hello_frame = read_frame(&mut stream).await?;
    if hello_frame.kind != FrameKind::ClientHello || hello_frame.id != 0 {
        return Err(LocalIpcError::InvalidHandshake);
    }
    let hello =
        ClientHello::decode(hello_frame.payload).map_err(|_| LocalIpcError::InvalidPayload)?;
    validate_client_hello(&hello)?;

    let (connection_id, _) = server.connect_authenticated(&credentials, quota)?;
    let result = serve_bound_connection(&mut stream, &server, connection_id, quota).await;
    let _ = server.disconnect(connection_id);
    result
}

async fn serve_bound_connection<S, A>(
    stream: &mut S,
    server: &IpcServer<A>,
    connection_id: ConnectionId,
    quota: ConnectionQuota,
) -> Result<(), LocalIpcError>
where
    S: AsyncRead + AsyncWrite + Unpin,
    A: OsCredentialAdapter,
{
    write_message(
        stream,
        FrameKind::ServerHello,
        0,
        &ServerHello {
            selected_version: IPC_PROTOCOL_VERSION,
            connection_id: connection_id.0.as_bytes().to_vec(),
            maximum_offers: to_u32(quota.maximum_offers)?,
            maximum_requirements: to_u32(quota.maximum_requirements)?,
            maximum_sessions: to_u32(quota.maximum_sessions)?,
            maximum_buffered_bytes: quota.maximum_buffered_bytes,
            hub_features: vec!["request-response".into(), "server-events".into()],
        },
    )
    .await?;

    let bind_frame = read_frame(stream).await?;
    if bind_frame.kind != FrameKind::BindApplication || bind_frame.id != 0 {
        return Err(LocalIpcError::InvalidHandshake);
    }
    let bind =
        BindApplication::decode(bind_frame.payload).map_err(|_| LocalIpcError::InvalidPayload)?;
    if bind
        .declared_app_id
        .as_ref()
        .is_some_and(|value| value.len() > MAX_IPC_STRING_BYTES)
    {
        return Err(LocalIpcError::InvalidHandshake);
    }
    let principal = server.bind_connection(connection_id, bind.declared_app_id.as_deref())?;
    write_message(
        stream,
        FrameKind::ApplicationBound,
        0,
        &ApplicationBound {
            stable_app_id: principal.stable_app_id,
            publisher_id: principal.publisher_id,
            signing_digest: principal.signing_digest.map(Vec::from),
            os_subject: principal.os_subject.0,
        },
    )
    .await?;

    let mut last_request_id = 0;
    loop {
        let frame = match read_frame(stream).await {
            Ok(frame) => frame,
            Err(LocalIpcError::Io(error))
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::UnexpectedEof
                        | std::io::ErrorKind::BrokenPipe
                        | std::io::ErrorKind::ConnectionReset
                ) =>
            {
                return Ok(());
            }
            Err(error) => return Err(error),
        };
        if frame.kind != FrameKind::Request || frame.id == 0 || frame.id <= last_request_id {
            return Err(LocalIpcError::InvalidHandshake);
        }
        last_request_id = frame.id;
        let request = Request::decode(frame.payload).map_err(|_| LocalIpcError::InvalidPayload)?;
        let response = dispatch_request(server, connection_id, request);
        write_message(stream, FrameKind::Response, frame.id, &response).await?;
    }
}

fn dispatch_request<A: OsCredentialAdapter>(
    server: &IpcServer<A>,
    connection_id: ConnectionId,
    request: Request,
) -> Response {
    let result = match request.body {
        Some(request::Body::PublishOffer(encoded)) => serde_json::from_slice(&encoded)
            .map_err(|_| IpcError::TruncatedFrame)
            .and_then(|offer| {
                server
                    .publish_offer(connection_id, offer, now_ms())
                    .map(|id| id.0.as_bytes().to_vec())
            }),
        Some(request::Body::RegisterRequirement(encoded)) => serde_json::from_slice(&encoded)
            .map_err(|_| IpcError::TruncatedFrame)
            .and_then(|requirement| {
                server
                    .register_requirement(connection_id, requirement, now_ms())
                    .map(|id| id.0.as_bytes().to_vec())
            }),
        Some(request::Body::ProposeSession(encoded)) => serde_json::from_slice(&encoded)
            .map_err(|_| IpcError::TruncatedFrame)
            .and_then(|plan| {
                server
                    .propose_local_session(connection_id, plan)
                    .map(|id| id.0.as_bytes().to_vec())
            }),
        Some(request::Body::AcceptSessionId(encoded)) => {
            decode_session_id(&encoded).and_then(|id| {
                server
                    .accept_local_session(connection_id, id)
                    .and_then(|plan| {
                        plan.map_or_else(
                            || Ok(Vec::new()),
                            |plan| serde_json::to_vec(&plan).map_err(|_| IpcError::Poisoned),
                        )
                    })
            })
        }
        Some(request::Body::Ping(true)) => Ok(Vec::new()),
        Some(request::Body::Ping(false)) | None => Err(IpcError::UnsupportedMessage),
    };
    match result {
        Ok(body) => Response {
            code: ResponseCode::Ok as i32,
            body,
            safe_message: String::new(),
        },
        Err(error) => error_response(&error),
    }
}

fn error_response(error: &IpcError) -> Response {
    let (code, message) = match error {
        IpcError::FrameTooLarge | IpcError::QuotaExceeded => {
            (ResponseCode::ResourceExhausted, "resource limit exceeded")
        }
        IpcError::UnsupportedMessage => (ResponseCode::UnsupportedMessage, "unsupported request"),
        IpcError::ConnectionNotFound | IpcError::InvitationNotFound => {
            (ResponseCode::NotFound, "object was not found")
        }
        IpcError::PrincipalMismatch | IpcError::NotInvited | IpcError::Identity(_) => {
            (ResponseCode::PermissionDenied, "permission denied")
        }
        IpcError::TruncatedFrame | IpcError::TrailingFrameData | IpcError::Session(_) => {
            (ResponseCode::InvalidArgument, "invalid request")
        }
        IpcError::Poisoned | IpcError::Registry(_) => (ResponseCode::Internal, "internal error"),
    };
    Response {
        code: code as i32,
        body: Vec::new(),
        safe_message: message.into(),
    }
}

fn validate_client_hello(hello: &ClientHello) -> Result<(), LocalIpcError> {
    if hello.protocol_versions.is_empty()
        || hello.protocol_versions.len() > MAX_IPC_VERSIONS
        || !hello.protocol_versions.contains(&IPC_PROTOCOL_VERSION)
        || hello.sdk_version.is_empty()
        || hello.sdk_version.len() > MAX_IPC_STRING_BYTES
        || hello.requested_features.len() > MAX_IPC_FEATURES
        || hello
            .requested_features
            .iter()
            .any(|feature| feature.is_empty() || feature.len() > MAX_IPC_STRING_BYTES)
    {
        return Err(LocalIpcError::InvalidHandshake);
    }
    Ok(())
}

async fn read_frame<S: AsyncRead + Unpin>(stream: &mut S) -> Result<Frame, LocalIpcError> {
    let mut prefix = [0; 4];
    stream.read_exact(&mut prefix).await?;
    let length = u32::from_be_bytes(prefix) as usize;
    if !(9..=MAX_CONTROL_FRAME_BYTES).contains(&length) {
        return Err(IpcError::FrameTooLarge.into());
    }
    let mut encoded = Vec::with_capacity(4 + length);
    encoded.extend_from_slice(&prefix);
    encoded.resize(4 + length, 0);
    stream.read_exact(&mut encoded[4..]).await?;
    Ok(Frame::decode(&encoded)?)
}

async fn write_message<S: AsyncWrite + Unpin>(
    stream: &mut S,
    kind: FrameKind,
    id: u64,
    message: &impl Message,
) -> Result<(), LocalIpcError> {
    let frame = Frame {
        kind,
        id,
        payload: Bytes::from(message.encode_to_vec()),
    };
    stream.write_all(&frame.encode()?).await?;
    stream.flush().await?;
    Ok(())
}

fn decode_session_id(encoded: &[u8]) -> Result<SessionId, IpcError> {
    let value: [u8; 16] = encoded.try_into().map_err(|_| IpcError::TruncatedFrame)?;
    Ok(SessionId(uuid::Uuid::from_bytes(value)))
}

fn to_u32(value: usize) -> Result<u32, LocalIpcError> {
    u32::try_from(value).map_err(|_| LocalIpcError::InvalidEndpoint)
}

fn now_ms() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| {
            u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
        })
}

#[cfg(windows)]
fn create_pipe(
    endpoint: &std::ffi::OsStr,
    first: bool,
) -> Result<tokio::net::windows::named_pipe::NamedPipeServer, LocalIpcError> {
    let mut options = tokio::net::windows::named_pipe::ServerOptions::new();
    options
        .first_pipe_instance(first)
        .reject_remote_clients(true)
        .max_instances(254);
    options.create(endpoint).map_err(LocalIpcError::from)
}

#[cfg(windows)]
fn windows_peer_credentials(
    pipe: &tokio::net::windows::named_pipe::NamedPipeServer,
) -> Result<PeerCredentials, LocalIpcError> {
    windows_credentials::peer_credentials(pipe)
}

#[cfg(windows)]
mod windows_credentials {
    #![allow(unsafe_code)]

    use std::os::windows::io::AsRawHandle;

    use fabric_core::{OsSubject, Platform};
    use fabric_identity::PeerCredentials;
    use tokio::net::windows::named_pipe::NamedPipeServer;
    use windows::{
        Win32::{
            Foundation::{HANDLE, HLOCAL},
            Security::{GetTokenInformation, TOKEN_QUERY, TOKEN_USER, TokenUser},
            System::{
                Pipes::GetNamedPipeClientProcessId,
                Threading::{
                    OpenProcess, OpenProcessToken, PROCESS_NAME_WIN32,
                    PROCESS_QUERY_LIMITED_INFORMATION, QueryFullProcessImageNameW,
                },
            },
        },
        core::{Owned, PWSTR},
    };

    use super::LocalIpcError;

    pub fn peer_credentials(pipe: &NamedPipeServer) -> Result<PeerCredentials, LocalIpcError> {
        let pipe_handle = HANDLE(pipe.as_raw_handle());
        let mut process_id = 0;
        unsafe { GetNamedPipeClientProcessId(pipe_handle, &raw mut process_id) }
            .map_err(platform_error)?;
        let process = unsafe {
            Owned::new(
                OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, process_id)
                    .map_err(platform_error)?,
            )
        };
        let stable_app_id = process_image(&process)?;
        let os_subject = process_sid(&process)?;
        Ok(PeerCredentials {
            platform: Platform::Windows,
            stable_app_id,
            publisher_id: None,
            signing_digest: None,
            os_subject: OsSubject(os_subject),
        })
    }

    fn process_image(process: &HANDLE) -> Result<String, LocalIpcError> {
        let mut buffer = vec![0_u16; 32_768];
        let mut length = u32::try_from(buffer.len()).map_err(|_| LocalIpcError::InvalidEndpoint)?;
        unsafe {
            QueryFullProcessImageNameW(
                *process,
                PROCESS_NAME_WIN32,
                PWSTR(buffer.as_mut_ptr()),
                &raw mut length,
            )
        }
        .map_err(platform_error)?;
        buffer.truncate(usize::try_from(length).map_err(|_| LocalIpcError::InvalidEndpoint)?);
        String::from_utf16(&buffer).map_err(|error| LocalIpcError::Credential(error.to_string()))
    }

    fn process_sid(process: &HANDLE) -> Result<String, LocalIpcError> {
        let mut token = HANDLE::default();
        unsafe { OpenProcessToken(*process, TOKEN_QUERY, &raw mut token) }
            .map_err(platform_error)?;
        let token = unsafe { Owned::new(token) };
        let mut required = 0;
        let _ = unsafe { GetTokenInformation(*token, TokenUser, None, 0, &raw mut required) };
        if required == 0 {
            return Err(LocalIpcError::Credential(
                "token user information is unavailable".into(),
            ));
        }
        let required_usize =
            usize::try_from(required).map_err(|_| LocalIpcError::InvalidEndpoint)?;
        let word_size = std::mem::size_of::<usize>();
        let word_count = required_usize.div_ceil(word_size);
        let mut buffer = vec![0_usize; word_count];
        unsafe {
            GetTokenInformation(
                *token,
                TokenUser,
                Some(buffer.as_mut_ptr().cast()),
                required,
                &raw mut required,
            )
        }
        .map_err(platform_error)?;
        let user = unsafe { &*buffer.as_ptr().cast::<TOKEN_USER>() };
        let mut text = PWSTR::null();
        unsafe {
            windows::Win32::Security::Authorization::ConvertSidToStringSidW(
                user.User.Sid,
                &raw mut text,
            )
        }
        .map_err(platform_error)?;
        let local = unsafe { Owned::new(HLOCAL(text.as_ptr().cast())) };
        let value = unsafe { text.to_string() }
            .map_err(|error| LocalIpcError::Credential(error.to_string()))?;
        drop(local);
        Ok(value)
    }

    #[allow(clippy::needless_pass_by_value)]
    fn platform_error(error: windows::core::Error) -> LocalIpcError {
        LocalIpcError::Credential(error.to_string())
    }
}

#[cfg(unix)]
fn unix_peer_credentials(
    stream: &tokio::net::UnixStream,
) -> Result<PeerCredentials, LocalIpcError> {
    let credentials = stream.peer_cred()?;
    let stable_app_id = credentials.pid().map_or_else(
        || format!("uid:{}", credentials.uid()),
        |pid| {
            #[cfg(target_os = "linux")]
            if let Ok(path) = std::fs::read_link(format!("/proc/{pid}/exe")) {
                return path.to_string_lossy().into_owned();
            }
            #[cfg(not(target_os = "linux"))]
            let _ = pid;
            format!("uid:{}", credentials.uid())
        },
    );
    let platform = if cfg!(target_os = "android") {
        Platform::Android
    } else if cfg!(target_os = "macos") {
        Platform::MacOs
    } else {
        Platform::Linux
    };
    Ok(PeerCredentials {
        platform,
        stable_app_id,
        publisher_id: None,
        signing_digest: None,
        os_subject: OsSubject(format!(
            "uid:{}:gid:{}",
            credentials.uid(),
            credentials.gid()
        )),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use fabric_core::{AppPrincipal, OsSubject, Platform};
    use fabric_identity::IdentityError;

    struct VerifiedCredentials;

    impl OsCredentialAdapter for VerifiedCredentials {
        fn authenticate(
            &self,
            credentials: &PeerCredentials,
        ) -> Result<AppPrincipal, IdentityError> {
            Ok(AppPrincipal {
                platform: credentials.platform,
                stable_app_id: credentials.stable_app_id.clone(),
                publisher_id: credentials.publisher_id.clone(),
                signing_digest: credentials.signing_digest,
                os_subject: credentials.os_subject.clone(),
            })
        }
    }

    fn peer() -> PeerCredentials {
        PeerCredentials {
            platform: Platform::Test,
            stable_app_id: "test.app".into(),
            publisher_id: None,
            signing_digest: None,
            os_subject: OsSubject("test:1".into()),
        }
    }

    async fn client_handshake<S: AsyncRead + AsyncWrite + Unpin>(
        stream: &mut S,
    ) -> Result<(), LocalIpcError> {
        write_message(
            stream,
            FrameKind::ClientHello,
            0,
            &ClientHello {
                protocol_versions: vec![IPC_PROTOCOL_VERSION],
                sdk_version: "test-sdk".into(),
                requested_features: vec!["request-response".into()],
            },
        )
        .await?;
        let hello = read_frame(stream).await?;
        assert_eq!(hello.kind, FrameKind::ServerHello);
        let hello =
            ServerHello::decode(hello.payload).map_err(|_| LocalIpcError::InvalidPayload)?;
        assert_eq!(hello.selected_version, IPC_PROTOCOL_VERSION);
        assert_eq!(hello.connection_id.len(), 16);

        write_message(
            stream,
            FrameKind::BindApplication,
            0,
            &BindApplication {
                declared_app_id: None,
            },
        )
        .await?;
        let bound = read_frame(stream).await?;
        assert_eq!(bound.kind, FrameKind::ApplicationBound);
        let bound =
            ApplicationBound::decode(bound.payload).map_err(|_| LocalIpcError::InvalidPayload)?;
        assert!(!bound.stable_app_id.is_empty());
        assert!(!bound.os_subject.is_empty());

        write_message(
            stream,
            FrameKind::Request,
            1,
            &Request {
                body: Some(request::Body::Ping(true)),
            },
        )
        .await?;
        let response = read_frame(stream).await?;
        assert_eq!(response.kind, FrameKind::Response);
        assert_eq!(response.id, 1);
        let response =
            Response::decode(response.payload).map_err(|_| LocalIpcError::InvalidPayload)?;
        assert_eq!(response.code, ResponseCode::Ok as i32);
        Ok(())
    }

    #[tokio::test]
    async fn framed_handshake_and_request_clean_up_connection() {
        let registry = Arc::new(fabric_registry::Registry::new());
        let server = Arc::new(IpcServer::new(VerifiedCredentials, registry));
        let (mut client, hub) = tokio::io::duplex(16 * 1024);
        let task_server = Arc::clone(&server);
        let task = tokio::spawn(async move {
            serve_connection(hub, peer(), task_server, ConnectionQuota::default()).await
        });
        client_handshake(&mut client).await.unwrap();
        assert_eq!(server.connection_count().unwrap(), 1);
        drop(client);
        task.await.unwrap().unwrap();
        assert_eq!(server.connection_count().unwrap(), 0);
    }

    #[cfg(windows)]
    #[tokio::test]
    async fn windows_named_pipe_uses_os_peer_credentials() {
        use tokio::net::windows::named_pipe::ClientOptions;

        let endpoint = PathBuf::from(format!(
            r"\\.\pipe\fabric-ipc-test-{}",
            uuid::Uuid::new_v4()
        ));
        let registry = Arc::new(fabric_registry::Registry::new());
        let server = Arc::new(IpcServer::new(VerifiedCredentials, registry));
        let listener = LocalIpcListener::bind(
            endpoint.clone(),
            Arc::clone(&server),
            ConnectionQuota::default(),
            4,
        )
        .unwrap();
        let (shutdown_tx, shutdown_rx) = watch::channel(false);
        let listener_task = tokio::spawn(listener.run(shutdown_rx));

        let mut client = loop {
            match ClientOptions::new().open(&endpoint) {
                Ok(client) => break client,
                Err(error) if error.raw_os_error() == Some(231) => {
                    tokio::task::yield_now().await;
                }
                Err(error) => panic!("failed to connect to test pipe: {error}"),
            }
        };
        client_handshake(&mut client).await.unwrap();
        drop(client);
        shutdown_tx.send(true).unwrap();
        listener_task.await.unwrap().unwrap();
    }
}
