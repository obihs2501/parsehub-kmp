use serde_json::Value;
use std::{
    fs::{self, OpenOptions},
    io::{BufRead, BufReader, Read},
    path::PathBuf,
    process::{Child, Command, Stdio},
    sync::{atomic::{AtomicBool, Ordering}, Arc, Condvar, Mutex},
    time::{Duration, Instant},
};

const START_TIMEOUT: Duration = Duration::from_secs(60);
const REQUEST_LIMIT: usize = 1_048_576;
const RESPONSE_LIMIT: usize = 16 * 1_048_576;

#[derive(Clone)]
struct Endpoint { port: u16, token: String }

pub struct Backend {
    child: Mutex<Option<Child>>,
    ready: (Mutex<Option<Result<Endpoint, String>>>, Condvar),
    stopping: AtomicBool,
    client: reqwest::Client,
}

impl Backend {
    pub fn new() -> Result<Arc<Self>, String> {
        Ok(Arc::new(Self {
            child: Mutex::new(None),
            ready: (Mutex::new(None), Condvar::new()),
            stopping: AtomicBool::new(false),
            client: reqwest::Client::builder().no_proxy()
                .redirect(reqwest::redirect::Policy::none())
                .connect_timeout(Duration::from_secs(5))
                .timeout(Duration::from_secs(180)).build().map_err(|e| e.to_string())?,
        }))
    }

    pub fn start(self: &Arc<Self>, app: &tauri::AppHandle) -> Result<(), String> {
        let resolver = app.path_resolver();
        // Tauri 1 preserves these relative resource paths on Windows.
        let java = resolver.resolve_resource(if cfg!(windows) { "runtime/bin/java.exe" } else { "runtime/bin/java" })
            .ok_or("Cannot resolve bundled Java runtime")?;
        let jar = resolver.resolve_resource("sidecar/parsehub-sidecar.jar")
            .ok_or("Cannot resolve bundled sidecar")?;
        let data = if std::env::var_os("PARSEHUB_DESKTOP_SMOKE_TEST").is_some() {
            std::env::current_exe().map_err(|e| e.to_string())?
                .parent().ok_or("Cannot resolve smoke executable directory")?.join("smoke-data")
        } else {
            resolver.app_data_dir().ok_or("Cannot resolve app data directory")?
        };
        let backend = Arc::clone(self);
        std::thread::spawn(move || {
            let outcome = backend.launch(java, jar, data);
            if outcome.is_err() { let _ = backend.shutdown(); }
            let mut ready = backend.ready.0.lock().unwrap_or_else(|e| e.into_inner());
            *ready = Some(outcome);
            backend.ready.1.notify_all();
        });
        Ok(())
    }

