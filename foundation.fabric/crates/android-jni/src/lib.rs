//! JNI bridge that owns the Rust Hub runtime inside the Android Hub process.

use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    io::{Read, Write},
    net::{IpAddr, SocketAddr, UdpSocket},
    path::{Path, PathBuf},
    sync::{Arc, Mutex, OnceLock, mpsc},
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use bytes::Bytes;
use fabric_app_broker::{
    BrokerError, RemoteRequest, accept_remote_stream, invoke_remote_stream, read_remote_request,
    reject_remote_stream, write_remote_result,
};
use fabric_core::{
    AbilityContractRef, AbilityInstanceId, AbilityKey, AbilityName, AbilityOffer, AppPrincipal,
    ChannelBinding, ChannelContract, ChannelId, ClockDomainId, ClockDomainRef, DeliverySemantics,
    DeviceId, FanoutPolicy, LeaseSpec, Namespace, OfferVisibility, OsSubject, Participant,
    ParticipantId, PayloadSecurity, Platform, PolicyRef, PolicySnapshotId, PortBinding, PortId,
    PortMode, PortRef, PropertyMap, RoleId, SessionExtensions, SessionId, SessionPlan,
    SessionState, TimingPolicy,
};
use fabric_discovery::{DiscoverySource, encode_rotating_hint, quic_socket_addresses};
use fabric_hub::{HealthStatus, Hub, HubConfig, LinkServices, RelayEndpointConfig};
use fabric_identity::{
    DeviceIdentity, DeviceTrust, DeviceTrustStore, FileDeviceLabelStore, FileDeviceTrustStore,
    IdentityError, OsCredentialAdapter, PeerCredentials, decode_device_id, encode_device_id,
};
use fabric_ipc::ConnectionQuota;
use fabric_link::{FabricLink, IncomingStream, StreamOpen};
use fabric_protocol::ControlMessage;
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JObject, JString},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint, jlong, jstring},
};
#[cfg(target_os = "android")]
use jni::{
    JavaVM,
    objects::{GlobalRef, JValue},
};
use sha2::{Digest, Sha256};
#[cfg(target_os = "android")]
use std::os::fd::{FromRawFd, RawFd};

static RUNTIME: OnceLock<Mutex<Option<RuntimeHandle>>> = OnceLock::new();
const ECHO_SCHEMA: &[u8] = include_bytes!("../../../examples/echo-ability/schemas/control.proto");
const BROKER_STREAM_HEADER: &[u8] = b"fabric-app-broker-v1";
const DEVICE_ONLINE_GRACE_MS: u64 = 10_000;
const RECONNECT_INTERVAL_MS: u64 = 25_000;
const DEVICE_ADDRESS_FILE: &str = "trusted-addresses.txt";
const COMMAND_TIMEOUT: Duration = Duration::from_secs(12);
/// Keep pairing snappy; a slow dial should fail over to another candidate, not hang the UI.
const PAIR_CONNECT_TIMEOUT: Duration = Duration::from_secs(3);
/// Cross-network relay dial can traverse the internet, so allow longer than a LAN dial.
const RELAY_PAIR_TIMEOUT: Duration = Duration::from_secs(10);
const PAIR_MESSAGE_TIMEOUT: Duration = Duration::from_secs(2);

enum Command {
    Register {
        ability: String,
        package: String,
        uid: i32,
        response: mpsc::Sender<Result<(), String>>,
    },
    Unregister {
        ability: String,
        package: String,
        uid: i32,
    },
    Pair {
        address: String,
        response: mpsc::Sender<Result<(), String>>,
    },
    PairRemote {
        device: String,
        response: mpsc::Sender<Result<(), String>>,
    },
    Accept {
        device: String,
        response: mpsc::Sender<Result<(), String>>,
    },
    Remove {
        device: String,
        response: mpsc::Sender<Result<(), String>>,
    },
    InvokeRemote {
        device: String,
        ability: String,
        payload: Vec<u8>,
        response: mpsc::Sender<Result<Vec<u8>, String>>,
    },
    OpenRemoteStream {
        device: String,
        ability: String,
        stream_name: String,
        read_fd: i32,
        write_fd: i32,
        response: mpsc::Sender<Result<(), String>>,
    },
    OpenSession {
        device_ids: Vec<String>,
        ability: String,
        config: Vec<u8>,
        response: mpsc::Sender<Result<String, String>>,
    },
    CloseSession {
        session_id: String,
    },
    EnsureSessionClock {
        session_id: String,
        response: mpsc::Sender<Result<(), String>>,
    },
    SessionLocalNow {
        response: mpsc::Sender<Result<u64, String>>,
    },
    SessionGroupToLocal {
        device: String,
        group_ns: i64,
        response: mpsc::Sender<Result<u64, String>>,
    },
    SessionLocalToGroup {
        device: String,
        local_ns: u64,
        response: mpsc::Sender<Result<i64, String>>,
    },
    SessionClockUncertainty {
        device: String,
        response: mpsc::Sender<Result<u64, String>>,
    },
    Stop,
}

struct AppSession {
    ability: String,
    devices: Vec<String>,
    fabric_session_id: Option<SessionId>,
    delivery: String,
    relay_root: Option<String>,
}

struct IncomingRequest {
    request_id: i64,
    ability: String,
    payload: Vec<u8>,
}

struct IncomingStreamRequest {
    request_id: i64,
    ability: String,
    stream_name: String,
    read_fd: i32,
    write_fd: i32,
}

type RemoteCompletion = tokio::sync::oneshot::Sender<Result<Vec<u8>, String>>;

struct RuntimeHandle {
    commands: tokio::sync::mpsc::Sender<Command>,
    status: Arc<Mutex<String>>,
    devices: Arc<Mutex<String>>,
    candidates: Arc<Mutex<String>>,
    sessions: Arc<Mutex<BTreeMap<String, AppSession>>>,
    incoming: Mutex<mpsc::Receiver<IncomingRequest>>,
    incoming_streams: Mutex<mpsc::Receiver<IncomingStreamRequest>>,
    completions: Arc<Mutex<BTreeMap<i64, RemoteCompletion>>>,
    join: thread::JoinHandle<()>,
}

#[cfg(target_os = "android")]
struct AndroidDiscoveryBridge {
    vm: JavaVM,
    context: GlobalRef,
    ble_class: GlobalRef,
    wifi_aware_class: GlobalRef,
}

#[cfg(target_os = "android")]
impl AndroidDiscoveryBridge {
    fn new(env: &mut JNIEnv<'_>, context: &JObject<'_>) -> Result<Self, String> {
        Ok(Self {
            vm: env.get_java_vm().map_err(|error| error.to_string())?,
            context: env
                .new_global_ref(context)
                .map_err(|error| error.to_string())?,
            ble_class: load_context_class(
                env,
                context,
                "com.mocharealm.foundation.fabric.AndroidBleDiscoveryBridge",
            )?,
            wifi_aware_class: load_context_class(
                env,
                context,
                "com.mocharealm.foundation.fabric.AndroidWifiAwareDiscoveryBridge",
            )?,
        })
    }

    fn call_payload_method(
        &self,
        class: &GlobalRef,
        method_name: &str,
        payload: Vec<u8>,
    ) -> Result<(), fabric_discovery::DiscoveryError> {
        let mut env = self.vm.attach_current_thread().map_err(platform_error)?;
        let class: &JClass<'_> = class.as_obj().into();
        let payload = JObject::from(
            env.byte_array_from_slice(&payload)
                .map_err(platform_error)?,
        );
        env.call_static_method(
            class,
            method_name,
            "(Landroid/content/Context;[B)V",
            &[
                JValue::Object(self.context.as_obj()),
                JValue::Object(&payload),
            ],
        )
        .map_err(platform_error)?;
        Ok(())
    }

    fn call_scan_method(
        &self,
        class: &GlobalRef,
        millis: i32,
    ) -> Result<Vec<Vec<u8>>, fabric_discovery::DiscoveryError> {
        let mut env = self.vm.attach_current_thread().map_err(platform_error)?;
        let class: &JClass<'_> = class.as_obj().into();
        let result = env
            .call_static_method(
                class,
                "scan",
                "(Landroid/content/Context;I)[B",
                &[JValue::Object(self.context.as_obj()), JValue::Int(millis)],
            )
            .map_err(platform_error)?
            .l()
            .map_err(platform_error)?;
        if result.is_null() {
            return Ok(Vec::new());
        }
        let bytes = env
            .convert_byte_array(JByteArray::from(result))
            .map_err(platform_error)?;
        parse_framed_payloads(&bytes)
    }

    fn call_stop_method(&self, class: &GlobalRef) -> Result<(), fabric_discovery::DiscoveryError> {
        let mut env = self.vm.attach_current_thread().map_err(platform_error)?;
        let class: &JClass<'_> = class.as_obj().into();
        env.call_static_method(class, "stop", "()V", &[])
            .map_err(platform_error)?;
        Ok(())
    }
}

#[cfg(target_os = "android")]
fn load_context_class(
    env: &mut JNIEnv<'_>,
    context: &JObject<'_>,
    class_name: &str,
) -> Result<GlobalRef, String> {
    let loader = env
        .call_method(context, "getClassLoader", "()Ljava/lang/ClassLoader;", &[])
        .map_err(|error| error.to_string())?
        .l()
        .map_err(|error| error.to_string())?;
    let name = env
        .new_string(class_name)
        .map_err(|error| error.to_string())?;
    let name = JObject::from(name);
    let class = env
        .call_method(
            &loader,
            "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;",
            &[JValue::Object(&name)],
        )
        .map_err(|error| error.to_string())?
        .l()
        .map_err(|error| error.to_string())?;
    env.new_global_ref(class).map_err(|error| error.to_string())
}

#[cfg(target_os = "android")]
impl fabric_discovery_ble::AndroidBleBridge for AndroidDiscoveryBridge {
    fn start_advertising(&self, payload: Vec<u8>) -> Result<(), fabric_discovery::DiscoveryError> {
        self.call_payload_method(&self.ble_class, "startAdvertising", payload)
    }

    fn stop_advertising(&self) -> Result<(), fabric_discovery::DiscoveryError> {
        self.call_stop_method(&self.ble_class)
    }

    fn scan(
        &self,
        scan_duration: Duration,
    ) -> Result<Vec<Vec<u8>>, fabric_discovery::DiscoveryError> {
        self.call_scan_method(&self.ble_class, duration_millis_i32(scan_duration))
    }
}

#[cfg(target_os = "android")]
impl fabric_discovery_wifi_aware::AndroidWifiAwareBridge for AndroidDiscoveryBridge {
    fn publish(&self, payload: Vec<u8>) -> Result<(), fabric_discovery::DiscoveryError> {
        self.call_payload_method(&self.wifi_aware_class, "publish", payload)
    }

