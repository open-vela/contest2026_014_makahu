use std::{
    collections::VecDeque,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc,
    },
    thread,
    time::{Duration, Instant},
};

use eframe::egui::{self, Color32, RichText};
use fabric_app_broker::{
    DEFAULT_BROKER_ADDRESS, DeviceInfo, EchoProvider, LOCAL_DEVICE_ID, invoke_on, list_devices,
};

const ECHO_ABILITY: &str = "com.example.echo";

enum AppEvent {
    Provider(bool),
    Devices(Vec<DeviceInfo>),
    Result {
        request: String,
        target: String,
        result: Result<String, String>,
        elapsed: Duration,
    },
}

struct ProviderHandle {
    stop: Arc<AtomicBool>,
    join: Option<thread::JoinHandle<()>>,
}

impl ProviderHandle {
    fn start(events: mpsc::Sender<AppEvent>) -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let thread_stop = Arc::clone(&stop);
        let join = thread::spawn(move || {
            let Ok(runtime) = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            else {
                return;
            };
            runtime.block_on(async move {
                while !thread_stop.load(Ordering::Relaxed) {
                    if let Ok(provider) =
                        EchoProvider::connect(DEFAULT_BROKER_ADDRESS, ECHO_ABILITY).await
                    {
                        let _ = events.send(AppEvent::Provider(true));
                        tokio::select! {
                            _ = provider.serve() => {}
                            () = wait_for_stop(Arc::clone(&thread_stop)) => {}
                        }
                        let _ = events.send(AppEvent::Provider(false));
                    } else {
                        let _ = events.send(AppEvent::Provider(false));
                        tokio::time::sleep(Duration::from_secs(1)).await;
                    }
                }
            });
        });
        Self {
            stop,
            join: Some(join),
        }
    }
}

impl Drop for ProviderHandle {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(join) = self.join.take() {
            let _ = join.join();
        }
    }
}