    fn launch(&self, java: PathBuf, jar: PathBuf, data: PathBuf) -> Result<Endpoint, String> {
        // Tauri 1 canonicalizes its resource directory, producing \?\ paths on
        // Windows. Java can read the manifest from that spelling but fail to
        // load classes through its file-URL based classpath. Pass ordinary
        // absolute disk/UNC paths across the process boundary instead.
        let java = java_path(java);
        let jar = java_path(jar);
        let data = java_path(data);
        if !java.is_file() || !jar.is_file() { return Err("Bundled runtime or sidecar is missing; run scripts/package-windows.ps1 before building".into()); }
        if std::env::var_os("PARSEHUB_DESKTOP_SMOKE_TEST").is_some() {
            eprintln!("Sidecar launch: java={java:?}, jar={jar:?}, data={data:?}, jar_bytes={:?}",
                fs::metadata(&jar).map(|m| m.len()));
        }
        fs::create_dir_all(&data).map_err(|e| e.to_string())?;
        let log = OpenOptions::new().create(true).append(true).open(data.join("sidecar-stderr.log"))
            .map_err(|e| e.to_string())?;
        // Two v4 UUIDs provide 244 random bits. Never return this token to the webview.
        let token = format!("{}{}", uuid::Uuid::new_v4().simple(), uuid::Uuid::new_v4().simple());
        let mut command = Command::new(java);
        command.arg("-jar").arg(jar).current_dir(&data)
            .env("PARSEHUB_SESSION_TOKEN", &token).env("PARSEHUB_DATA_DIR", &data)
            .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::from(log));
        #[cfg(windows)] {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x08000000); // CREATE_NO_WINDOW
        }
        let (tx, rx) = std::sync::mpsc::sync_channel(1);
        {
            let mut slot = self.child.lock().unwrap_or_else(|e| e.into_inner());
            if self.stopping.load(Ordering::SeqCst) { return Err("Backend is stopping".into()); }
            let mut child = command.spawn().map_err(|e| format!("Cannot launch bundled Java: {e}"))?;
            let stdout = child.stdout.take().ok_or("Java stdout pipe missing")?;
            *slot = Some(child);
            std::thread::spawn(move || {
                let mut reader = BufReader::new(stdout);
                let mut announced = false;
                loop {
                    // Bound each stdout line even if a dependency emits malformed output.
                    let mut line = Vec::new();
                    let mut bounded = Read::take(&mut reader, 64 * 1024);
                    match BufRead::read_until(&mut bounded, b'\n', &mut line) {
                        Ok(0) | Err(_) => break,
                        Ok(_) => {}
                    }
                    if !announced {
                        if let Ok(v) = serde_json::from_slice::<Value>(&line) {
                            if v["event"] == "ready" && v["protocol"].as_u64() == Some(1) {
                                if let Some(port) = v["port"].as_u64().filter(|p| *p > 0 && *p <= 65535) {
                                    let _ = tx.send(port as u16);
                                    announced = true;
                                }
                            }
                        }
                    }
                    // Keep draining after readiness, for the full process lifetime.
                }
            });
        }
        let port = rx.recv_timeout(START_TIMEOUT).map_err(|e| format!("Sidecar readiness failed: {e}; see sidecar-stderr.log"))?;
        Ok(Endpoint { port, token })
    }

    async fn endpoint(self: &Arc<Self>) -> Result<Endpoint, String> {
        let backend = Arc::clone(self);
        tauri::async_runtime::spawn_blocking(move || {
            let guard = backend.ready.0.lock().map_err(|_| "Backend state poisoned")?;
            let (guard, _) = backend.ready.1.wait_timeout_while(guard, START_TIMEOUT + Duration::from_secs(10), |r| r.is_none())
                .map_err(|_| "Backend state poisoned")?;
            guard.as_ref().cloned().unwrap_or_else(|| Err("Backend startup timed out".into()))
        }).await.map_err(|e| e.to_string())?
    }

    pub async fn request(self: &Arc<Self>, method: String, path: String, body: Option<Value>) -> Result<Value, String> {
        validate_route(&method, &path)?;
        if self.stopping.load(Ordering::SeqCst) { return Err("Backend is stopping".into()); }
        let payload = body.map(|v| serde_json::to_vec(&v)).transpose().map_err(|e| e.to_string())?;
        if payload.as_ref().map_or(false, |v| v.len() > REQUEST_LIMIT) { return Err("Request exceeds 1 MiB".into()); }
        if matches!(method.as_str(), "GET" | "DELETE") && payload.is_some() { return Err("This method does not accept a body".into()); }
        let endpoint = self.endpoint().await?;
        let method = reqwest::Method::from_bytes(method.as_bytes()).map_err(|e| e.to_string())?;
        let mut request = self.client.request(method, format!("http://127.0.0.1:{}{}", endpoint.port, path))
            .bearer_auth(&endpoint.token);
        if let Some(payload) = payload { request = request.header(reqwest::header::CONTENT_TYPE, "application/json").body(payload); }
        let mut response = request.send().await.map_err(|e| format!("Backend request failed: {e}"))?;
        let status = response.status();
        if response.content_length().map_or(false, |n| n > RESPONSE_LIMIT as u64) { return Err("Backend response exceeds 16 MiB".into()); }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|e| e.to_string())? {
            if bytes.len() + chunk.len() > RESPONSE_LIMIT { return Err("Backend response exceeds 16 MiB".into()); }
            bytes.extend_from_slice(&chunk);
        }
        let value: Value = serde_json::from_slice(&bytes).map_err(|_| format!("Backend returned non-JSON (HTTP {status})"))?;
        if !status.is_success() {
            let detail = value.get("error").and_then(Value::as_str).unwrap_or("Request rejected");
            return Err(format!("Backend HTTP {}: {}", status.as_u16(), detail.chars().take(300).collect::<String>()));
        }
        Ok(value)
    }

    pub fn shutdown(&self) -> Result<(), String> {
        self.stopping.store(true, Ordering::SeqCst);
        let child = self.child.lock().unwrap_or_else(|e| e.into_inner()).take();
        if let Some(mut child) = child {
            drop(child.stdin.take()); // EOF triggers the Java graceful shutdown protocol.
            let deadline = Instant::now() + Duration::from_secs(8);
            loop {
                let failure = match child.try_wait() {
                    Ok(Some(status)) if status.success() => return Ok(()),
                    Ok(Some(status)) => return Err(format!("Sidecar exited unsuccessfully: {status}")),
                    Ok(None) if Instant::now() < deadline => {
                        std::thread::sleep(Duration::from_millis(50));
                        continue;
                    }
                    Ok(None) => "Sidecar exceeded graceful shutdown deadline; forced termination required".to_string(),
                    Err(error) => format!("Cannot observe graceful sidecar shutdown: {error}"),
                };
                child.kill().map_err(|error| format!("{failure}; kill failed: {error}"))?;
                child.wait().map_err(|error| format!("{failure}; reap failed: {error}"))?;
                return Err(failure);
            }
        }
        Ok(())
    }
}

