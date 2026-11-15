//! Phones paired with this computer (docs/EMPARELLAR.md): the computer's side. The protocol and
//! the file transfer are in murmur-core (sync.rs); here, the keys in the keychain, the list of
//! phones, the QR, the server on the local network, Bonjour, and where a received meeting goes.
//!
//! Off until a phone is paired: with no phones and no code on screen, no port is open and nothing
//! is announced.

use std::io::ErrorKind;
use std::net::{TcpListener, TcpStream, UdpSocket};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tauri::{AppHandle, Emitter, Manager};

use murmur_core::sync::{self, Accept, Arrival, Incoming, Keys, Msg, PairingCode, Receiving};

use crate::i18n::tr;

const KEY_SERVICE: &str = "com.quicodomingo.murmur.mobile";
const KEY_ACCOUNT: &str = "computer";
/// How long a connection may stay silent before it's dropped.
const IDLE: Duration = Duration::from_secs(90);

/// A paired phone. The key is public: it's kept in a plain file with the name.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Phone {
    /// Its public key, in hex: also its id.
    pub id: String,
    pub name: String,
    /// Seconds since the epoch.
    pub paired_at: f64,
    pub last_seen: Option<f64>,
}

/// What the settings show.
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub computer: String,
    pub phones: Vec<Phone>,
    pub pairing: bool,
}

/// A code on screen, waiting for a phone.
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Code {
    pub uri: String,
    /// The QR, as SVG (dark modules in `currentColor`).
    pub svg: String,
    pub expires_in: u64,
}

struct Pairing {
    secret: [u8; 32],
    until: Instant,
}

struct Server {
    port: u16,
    stop: Arc<AtomicBool>,
    mdns: Option<mdns_sd::ServiceDaemon>,
}

#[derive(Default)]
struct Inner {
    phones: Option<Vec<Phone>>,
    pairing: Option<Pairing>,
    server: Option<Server>,
}

#[derive(Default)]
pub struct Mobile(Mutex<Inner>);

fn now() -> f64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs_f64()).unwrap_or(0.0)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn unhex(s: &str) -> Option<[u8; 32]> {
    let bytes: Option<Vec<u8>> = (0..s.len()).step_by(2).map(|i| u8::from_str_radix(s.get(i..i + 2)?, 16).ok()).collect();
    bytes?.try_into().ok()
}

fn data_dir(app: &AppHandle) -> Result<PathBuf> {
    app.path().app_data_dir().map_err(|e| anyhow!("{e}"))
}

fn phones_file(app: &AppHandle) -> Result<PathBuf> {
    Ok(data_dir(app)?.join("phones.json"))
}

/// The computer's keys: made once and kept in the keychain.
fn keys() -> Result<Keys> {
    if let Some(private) = crate::keychain::get(KEY_SERVICE, KEY_ACCOUNT) {
        if let Ok(keys) = Keys::from_private(&private) {
            return Ok(keys);
        }
    }
    let keys = Keys::generate()?;
    crate::keychain::set(KEY_SERVICE, KEY_ACCOUNT, &keys.private)?;
    Ok(keys)
}

/// The name people know this computer by ("MacBook de Quico").
pub fn computer_name() -> String {
    #[cfg(target_os = "macos")]
    if let Ok(out) = std::process::Command::new("/usr/sbin/scutil").args(["--get", "ComputerName"]).output() {
        let name = String::from_utf8_lossy(&out.stdout).trim().to_string();
        if !name.is_empty() {
            return name;
        }
    }
    #[cfg(windows)]
    if let Ok(name) = std::env::var("COMPUTERNAME") {
        return name;
    }
    std::fs::read_to_string("/etc/hostname").map(|s| s.trim().to_string()).ok().filter(|s| !s.is_empty()).unwrap_or_else(|| "Murmur".into())
}

/// This computer's address on the local network (the one it would use to go out; nothing is sent).
fn local_address() -> Option<String> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("192.0.2.1:9").ok()?;
    Some(socket.local_addr().ok()?.ip().to_string())
}