async fn wait_for_stop(stop: Arc<AtomicBool>) {
    while !stop.load(Ordering::Relaxed) {
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

struct EchoApp {
    _provider: ProviderHandle,
    events: mpsc::Receiver<AppEvent>,
    event_sender: mpsc::Sender<AppEvent>,
    next_request: Arc<AtomicU64>,
    provider_online: bool,
    devices: Vec<DeviceInfo>,
    selected_device: String,
    last_device_refresh: Instant,
    input: String,
    output: String,
    sending: bool,
    history: VecDeque<String>,
}

impl EchoApp {
    fn new() -> Self {
        let (event_sender, events) = mpsc::channel();
        Self {
            _provider: ProviderHandle::start(event_sender.clone()),
            events,
            event_sender,
            next_request: Arc::new(AtomicU64::new(1)),
            provider_online: false,
            devices: vec![DeviceInfo {
                id: LOCAL_DEVICE_ID.into(),
                label: "This device".into(),
                paired: true,
                online: false,
            }],
            selected_device: LOCAL_DEVICE_ID.into(),
            last_device_refresh: Instant::now()
                .checked_sub(Duration::from_secs(2))
                .unwrap_or_else(Instant::now),
            input: "Hello from Desktop Echo".into(),
            output: String::new(),
            sending: false,
            history: VecDeque::new(),
        }
    }

    fn send(&mut self) {
        if self.sending || self.input.len() > 512 * 1_024 {
            return;
        }
        self.sending = true;
        let request = self.input.clone();
        let payload = request.as_bytes().to_vec();
        let request_id = self.next_request.fetch_add(1, Ordering::Relaxed);
        let target = self.selected_device.clone();
        let events = self.event_sender.clone();
        thread::spawn(move || {
            let started = Instant::now();
            let result = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .map_err(|error| error.to_string())
                .and_then(|runtime| {
                    runtime
                        .block_on(invoke_on(
                            DEFAULT_BROKER_ADDRESS,
                            request_id,
                            &target,
                            ECHO_ABILITY,
                            payload,
                        ))
                        .map_err(|error| error.to_string())
                })
                .and_then(|bytes| String::from_utf8(bytes).map_err(|error| error.to_string()));
            let _ = events.send(AppEvent::Result {
                request,
                target,
                result,
                elapsed: started.elapsed(),
            });
        });
    }

    fn poll_events(&mut self) {
        while let Ok(event) = self.events.try_recv() {
            match event {
                AppEvent::Provider(online) => self.provider_online = online,
                AppEvent::Devices(devices) => {
                    if !devices
                        .iter()
                        .any(|device| device.id == self.selected_device)
                    {
                        self.selected_device = LOCAL_DEVICE_ID.into();
                    }
                    self.devices = devices;
                }
                AppEvent::Result {
                    request,
                    target,
                    result,
                    elapsed,
                } => {
                    self.sending = false;
                    match result {
                        Ok(output) => {
                            self.output.clone_from(&output);
                            self.history.push_front(format!(
                                "{} ms  {} -> {} -> {}",
                                elapsed.as_millis(),
                                &target[..target.len().min(12)],
                                request,
                                output
                            ));
                        }
                        Err(error) => {
                            self.output = format!("Request failed: {error}");
                            self.history.push_front(self.output.clone());
                        }
                    }
                    self.history.truncate(20);
                }
            }
        }
    }

    fn refresh_devices(&mut self) {
        if self.last_device_refresh.elapsed() < Duration::from_secs(1) {
            return;
        }
        self.last_device_refresh = Instant::now();
        let events = self.event_sender.clone();
        thread::spawn(move || {
            let devices = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .ok()
                .and_then(|runtime| runtime.block_on(list_devices(DEFAULT_BROKER_ADDRESS)).ok());
            if let Some(devices) = devices {
                let _ = events.send(AppEvent::Devices(devices));
            }
        });
    }
}

impl eframe::App for EchoApp {
    fn update(&mut self, context: &egui::Context, _frame: &mut eframe::Frame) {
        self.poll_events();
        self.refresh_devices();
        context.request_repaint_after(Duration::from_millis(200));
        egui::TopBottomPanel::top("header").show(context, |ui| {
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                ui.heading("Echo Ability");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let (label, color) = if self.provider_online {
                        ("PROVIDER ONLINE", Color32::from_rgb(38, 130, 87))
                    } else {
                        ("HUB OFFLINE", Color32::from_rgb(180, 54, 54))
                    };
                    ui.label(RichText::new(label).strong().color(color));
                });
            });
            ui.add_space(8.0);
        });
        egui::CentralPanel::default().show(context, |ui| {
            ui.add_space(10.0);
            ui.horizontal(|ui| {
                ui.label(RichText::new("Target device").strong());
                let selected = self
                    .devices
                    .iter()
                    .find(|device| device.id == self.selected_device)
                    .map_or("No device", |device| device.label.as_str());
                egui::ComboBox::from_id_source("target_device")
                    .selected_text(selected)
                    .show_ui(ui, |ui| {
                        for device in &self.devices {
                            let status = if device.online { "online" } else { "offline" };
                            ui.selectable_value(
                                &mut self.selected_device,
                                device.id.clone(),
                                format!("{} ({status})", device.label),
                            );
                        }
                    });
            });
            ui.add_space(12.0);
            ui.label(RichText::new("Request payload").strong());
            ui.add(
                egui::TextEdit::multiline(&mut self.input)
                    .desired_rows(5)
                    .desired_width(f32::INFINITY),
            );
            ui.add_space(8.0);
            let target_online = self
                .devices
                .iter()
                .any(|device| device.id == self.selected_device && device.online);
            let can_send = target_online && !self.sending && !self.input.is_empty();
            let label = if self.sending { "Sending..." } else { "Send" };
            if ui
                .add_enabled(
                    can_send,
                    egui::Button::new(label).min_size([100.0, 34.0].into()),
                )
                .clicked()
            {
                self.send();
            }
            ui.add_space(18.0);
            ui.label(RichText::new("Echo response").strong());
            ui.add(
                egui::TextEdit::multiline(&mut self.output)
                    .desired_rows(4)
                    .desired_width(f32::INFINITY)
                    .interactive(false),
            );
            ui.add_space(18.0);
            ui.separator();
            ui.add_space(8.0);
            ui.label(RichText::new("Recent requests").strong());
            egui::ScrollArea::vertical().show(ui, |ui| {
                for entry in &self.history {
                    ui.monospace(entry);
                }
            });
        });
    }
}

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([700.0, 600.0])
            .with_min_inner_size([580.0, 480.0]),
        ..Default::default()
    };
    eframe::run_native(
        "Device Fabric Echo",
        options,
        Box::new(|_| Ok(Box::new(EchoApp::new()))),
    )
}
