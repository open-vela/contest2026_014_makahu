use std::{
    env,
    fs::OpenOptions,
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use fabric_core::{AppPrincipal, Platform};
use fabric_hub::{Hub, HubConfig};
use fabric_identity::{
    DeviceIdentity, IdentityError, MemoryDeviceTrustStore, OsCredentialAdapter, PeerCredentials,
};

struct OsResolvedCredentials;

impl OsCredentialAdapter for OsResolvedCredentials {
    fn authenticate(&self, credentials: &PeerCredentials) -> Result<AppPrincipal, IdentityError> {
        if credentials.platform == Platform::Test
            || credentials.stable_app_id.is_empty()
            || credentials.os_subject.0.is_empty()
        {
            return Err(IdentityError::AppIdentityMismatch);
        }
        let principal = AppPrincipal {
            platform: credentials.platform,
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

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut arguments = env::args_os().skip(1);
    let database_argument = arguments.next();
    let key_argument = arguments.next();
    if arguments.next().is_some() {
        return Err("usage: fabric-hub [database-path] [device-key-path]".into());
    }
    let (database_path, key_path) = match (database_argument, key_argument) {
        (Some(database), Some(key)) => (PathBuf::from(database), PathBuf::from(key)),
        (Some(database), None) => (
            PathBuf::from(database),
            default_state_dir()?.join("fabric-device.key"),
        ),
        (None, None) => {
            let directory = default_state_dir()?;
            (
                directory.join("fabric.sqlite"),
                directory.join("fabric-device.key"),
            )
        }
        (None, Some(_)) => unreachable!("the second argument cannot exist without the first"),
    };
    let identity = DeviceIdentity::from_seed(load_or_create_seed(&key_path)?);
    let config = HubConfig {
        database_path,
        device_label: std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Desktop hub".into()),
        quic_port: 44330,
        maximum_ipc_connections: 256,
        maximum_sessions: 1024,
        maximum_buffered_bytes: 256 * 1024 * 1024,
        graceful_shutdown_ms: 5_000,
        relay: None,
    };
    let now_ms = u64::try_from(SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis())?;
    let mut hub = Hub::start(config, identity, now_ms)?;
    let services = hub
        .start_platform_services(
            Arc::new(OsResolvedCredentials),
            Arc::new(MemoryDeviceTrustStore::default()),
        )
        .await?;
    #[cfg(feature = "quic")]
    let link_address = services.links.local_addr()?;
    #[cfg(not(feature = "quic"))]
    let _ = &services;
    println!(
        "Device Fabric Hub running ({}, status: {:?}, IPC: {}){}",
        hub.diagnostics().device_id_short,
        hub.diagnostics().status,
        services.ipc_endpoint.display(),
        {
            #[cfg(feature = "quic")]
            {
                format!(" on QUIC {link_address}")
            }
            #[cfg(not(feature = "quic"))]
            {
                String::new()
            }
        }
    );
    tokio::signal::ctrl_c().await?;
    hub.shutdown().await?;
    Ok(())
}

fn default_state_dir() -> Result<PathBuf, Box<dyn std::error::Error>> {
    #[cfg(windows)]
    let directory = env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .ok_or("LOCALAPPDATA is unavailable")?
        .join("DeviceFabric");
    #[cfg(not(windows))]
    let directory = env::var_os("XDG_STATE_HOME")
        .map(PathBuf::from)
        .or_else(|| {
            env::var_os("HOME").map(|home| PathBuf::from(home).join(".local").join("state"))
        })
        .ok_or("neither XDG_STATE_HOME nor HOME is available")?
        .join("device-fabric");

    std::fs::create_dir_all(&directory)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&directory, std::fs::Permissions::from_mode(0o700))?;
    }
    Ok(directory)
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
            let identity = DeviceIdentity::generate();
            let seed = identity.seed_for_secure_storage();
            let mut options = OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            let mut file = options.open(path)?;
            file.write_all(&seed)?;
            file.sync_all()?;
            Ok(seed)
        }
        Err(error) => Err(error.into()),
    }
}