    fn scan(
        &self,
        scan_duration: Duration,
    ) -> Result<Vec<Vec<u8>>, fabric_discovery::DiscoveryError> {
        self.call_scan_method(&self.wifi_aware_class, duration_millis_i32(scan_duration))
    }

    fn stop(&self) -> Result<(), fabric_discovery::DiscoveryError> {
        self.call_stop_method(&self.wifi_aware_class)
    }
}

#[cfg(target_os = "android")]
fn register_android_discovery_bridges(
    env: &mut JNIEnv<'_>,
    context: &JObject<'_>,
) -> Result<(), String> {
    let bridge = Arc::new(AndroidDiscoveryBridge::new(env, context)?);
    fabric_discovery_ble::set_android_ble_bridge(bridge.clone());
    fabric_discovery_wifi_aware::set_android_wifi_aware_bridge(bridge);
    Ok(())
}

#[cfg(target_os = "android")]
fn parse_framed_payloads(bytes: &[u8]) -> Result<Vec<Vec<u8>>, fabric_discovery::DiscoveryError> {
    let mut cursor = 0;
    let mut payloads = Vec::new();
    while cursor < bytes.len() {
        let Some(header) = bytes.get(cursor..cursor + 2) else {
            return Err(fabric_discovery::DiscoveryError::InvalidAdvertisement);
        };
        cursor += 2;
        let len = usize::from(u16::from_be_bytes([header[0], header[1]]));
        let Some(payload) = bytes.get(cursor..cursor + len) else {
            return Err(fabric_discovery::DiscoveryError::InvalidAdvertisement);
        };
        payloads.push(payload.to_vec());
        cursor += len;
    }
    Ok(payloads)
}

#[cfg(target_os = "android")]
fn duration_millis_i32(duration: Duration) -> i32 {
    i32::try_from(duration.as_millis()).unwrap_or(i32::MAX)
}

#[cfg(target_os = "android")]
fn platform_error(error: impl std::fmt::Display) -> fabric_discovery::DiscoveryError {
    fabric_discovery::DiscoveryError::Platform(error.to_string())
}

struct AndroidCredentials;

