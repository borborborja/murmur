//! Obsidian: the vaults this person has, read from Obsidian's own list (`obsidian.json`), so
//! Settings › Exportar can offer them by name. Only read, never written. No list (Obsidian isn't
//! installed, or never opened a vault): nothing to offer, and the user can still pick a folder.

use std::path::PathBuf;

use serde::Serialize;

#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Vault {
    /// The folder's name, as Obsidian shows it.
    pub name: String,
    pub path: String,
    /// The one Obsidian has open now (or had last).
    pub open: bool,
}

/// Where Obsidian keeps its list of vaults on each system.
fn lists() -> Vec<PathBuf> {
    let mut out = vec![];
    if let Some(config) = dirs::config_dir() {
        // macOS: ~/Library/Application Support; Windows: %APPDATA%; Linux: ~/.config.
        out.push(config.join("obsidian").join("obsidian.json"));
    }
    if let Some(home) = dirs::home_dir() {
        // Linux, installed with Flatpak.
        out.push(home.join(".var/app/md.obsidian.Obsidian/config/obsidian/obsidian.json"));
    }
    out
}

/// The vaults whose folder still exists, the open one first.
pub fn vaults() -> Vec<Vault> {
    let mut found: Vec<Vault> = vec![];
    for list in lists() {
        let Ok(text) = std::fs::read_to_string(&list) else { continue };
        found.extend(parse(&text).into_iter().filter(|v| std::path::Path::new(&v.path).is_dir()));
    }
    found.sort_by(|a, b| b.open.cmp(&a.open).then(a.name.to_lowercase().cmp(&b.name.to_lowercase())));
    found.dedup_by(|a, b| a.path == b.path);
    found
}

fn parse(text: &str) -> Vec<Vault> {
    let Ok(json) = serde_json::from_str::<serde_json::Value>(text) else { return vec![] };
    let Some(vaults) = json.get("vaults").and_then(|v| v.as_object()) else { return vec![] };
    vaults
        .values()
        .filter_map(|v| {
            let path = v.get("path")?.as_str()?.to_string();
            let name = std::path::Path::new(&path).file_name()?.to_string_lossy().into_owned();
            Some(Vault { name, path, open: v.get("open").and_then(|o| o.as_bool()).unwrap_or(false) })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_obsidians_list() {
        let text = r#"{"vaults":{"a1":{"path":"/Users/x/Notes","ts":1,"open":true},"b2":{"path":"/Users/x/Feina","ts":2}}}"#;
        let v = parse(text);
        assert_eq!(v.len(), 2);
        assert!(v.iter().any(|v| v.name == "Notes" && v.open));
        assert!(v.iter().any(|v| v.name == "Feina" && !v.open));
        assert!(parse("not json").is_empty());
        assert!(parse("{}").is_empty());
    }
}
