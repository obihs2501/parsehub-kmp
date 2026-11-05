// Prevent an additional console window on Windows in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod backend;

use std::sync::Arc;
use tauri::Manager;

fn safe_local_path(path: &str) -> Result<std::path::PathBuf, String> {
    let candidate = std::path::Path::new(path);
    if path.len() > 32768 || !candidate.is_absolute()
        || candidate.components().any(|c| matches!(c, std::path::Component::ParentDir)) {
        return Err("Expected an absolute local path without parent traversal".into());
    }
    if path.starts_with(r"\\") || path.starts_with("//") || path.chars().any(|c| c.is_control()) {
        return Err("Network and device paths are not permitted".into());
    }
    #[cfg(windows)]
    if path.char_indices().any(|(i, c)| c == ':' && i != 1) {
        return Err("Alternate data streams are not permitted".into());
    }
    let resolved = candidate.canonicalize().map_err(|e| e.to_string())?;
    #[cfg(windows)]
    if resolved.to_string_lossy().starts_with(r"\\?\UNC\") {
        return Err("Network paths are not permitted".into());
    }
    Ok(resolved)
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct PathMetadata { path: String, modified_at: Option<u64> }

#[tauri::command]
fn filesystem_metadata(paths: Vec<String>) -> Vec<PathMetadata> {
    use std::time::UNIX_EPOCH;
    paths.into_iter().map(|path| {
        let modified_at = safe_local_path(&path).ok()
            .and_then(|p| std::fs::metadata(p).ok())
            .and_then(|metadata| metadata.modified().ok())
            .and_then(|modified| modified.duration_since(UNIX_EPOCH).ok())
            .map(|duration| duration.as_millis() as u64);
        PathMetadata { path, modified_at }
    }).collect()
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct DirEntryInfo {
    name: String,
    path: String,
    is_dir: bool,
    modified_at: Option<u64>,
    size: Option<u64>,
}

/// Read one directory level for the gallery, including metadata for local symlinks.
#[tauri::command]
fn read_dir_with_metadata(path: String) -> Result<Vec<DirEntryInfo>, String> {
    use std::time::UNIX_EPOCH;
    safe_local_path(&path)?;
    // Preserve normal drive-letter paths in the response for convertFileSrc.
    let read = std::fs::read_dir(&path).map_err(|e| e.to_string())?;
    let mut result = Vec::new();
    for entry in read {
        let entry = match entry { Ok(e) => e, Err(_) => continue };
        let metadata = match safe_local_path(&entry.path().to_string_lossy())
            .and_then(|p| std::fs::metadata(p).map_err(|e| e.to_string())) {
            Ok(m) => m,
            Err(_) => continue,
        };
        let is_dir = metadata.is_dir();
        let modified_at = metadata.modified().ok()
            .and_then(|modified| modified.duration_since(UNIX_EPOCH).ok())
            .map(|duration| duration.as_millis() as u64);
        result.push(DirEntryInfo {
            name: entry.file_name().to_string_lossy().to_string(),
            path: entry.path().to_string_lossy().to_string(),
            is_dir,
            modified_at,
            size: if is_dir { None } else { Some(metadata.len()) },
        });
        if result.len() > 100_000 { return Err("Directory exceeds 100000 entries".into()); }
    }
    Ok(result)
}

/// Reveal a validated local file without granting frontend process execution.
#[tauri::command]
fn show_in_folder(path: String) -> Result<(), String> {
    let resolved = safe_local_path(&path)?;
    #[cfg(windows)] {
        use std::os::windows::process::CommandExt;
        let display = resolved.to_string_lossy();
        let display = display.strip_prefix(r"\\?\").unwrap_or(&display);
        let explorer = std::env::var_os("SystemRoot")
            .map(std::path::PathBuf::from).ok_or("SystemRoot is unavailable")?.join("explorer.exe");
        std::process::Command::new(explorer)
            .arg(format!("/select,{display}"))
            .creation_flags(0x08000000).spawn().map_err(|e| e.to_string())?;
    }
    #[cfg(not(windows))] {
        let _ = resolved;
        return Err("Reveal in folder is currently supported on Windows only".into());
    }
    #[cfg(windows)]
    Ok(())
}

fn main() {
    let backend = backend::Backend::new().expect("Cannot initialize backend HTTP client");
    let smoke = std::env::var_os("PARSEHUB_DESKTOP_SMOKE_TEST").is_some();
    let lifecycle = Arc::clone(&backend);
    let app = tauri::Builder::default()
        .manage(Arc::clone(&backend))
        .setup(move |app| {
            if smoke {
                if let Some(window) = app.get_window("main") { let _ = window.hide(); }
            }
            lifecycle.start(&app.handle()).map_err(std::io::Error::other)?;
            if smoke {
                let handle = app.handle();
                let backend = Arc::clone(&lifecycle);
                tauri::async_runtime::spawn(async move {
                    let result = backend.request("GET".into(), "/api/health".into(), None).await;
                    let success = matches!(&result, Ok(v) if v["status"] == "ok" && v["protocol"].as_u64() == Some(1));
                    if !success { eprintln!("Desktop backend smoke failed: {result:?}"); }
                    let shutdown = tauri::async_runtime::spawn_blocking(move || backend.shutdown()).await;
                    let clean_shutdown = matches!(&shutdown, Ok(Ok(())));
                    if !clean_shutdown { eprintln!("Desktop backend shutdown failed: {shutdown:?}"); }
                    handle.exit(if success && clean_shutdown { 0 } else { 1 });
                });
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            backend::backend_request,
            filesystem_metadata,
            read_dir_with_metadata,
            show_in_folder,
        ])
        .build(tauri::generate_context!())
        .expect("Error building ParseHub Studio");
    app.run(move |_, event| {
        if matches!(event, tauri::RunEvent::Exit) {
            if let Err(error) = backend.shutdown() { eprintln!("Backend shutdown failed: {error}"); }
        }
    });
}