impl Mobile {
    fn phones(&self, app: &AppHandle) -> Vec<Phone> {
        let mut inner = self.0.lock().unwrap();
        if inner.phones.is_none() {
            let loaded = phones_file(app).ok().and_then(|p| std::fs::read_to_string(p).ok()).and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
            inner.phones = Some(loaded);
        }
        inner.phones.clone().unwrap_or_default()
    }

    fn save_phones(&self, app: &AppHandle, phones: Vec<Phone>) -> Result<()> {
        let path = phones_file(app)?;
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        std::fs::write(&path, serde_json::to_string_pretty(&phones)?)?;
        self.0.lock().unwrap().phones = Some(phones);
        Ok(())
    }

    fn pairing_secret(&self) -> Option<[u8; 32]> {
        let inner = self.0.lock().unwrap();
        inner.pairing.as_ref().filter(|p| p.until > Instant::now()).map(|p| p.secret)
    }
}

/// At startup: listen if there are phones.
pub fn init(app: &AppHandle) {
    if let Err(e) = refresh(app) {
        log::error!("mobile: {e:#}");
    }
}

pub fn status(app: &AppHandle) -> Status {
    let mobile = app.state::<Mobile>();
    Status { computer: computer_name(), phones: mobile.phones(app), pairing: mobile.pairing_secret().is_some() }
}

/// "Connecta un mòbil": a new one-time code, valid for a few minutes.
pub fn start_pairing(app: &AppHandle) -> Result<Code> {
    let keys = keys()?;
    let mut secret = [0u8; 32];
    crate::keychain::random_bytes(&mut secret);
    app.state::<Mobile>().0.lock().unwrap().pairing = Some(Pairing { secret, until: Instant::now() + Duration::from_secs(sync::PAIRING_TTL_S) });
    let port = refresh(app)?.ok_or_else(|| anyhow!(tr!("mobile_no_network")))?;
    let code = PairingCode {
        version: sync::VERSION,
        name: computer_name(),
        key: keys.public,
        secret,
        addrs: local_address().into_iter().collect(),
        port,
    };
    let uri = code.to_uri();
    let svg = qrcode::QrCode::new(uri.as_bytes())?
        .render::<qrcode::render::svg::Color>()
        .dark_color(qrcode::render::svg::Color("currentColor"))
        .light_color(qrcode::render::svg::Color("transparent"))
        .quiet_zone(false)
        .build();
    // When the code expires, stop listening if there's no phone yet.
    let later = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_secs(sync::PAIRING_TTL_S + 1));
        let _ = refresh(&later);
    });
    Ok(Code { uri, svg, expires_in: sync::PAIRING_TTL_S })
}

pub fn cancel_pairing(app: &AppHandle) -> Result<()> {
    app.state::<Mobile>().0.lock().unwrap().pairing = None;
    refresh(app).map(|_| ())
}

pub fn unpair(app: &AppHandle, id: &str) -> Result<()> {
    let mobile = app.state::<Mobile>();
    let phones: Vec<Phone> = mobile.phones(app).into_iter().filter(|p| p.id != id).collect();
    mobile.save_phones(app, phones)?;
    refresh(app).map(|_| ())
}

/// Starts or stops listening (and announcing) to match: on while there are phones or a code on
/// screen. Returns the port when listening.
fn refresh(app: &AppHandle) -> Result<Option<u16>> {
    let mobile = app.state::<Mobile>();
    let wanted = !mobile.phones(app).is_empty() || mobile.pairing_secret().is_some();
    let mut inner = mobile.0.lock().unwrap();
    if !wanted {
        if let Some(server) = inner.server.take() {
            server.stop.store(true, Ordering::SeqCst);
            if let Some(mdns) = server.mdns {
                let _ = mdns.shutdown();
            }
        }
        return Ok(None);
    }
    if let Some(server) = &inner.server {
        return Ok(Some(server.port));
    }
    let listener = TcpListener::bind("0.0.0.0:0").context(tr!("mobile_no_network"))?;
    listener.set_nonblocking(true)?;
    let port = listener.local_addr()?.port();
    let stop = Arc::new(AtomicBool::new(false));
    let mdns = announce(port).map_err(|e| log::warn!("mobile: Bonjour: {e:#}")).ok();
    let (app2, stop2) = (app.clone(), stop.clone());
    std::thread::spawn(move || serve(app2, listener, stop2));
    inner.server = Some(Server { port, stop, mdns });
    Ok(Some(port))
}

