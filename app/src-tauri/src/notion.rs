//! Notion (Settings › Exportar, menu Exporta): meetings as pages in the user's own Notion,
//! through Notion's hosted MCP server. "Connecta amb Notion" signs in in the browser with OAuth
//! and PKCE, as a public client registered on the fly: no key to create and no Murmur server in
//! between. The grant (client id, access and refresh tokens) lives in the keychain; tokens rotate
//! on each refresh. Only the meeting's text goes to Notion, when the user exports it.

use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use anyhow::{Result, anyhow, bail};
use base64::Engine as _;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use crate::i18n::tr;

const MCP: &str = "https://mcp.notion.com/mcp";
const METADATA: &str = "https://mcp.notion.com/.well-known/oauth-authorization-server";
const ISSUER: &str = "https://mcp.notion.com";
const SERVICE: &str = "com.quicodomingo.murmur.notion";
const ACCOUNT: &str = "grant";
/// How long the browser has to come back.
const SIGN_IN: Duration = Duration::from_secs(5 * 60);
/// Refresh this long before the access token expires.
const MARGIN_S: f64 = 300.0;
/// Over this, the transcript goes to its own subpages (Notion refuses content too big for one call).
const PIECE: usize = 40_000;

/// What's kept in the keychain.
#[derive(Clone, Serialize, Deserialize)]
struct Grant {
    client_id: String,
    access_token: String,
    refresh_token: String,
    /// Seconds since the epoch.
    expires_at: f64,
    workspace: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub connected: bool,
    pub workspace: Option<String>,
}

/// A page to save under, for the picker.
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Place {
    pub id: String,
    pub title: String,
    pub url: String,
}

/// Refreshes one at a time: a refresh token reused after rotating revokes the whole grant.
static REFRESH: Mutex<()> = Mutex::new(());
static CANCEL: AtomicBool = AtomicBool::new(false);

fn now() -> f64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs_f64()).unwrap_or(0.0)
}

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(15)).timeout(Duration::from_secs(120)).build()
}

fn load() -> Option<Grant> {
    serde_json::from_slice(&crate::keychain::get(SERVICE, ACCOUNT)?).ok()
}

fn store(grant: &Grant) -> Result<()> {
    crate::keychain::set(SERVICE, ACCOUNT, &serde_json::to_vec(grant)?)
}

pub fn status() -> Status {
    let grant = load();
    Status { connected: grant.is_some(), workspace: grant.map(|g| g.workspace) }
}

pub fn disconnect() -> Result<()> {
    crate::keychain::delete(SERVICE, ACCOUNT)
}

/// Stops a sign-in waiting for the browser.
pub fn cancel() {
    CANCEL.store(true, Ordering::SeqCst);
}

fn http_error(e: ureq::Error) -> anyhow::Error {
    match e {
        ureq::Error::Status(code, r) => {
            let body: Value = r.into_json().unwrap_or(Value::Null);
            let what = body["error_description"].as_str().or(body["error"].as_str()).unwrap_or("").to_string();
            anyhow!("Notion {code} {what}")
        }
        ureq::Error::Transport(_) => anyhow!(tr!("cloud_offline")),
    }
}

