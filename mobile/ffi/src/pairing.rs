//! The phone's side of pairing and syncing with the computer (docs/EMPARELLAR.md). The protocol
//! is `murmur_core::sync`, which the computer's side (mobile.rs) shares.
//!
//! - **Keys:** the phone's private key lives in the iPhone's keychain; Swift keeps it and passes it
//!   in. The computer's public key and name aren't secret and go in the app's settings.
//! - **Addresses:** Swift finds the computer with Bonjour and passes every candidate ("host:port");
//!   the handshake only completes with the right computer, so the others are just skipped.
//! - **What's been sent:** `sync.json` in the data folder (which meetings the computer has stored,
//!   which came back revised, and since when to ask), so the meeting's format stays the computer's.

use std::collections::BTreeSet;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::Duration;

use murmur_core::sync::{self, Incoming, Keys, Msg, PairingCode, Session};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::{Murmur, MurmurError, Result, lock};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(3);
/// A computer busy with a long file still answers well within this.
const IO_TIMEOUT: Duration = Duration::from_secs(60);

/// What the phone remembers about syncing, per meeting.
#[derive(Default, Serialize, Deserialize)]
pub(crate) struct SyncState {
    /// Results revised after this (seconds since 1970, the computer's clock) are still to come.
    #[serde(default)]
    pub since: f64,
    /// Stored on the computer, whole and checked.
    #[serde(default)]
    pub sent: BTreeSet<String>,
    /// Came back after the final pass.
    #[serde(default)]
    pub revised: BTreeSet<String>,
    /// Renamed (the meeting or a speaker) on the phone after it was sent: those names win over
    /// the computer's when the results come back.
    #[serde(default)]
    pub edited: BTreeSet<String>,
}

/// What's in a pairing code, to show before pairing.
#[derive(uniffi::Record)]
pub struct PairingCodeInfo {
    pub computer_name: String,
    /// Where the computer was listening when it showed the code ("host:port").
    pub addresses: Vec<String>,
}

/// The computer this phone is paired with.
#[derive(uniffi::Record)]
pub struct Computer {
    pub name: String,
    /// Its public key (not secret): to know it again.
    pub key: Vec<u8>,
    /// The address that answered, to try first next time.
    pub address: String,
}

/// What a sync did.
#[derive(uniffi::Record)]
pub struct SyncReport {
    pub address: String,
    pub computer_name: String,
    /// Meetings the computer stored now.
    pub sent: u32,
    /// Meetings that came back revised now.
    pub revised: u32,
    /// Meetings the computer refused (they'll be tried again next time).
    pub failed: u32,
}

/// A new private key for this phone, for the keychain.
#[uniffi::export]
pub fn new_sync_key() -> Result<Vec<u8>> {
    Ok(Keys::generate()?.private)
}

/// Reads the QR's text. Fails if it isn't a Murmur pairing code (or one from another version).
#[uniffi::export]
pub fn read_pairing_code(uri: String) -> Result<PairingCodeInfo> {
    let code = PairingCode::parse(uri.trim()).map_err(|_| MurmurError::NotACode)?;
    Ok(PairingCodeInfo { computer_name: code.name.clone(), addresses: addresses(&code) })
}

fn addresses(code: &PairingCode) -> Vec<String> {
    code.addrs.iter().map(|a| if a.contains(':') { format!("[{a}]:{}", code.port) } else { format!("{a}:{}", code.port) }).collect()
}

/// The first of `candidates` that answers and completes the handshake.
fn reach<T>(candidates: &[String], mut handshake: impl FnMut(TcpStream) -> anyhow::Result<T>) -> Result<(String, T)> {
    let mut refused = false;
    let mut seen = BTreeSet::new();
    for candidate in candidates {
        if !seen.insert(candidate.clone()) {
            continue;
        }
        let Ok(addrs) = candidate.to_socket_addrs() else { continue };
        let addrs: Vec<SocketAddr> = addrs.collect();
        for addr in addrs {
            let Ok(stream) = TcpStream::connect_timeout(&addr, CONNECT_TIMEOUT) else { continue };
            let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
            let _ = stream.set_write_timeout(Some(IO_TIMEOUT));
            match handshake(stream) {
                Ok(t) => return Ok((candidate.clone(), t)),
                // It answered but it isn't this phone's computer (another one on the network, or
                // ours after it unpaired this phone).
                Err(e) => {
                    log_debug(&format!("{candidate}: {e:#}"));
                    refused = true;
                }
            }
        }
    }
    Err(if refused { MurmurError::NotPaired } else { MurmurError::Unreachable })
}