/// Bonjour: `_murmur._tcp` with a random name; nothing about the user.
fn announce(port: u16) -> Result<mdns_sd::ServiceDaemon> {
    let daemon = mdns_sd::ServiceDaemon::new()?;
    let mut id = [0u8; 6];
    crate::keychain::random_bytes(&mut id);
    let instance = format!("murmur-{}", hex(&id));
    let host = format!("{instance}.local.");
    let props = [("v", sync::VERSION.to_string())];
    let info = mdns_sd::ServiceInfo::new(sync::SERVICE, &instance, &host, "", port, &props[..])?.enable_addr_auto();
    daemon.register(info)?;
    Ok(daemon)
}

fn serve(app: AppHandle, listener: TcpListener, stop: Arc<AtomicBool>) {
    while !stop.load(Ordering::SeqCst) {
        match listener.accept() {
            Ok((stream, _)) => {
                let app = app.clone();
                std::thread::spawn(move || {
                    if let Err(e) = handle(&app, stream) {
                        log::warn!("mobile: {e:#}");
                    }
                });
            }
            Err(e) if e.kind() == ErrorKind::WouldBlock => std::thread::sleep(Duration::from_millis(250)),
            Err(e) => {
                log::error!("mobile: {e}");
                std::thread::sleep(Duration::from_secs(1));
            }
        }
    }
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Paired {
    id: String,
    name: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Received {
    meeting_id: String,
    phone: String,
}

fn handle(app: &AppHandle, stream: TcpStream) -> Result<()> {
    stream.set_nonblocking(false)?;
    stream.set_read_timeout(Some(IDLE))?;
    let mobile = app.state::<Mobile>();
    let paired: Vec<[u8; 32]> = mobile.phones(app).iter().filter_map(|p| unhex(&p.id)).collect();
    let secret = mobile.pairing_secret();
    let (mut session, arrival) = sync::accept(stream, &keys()?, &Accept { pairing_secret: secret.as_ref(), paired: &paired }, &computer_name())?;
    let id = hex(&session.remote);
    let mut phones = mobile.phones(app);
    if arrival == Arrival::Paired {
        mobile.0.lock().unwrap().pairing = None;
        phones.retain(|p| p.id != id);
        phones.push(Phone { id: id.clone(), name: session.remote_name.clone(), paired_at: now(), last_seen: Some(now()) });
        let _ = app.emit("mobile-paired", Paired { id: id.clone(), name: session.remote_name.clone() });
    } else if let Some(p) = phones.iter_mut().find(|p| p.id == id) {
        p.last_seen = Some(now());
        p.name = session.remote_name.clone();
    }
    mobile.save_phones(app, phones)?;
    let device = json!({ "id": id, "name": session.remote_name });

    loop {
        match session.recv() {
            Ok(Incoming::Msg(Msg::PutMeeting { meeting, files })) => {
                let meeting_id = crate::safe_id(meeting.get("id").and_then(Value::as_str).unwrap_or_default());
                if meeting_id.is_empty() {
                    session.send(&Msg::Failed { meeting_id, reason: "no id".into() })?;
                    continue;
                }
                let dir = data_dir(app)?.join("incoming").join(&meeting_id);
                let receiving = Receiving::new(dir.clone(), files)?;
                session.send(&Msg::Have { meeting_id: meeting_id.clone(), files: receiving.progress() })?;
                while !receiving.finish()? {
                    match session.recv()? {
                        Incoming::Chunk { name, offset, data, .. } => receiving.write(&name, offset, &data)?,
                        Incoming::Msg(m) => return Err(anyhow!("expected a file chunk, got {m:?}")),
                    }
                }
                match place(app, meeting, &dir, &device) {
                    Ok(fresh) => {
                        session.send(&Msg::Stored { meeting_id: meeting_id.clone() })?;
                        if fresh {
                            let _ = app.emit("mobile-meeting", Received { meeting_id, phone: session.remote_name.clone() });
                        }
                    }
                    Err(e) => session.send(&Msg::Failed { meeting_id, reason: format!("{e:#}") })?,
                }
            }
            Ok(Incoming::Msg(Msg::GetResults { since })) => {
                let changed = crate::with_db(app, |db| db.changed_for_device(&id, since)).map_err(|e| anyhow!(e))?;
                for (meeting, revised_at) in changed {
                    session.send(&Msg::Results { meeting: for_phone(meeting), revised_at })?;
                }
                session.send(&Msg::Bye)?;
            }
            Ok(Incoming::Msg(Msg::Bye)) => return Ok(()),
            Ok(other) => return Err(anyhow!("unexpected {other:?}")),
            // The phone went away (out of the network, locked): it'll carry on next time.
            Err(e) if e.downcast_ref::<std::io::Error>().is_some() => return Ok(()),
            Err(e) => return Err(e),
        }
    }
}

/// A received meeting goes where the computer's own recordings go, marked as from the phone and
/// waiting for its final pass. Returns false if it was already here (sent again after a cut).
fn place(app: &AppHandle, mut meeting: Value, dir: &Path, device: &Value) -> Result<bool> {
    let id = crate::safe_id(meeting["id"].as_str().unwrap_or_default());
    if crate::with_db(app, |db| db.get(&id)).map_err(|e| anyhow!(e))?.is_some() {
        let _ = std::fs::remove_dir_all(dir);
        return Ok(false);
    }
    let data = data_dir(app)?;
    let audio = dir.join("audio.ogg");
    meeting["audioPath"] = if audio.exists() {
        let to = data.join("recordings").join(format!("{id}.ogg"));
        std::fs::create_dir_all(to.parent().unwrap())?;
        std::fs::rename(&audio, &to)?;
        json!(to.to_string_lossy())
    } else {
        Value::Null
    };
    if let Some(screens) = meeting.get_mut("screens").and_then(Value::as_array_mut) {
        let to_dir = data.join("screens").join(&id);
        std::fs::create_dir_all(&to_dir)?;
        for screen in screens.iter_mut() {
            let name = screen["path"].as_str().map(sync::safe_name).transpose()?.unwrap_or_default().to_string();
            let from = dir.join(&name);
            let file = Path::new(&name).file_name().ok_or_else(|| anyhow!("bad screen name"))?;
            let to = to_dir.join(file);
            if from.exists() {
                std::fs::rename(&from, &to)?;
            }
            screen["path"] = json!(to.to_string_lossy());
        }
    }
    meeting["device"] = device.clone();
    meeting["status"] = json!("processing");
    crate::with_db(app, |db| db.put(&meeting)).map_err(|e| anyhow!(e))?;
    let _ = std::fs::remove_dir_all(dir);
    Ok(true)
}

/// What goes back to the phone: the text and what was made from it, not the computer's paths or links.
fn for_phone(mut meeting: Value) -> Value {
    if let Some(m) = meeting.as_object_mut() {
        for key in ["audioPath", "screens", "shares", "device"] {
            m.remove(key);
        }
    }
    meeting
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_round_trips_and_rejects_junk() {
        let key = [0xab; 32];
        assert_eq!(unhex(&hex(&key)), Some(key));
        assert_eq!(unhex("zz"), None);
        assert_eq!(unhex(&hex(&[1; 16])), None);
    }

    #[test]
    fn what_goes_back_to_the_phone_has_no_computer_paths() {
        let m = for_phone(json!({ "id": "m1", "title": "T", "audioPath": "/Users/x/rec.ogg", "screens": [], "shares": [], "device": {} }));
        assert_eq!(m, json!({ "id": "m1", "title": "T" }));
    }
}