/// "Connecta amb Notion": signs in in the browser and keeps the grant. Returns the workspace's name.
pub fn connect(open: impl Fn(&str) -> Result<()>) -> Result<String> {
    CANCEL.store(false, Ordering::SeqCst);
    let meta: Value = agent().get(METADATA).call().map_err(http_error)?.into_json()?;
    if meta["issuer"] != ISSUER {
        bail!("Notion: unexpected issuer");
    }
    let field = |k: &str| meta[k].as_str().map(str::to_owned).ok_or_else(|| anyhow!("Notion: no {k}"));
    let (authorize, token, register) = (field("authorization_endpoint")?, field("token_endpoint")?, field("registration_endpoint")?);

    // The browser comes back here, on this computer only.
    let listener = TcpListener::bind("127.0.0.1:0")?;
    listener.set_nonblocking(true)?;
    let redirect = format!("http://127.0.0.1:{}/callback", listener.local_addr()?.port());

    let reg: Value = agent()
        .post(&register)
        .send_json(json!({
            "client_name": "Murmur",
            "redirect_uris": [redirect],
            "grant_types": ["authorization_code", "refresh_token"],
            "response_types": ["code"],
            "token_endpoint_auth_method": "none",
        }))
        .map_err(http_error)?
        .into_json()?;
    let client_id = reg["client_id"].as_str().ok_or_else(|| anyhow!("Notion: no client id"))?.to_string();

    let mut random = [0u8; 32];
    crate::keychain::random_bytes(&mut random);
    let verifier = B64.encode(random);
    let challenge = B64.encode(Sha256::digest(verifier.as_bytes()));
    crate::keychain::random_bytes(&mut random[..16]);
    let state = B64.encode(&random[..16]);
    let url = format!(
        "{authorize}?response_type=code&client_id={}&redirect_uri={}&state={state}&code_challenge={challenge}&code_challenge_method=S256&resource={}&prompt=consent",
        enc(&client_id),
        enc(&redirect),
        enc(MCP)
    );
    open(&url)?;

    let code = wait_for_code(&listener, &state)?;
    let tok: Value = agent()
        .post(&token)
        .send_form(&[
            ("grant_type", "authorization_code"),
            ("code", &code),
            ("redirect_uri", &redirect),
            ("client_id", &client_id),
            ("code_verifier", &verifier),
            ("resource", MCP),
        ])
        .map_err(http_error)?
        .into_json()?;
    let grant = Grant {
        client_id,
        access_token: tok["access_token"].as_str().ok_or_else(|| anyhow!("Notion: no token"))?.into(),
        refresh_token: tok["refresh_token"].as_str().unwrap_or_default().into(),
        expires_at: now() + tok["expires_in"].as_f64().unwrap_or(3600.0),
        workspace: tok["workspace_name"].as_str().unwrap_or("Notion").into(),
    };
    store(&grant)?;
    Ok(grant.workspace)
}