impl Drop for Backend { fn drop(&mut self) { let _ = self.shutdown(); } }

// Preserve non-Unicode Windows paths and leave non-filesystem device prefixes alone.
fn java_path(path: PathBuf) -> PathBuf {
    #[cfg(windows)] {
        use std::path::{Component, Prefix};
        let mut components = path.components();
        let mut ordinary = match components.next() {
            Some(Component::Prefix(prefix)) => match prefix.kind() {
                Prefix::VerbatimDisk(drive) => PathBuf::from(format!("{}:\\", drive as char)),
                Prefix::VerbatimUNC(server, share) => PathBuf::from(r"\\").join(server).join(share),
                _ => return path,
            },
            _ => return path,
        };
        if !matches!(components.next(), Some(Component::RootDir)) { return path; }
        ordinary.push(components.as_path());
        return ordinary;
    }
    #[cfg(not(windows))]
    path
}

fn validate_route(method: &str, path: &str) -> Result<(), String> {
    if path.len() > 128 || method.len() > 6 { return Err("Unsupported backend route or method".into()); }
    let fixed = matches!((method, path),
        ("GET", "/api/health" | "/api/platforms" | "/api/settings" | "/api/tasks" | "/api/history" | "/api/history/export") |
        ("POST", "/api/detect" | "/api/parse" | "/api/tasks") |
        ("PUT", "/api/settings") | ("DELETE", "/api/history"));
    let parts: Vec<_> = path.split('/').collect();
    let task = parts.len() >= 4 && parts[0].is_empty() && parts[1] == "api" && parts[2] == "tasks"
        && uuid::Uuid::parse_str(parts[3]).map_or(false, |id| id.to_string() == parts[3])
        && ((method == "GET" && parts.len() == 4) || (method == "POST" && parts.len() == 5 && parts[4] == "cancel"));
    if fixed || task { Ok(()) } else { Err("Unsupported backend route or method".into()) }
}

#[tauri::command]
pub async fn backend_request(method: String, path: String, body: Option<Value>, state: tauri::State<'_, Arc<Backend>>) -> Result<Value, String> {
    state.inner().request(method, path, body).await
}