fn log_debug(s: &str) {
    if cfg!(debug_assertions) {
        eprintln!("murmur sync: {s}");
    }
}

fn key32(key: &[u8]) -> Result<[u8; 32]> {
    key.try_into().map_err(|_| MurmurError::Failed("bad key".into()))
}

#[uniffi::export]
impl Murmur {
    /// Pairs with the computer of the QR's `uri`. `extra` are more addresses to try (the ones
    /// Bonjour found), after the code's own. Returns the computer, to remember.
    pub fn pair(&self, uri: String, extra: Vec<String>, private_key: Vec<u8>, phone_name: String) -> Result<Computer> {
        let code = PairingCode::parse(uri.trim()).map_err(|_| MurmurError::NotACode)?;
        let keys = Keys::from_private(&private_key)?;
        let mut candidates = addresses(&code);
        candidates.extend(extra);
        let (address, session) = reach(&candidates, |stream| sync::pair(stream, &keys, &code, &phone_name)).map_err(|e| match e {
            // With a code, a refused handshake means the code isn't on screen any more.
            MurmurError::NotPaired => MurmurError::CodeExpired,
            e => e,
        })?;
        let mut session = session;
        let _ = session.send(&Msg::Bye);
        // A new computer (or the same one again): everything goes to it from the start.
        self.save_sync(&SyncState { revised: self.load_sync().revised, ..Default::default() })?;
        Ok(Computer { name: session.remote_name.clone(), key: code.key.to_vec(), address })
    }

    /// Unpaired on the phone: what was sent is forgotten (pairing again sends it all; the computer
    /// knows what it already has). What came back revised stays revised.
    pub fn forget_computer(&self) -> Result<()> {
        let state = self.load_sync();
        self.save_sync(&SyncState { revised: state.revised, ..Default::default() })
    }

    /// Finished meetings the computer doesn't have yet.
    pub fn unsent_count(&self) -> Result<u32> {
        let state = self.load_sync();
        Ok(lock(&self.db).list()?.iter().filter(|m| m.status == "done" && !state.sent.contains(&m.id)).count() as u32)
    }

    /// Sends the computer every finished meeting it doesn't have yet (with its audio, resuming a
    /// cut one), then picks up what it has revised. One sync at a time.
    pub fn sync(&self, candidates: Vec<String>, private_key: Vec<u8>, computer_key: Vec<u8>, phone_name: String) -> Result<SyncReport> {
        let _one = self.syncing.try_lock().map_err(|_| MurmurError::Busy)?;
        let keys = Keys::from_private(&private_key)?;
        let computer = key32(&computer_key)?;
        let (address, mut session) = reach(&candidates, |stream| sync::connect(stream, &keys, &computer, &phone_name))?;
        let mut report = SyncReport { address, computer_name: session.remote_name.clone(), sent: 0, revised: 0, failed: 0 };
        let mut state = self.load_sync();

        let pending: Vec<String> = lock(&self.db).list()?.into_iter().filter(|m| m.status == "done" && !state.sent.contains(&m.id)).map(|m| m.id).collect();
        for id in pending {
            match self.send_meeting(&mut session, &id)? {
                true => {
                    state.sent.insert(id);
                    report.sent += 1;
                    self.save_sync(&state)?;
                }
                false => report.failed += 1,
            }
        }

        session.send(&Msg::GetResults { since: state.since }).map_err(io)?;
        loop {
            match session.recv().map_err(io)? {
                Incoming::Msg(Msg::Results { meeting, revised_at }) => {
                    if self.take_results(meeting, &state)? {
                        report.revised += 1;
                    }
                    // Re-read first: take_results marked it revised.
                    state = self.load_sync();
                    state.since = state.since.max(revised_at);
                    self.save_sync(&state)?;
                }
                Incoming::Msg(Msg::Bye) => break,
                other => return Err(MurmurError::Failed(format!("unexpected {other:?}"))),
            }
        }
        let _ = session.send(&Msg::Bye);
        Ok(report)
    }
}