fn enc(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

fn dec(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = vec![];
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                out.push(u8::from_str_radix(&s[i + 1..i + 3], 16).unwrap_or(b'?'));
                i += 3;
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Waits for the browser's redirect and answers it with a page that says it can be closed.
fn wait_for_code(listener: &TcpListener, state: &str) -> Result<String> {
    let until = Instant::now() + SIGN_IN;
    loop {
        if CANCEL.load(Ordering::SeqCst) {
            bail!(tr!("notion_cancelled"));
        }
        if Instant::now() > until {
            bail!(tr!("notion_timed_out"));
        }
        let (mut stream, _) = match listener.accept() {
            Ok(s) => s,
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(Duration::from_millis(200));
                continue;
            }
            Err(e) => return Err(e.into()),
        };
        stream.set_nonblocking(false)?;
        stream.set_read_timeout(Some(Duration::from_secs(5)))?;
        let mut line = String::new();
        BufReader::new(&stream).read_line(&mut line)?;
        // "GET /callback?code=…&state=… HTTP/1.1"
        let target = line.split_whitespace().nth(1).unwrap_or("");
        let Some(query) = target.strip_prefix("/callback?") else {
            let _ = stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
            continue;
        };
        let param = |k: &str| query.split('&').find_map(|kv| kv.strip_prefix(k).and_then(|v| v.strip_prefix('='))).map(dec);
        let page = tr!("notion_signed_in_page");
        let body = format!("<!doctype html><meta charset=utf-8><title>Murmur</title><p style=\"font:16px -apple-system,system-ui,sans-serif;margin:4em auto;max-width:28em\">{page}</p>");
        let _ = write!(stream, "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
        if param("state").as_deref() != Some(state) {
            bail!("Notion: state mismatch");
        }
        if let Some(e) = param("error") {
            bail!(tr!("notion_refused", error = e));
        }
        if param("iss").is_some_and(|i| i != ISSUER) {
            bail!("Notion: unexpected issuer");
        }
        return param("code").ok_or_else(|| anyhow!("Notion: no code"));
    }
}

/// A valid access token, refreshing it first if it's about to expire.
fn token() -> Result<String> {
    let _one = REFRESH.lock().unwrap();
    let mut grant = load().ok_or_else(|| anyhow!(tr!("notion_not_connected")))?;
    if grant.expires_at - now() > MARGIN_S {
        return Ok(grant.access_token);
    }
    let meta: Value = agent().get(METADATA).call().map_err(http_error)?.into_json()?;
    let endpoint = meta["token_endpoint"].as_str().ok_or_else(|| anyhow!("Notion: no token endpoint"))?;
    let res = agent().post(endpoint).send_form(&[
        ("grant_type", "refresh_token"),
        ("refresh_token", &grant.refresh_token),
        ("client_id", &grant.client_id),
        ("resource", MCP),
    ]);
    let tok: Value = match res {
        Ok(r) => r.into_json()?,
        // The grant ended (expired, revoked, or the app removed in Notion): connect again.
        Err(ureq::Error::Status(400 | 401, _)) => {
            let _ = disconnect();
            bail!(tr!("notion_reconnect"));
        }
        Err(e) => return Err(http_error(e)),
    };
    grant.access_token = tok["access_token"].as_str().ok_or_else(|| anyhow!("Notion: no token"))?.into();
    if let Some(r) = tok["refresh_token"].as_str() {
        grant.refresh_token = r.into();
    }
    grant.expires_at = now() + tok["expires_in"].as_f64().unwrap_or(3600.0);
    store(&grant)?;
    Ok(grant.access_token)
}

/// One MCP exchange with Notion: a session, then the tool call. Returns the tool's result, parsed
/// as JSON when it's JSON.
fn call(tool: &str, args: Value) -> Result<Value> {
    let token = token()?;
    let mut session: Option<String> = None;
    let mut post = |body: Value| -> Result<Value> {
        let mut req = agent().post(MCP).set("Authorization", &format!("Bearer {token}")).set("Accept", "application/json, text/event-stream");
        if let Some(s) = &session {
            req = req.set("Mcp-Session-Id", s);
        }
        let res = req.send_json(body).map_err(http_error)?;
        if let Some(s) = res.header("mcp-session-id") {
            session = Some(s.to_string());
        }
        let text = res.into_string()?;
        if text.trim().is_empty() {
            return Ok(Value::Null);
        }
        let json = if text.trim_start().starts_with('{') {
            text
        } else {
            // Server-sent events: the last data line is the answer.
            text.lines().filter_map(|l| l.strip_prefix("data:")).last().unwrap_or("null").trim().to_string()
        };
        Ok(serde_json::from_str(&json)?)
    };
    post(json!({ "jsonrpc": "2.0", "id": 1, "method": "initialize", "params": { "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": { "name": "Murmur", "version": env!("CARGO_PKG_VERSION") } } }))?;
    post(json!({ "jsonrpc": "2.0", "method": "notifications/initialized", "params": {} }))?;
    let res = post(json!({ "jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": { "name": tool, "arguments": args } }))?;
    if let Some(e) = res.get("error") {
        bail!("Notion: {}", e["message"].as_str().unwrap_or("error"));
    }
    let result = &res["result"];
    let text: String = result["content"].as_array().into_iter().flatten().filter_map(|c| c["text"].as_str()).collect::<Vec<_>>().join("\n");
    if result["isError"].as_bool() == Some(true) {
        bail!("Notion: {text}");
    }
    if let Some(structured) = result.get("structuredContent").filter(|v| !v.is_null()) {
        return Ok(structured.clone());
    }
    Ok(serde_json::from_str(&text).unwrap_or(Value::String(text)))
}

/// The picker in Settings › Exportar: pages matching `query`, or the recent ones.
pub fn find(query: &str) -> Result<Vec<Place>> {
    let res = if query.trim().is_empty() { call("notion-list-recent-pages", json!({ "limit": 30 }))? } else { call("notion-search", json!({ "query": query }))? };
    let items = res["results"].as_array().or(res["pages"].as_array()).cloned().unwrap_or_default();
    Ok(items
        .iter()
        .filter(|r| r["type"].as_str().is_none_or(|t| t == "page"))
        .filter_map(|r| {
            Some(Place {
                id: r["id"].as_str().map(str::to_owned).or_else(|| r["url"].as_str().and_then(page_id_from_url))?,
                title: r["title"].as_str().filter(|t| !t.is_empty()).unwrap_or(tr!("notion_untitled")).to_string(),
                url: r["url"].as_str().unwrap_or_default().to_string(),
            })
        })
        .collect())
}

fn page_id_from_url(url: &str) -> Option<String> {
    let last = url.split('?').next()?.rsplit('/').next()?;
    let hex: String = last.chars().rev().take(32).collect::<String>().chars().rev().collect();
    (hex.len() == 32 && hex.chars().all(|c| c.is_ascii_hexdigit())).then_some(hex)
}

/// Creates a page under `parent` (a page id) and returns its id and url.
fn create_page(parent: Option<&str>, title: &str, content: &str) -> Result<(String, String)> {
    let mut args = json!({ "pages": [{ "properties": { "title": title }, "content": content }] });
    if let Some(p) = parent {
        args["parent"] = json!({ "page_id": p });
    }
    let res = call("notion-create-pages", args)?;
    let page = res["pages"].get(0).ok_or_else(|| anyhow!(tr!("notion_export_failed", error = "no page")))?;
    Ok((page["id"].as_str().unwrap_or_default().into(), page["url"].as_str().unwrap_or_default().into()))
}

/// "Exporta › Notion": the meeting as a page under `parent` (or a private page). A transcript too
/// big for one call goes in subpages, a piece each. Returns the page's url.
pub fn export(title: &str, summary: &str, transcript: &str, parent: Option<&str>) -> Result<String> {
    if summary.len() + transcript.len() < PIECE {
        let content = if transcript.is_empty() { summary.to_string() } else { format!("{summary}\n\n{transcript}") };
        return Ok(create_page(parent, title, &content)?.1);
    }
    let (id, url) = create_page(parent, title, summary)?;
    let pieces = split(transcript, PIECE);
    for (i, piece) in pieces.iter().enumerate() {
        let name = if pieces.len() == 1 { tr!("notion_transcript").to_string() } else { format!("{} ({}/{})", tr!("notion_transcript"), i + 1, pieces.len()) };
        create_page(Some(&id), &name, piece)?;
    }
    Ok(url)
}

/// Splits Markdown at paragraph breaks into pieces of at most `max` bytes.
fn split(text: &str, max: usize) -> Vec<String> {
    let mut out = vec![String::new()];
    for para in text.split("\n\n") {
        let last = out.last_mut().unwrap();
        if !last.is_empty() && last.len() + para.len() + 2 > max {
            out.push(String::new());
        }
        let last = out.last_mut().unwrap();
        if !last.is_empty() {
            last.push_str("\n\n");
        }
        last.push_str(para);
    }
    out.retain(|p| !p.trim().is_empty());
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn urls_and_long_transcripts() {
        assert_eq!(enc("http://127.0.0.1:5/callback"), "http%3A%2F%2F127.0.0.1%3A5%2Fcallback");
        assert_eq!(dec("a%2Fb+c"), "a/b c");
        assert_eq!(page_id_from_url("https://app.notion.com/p/67482b946939821d988f81f10c5d7fc1?pvs=204").as_deref(), Some("67482b946939821d988f81f10c5d7fc1"));
        let text = (0..100).map(|i| format!("Frase {i}.")).collect::<Vec<_>>().join("\n\n");
        let parts = split(&text, 200);
        assert!(parts.len() > 1 && parts.iter().all(|p| p.len() <= 200));
        assert_eq!(parts.join("\n\n"), text);
    }
}