impl OsCredentialAdapter for AndroidCredentials {
    fn authenticate(&self, credentials: &PeerCredentials) -> Result<AppPrincipal, IdentityError> {
        if credentials.platform != Platform::Android
            || credentials.stable_app_id.is_empty()
            || !credentials.os_subject.0.starts_with("android:uid:")
        {
            return Err(IdentityError::AppIdentityMismatch);
        }
        let principal = AppPrincipal {
            platform: Platform::Android,
            stable_app_id: credentials.stable_app_id.clone(),
            publisher_id: credentials.publisher_id.clone(),
            signing_digest: credentials.signing_digest,
            os_subject: credentials.os_subject.clone(),
        };
        principal
            .validate()
            .map_err(|_| IdentityError::AppIdentityMismatch)?;
        Ok(principal)
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    #[cfg_attr(not(target_os = "android"), allow(unused_variables))] context: JObject,
    state_directory: JString,
    device_label: JString,
    quic_port: jint,
) -> jboolean {
    #[cfg(target_os = "android")]
    if register_android_discovery_bridges(&mut env, &context).is_err() {
        return JNI_FALSE;
    }
    let Ok(state_directory) = env.get_string(&state_directory) else {
        return JNI_FALSE;
    };
    let Ok(device_label) = env.get_string(&device_label) else {
        return JNI_FALSE;
    };
    let Ok(port) = u16::try_from(quic_port) else {
        return JNI_FALSE;
    };
    let mut guard = runtime()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if guard.is_some() {
        return JNI_TRUE;
    }
    let path = PathBuf::from(state_directory.to_string_lossy().into_owned());
    let label = device_label.to_string_lossy().into_owned();
    let status = Arc::new(Mutex::new("Starting".into()));
    let devices = Arc::new(Mutex::new(String::new()));
    let candidates = Arc::new(Mutex::new(String::new()));
    let sessions = Arc::new(Mutex::new(BTreeMap::new()));
    let completions = Arc::new(Mutex::new(BTreeMap::new()));
    let (incoming_tx, incoming_rx) = mpsc::channel();
    let (incoming_stream_tx, incoming_stream_rx) = mpsc::channel();
    let (commands, command_rx) = tokio::sync::mpsc::channel(64);
    let thread_status = Arc::clone(&status);
    let thread_devices = Arc::clone(&devices);
    let thread_candidates = Arc::clone(&candidates);
    let thread_sessions = Arc::clone(&sessions);
    let thread_completions = Arc::clone(&completions);
    let join = thread::spawn(move || {
        let result = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .map_err(|error| error.to_string())
            .and_then(|runtime| {
                runtime.block_on(run_hub(
                    path,
                    label,
                    port,
                    command_rx,
                    &thread_status,
                    &thread_devices,
                    &thread_candidates,
                    &thread_sessions,
                    incoming_tx,
                    incoming_stream_tx,
                    thread_completions,
                ))
            });
        if let Err(error) = result {
            set_status(&thread_status, format!("Failed: {error}"));
        }
    });
    *guard = Some(RuntimeHandle {
        commands,
        status,
        devices,
        candidates,
        sessions,
        incoming: Mutex::new(incoming_rx),
        incoming_streams: Mutex::new(incoming_stream_rx),
        completions,
        join,
    });
    JNI_TRUE
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    let handle = runtime()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .take();
    if let Some(handle) = handle {
        let _ = handle.commands.blocking_send(Command::Stop);
        let _ = handle.join.join();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeStatus(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let value = runtime_value(|handle| {
        handle
            .status
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone()
    })
    .unwrap_or_else(|| "Stopped".into());
    env.new_string(value)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeDevices(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let value = runtime_value(|handle| {
        handle
            .devices
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone()
    })
    .unwrap_or_default();
    env.new_string(value)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeCandidates(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let value = runtime_value(|handle| {
        handle
            .candidates
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone()
    })
    .unwrap_or_default();
    env.new_string(value)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativePair(
    mut env: JNIEnv,
    _class: JClass,
    address: JString,
) -> jboolean {
    let Ok(address) = env.get_string(&address) else {
        return JNI_FALSE;
    };
    command_result(|response| Command::Pair {
        address: address.to_string_lossy().into_owned(),
        response,
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativePairRemote(
    mut env: JNIEnv,
    _class: JClass,
    device_id: JString,
) -> jboolean {
    let Ok(device_id) = env.get_string(&device_id) else {
        return JNI_FALSE;
    };
    command_result(|response| Command::PairRemote {
        device: device_id.to_string_lossy().into_owned(),
        response,
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeAccept(
    mut env: JNIEnv,
    _class: JClass,
    device: JString,
) -> jboolean {
    let Ok(device) = env.get_string(&device) else {
        return JNI_FALSE;
    };
    command_result(|response| Command::Accept {
        device: device.to_string_lossy().into_owned(),
        response,
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeRemove(
    mut env: JNIEnv,
    _class: JClass,
    device: JString,
) -> jboolean {
    let Ok(device) = env.get_string(&device) else {
        return JNI_FALSE;
    };
    command_result(|response| Command::Remove {
        device: device.to_string_lossy().into_owned(),
        response,
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeRegisterAbility(
    mut env: JNIEnv,
    _class: JClass,
    ability: JString,
    package: JString,
    uid: jint,
) -> jboolean {
    let (Ok(ability), Ok(package)) = (env.get_string(&ability), env.get_string(&package)) else {
        return JNI_FALSE;
    };
    command_result(|response| Command::Register {
        ability: ability.to_string_lossy().into_owned(),
        package: package.to_string_lossy().into_owned(),
        uid,
        response,
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeUnregisterAbility(
    mut env: JNIEnv,
    _class: JClass,
    ability: JString,
    package: JString,
    uid: jint,
) {
    let (Ok(ability), Ok(package)) = (env.get_string(&ability), env.get_string(&package)) else {
        return;
    };
    send_command(Command::Unregister {
        ability: ability.to_string_lossy().into_owned(),
        package: package.to_string_lossy().into_owned(),
        uid,
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeInvokeRemote(
    mut env: JNIEnv,
    _class: JClass,
    device: JString,
    ability: JString,
    payload: JByteArray,
) -> jbyteArray {
    let (Ok(device), Ok(ability), Ok(payload)) = (
        env.get_string(&device),
        env.get_string(&ability),
        env.convert_byte_array(&payload),
    ) else {
        return std::ptr::null_mut();
    };
    let (response_tx, response_rx) = mpsc::channel();
    let command = Command::InvokeRemote {
        device: device.to_string_lossy().into_owned(),
        ability: ability.to_string_lossy().into_owned(),
        payload,
        response: response_tx,
    };
    if !send_command(command) {
        return std::ptr::null_mut();
    }
    match response_rx.recv_timeout(Duration::from_secs(12)) {
        Ok(Ok(value)) => env
            .byte_array_from_slice(&value)
            .map_or(std::ptr::null_mut(), JByteArray::into_raw),
        Ok(Err(message)) => {
            // Surface the category-tagged failure (not trusted / no address /
            // dial failed / provider unavailable) so Java can log and forward
            // it instead of collapsing everything to one generic string.
            let _ = env.throw_new("java/lang/RuntimeException", &message);
            std::ptr::null_mut()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeOpenRemoteStream(
    mut env: JNIEnv,
    _class: JClass,
    device: JString,
    ability: JString,
    stream_name: JString,
    read_fd: jint,
    write_fd: jint,
) -> jboolean {
    let (Ok(device), Ok(ability), Ok(stream_name)) = (
        env.get_string(&device),
        env.get_string(&ability),
        env.get_string(&stream_name),
    ) else {
        return JNI_FALSE;
    };
    let (response_tx, response_rx) = mpsc::channel();
    if !send_command(Command::OpenRemoteStream {
        device: device.to_string_lossy().into_owned(),
        ability: ability.to_string_lossy().into_owned(),
        stream_name: stream_name.to_string_lossy().into_owned(),
        read_fd,
        write_fd,
        response: response_tx,
    }) {
        return JNI_FALSE;
    }
    match response_rx.recv_timeout(COMMAND_TIMEOUT) {
        Ok(Ok(())) => JNI_TRUE,
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/RuntimeException", &message);
            JNI_FALSE
        }
        Err(_) => JNI_FALSE,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeTakeRemoteRequest(
    env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    // `runtime_value` holds the global RUNTIME mutex for the whole closure, so
    // this must NOT block: a blocking `recv` here would pin the global lock and
    // stall every other JNI call (status/devices/invoke). Poll non-blocking and
    // let the Java side back off when empty (see FabricHubService).
    let request = runtime_value(|handle| {
        handle
            .incoming
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .try_recv()
            .ok()
    })
    .flatten();
    let Some(request) = request else {
        return std::ptr::null_mut();
    };
    let Ok(ability_len) = u16::try_from(request.ability.len()) else {
        return std::ptr::null_mut();
    };
    let mut encoded = Vec::with_capacity(10 + request.ability.len() + request.payload.len());
    encoded.extend_from_slice(&request.request_id.to_be_bytes());
    encoded.extend_from_slice(&ability_len.to_be_bytes());
    encoded.extend_from_slice(request.ability.as_bytes());
    encoded.extend_from_slice(&request.payload);
    env.byte_array_from_slice(&encoded)
        .map_or(std::ptr::null_mut(), JByteArray::into_raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeTakeRemoteStream(
    env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    // Non-blocking for the same reason as nativeTakeRemoteRequest: never hold
    // the global RUNTIME mutex across a blocking wait.
    let request = runtime_value(|handle| {
        handle
            .incoming_streams
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .try_recv()
            .ok()
    })
    .flatten();
    let Some(request) = request else {
        return std::ptr::null_mut();
    };
    let Ok(ability_len) = u16::try_from(request.ability.len()) else {
        close_raw_fd(request.read_fd);
        close_raw_fd(request.write_fd);
        return std::ptr::null_mut();
    };
    let Ok(stream_len) = u16::try_from(request.stream_name.len()) else {
        close_raw_fd(request.read_fd);
        close_raw_fd(request.write_fd);
        return std::ptr::null_mut();
    };
    let mut encoded = Vec::with_capacity(16 + usize::from(ability_len) + usize::from(stream_len));
    encoded.extend_from_slice(&request.request_id.to_be_bytes());
    encoded.extend_from_slice(&request.read_fd.to_be_bytes());
    encoded.extend_from_slice(&request.write_fd.to_be_bytes());
    encoded.extend_from_slice(&ability_len.to_be_bytes());
    encoded.extend_from_slice(&stream_len.to_be_bytes());
    encoded.extend_from_slice(request.ability.as_bytes());
    encoded.extend_from_slice(request.stream_name.as_bytes());
    env.byte_array_from_slice(&encoded)
        .map_or(std::ptr::null_mut(), JByteArray::into_raw)
}

async fn open_audio_network_session(
    links: &Arc<LinkServices>,
    sessions: &Arc<Mutex<BTreeMap<String, AppSession>>>,
    device_ids: Vec<String>,
    ability: String,
    config: &[u8],
) -> Result<String, String> {
    let peers: Vec<String> = device_ids
        .into_iter()
        .filter(|id| id != "local" && !id.is_empty())
        .collect();
    if peers.is_empty() {
        return Err("no remote devices".into());
    }
    let peer_ids = peers
        .iter()
        .map(|id| decode_device_id(id).map_err(|e| e.to_string()))
        .collect::<Result<Vec<_>, _>>()?;
    let local = links.local_device_id();
    let (delivery, relay_root) = parse_session_config(config, &peers);
    let plan = build_audio_session_plan(local, &peer_ids, &ability, delivery.as_str())?;
    let fabric_id = links
        .propose_network_session(plan)
        .await
        .map_err(|e| format!("propose failed: {e}"))?;
    // Wait for Session Active (remote hubs auto-accept).
    let mut last_state = None;
    let mut active = false;
    for _ in 0..100 {
        last_state = links.network_session_state(fabric_id).await;
        if last_state == Some(SessionState::Active) {
            active = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    if !active {
        links.remove_session(fabric_id).await;
        // Naming the state it got stuck in separates "peer never answered"
        // (Proposed) from "handshake stalled midway" (Negotiating/Preparing).
        return Err(format!(
            "session did not become active after 5s (stuck at {last_state:?})"
        ));
    }
    // Clock sync is best effort here: a peer without a mapping still receives
    // metadata and media, it just starts unsynced. `ensureSessionClock` is the
    // caller's strict check when it actually needs a barrier.
    for peer in &peer_ids {
        let _ = links.ensure_peer_clock(*peer).await;
    }
    let app_id = fabric_id.0.to_string();
    sessions
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .insert(
            app_id.clone(),
            AppSession {
                ability,
                devices: peers,
                fabric_session_id: Some(fabric_id),
                delivery,
                relay_root,
            },
        );
    Ok(app_id)
}

fn parse_session_config(config: &[u8], peers: &[String]) -> (String, Option<String>) {
    let value = serde_json::from_slice::<serde_json::Value>(config).ok();
    let delivery = value
        .as_ref()
        .and_then(|v| v.get("delivery"))
        .and_then(|v| v.as_str())
        .unwrap_or("per_peer_unicast")
        .to_owned();
    let relay_root = if delivery == "relay_tree" {
        peers.first().cloned()
    } else {
        None
    };
    (delivery, relay_root)
}

fn build_audio_session_plan(
    local: DeviceId,
    peers: &[DeviceId],
    ability: &str,
    delivery: &str,
) -> Result<SessionPlan, String> {
    let (namespace, name) = ability
        .rsplit_once('.')
        .ok_or_else(|| "ability must contain a namespace".to_string())?;
    let source_id = ParticipantId::new();
    let mut participants = vec![Participant {
        id: source_id,
        device_id: local,
        app_principal: AppPrincipal {
            platform: Platform::Android,
            stable_app_id: "com.mocharealm.clef".into(),
            publisher_id: None,
            signing_digest: None,
            os_subject: OsSubject("android:hub".into()),
        },
        ability_instance: AbilityInstanceId::new(),
        role: RoleId::new("source").map_err(|e| e.to_string())?,
        port_bindings: vec![
            PortBinding {
                id: PortId::new(),
                declaration_id: "control".into(),
            },
            PortBinding {
                id: PortId::new(),
                declaration_id: "media".into(),
            },
            PortBinding {
                id: PortId::new(),
                declaration_id: "feedback".into(),
            },
        ],
    }];
    let mut renderer_control_ports = Vec::new();
    let mut renderer_media_ports = Vec::new();
    let mut renderer_feedback_ports = Vec::new();
    for peer in peers {
        let pid = ParticipantId::new();
        let control = PortId::new();
        let media = PortId::new();
        let feedback = PortId::new();
        renderer_control_ports.push(PortRef {
            participant: pid,
            port: control,
        });
        renderer_media_ports.push(PortRef {
            participant: pid,
            port: media,
        });
        renderer_feedback_ports.push(PortRef {
            participant: pid,
            port: feedback,
        });
        participants.push(Participant {
            id: pid,
            device_id: *peer,
            app_principal: AppPrincipal {
                platform: Platform::Android,
                stable_app_id: "com.mocharealm.clef".into(),
                publisher_id: None,
                signing_digest: None,
                os_subject: OsSubject("android:hub".into()),
            },
            ability_instance: AbilityInstanceId::new(),
            role: RoleId::new("renderer").map_err(|e| e.to_string())?,
            port_bindings: vec![
                PortBinding {
                    id: control,
                    declaration_id: "control".into(),
                },
                PortBinding {
                    id: media,
                    declaration_id: "media".into(),
                },
                PortBinding {
                    id: feedback,
                    declaration_id: "feedback".into(),
                },
            ],
        });
    }
    let source_control = participants[0].port_bindings[0].id;
    let source_media = participants[0].port_bindings[1].id;
    let source_feedback = participants[0].port_bindings[2].id;
    // Control and media are reliable: no delivery policy may silently drop a
    // renderer. Relay/multicast change *where* the bytes are duplicated, not
    // whether a destination may be skipped, so fanout is All either way.
    let fanout = FanoutPolicy::All;
    let channels = vec![
        ChannelBinding {
            id: ChannelId::new(),
            sources: vec![PortRef {
                participant: source_id,
                port: source_control,
            }],
            destinations: renderer_control_ports.clone(),
            contract: ChannelContract {
                mode: PortMode::ReliableMessages,
                delivery: DeliverySemantics::ReliableOrdered,
                priority: 8,
                max_message_bytes: Some(512 * 1024),
                max_buffered_bytes: 2 * 1024 * 1024,
                latency_target_ms: Some(100),
                idle_timeout_ms: Some(60_000),
                fanout,
                payload_security: PayloadSecurity::HubReadable,
                timing: TimingPolicy::None,
            },
        },
        ChannelBinding {
            id: ChannelId::new(),
            sources: vec![PortRef {
                participant: source_id,
                port: source_media,
            }],
            destinations: renderer_media_ports,
            contract: ChannelContract {
                mode: PortMode::ReliableStream,
                delivery: DeliverySemantics::ReliableOrdered,
                priority: 4,
                max_message_bytes: None,
                max_buffered_bytes: 16 * 1024 * 1024,
                latency_target_ms: Some(200),
                idle_timeout_ms: Some(120_000),
                fanout,
                payload_security: PayloadSecurity::HubReadable,
                timing: TimingPolicy::PresentationTime,
            },
        },
        ChannelBinding {
            id: ChannelId::new(),
            sources: renderer_feedback_ports,
            destinations: vec![PortRef {
                participant: source_id,
                port: source_feedback,
            }],
            contract: ChannelContract {
                mode: PortMode::Datagram,
                delivery: DeliverySemantics::BestEffort,
                priority: 2,
                max_message_bytes: Some(8 * 1024),
                max_buffered_bytes: 64 * 1024,
                latency_target_ms: Some(50),
                idle_timeout_ms: Some(30_000),
                fanout: FanoutPolicy::DropSlowDestinations,
                payload_security: PayloadSecurity::HubReadable,
                timing: TimingPolicy::Timestamped,
            },
        },
    ];
    // Bidirectional control: also allow renderer -> source control.
    let mut channels = channels;
    channels.push(ChannelBinding {
        id: ChannelId::new(),
        sources: renderer_control_ports,
        destinations: vec![PortRef {
            participant: source_id,
            port: source_control,
        }],
        contract: ChannelContract {
            mode: PortMode::ReliableMessages,
            delivery: DeliverySemantics::ReliableOrdered,
            priority: 8,
            max_message_bytes: Some(512 * 1024),
            max_buffered_bytes: 2 * 1024 * 1024,
            latency_target_ms: Some(100),
            idle_timeout_ms: Some(60_000),
            fanout: FanoutPolicy::All,
            payload_security: PayloadSecurity::HubReadable,
            timing: TimingPolicy::None,
        },
    });
    Ok(SessionPlan {
        session_id: SessionId::new(),
        contract: AbilityContractRef {
            key: AbilityKey {
                namespace: Namespace::new(namespace).map_err(|e| e.to_string())?,
                name: AbilityName::new(name).map_err(|e| e.to_string())?,
                major: 1,
            },
            protocol_hash: Sha256::digest(b"com.mocharealm.foundation.audio.render@1").into(),
        },
        epoch: 1,
        coordinator: source_id,
        participants,
        channels,
        extensions: SessionExtensions {
            clock: Some(ClockDomainRef {
                id: ClockDomainId::new(),
                epoch: 1,
            }),
            barrier_enabled: true,
            opaque_ability_config: Vec::new(),
        },
        policy_snapshot: PolicySnapshotId::new(),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeOpenSession(
    mut env: JNIEnv,
    _class: JClass,
    device_ids: jni::objects::JObjectArray,
    ability: JString,
    config: JByteArray,
) -> jstring {
    let Ok(ability) = env.get_string(&ability) else {
        return std::ptr::null_mut();
    };
    let config = env.convert_byte_array(&config).unwrap_or_default();
    let mut devices = Vec::new();
    if let Ok(len) = env.get_array_length(&device_ids) {
        for index in 0..len {
            if let Ok(obj) = env.get_object_array_element(&device_ids, index)
                && let Ok(js) = env.get_string(&JString::from(obj))
            {
                devices.push(js.to_string_lossy().into_owned());
            }
        }
    }
    let (response_tx, response_rx) = mpsc::channel();
    if !send_command(Command::OpenSession {
        device_ids: devices,
        ability: ability.to_string_lossy().into_owned(),
        config,
        response: response_tx,
    }) {
        return std::ptr::null_mut();
    }
    // Session ids are UUIDs, so a leading '!' unambiguously marks a failure
    // reason. Collapsing every failure to an empty string made "could not cast"
    // impossible to diagnose from the app side.
    let reply = match response_rx.recv_timeout(COMMAND_TIMEOUT) {
        Ok(Ok(session_id)) => session_id,
        Ok(Err(reason)) => format!("!{reason}"),
        Err(_) => "!hub did not answer openSession within the command timeout".to_owned(),
    };
    env.new_string(reply)
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeCloseSession(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
) {
    let Ok(session_id) = env.get_string(&session_id) else {
        return;
    };
    let _ = send_command(Command::CloseSession {
        session_id: session_id.to_string_lossy().into_owned(),
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeEnsureSessionClock(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
) -> jboolean {
    let Ok(session_id) = env.get_string(&session_id) else {
        return JNI_FALSE;
    };
    command_result(|response| Command::EnsureSessionClock {
        session_id: session_id.to_string_lossy().into_owned(),
        response,
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeSessionLocalNowNs(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let (response_tx, response_rx) = mpsc::channel();
    if !send_command(Command::SessionLocalNow {
        response: response_tx,
    }) {
        return 0;
    }
    match response_rx.recv_timeout(COMMAND_TIMEOUT) {
        Ok(Ok(value)) => value as jlong,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeSessionGroupToLocalNs(
    mut env: JNIEnv,
    _class: JClass,
    device: JString,
    group_ns: jlong,
) -> jlong {
    let Ok(device) = env.get_string(&device) else {
        return -1;
    };
    let (response_tx, response_rx) = mpsc::channel();
    if !send_command(Command::SessionGroupToLocal {
        device: device.to_string_lossy().into_owned(),
        group_ns,
        response: response_tx,
    }) {
        return -1;
    }
    match response_rx.recv_timeout(COMMAND_TIMEOUT) {
        Ok(Ok(value)) => value as jlong,
        _ => -1,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeSessionLocalToGroupNs(
    mut env: JNIEnv,
    _class: JClass,
    device: JString,
    local_ns: jlong,
) -> jlong {
    let Ok(device) = env.get_string(&device) else {
        return i64::MIN;
    };
    let (response_tx, response_rx) = mpsc::channel();
    if !send_command(Command::SessionLocalToGroup {
        device: device.to_string_lossy().into_owned(),
        local_ns: local_ns as u64,
        response: response_tx,
    }) {
        return i64::MIN;
    }
    match response_rx.recv_timeout(COMMAND_TIMEOUT) {
        Ok(Ok(value)) => value,
        _ => i64::MIN,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeSessionClockUncertaintyNs(
    mut env: JNIEnv,
    _class: JClass,
    device: JString,
) -> jlong {
    let Ok(device) = env.get_string(&device) else {
        return -1;
    };
    let (response_tx, response_rx) = mpsc::channel();
    if !send_command(Command::SessionClockUncertainty {
        device: device.to_string_lossy().into_owned(),
        response: response_tx,
    }) {
        return -1;
    }
    match response_rx.recv_timeout(COMMAND_TIMEOUT) {
        Ok(Ok(value)) => value as jlong,
        _ => -1,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_fabric_NativeHub_nativeCompleteRemote(
    env: JNIEnv,
    _class: JClass,
    request_id: jlong,
    payload: JByteArray,
) {
    let result = if payload.is_null() {
        Err("ability provider is unavailable".into())
    } else {
        env.convert_byte_array(&payload)
            .map_err(|error| error.to_string())
    };
    if let Some(completion) = runtime_value(|handle| {
        handle
            .completions
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .remove(&request_id)
    })
    .flatten()
    {
        let _ = completion.send(result);
    }
}

fn command_result(build: impl FnOnce(mpsc::Sender<Result<(), String>>) -> Command) -> jboolean {
    let (response_tx, response_rx) = mpsc::channel();
    if !send_command(build(response_tx)) {
        return JNI_FALSE;
    }
    if matches!(response_rx.recv_timeout(COMMAND_TIMEOUT), Ok(Ok(()))) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

fn send_command(command: Command) -> bool {
    runtime_value(|handle| handle.commands.blocking_send(command).is_ok()).unwrap_or(false)
}

fn runtime_value<T>(read: impl FnOnce(&RuntimeHandle) -> T) -> Option<T> {
    runtime()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .as_ref()
        .map(read)
}

fn runtime() -> &'static Mutex<Option<RuntimeHandle>> {
    RUNTIME.get_or_init(|| Mutex::new(None))
}

#[allow(clippy::too_many_arguments)]
#[allow(clippy::too_many_lines)]
async fn run_hub(
    state_directory: PathBuf,
    device_label: String,
    quic_port: u16,
    mut commands: tokio::sync::mpsc::Receiver<Command>,
    status: &Arc<Mutex<String>>,
    devices: &Arc<Mutex<String>>,
    candidates: &Arc<Mutex<String>>,
    sessions: &Arc<Mutex<BTreeMap<String, AppSession>>>,
    incoming: mpsc::Sender<IncomingRequest>,
    incoming_streams: mpsc::Sender<IncomingStreamRequest>,
    completions: Arc<Mutex<BTreeMap<i64, RemoteCompletion>>>,
) -> Result<(), String> {
    std::fs::create_dir_all(&state_directory).map_err(|error| error.to_string())?;
    let device_label = fabric_identity::sanitize_device_label(&device_label);
    let identity = DeviceIdentity::from_seed(
        load_or_create_seed(&state_directory.join("fabric-device.key"))
            .map_err(|error| error.to_string())?,
    );
    let mut hub = Hub::start(
        HubConfig {
            database_path: state_directory.join("fabric.sqlite"),
            device_label: device_label.clone(),
            quic_port,
            maximum_ipc_connections: 128,
            maximum_sessions: 512,
            maximum_buffered_bytes: 128 * 1_024 * 1_024,
            graceful_shutdown_ms: 5_000,
            relay: load_relay_config(&state_directory),
        },
        identity,
        now_ms()?,
    )
    .map_err(|error| error.to_string())?;
    let trust = Arc::new(
        FileDeviceTrustStore::open(state_directory.join("trusted-devices.txt"))
            .map_err(|error| error.to_string())?,
    );
    let labels = Arc::new(
        FileDeviceLabelStore::open(state_directory.join("device-labels.txt"))
            .map_err(|error| error.to_string())?,
    );
    let services = hub
        .start_embedded_platform_services(Arc::new(AndroidCredentials), trust.clone())
        .await
        .map_err(|error| error.to_string())?;
    let diagnostics = hub.diagnostics();
    if diagnostics.status != HealthStatus::Healthy {
        return Err(format!("unexpected Hub state: {:?}", diagnostics.status));
    }
    let address = services
        .links
        .local_addr()
        .map_err(|error| error.to_string())?;
    set_status(
        status,
        format!(
            "Healthy | device {} | QUIC {}",
            diagnostics.device_id_short,
            reachable_address(address)
        ),
    );

    // Abilities the Java layer has successfully registered, shared with the
    // incoming task so remote invoke/stream requests for an unregistered
    // ability are refused with an explicit error instead of a pipe that
    // silently EOFs (or a 10 s provider timeout).
    let registered_abilities: Arc<Mutex<BTreeSet<String>>> = Arc::new(Mutex::new(BTreeSet::new()));
    // Snapshot of dialable addresses (known trusted addresses + currently
    // discovered candidates) refreshed on the 500 ms tick, so on-demand dials
    // from spawned invoke/stream tasks never touch the command loop's state.
    let dial_hints: Arc<Mutex<DialHints>> = Arc::new(Mutex::new(DialHints::default()));

    let incoming_links = Arc::clone(&services.links);
    let incoming_completions = Arc::clone(&completions);
    let incoming_registered = Arc::clone(&registered_abilities);
    let incoming_task = tokio::spawn(async move {
        let mut next_request_id = -1_i64;
        while let Some(item) = incoming_links.next_stream().await {
            if let IncomingStream::Bi(header, mut stream) = item.stream
                && header.e2ee_header.as_ref() == BROKER_STREAM_HEADER
            {
                let Ok(request) = read_remote_request(&mut stream).await else {
                    continue;
                };
                let local_id = next_request_id;
                next_request_id = next_request_id.checked_sub(1).unwrap_or(-1);
                let has_provider = incoming_registered
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                    .contains(match &request {
                        RemoteRequest::Invocation(request) => &request.ability,
                        RemoteRequest::StreamOpen(request) => &request.ability,
                    });
                if !has_provider {
                    // provider-missing: answer immediately instead of leaving
                    // the requester to hit a timeout (invoke) or a dead pipe
                    // (stream).
                    tokio::spawn(async move {
                        let (request_id, is_stream) = match &request {
                            RemoteRequest::Invocation(request) => (request.request_id, false),
                            RemoteRequest::StreamOpen(request) => (request.request_id, true),
                        };
                        if is_stream {
                            let _ = reject_remote_stream(
                                &mut stream,
                                request_id,
                                "ability provider is unavailable",
                            )
                            .await;
                        } else {
                            let _ = write_remote_result(
                                &mut stream,
                                request_id,
                                Err("ability provider is unavailable".into()),
                            )
                            .await;
                        }
                    });
                    continue;
                }
                match request {
                    RemoteRequest::Invocation(request) => {
                        let (completion_tx, completion_rx) = tokio::sync::oneshot::channel();
                        incoming_completions
                            .lock()
                            .unwrap_or_else(std::sync::PoisonError::into_inner)
                            .insert(local_id, completion_tx);
                        if incoming
                            .send(IncomingRequest {
                                request_id: local_id,
                                ability: request.ability,
                                payload: request.payload,
                            })
                            .is_err()
                        {
                            break;
                        }
                        tokio::spawn(async move {
                            let result =
                                match tokio::time::timeout(Duration::from_secs(10), completion_rx)
                                    .await
                                {
                                    Ok(Ok(result)) => result,
                                    _ => Err("provider timed out".into()),
                                };
                            let _ =
                                write_remote_result(&mut stream, request.request_id, result).await;
                        });
                    }
                    RemoteRequest::StreamOpen(request) => {
                        let Ok(fds) = create_app_stream_fds() else {
                            continue;
                        };
                        if accept_remote_stream(&mut stream, request.request_id)
                            .await
                            .is_err()
                        {
                            close_raw_fd(fds.app_read_fd);
                            close_raw_fd(fds.app_write_fd);
                            close_raw_fd(fds.native_read_fd);
                            close_raw_fd(fds.native_write_fd);
                            continue;
                        }
                        if incoming_streams
                            .send(IncomingStreamRequest {
                                request_id: local_id,
                                ability: request.ability,
                                stream_name: request.stream_name,
                                read_fd: fds.app_read_fd,
                                write_fd: fds.app_write_fd,
                            })
                            .is_err()
                        {
                            close_raw_fd(fds.app_read_fd);
                            close_raw_fd(fds.app_write_fd);
                            close_raw_fd(fds.native_read_fd);
                            close_raw_fd(fds.native_write_fd);
                            break;
                        }
                        tokio::spawn(bridge_fd_stream(
                            stream,
                            fds.native_read_fd,
                            fds.native_write_fd,
                        ));
                    }
                }
            }
        }
    });

    let address_path = state_directory.join(DEVICE_ADDRESS_FILE);
    let mut registrations = BTreeMap::new();
    let mut addresses = load_peer_addresses(&address_path).unwrap_or_default();
    let mut last_seen = BTreeMap::<DeviceId, u64>::new();
    let first_reconnect_at = now_ms()?.saturating_add(5_000);
    let mut reconnect_after = addresses
        .keys()
        .copied()
        .map(|device| (device, first_reconnect_at))
        .collect::<BTreeMap<_, _>>();
    let mut tick = tokio::time::interval(Duration::from_millis(500));
    loop {
        tokio::select! {
            command = commands.recv() => match command {
                Some(Command::Register { ability, package, uid, response }) => {
                    let key = (ability.clone(), package.clone(), uid);
                    let result = if let std::collections::btree_map::Entry::Vacant(entry) = registrations.entry(key) {
                        register_offer(&services.ipc, &ability, &package, uid, now_ms()?).map(|id| { entry.insert(id); })
                    } else { Ok(()) };
                    if result.is_ok() {
                        registered_abilities
                            .lock()
                            .unwrap_or_else(std::sync::PoisonError::into_inner)
                            .insert(ability);
                    }
                    let _ = response.send(result);
                }
                Some(Command::Unregister { ability, package, uid }) => {
                    if let Some(connection) = registrations.remove(&(ability.clone(), package, uid)) { let _ = services.ipc.disconnect(connection); }
                    if !registrations.keys().any(|(registered, _, _)| registered == &ability) {
                        registered_abilities
                            .lock()
                            .unwrap_or_else(std::sync::PoisonError::into_inner)
                            .remove(&ability);
                    }
                }
                Some(Command::Pair { address, response }) => {
                    let result = pair_address(
                        &services.links,
                        &trust,
                        &labels,
                        &device_label,
                        services.candidates.as_deref(),
                        &mut addresses,
                        &address,
                    ).await
                        .and_then(|()| persist_peer_addresses(&address_path, &addresses));
                    let _ = response.send(result);
                }
                Some(Command::PairRemote { device, response }) => {
                    let result = pair_remote_device(
                        &services.links,
                        &trust,
                        &device_label,
                        quic_port,
                        &device,
                    )
                    .await;
                    let _ = response.send(result);
                }
                Some(Command::Accept { device, response }) => {
                    let result =
                        accept_device(&services.links, &trust, &device_label, &device).await;
                    let _ = response.send(result);
                }
                Some(Command::Remove { device, response }) => {
                    let result = remove_device(&services.links, &trust, &labels, &mut addresses, &device).await
                        .and_then(|()| persist_peer_addresses(&address_path, &addresses));
                    let _ = response.send(result);
                }
                // These three each wait on the network (up to COMMAND_TIMEOUT).
                // Awaiting them inline would stall every other command -- and,
                // worse, the SessionPropose auto-accept arm below -- for the whole
                // round trip. Spawn so the loop stays responsive.
                Some(Command::InvokeRemote { device, ability, payload, response }) => {
                    let links = Arc::clone(&services.links);
                    let trust = Arc::clone(&trust);
                    let sessions = Arc::clone(sessions);
                    let dial = Arc::clone(&dial_hints);
                    tokio::spawn(async move {
                        let result =
                            invoke_remote(&links, &trust, &sessions, &dial, quic_port, &device, &ability, payload)
                                .await
                                .map_err(|error| error.to_string());
                        let _ = response.send(result);
                    });
                }
                Some(Command::OpenRemoteStream { device, ability, stream_name, read_fd, write_fd, response }) => {
                    let links = Arc::clone(&services.links);
                    let trust = Arc::clone(&trust);
                    let sessions = Arc::clone(sessions);
                    let dial = Arc::clone(&dial_hints);
                    tokio::spawn(async move {
                        let result = open_remote_fd_stream(
                            &links,
                            &trust,
                            &sessions,
                            &dial,
                            quic_port,
                            &device,
                            &ability,
                            &stream_name,
                            read_fd,
                            write_fd,
                        ).await;
                        let _ = response.send(result);
                    });
                }
                Some(Command::OpenSession { device_ids, ability, config, response }) => {
                    let links = Arc::clone(&services.links);
                    let sessions = Arc::clone(sessions);
                    tokio::spawn(async move {
                        let result = open_audio_network_session(
                            &links,
                            &sessions,
                            device_ids,
                            ability,
                            &config,
                        )
                        .await;
                        let _ = response.send(result);
                    });
                }
                Some(Command::CloseSession { session_id }) => {
                    let fabric_id = sessions
                        .lock()
                        .unwrap_or_else(std::sync::PoisonError::into_inner)
                        .remove(&session_id)
                        .and_then(|session| session.fabric_session_id);
                    if let Some(fabric_id) = fabric_id {
                        services.links.remove_session(fabric_id).await;
                    }
                }
                Some(Command::EnsureSessionClock { session_id, response }) => {
                    let peers = sessions
                        .lock()
                        .unwrap_or_else(std::sync::PoisonError::into_inner)
                        .get(&session_id)
                        .map(|session| session.devices.clone());
                    let result = match peers {
                        None => Err("session not found".into()),
                        Some(peers) => {
                            let mut ok = true;
                            for peer in peers {
                                match decode_device_id(&peer) {
                                    Ok(device_id) => {
                                        if services.links.ensure_peer_clock(device_id).await.is_err() {
                                            ok = false;
                                        }
                                    }
                                    Err(_) => ok = false,
                                }
                            }
                            if ok {
                                Ok(())
                            } else {
                                Err("clock sync failed for one or more peers".into())
                            }
                        }
                    };
                    let _ = response.send(result);
                }
                Some(Command::SessionLocalNow { response }) => {
                    let _ = response.send(Ok(services.links.hub_local_now_ns()));
                }
                Some(Command::SessionGroupToLocal { device, group_ns, response }) => {
                    let result = match decode_device_id(&device) {
                        Ok(device_id) => match services
                            .links
                            .peer_group_to_local(device_id, i128::from(group_ns))
                            .await
                        {
                            Some((local, _)) => Ok(local),
                            None => Err("clock mapping unavailable".into()),
                        },
                        Err(error) => Err(error.to_string()),
                    };
                    let _ = response.send(result);
                }
                Some(Command::SessionLocalToGroup { device, local_ns, response }) => {
                    let result = match decode_device_id(&device) {
                        Ok(device_id) => match services
                            .links
                            .peer_local_to_group(device_id, local_ns)
                            .await
                        {
                            Some((group, _)) => Ok(i64::try_from(group).unwrap_or(i64::MAX)),
                            None => Err("clock mapping unavailable".into()),
                        },
                        Err(error) => Err(error.to_string()),
                    };
                    let _ = response.send(result);
                }
                Some(Command::SessionClockUncertainty { device, response }) => {
                    let result = match decode_device_id(&device) {
                        Ok(device_id) => match services.links.peer_clock_mapping(device_id).await {
                            Some(mapping) => Ok(mapping.uncertainty_ns),
                            None => Err("clock mapping unavailable".into()),
                        },
                        Err(error) => Err(error.to_string()),
                    };
                    let _ = response.send(result);
                }
                Some(Command::Stop) | None => break,
            },
            control = services.links.next_control() => {
                if let Some(control) = control {
                    match control.message {
                        ControlMessage::PairingStart(start) => {
                            labels
                                .set_label(control.peer, &start.device_label)
                                .map_err(|error| error.to_string())?;
                            trust.set_trust(control.peer, DeviceTrust::PendingUserConfirmation).map_err(|error| error.to_string())?;
                            // Refresh device list immediately so the pending approval row appears
                            // without waiting for the next 500ms tick + UI poll.
                            update_devices(devices, &services.links, &trust, &labels, &mut last_seen).await?;
                        }
                        ControlMessage::PairingComplete(complete) if complete.device_id == control.peer => {
                            labels
                                .set_label(control.peer, &complete.device_label)
                                .map_err(|error| error.to_string())?;
                            trust.set_trust(control.peer, DeviceTrust::Trusted).map_err(|error| error.to_string())?;
                            services.links.disconnect_peer(control.peer).await;
                            if let Some(address) = addresses.get(&control.peer).copied() {
                                let _ = services.links.connect(address).await;
                            } else if services.links.has_relay() {
                                // Relay-paired peers have no direct address yet.
                                let _ = services.links.connect_by_device_via_relay(control.peer, local_candidates(quic_port)).await;
                            }
                        }
                        ControlMessage::SessionPropose(propose) => {
                            // Auto-accept audio (and any) network sessions for trusted peers.
                            let sid = propose.header.session_id;
                            if services.links.accept_network_session(sid).await.is_ok() {
                                let app_id = sid.0.to_string();
                                // Record the real ability from the plan's contract
                                // so `negotiated_session` can match this session
                                // when the accepting side later opens streams or
                                // invokes back (relay fanout, renderer->source).
                                let (ability_name, peers) =
                                    serde_json::from_slice::<SessionPlan>(&propose.encoded_draft_plan)
                                        .ok()
                                        .map(|plan| {
                                            let key = &plan.contract.key;
                                            let ability = format!(
                                                "{}.{}",
                                                key.namespace.as_str(),
                                                key.name.as_str()
                                            );
                                            let peers = plan
                                                .participants
                                                .iter()
                                                .map(|p| encode_device_id(p.device_id))
                                                .collect::<Vec<_>>();
                                            (ability, peers)
                                        })
                                        .unwrap_or_else(|| ("incoming".into(), Vec::new()));
                                sessions
                                    .lock()
                                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                                    .insert(
                                        app_id,
                                        AppSession {
                                            ability: ability_name,
                                            devices: peers,
                                            fabric_session_id: Some(sid),
                                            delivery: "per_peer_unicast".into(),
                                            relay_root: None,
                                        },
                                    );
                            }
                        }
                        _ => {}
                    }
                }
            },
            _ = tick.tick() => {
                let mut runtime_status = format!(
                    "Healthy | device {} | QUIC {}",
                    diagnostics.device_id_short,
                    reachable_address(address),
                );
                if let Some(error) = services.links.last_dial_error().await {
                    runtime_status.push_str(" | link: ");
                    runtime_status.push_str(&error);
                }
                set_status(status, runtime_status);
                update_devices(devices, &services.links, &trust, &labels, &mut last_seen).await?;
                persist_authenticated_direct_addresses(
                    &services.links,
                    &trust,
                    &mut addresses,
                    &address_path,
                ).await?;
                let has_discovered_addresses = services
                    .candidates
                    .as_ref()
                    .and_then(|store| store.values().ok())
                    .is_some_and(|values| {
                        values
                            .iter()
                            .any(|candidate| !quic_socket_addresses(candidate).is_empty())
                    });
                if !has_discovered_addresses {
                    reconnect_known_addresses(
                        &services.links,
                        &trust,
                        &addresses,
                        &last_seen,
                        &mut reconnect_after,
                    ).await?;
                }
                if let Some(store) = &services.candidates {
                    update_candidates(candidates, store, &addresses, &trust)?;
                }
                refresh_dial_hints(&dial_hints, &addresses, services.candidates.as_deref());
            },
        }
    }
    incoming_task.abort();
    let _ = incoming_task.await;
    for connection in registrations.into_values() {
        let _ = services.ipc.disconnect(connection);
    }
    hub.shutdown().await.map_err(|error| error.to_string())?;
    set_status(status, "Stopped".into());
    Ok(())
}

fn update_candidates(
    output: &Arc<Mutex<String>>,
    store: &fabric_hub::CandidateStore,
    known_addresses: &BTreeMap<DeviceId, SocketAddr>,
    trust: &Arc<FileDeviceTrustStore>,
) -> Result<(), String> {
    // Hide peers we already know (trusted or pending). Match by IP so port churn
    // from mDNS restarts does not re-surface paired devices as "Nearby".
    let mut known_ips = BTreeSet::new();
    for (device, address) in known_addresses {
        match trust.trust(*device).map_err(|error| error.to_string())? {
            DeviceTrust::Trusted | DeviceTrust::PendingUserConfirmation => {
                known_ips.insert(address.ip());
            }
            DeviceTrust::Blocked | DeviceTrust::Unknown => {}
        }
    }
    let mut merged = BTreeMap::<String, (String, SocketAddr, Vec<&'static str>)>::new();
    for candidate in store.values().map_err(|error| error.to_string())? {
        let Some(address) = quic_socket_addresses(&candidate).into_iter().next() else {
            continue;
        };
        if known_ips.contains(&address.ip()) {
            continue;
        }
        let key = candidate.device_hint.map_or_else(
            || candidate.discovery_key.0.clone(),
            |hint| encode_rotating_hint(&hint),
        );
        let label = candidate.label;
        let entry = merged
            .entry(key)
            .or_insert_with(|| (label, address, Vec::new()));
        for source in candidate.sources {
            let label = match source {
                DiscoverySource::Mdns => "mDNS",
                DiscoverySource::Ble => "BLE",
                DiscoverySource::WifiAware => "Wi-Fi Aware",
            };
            if !entry.2.contains(&label) {
                entry.2.push(label);
            }
        }
    }
    let value = merged
        .into_values()
        .map(|(label, address, sources)| format!("{label}|{address}|{}", sources.join(" + ")))
        .collect::<Vec<_>>()
        .join("\n");
    let mut guard = output
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    // Avoid waking UI/JNI readers when nothing changed.
    if *guard != value {
        *guard = value;
    }
    Ok(())
}

fn label_for_candidate_address(
    store: &fabric_hub::CandidateStore,
    address: SocketAddr,
) -> Result<Option<String>, String> {
    Ok(store
        .values()
        .map_err(|error| error.to_string())?
        .into_iter()
        .find(|candidate| quic_socket_addresses(candidate).contains(&address))
        .map(|candidate| candidate.label))
}

async fn pair_address(
    links: &Arc<LinkServices>,
    trust: &Arc<FileDeviceTrustStore>,
    labels: &Arc<FileDeviceLabelStore>,
    device_label: &str,
    candidates: Option<&fabric_hub::CandidateStore>,
    addresses: &mut BTreeMap<DeviceId, SocketAddr>,
    encoded: &str,
) -> Result<(), String> {
    let address = encoded
        .parse::<SocketAddr>()
        .map_err(|_| "invalid IP address and port".to_string())?;
    let discovered_label =
        candidates.and_then(|store| label_for_candidate_address(store, address).ok().flatten());
    // Prefer an existing link to this address/peer so Pair does not re-dial for 3–8s.
    let peer = if let Some((device, _)) = addresses.iter().find(|(_, known)| **known == address) {
        let device = *device;
        if links.link(device).await.is_some() {
            device
        } else {
            tokio::time::timeout(PAIR_CONNECT_TIMEOUT, links.connect(address))
                .await
                .map_err(|_| "pairing connection timed out".to_string())?
                .map_err(|error| error.to_string())?
        }
    } else {
        tokio::time::timeout(PAIR_CONNECT_TIMEOUT, links.connect(address))
            .await
            .map_err(|_| "pairing connection timed out".to_string())?
            .map_err(|error| error.to_string())?
    };
    addresses.insert(peer, address);
    if let Some(label) = discovered_label {
        labels
            .set_label(peer, &label)
            .map_err(|error| error.to_string())?;
    }
    if trust.trust(peer).map_err(|error| error.to_string())? != DeviceTrust::Trusted {
        // Mark pending *before* the wire request so both UIs can show "Approval required"
        // as soon as the next device snapshot is read (and on the remote once PairingStart lands).
        trust
            .set_trust(peer, DeviceTrust::PendingUserConfirmation)
            .map_err(|error| error.to_string())?;
        // Fire PairingStart without stacking behind a long timeout if the peer is already linked.
        links
            .request_pairing(peer, device_label)
            .await
            .map_err(|error| error.to_string())?;
    }
    Ok(())
}

/// Dials `encoded` (a peer's `DeviceId`) through the configured relay and, if the
/// peer is not yet trusted, sends a pairing request. Used for cross-network
/// pairing where no LAN address is known.
async fn pair_remote_device(
    links: &Arc<LinkServices>,
    trust: &Arc<FileDeviceTrustStore>,
    device_label: &str,
    quic_port: u16,
    encoded: &str,
) -> Result<(), String> {
    if !links.has_relay() {
        return Err("no relay configured (add relay.conf to the state directory)".to_string());
    }
    let peer = decode_device_id(encoded).map_err(|error| error.to_string())?;
    tokio::time::timeout(
        RELAY_PAIR_TIMEOUT,
        links.connect_by_device_via_relay(peer, local_candidates(quic_port)),
    )
    .await
    .map_err(|_| "relay connection timed out".to_string())?
    .map_err(|error| error.to_string())?;
    if trust.trust(peer).map_err(|error| error.to_string())? != DeviceTrust::Trusted {
        trust
            .set_trust(peer, DeviceTrust::PendingUserConfirmation)
            .map_err(|error| error.to_string())?;
        links
            .request_pairing(peer, device_label)
            .await
            .map_err(|error| error.to_string())?;
    }
    Ok(())
}

async fn accept_device(
    links: &Arc<LinkServices>,
    trust: &Arc<FileDeviceTrustStore>,
    device_label: &str,
    encoded: &str,
) -> Result<(), String> {
    let peer = decode_device_id(encoded).map_err(|error| error.to_string())?;
    tokio::time::timeout(
        PAIR_MESSAGE_TIMEOUT,
        links.complete_pairing(peer, device_label),
    )
    .await
    .map_err(|_| "pairing approval timed out".to_string())?
    .map_err(|error| error.to_string())?;
    trust
        .set_trust(peer, DeviceTrust::Trusted)
        .map_err(|error| error.to_string())?;
    tokio::time::sleep(Duration::from_millis(100)).await;
    links.disconnect_peer(peer).await;
    Ok(())
}

async fn remove_device(
    links: &Arc<LinkServices>,
    trust: &Arc<FileDeviceTrustStore>,
    labels: &Arc<FileDeviceLabelStore>,
    addresses: &mut BTreeMap<DeviceId, SocketAddr>,
    encoded: &str,
) -> Result<(), String> {
    let peer = decode_device_id(encoded).map_err(|error| error.to_string())?;
    trust.remove(peer).map_err(|error| error.to_string())?;
    labels.remove(peer).map_err(|error| error.to_string())?;
    addresses.remove(&peer);
    links.disconnect_peer(peer).await;
    Ok(())
}

/// Fabric session negotiated by `openSession` for this (device, ability) pair.
///
/// Media and control streams must ride it, otherwise the delivery policy and
/// clock domain agreed during session setup apply to nothing and every stream
/// looks like an unrelated one-off to the peer.
fn negotiated_session(
    sessions: &Arc<Mutex<BTreeMap<String, AppSession>>>,
    encoded_device: &str,
    ability: &str,
) -> Option<(SessionId, u64)> {
    sessions
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .values()
        .find(|session| {
            session.ability == ability && session.devices.iter().any(|id| id == encoded_device)
        })
        .and_then(|session| session.fabric_session_id.map(|id| (id, SESSION_EPOCH)))
}

/// Epoch used by `build_audio_session_plan`; streams must match it.
const SESSION_EPOCH: u64 = 1;

/// Per-attempt timeout for an on-demand dial. Kept short so the total dial
/// budget stays well inside the 12 s JNI command timeout that also covers the
/// broker round trip.
const ON_DEMAND_DIAL_TIMEOUT: Duration = Duration::from_millis(1_500);
/// At most this many direct addresses are tried per on-demand dial.
const ON_DEMAND_DIAL_ATTEMPTS: usize = 2;

/// Snapshot of dialable addresses for spawned invoke/stream tasks, refreshed
/// on the command-loop tick. `known` mirrors trusted-addresses.txt; `discovered`
/// holds current discovery-candidate QUIC addresses. Dialing a discovered
/// address never weakens trust: the QUIC handshake authenticates whoever
/// answers, and the caller re-checks `links.link(peer)` for the wanted device.
#[derive(Default)]
struct DialHints {
    known: BTreeMap<DeviceId, SocketAddr>,
    discovered: Vec<SocketAddr>,
}

/// Return an existing link to `peer`, or make one bounded on-demand connection
/// attempt (known address, then discovered candidates, then relay) before
/// giving up with a category-tagged error.
async fn ensure_link(
    links: &Arc<LinkServices>,
    dial: &Arc<Mutex<DialHints>>,
    quic_port: u16,
    peer: DeviceId,
) -> Result<Arc<fabric_transport_quic::QuicLink>, String> {
    if let Some(link) = links.link(peer).await {
        return Ok(link);
    }
    let (known, discovered) = {
        let hints = dial
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        (hints.known.get(&peer).copied(), hints.discovered.clone())
    };
    let mut attempts: Vec<SocketAddr> = Vec::new();
    if let Some(address) = known {
        attempts.push(address);
    }
    for address in discovered {
        if attempts.len() >= ON_DEMAND_DIAL_ATTEMPTS {
            break;
        }
        if !attempts.contains(&address) {
            attempts.push(address);
        }
    }
    if attempts.is_empty() && !links.has_relay() {
        return Err("device offline: no known address".into());
    }
    for address in &attempts {
        let _ = tokio::time::timeout(ON_DEMAND_DIAL_TIMEOUT, links.connect(*address)).await;
        if let Some(link) = links.link(peer).await {
            return Ok(link);
        }
    }
    if links.has_relay() {
        let _ = tokio::time::timeout(
            PAIR_CONNECT_TIMEOUT,
            links.connect_by_device_via_relay(peer, local_candidates(quic_port)),
        )
        .await;
        if let Some(link) = links.link(peer).await {
            return Ok(link);
        }
    }
    Err(if attempts.is_empty() {
        "device offline: no known address".into()
    } else {
        format!(
            "device offline: dial failed ({} address(es) tried)",
            attempts.len()
        )
    })
}

/// Refresh the shared dial snapshot from the command loop's authoritative
/// address book and the current discovery candidates.
fn refresh_dial_hints(
    dial: &Arc<Mutex<DialHints>>,
    addresses: &BTreeMap<DeviceId, SocketAddr>,
    store: Option<&fabric_hub::CandidateStore>,
) {
    const MAX_DISCOVERED: usize = 8;
    let mut discovered = Vec::new();
    if let Some(store) = store
        && let Ok(values) = store.values()
    {
        for candidate in values {
            if discovered.len() >= MAX_DISCOVERED {
                break;
            }
            if let Some(address) = quic_socket_addresses(&candidate).into_iter().next()
                && !discovered.contains(&address)
            {
                discovered.push(address);
            }
        }
    }
    let mut hints = dial
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    hints.known = addresses.clone();
    hints.discovered = discovered;
}

/// Keep trusted-addresses.txt in sync with physical addresses that successfully
/// authenticated as trusted peers. Never derive this from the active QUIC path:
/// a magic-socket connection reports a synthetic mapped remote address which is
/// neither the peer's listener nor stable across process restarts.
async fn persist_authenticated_direct_addresses(
    links: &Arc<LinkServices>,
    trust: &Arc<FileDeviceTrustStore>,
    addresses: &mut BTreeMap<DeviceId, SocketAddr>,
    address_path: &Path,
) -> Result<(), String> {
    let mut changed = false;
    for peer in links.active_peers().await {
        if trust
            .trust(peer.device_id)
            .map_err(|error| error.to_string())?
            != DeviceTrust::Trusted
        {
            continue;
        }
        let Some(address) = links.direct_address(peer.device_id).await else {
            continue;
        };
        if addresses.get(&peer.device_id) != Some(&address) {
            addresses.insert(peer.device_id, address);
            changed = true;
        }
    }
    if changed {
        persist_peer_addresses(address_path, addresses)?;
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
async fn invoke_remote(
    links: &Arc<LinkServices>,
    trust: &Arc<FileDeviceTrustStore>,
    sessions: &Arc<Mutex<BTreeMap<String, AppSession>>>,
    dial: &Arc<Mutex<DialHints>>,
    quic_port: u16,
    encoded: &str,
    ability: &str,
    payload: Vec<u8>,
) -> Result<Vec<u8>, BrokerError> {
    let peer =
        decode_device_id(encoded).map_err(|_| BrokerError::Rejected("invalid device id".into()))?;
    let trusted = trust
        .trust(peer)
        .map_err(|error| BrokerError::Rejected(error.to_string()))?
        == DeviceTrust::Trusted;
    if !trusted {
        return Err(BrokerError::Rejected("device is not trusted".into()));
    }
    let link = ensure_link(links, dial, quic_port, peer)
        .await
        .map_err(BrokerError::Rejected)?;
    let (session_id, epoch) =
        negotiated_session(sessions, encoded, ability).unwrap_or((SessionId::new(), 0));
    let mut stream = link
        .open_bi(StreamOpen {
            session_id,
            epoch,
            channel_id: ChannelId::new(),
            sender: ParticipantId::new(),
            destination_binding: [0; 16],
            flags: 0,
            e2ee_header: Bytes::from_static(BROKER_STREAM_HEADER),
        })
        .await
        .map_err(|_| BrokerError::DeviceUnavailable)?;
    invoke_remote_stream(&mut stream, 1, ability, payload).await
}

#[cfg(target_os = "android")]
#[allow(clippy::too_many_arguments)]
async fn open_remote_fd_stream(
    links: &Arc<LinkServices>,
    trust: &Arc<FileDeviceTrustStore>,
    sessions: &Arc<Mutex<BTreeMap<String, AppSession>>>,
    dial: &Arc<Mutex<DialHints>>,
    quic_port: u16,
    encoded: &str,
    ability: &str,
    stream_name: &str,
    read_fd: i32,
    write_fd: i32,
) -> Result<(), String> {
    use tokio::io::AsyncWriteExt as _;

    let peer = decode_device_id(encoded).map_err(|error| error.to_string())?;
    let trusted = trust.trust(peer).map_err(|error| error.to_string())? == DeviceTrust::Trusted;
    if !trusted {
        return Err("device is not trusted".into());
    }
    let link = ensure_link(links, dial, quic_port, peer).await?;
    let (session_id, epoch) =
        negotiated_session(sessions, encoded, ability).unwrap_or((SessionId::new(), 0));
    let mut stream = link
        .open_bi(StreamOpen {
            session_id,
            epoch,
            channel_id: ChannelId::new(),
            sender: ParticipantId::new(),
            destination_binding: [0; 16],
            flags: 0,
            e2ee_header: Bytes::from_static(BROKER_STREAM_HEADER),
        })
        .await
        .map_err(|error| error.to_string())?;
    fabric_app_broker::open_remote_stream(&mut stream, 1, ability, stream_name)
        .await
        .map_err(|error| error.to_string())?;
    let read_file = unsafe { std::fs::File::from_raw_fd(read_fd as RawFd) };
    let write_file = unsafe { std::fs::File::from_raw_fd(write_fd as RawFd) };
    let mut app_read = tokio::fs::File::from_std(read_file);
    let mut app_write = tokio::fs::File::from_std(write_file);
    let (mut remote_read, mut remote_write) = tokio::io::split(stream);
    tokio::spawn(async move {
        let upload = async {
            let _ = tokio::io::copy(&mut app_read, &mut remote_write).await;
            let _ = remote_write.shutdown().await;
        };
        let download = async {
            let _ = tokio::io::copy(&mut remote_read, &mut app_write).await;
            let _ = app_write.shutdown().await;
        };
        tokio::join!(upload, download);
    });
    Ok(())
}

#[cfg(not(target_os = "android"))]
#[allow(clippy::too_many_arguments)]
async fn open_remote_fd_stream(
    _links: &Arc<LinkServices>,
    _trust: &Arc<FileDeviceTrustStore>,
    _sessions: &Arc<Mutex<BTreeMap<String, AppSession>>>,
    _dial: &Arc<Mutex<DialHints>>,
    _quic_port: u16,
    _encoded: &str,
    _ability: &str,
    _stream_name: &str,
    _read_fd: i32,
    _write_fd: i32,
) -> Result<(), String> {
    Err("fd streams are only available on Android".into())
}

struct AppStreamFds {
    app_read_fd: i32,
    app_write_fd: i32,
    native_read_fd: i32,
    native_write_fd: i32,
}

#[cfg(target_os = "android")]
fn create_app_stream_fds() -> Result<AppStreamFds, String> {
    let mut inbound = [0_i32; 2];
    let mut outbound = [0_i32; 2];
    // SAFETY: both arrays are valid two-int buffers for pipe(2).
    if unsafe { libc::pipe(inbound.as_mut_ptr()) } != 0 {
        return Err("failed to create inbound pipe".into());
    }
    // SAFETY: both arrays are valid two-int buffers for pipe(2).
    if unsafe { libc::pipe(outbound.as_mut_ptr()) } != 0 {
        close_raw_fd(inbound[0]);
        close_raw_fd(inbound[1]);
        return Err("failed to create outbound pipe".into());
    }
    Ok(AppStreamFds {
        app_read_fd: inbound[0],
        app_write_fd: outbound[1],
        native_read_fd: outbound[0],
        native_write_fd: inbound[1],
    })
}

#[cfg(not(target_os = "android"))]
fn create_app_stream_fds() -> Result<AppStreamFds, String> {
    Err("fd streams are only available on Android".into())
}

#[cfg(target_os = "android")]
async fn bridge_fd_stream<S>(stream: S, read_fd: i32, write_fd: i32)
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    use tokio::io::AsyncWriteExt as _;

    let read_file = unsafe { std::fs::File::from_raw_fd(read_fd as RawFd) };
    let write_file = unsafe { std::fs::File::from_raw_fd(write_fd as RawFd) };
    let mut app_read = tokio::fs::File::from_std(read_file);
    let mut app_write = tokio::fs::File::from_std(write_file);
    let (mut remote_read, mut remote_write) = tokio::io::split(stream);
    let upload = async {
        let _ = tokio::io::copy(&mut app_read, &mut remote_write).await;
        let _ = remote_write.shutdown().await;
    };
    let download = async {
        let _ = tokio::io::copy(&mut remote_read, &mut app_write).await;
        let _ = app_write.shutdown().await;
    };
    tokio::join!(upload, download);
}

#[cfg(not(target_os = "android"))]
async fn bridge_fd_stream<S>(_stream: S, _read_fd: i32, _write_fd: i32)
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
}

#[cfg(target_os = "android")]
fn close_raw_fd(fd: i32) {
    // SAFETY: closing an owned raw fd is safe; errors are intentionally ignored.
    let _ = unsafe { libc::close(fd) };
}

#[cfg(not(target_os = "android"))]
fn close_raw_fd(_fd: i32) {}

async fn update_devices(
    output: &Arc<Mutex<String>>,
    links: &Arc<LinkServices>,
    trust: &Arc<FileDeviceTrustStore>,
    labels: &Arc<FileDeviceLabelStore>,
    last_seen: &mut BTreeMap<DeviceId, u64>,
) -> Result<(), String> {
    let now = now_ms()?;
    let online = links
        .active_peers()
        .await
        .into_iter()
        .map(|peer| peer.device_id)
        .collect::<BTreeSet<_>>();
    for device in &online {
        last_seen.insert(*device, now);
    }
    let mut rows = Vec::new();
    for (device, stored) in trust.entries().map_err(|error| error.to_string())? {
        let state = match stored {
            DeviceTrust::PendingUserConfirmation => "pending",
            DeviceTrust::Trusted => "trusted",
            DeviceTrust::Blocked => "blocked",
            DeviceTrust::Unknown => "unknown",
        };
        let is_online = online.contains(&device)
            || last_seen
                .get(&device)
                .is_some_and(|seen_at| now.saturating_sub(*seen_at) <= DEVICE_ONLINE_GRACE_MS);
        let encoded = encode_device_id(device);
        let label = labels
            .label(device)
            .map_err(|error| error.to_string())?
            .unwrap_or_else(|| format!("Device {}", encoded.chars().take(12).collect::<String>()));
        rows.push(format!("{}|{}|{}|{}", encoded, state, is_online, label));
    }
    let value = rows.join("\n");
    let mut guard = output
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if *guard != value {
        *guard = value;
    }
    Ok(())
}

async fn reconnect_known_addresses(
    links: &Arc<LinkServices>,
    trust: &Arc<FileDeviceTrustStore>,
    addresses: &BTreeMap<DeviceId, SocketAddr>,
    last_seen: &BTreeMap<DeviceId, u64>,
    reconnect_after: &mut BTreeMap<DeviceId, u64>,
) -> Result<(), String> {
    let now = now_ms()?;
    let active = links
        .active_peers()
        .await
        .into_iter()
        .map(|peer| peer.device_id)
        .collect::<BTreeSet<_>>();
    for (device, address) in addresses {
        if links.local_device_id() < *device
            || active.contains(device)
            || last_seen
                .get(device)
                .is_some_and(|seen_at| now.saturating_sub(*seen_at) <= DEVICE_ONLINE_GRACE_MS)
            || reconnect_after
                .get(device)
                .is_some_and(|deadline| *deadline > now)
            || trust.trust(*device).map_err(|error| error.to_string())? != DeviceTrust::Trusted
        {
            continue;
        }
        reconnect_after.insert(*device, now.saturating_add(RECONNECT_INTERVAL_MS));
        let links = Arc::clone(links);
        let address = *address;
        tokio::spawn(async move {
            let _ = tokio::time::timeout(Duration::from_secs(20), links.connect(address)).await;
        });
    }
    reconnect_after.retain(|device, deadline| addresses.contains_key(device) && *deadline > now);
    Ok(())
}

fn load_peer_addresses(path: &Path) -> Result<BTreeMap<DeviceId, SocketAddr>, String> {
    let contents = match fs::read_to_string(path) {
        Ok(contents) => contents,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(BTreeMap::new()),
        Err(error) => return Err(error.to_string()),
    };
    let mut addresses = BTreeMap::new();
    for line in contents.lines().filter(|line| !line.trim().is_empty()) {
        let Some((device, address)) = line.split_once(' ') else {
            continue;
        };
        let (Ok(device), Ok(address)) = (decode_device_id(device), address.parse::<SocketAddr>())
        else {
            continue;
        };
        // Migration for builds which persisted magic-socket's mapped path as if
        // it were a peer listener. Both peers could receive this exact address,
        // producing permanent Offline entries plus a duplicate Nearby device.
        if !is_magic_socket_mapped_address(address) {
            addresses.insert(device, address);
        }
    }
    Ok(addresses)
}

fn is_magic_socket_mapped_address(address: SocketAddr) -> bool {
    let IpAddr::V6(ip) = address.ip() else {
        return false;
    };
    let segments = ip.segments();
    segments[..3] == [0xfd15, 0x070a, 0x510b] && address.port() == 0xF00D
}

fn persist_peer_addresses(
    path: &Path,
    addresses: &BTreeMap<DeviceId, SocketAddr>,
) -> Result<(), String> {
    if let Some(parent) = path.parent().filter(|path| !path.as_os_str().is_empty()) {
        fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    let temporary = temporary_path(path);
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(&temporary)
        .map_err(|error| error.to_string())?;
    for (device, address) in addresses {
        writeln!(file, "{} {}", encode_device_id(*device), address)
            .map_err(|error| error.to_string())?;
    }
    file.sync_all().map_err(|error| error.to_string())?;
    if path.exists() {
        fs::remove_file(path).map_err(|error| error.to_string())?;
    }
    fs::rename(&temporary, path).map_err(|error| error.to_string())
}

fn temporary_path(path: &Path) -> PathBuf {
    let mut temporary = path.to_path_buf();
    temporary.set_extension("tmp");
    temporary
}

fn register_offer(
    ipc: &fabric_hub::HubIpcServer,
    ability: &str,
    package: &str,
    uid: i32,
    now_ms: u64,
) -> Result<fabric_core::ConnectionId, String> {
    let (namespace, name) = ability
        .rsplit_once('.')
        .ok_or_else(|| "ability must contain a namespace".to_string())?;
    let credentials = PeerCredentials {
        platform: Platform::Android,
        stable_app_id: package.into(),
        publisher_id: None,
        signing_digest: None,
        os_subject: OsSubject(format!("android:uid:{uid}")),
    };
    let (connection, principal) = ipc
        .connect(&credentials, Some(package), ConnectionQuota::default())
        .map_err(|error| error.to_string())?;
    let protocol_hash: [u8; 32] = Sha256::digest(ECHO_SCHEMA).into();
    let offer = AbilityOffer {
        instance_id: AbilityInstanceId::new(),
        contract: AbilityContractRef {
            key: AbilityKey {
                namespace: Namespace::new(namespace).map_err(|error| error.to_string())?,
                name: AbilityName::new(name).map_err(|error| error.to_string())?,
                major: 1,
            },
            protocol_hash,
        },
        app: principal,
        roles: vec![RoleId::new("peer").map_err(|error| error.to_string())?],
        properties: PropertyMap::new(),
        visibility: OfferVisibility::TrustedDevices,
        access_policy: PolicyRef("default".into()),
        lease: LeaseSpec::ConnectionBound,
    };
    if let Err(error) = ipc.publish_offer(connection, offer, now_ms) {
        let _ = ipc.disconnect(connection);
        return Err(error.to_string());
    }
    Ok(connection)
}

fn set_status(status: &Arc<Mutex<String>>, value: String) {
    *status
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = value;
}

fn reachable_address(bound: SocketAddr) -> String {
    if !bound.ip().is_unspecified() {
        return bound.to_string();
    }
    UdpSocket::bind("0.0.0.0:0")
        .and_then(|socket| {
            socket.connect("8.8.8.8:80")?;
            socket.local_addr()
        })
        .map_or_else(
            |_| bound.to_string(),
            |local| SocketAddr::new(local.ip(), bound.port()).to_string(),
        )
}

fn now_ms() -> Result<u64, String> {
    u64::try_from(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|error| error.to_string())?
            .as_millis(),
    )
    .map_err(|error| error.to_string())
}

/// Loads relay settings from `relay.conf` in the state directory (line 1:
/// `host:port`, optional line 2: 64-hex-char TLS fingerprint). The Android host
/// app writes this file when the user configures a relay; there is no relay
/// unless it exists.
fn load_relay_config(state_directory: &Path) -> Option<RelayEndpointConfig> {
    let content = std::fs::read_to_string(state_directory.join("relay.conf")).ok()?;
    let mut lines = content
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'));
    let address = lines.next()?.parse().ok()?;
    let tls_fingerprint = lines.next().and_then(parse_fingerprint_hex);
    Some(RelayEndpointConfig {
        address,
        tls_fingerprint,
    })
}

/// Parses a 64-character hex string into a pinned certificate fingerprint.
fn parse_fingerprint_hex(text: &str) -> Option<[u8; 32]> {
    let text = text.trim();
    if text.len() != 64 {
        return None;
    }
    let mut fingerprint = [0u8; 32];
    for (index, chunk) in text.as_bytes().chunks_exact(2).enumerate() {
        let hex = std::str::from_utf8(chunk).ok()?;
        fingerprint[index] = u8::from_str_radix(hex, 16).ok()?;
    }
    Some(fingerprint)
}

/// Our directly-reachable addresses to advertise as hole-punch candidates.
fn local_candidates(quic_port: u16) -> Vec<SocketAddr> {
    std::net::UdpSocket::bind("0.0.0.0:0")
        .and_then(|socket| {
            socket.connect("8.8.8.8:80")?;
            socket.local_addr()
        })
        .map(|local| vec![SocketAddr::new(local.ip(), quic_port)])
        .unwrap_or_default()
}

fn load_or_create_seed(path: &Path) -> Result<[u8; 32], Box<dyn std::error::Error>> {
    match OpenOptions::new().read(true).open(path) {
        Ok(mut file) => {
            let mut seed = [0; 32];
            file.read_exact(&mut seed)?;
            let mut extra = [0; 1];
            if file.read(&mut extra)? != 0 {
                return Err("device key file must contain exactly 32 bytes".into());
            }
            Ok(seed)
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            let seed = DeviceIdentity::generate().seed_for_secure_storage();
            let mut file = OpenOptions::new().write(true).create_new(true).open(path)?;
            file.write_all(&seed)?;
            file.sync_all()?;
            Ok(seed)
        }
        Err(error) => Err(error.into()),
    }
}