/// A connection that drops halfway: the computer went away; next time it carries on.
fn io(e: anyhow::Error) -> MurmurError {
    if e.downcast_ref::<std::io::Error>().is_some() { MurmurError::Unreachable } else { e.into() }
}

impl Murmur {
    fn sync_file(&self) -> std::path::PathBuf {
        self.data_dir.join("sync.json")
    }

    pub(crate) fn load_sync(&self) -> SyncState {
        std::fs::read_to_string(self.sync_file()).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
    }

    pub(crate) fn save_sync(&self, state: &SyncState) -> Result<()> {
        let tmp = self.sync_file().with_extension("json.tmp");
        std::fs::write(&tmp, serde_json::to_vec(state).map_err(|e| MurmurError::Failed(e.to_string()))?).map_err(|e| MurmurError::Failed(e.to_string()))?;
        std::fs::rename(&tmp, self.sync_file()).map_err(|e| MurmurError::Failed(e.to_string()))
    }

    /// A rename on the phone after the meeting was sent: the phone's names win when it comes back.
    pub(crate) fn note_edit(&self, id: &str) {
        let mut state = self.load_sync();
        if state.sent.contains(id) && state.edited.insert(id.to_owned()) {
            let _ = self.save_sync(&state);
        }
    }

    /// One meeting and its audio. True when the computer has it whole; false when it refused it.
    fn send_meeting(&self, session: &mut Session<TcpStream>, id: &str) -> Result<bool> {
        let Some(mut meeting) = lock(&self.db).get(id)? else { return Ok(true) };
        let audio = meeting["audioPath"].as_str().map(std::path::PathBuf::from).filter(|p| p.exists());
        // The phone's own paths mean nothing on the computer.
        if let Some(m) = meeting.as_object_mut() {
            m.remove("audioPath");
        }
        let files = match &audio {
            Some(path) => vec![sync::file_info("audio.ogg", path)?],
            None => vec![],
        };
        session.send(&Msg::PutMeeting { meeting, files }).map_err(io)?;
        match session.recv_msg().map_err(io)? {
            Msg::Have { files, .. } => {
                if let (Some(path), Some(have)) = (&audio, files.iter().find(|f| f.name == "audio.ogg")) {
                    sync::send_file(session, id, "audio.ogg", path, have.have).map_err(io)?;
                }
            }
            Msg::Failed { reason, .. } => {
                log_debug(&format!("{id} refused: {reason}"));
                return Ok(false);
            }
            other => return Err(MurmurError::Failed(format!("expected Have, got {other:?}"))),
        }
        match session.recv_msg().map_err(io)? {
            Msg::Stored { .. } => Ok(true),
            Msg::Failed { reason, .. } => {
                log_debug(&format!("{id} failed: {reason}"));
                Ok(false)
            }
            other => Err(MurmurError::Failed(format!("expected Stored, got {other:?}"))),
        }
    }

    /// A meeting after the computer's final pass takes the place of the phone's: its lines,
    /// speakers, summary and translation. The phone keeps its audio, and its names if they were
    /// changed here after sending. A meeting deleted on the phone stays deleted.
    fn take_results(&self, mut meeting: Value, state: &SyncState) -> Result<bool> {
        let id = meeting["id"].as_str().unwrap_or_default().to_owned();
        let mut db = lock(&self.db);
        let Some(mine) = db.get(&id)? else { return Ok(false) };
        meeting["audioPath"] = mine["audioPath"].clone();
        if state.edited.contains(&id) {
            meeting["title"] = mine["title"].clone();
            meeting["speakerNames"] = mine["speakerNames"].clone();
        }
        meeting["status"] = Value::from("done");
        db.put(&meeting)?;
        drop(db);
        let mut state = self.load_sync();
        state.revised.insert(id.clone());
        state.edited.remove(&id);
        self.save_sync(&state)?;
        Ok(true)
    }
}
