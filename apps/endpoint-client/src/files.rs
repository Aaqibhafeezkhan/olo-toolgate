// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The device files area (ADR 027): one capability-scoped, service-owned directory that the executor brokers for
//! SDK tools over tool protocol v2. Tools never provide ambient OS paths. Operations are generic primitives, serialized
//! by the executor and performed only within an online-authorized invocation whose signed registration grants them.
use crate::{Failure, Result};
use cap_fs_ext::{DirExt, FollowSymlinks, OpenOptionsFollowExt};
use cap_std::fs::{Dir, OpenOptions};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    io::{Read, Write},
    path::{Path, PathBuf},
};

#[derive(Clone, PartialEq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Settings {
    pub root: PathBuf,
    pub max_file_bytes: u64,
    pub max_entries: usize,
    pub extensions: Vec<String>,
}
impl Settings {
    pub fn validate(&self) -> Result<()> {
        if !self.root.is_absolute()
            || !(1..=65536).contains(&self.max_file_bytes)
            || !(1..=1024).contains(&self.max_entries)
            || self.extensions.is_empty()
            || self.extensions.len() > 32
            || self.extensions.iter().any(|s| {
                s.is_empty()
                    || s.len() > 16
                    || !s
                        .bytes()
                        .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit())
            })
        {
            return Err(Failure::Validation);
        }
        Ok(())
    }
}

/// One lowercase portable spelling prevents case aliases on Windows/macOS filesystems.
/// No absolute names, ADS, devices, short-name aliases or traversal.
pub fn components(path: &str) -> Result<Vec<&str>> {
    if path.is_empty() || path.len() > 1024 {
        return Err(Failure::Validation);
    }
    let parts: Vec<_> = path.split('/').collect();
    if parts.len() > 16
        || parts.iter().any(|part| {
            let stem = part
                .split('.')
                .next()
                .unwrap_or_default()
                .to_ascii_uppercase();
            part.is_empty()
                || part.len() > 128
                || *part == "."
                || *part == ".."
                || part.starts_with('.')
                || part.ends_with('.')
                || part.ends_with(' ')
                || !part
                    .bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b"._- ".contains(&b))
                || matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
                || (stem.len() == 4
                    && (stem.starts_with("COM") || stem.starts_with("LPT"))
                    && stem.as_bytes()[3].is_ascii_digit())
        })
    {
        return Err(Failure::Validation);
    }
    Ok(parts)
}

pub struct Area {
    root: Dir,
    settings: Settings,
}
impl Area {
    pub fn open(settings: Settings) -> Result<Self> {
        settings.validate()?;
        // Administrator-owned ancestors and owner-only root exclude untrusted writers.
        let _store = crate::storage::ProtectedStore::open(settings.root.clone())?;
        let root = Dir::open_ambient_dir(&settings.root, cap_std::ambient_authority())
            .map_err(|_| Failure::Unavailable)?;
        Ok(Self { root, settings })
    }
    fn parent(&self, path: &str, file: bool) -> Result<(Dir, String)> {
        let parts = components(path)?;
        if file {
            let extension = Path::new(path)
                .extension()
                .and_then(|s| s.to_str())
                .ok_or(Failure::Validation)?;
            if !self.settings.extensions.iter().any(|s| s == extension) {
                return Err(Failure::Unauthorized);
            }
        }
        let mut dir = self.root.try_clone().map_err(|_| Failure::Unavailable)?;
        for name in &parts[..parts.len() - 1] {
            dir = dir
                .open_dir_nofollow(name)
                .map_err(|_| Failure::Unauthorized)?;
        }
        Ok((dir, parts[parts.len() - 1].to_owned()))
    }
    fn regular(&self, dir: &Dir, name: &str, _path: &str) -> Result<()> {
        let meta = dir
            .symlink_metadata(name)
            .map_err(|_| Failure::Unavailable)?;
        if !meta.is_file()
            || meta.file_type().is_symlink()
            || meta.len() > self.settings.max_file_bytes
        {
            return Err(Failure::Unauthorized);
        }
        #[cfg(windows)]
        {
            let full = self.settings.root.join(_path);
            crate::storage::check_parents(&full)?;
            crate::storage::check_owned(&full, true)?;
            use cap_std::fs::MetadataExt;
            if meta.file_attributes() & 0x400 != 0 {
                return Err(Failure::Unauthorized);
            }
            let mut options = OpenOptions::new();
            options.read(true).follow(FollowSymlinks::No);
            let file = dir
                .open_with(name, &options)
                .map_err(|_| Failure::Unauthorized)?;
            let opened = file.metadata().map_err(|_| Failure::Unauthorized)?;
            if cap_fs_ext::MetadataExt::nlink(&opened) != 1 {
                return Err(Failure::Unauthorized);
            }
        }
        #[cfg(unix)]
        {
            use cap_std::fs::MetadataExt;
            if meta.nlink() != 1
                || meta.mode() & 0o077 != 0
                || meta.uid() != unsafe { libc::geteuid() }
            {
                return Err(Failure::Unauthorized);
            }
        }
        Ok(())
    }
    pub fn read(&self, path: &str) -> Result<Vec<u8>> {
        let (dir, name) = self.parent(path, true)?;
        self.regular(&dir, &name, path)?;
        let mut options = OpenOptions::new();
        options.read(true).follow(FollowSymlinks::No);
        let mut file = dir
            .open_with(&name, &options)
            .map_err(|_| Failure::Unauthorized)?;
        let mut bytes = Vec::new();
        Read::by_ref(&mut file)
            .take(self.settings.max_file_bytes + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| Failure::Unavailable)?;
        if bytes.len() as u64 > self.settings.max_file_bytes {
            return Err(Failure::Validation);
        }
        Ok(bytes)
    }
    /// Durable replace never truncates a linked target. Temporary files cannot be addressed by tools.
    pub fn write(&self, path: &str, bytes: &[u8], append: bool) -> Result<()> {
        if bytes.len() as u64 > self.settings.max_file_bytes {
            return Err(Failure::Validation);
        }
        let (dir, name) = self.parent(path, true)?;
        let mut content = if append { self.read(path)? } else { Vec::new() };
        content.extend_from_slice(bytes);
        if content.len() as u64 > self.settings.max_file_bytes {
            return Err(Failure::Validation);
        }
        match dir.symlink_metadata(&name) {
            Ok(_) => self.regular(&dir, &name, path)?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => self.capacity()?,
            Err(_) => return Err(Failure::Unavailable),
        }
        let temporary = format!(".write-{}", crate::identity::nonce()?);
        let mut options = OpenOptions::new();
        options
            .write(true)
            .create_new(true)
            .follow(FollowSymlinks::No);
        #[cfg(unix)]
        {
            use cap_std::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let result = (|| {
            let mut file = dir
                .open_with(&temporary, &options)
                .map_err(|_| Failure::Unavailable)?;
            file.write_all(&content)
                .and_then(|_| file.sync_all())
                .map_err(|_| Failure::Unavailable)?;
            drop(file);
            dir.rename(&temporary, &dir, &name)
                .map_err(|_| Failure::Unavailable)?;
            #[cfg(unix)]
            dir.open(".")
                .map_err(|_| Failure::Unavailable)?
                .sync_all()
                .map_err(|_| Failure::Unavailable)?;
            Ok(())
        })();
        if result.is_err() {
            let _ = dir.remove_file(&temporary);
        }
        result
    }
    pub fn mkdir(&self, path: &str) -> Result<()> {
        self.capacity()?;
        let (dir, name) = self.parent(path, false)?;
        let builder = cap_std::fs::DirBuilder::new();
        #[cfg(unix)]
        let mut builder = builder;
        #[cfg(unix)]
        {
            use cap_std::fs::DirBuilderExt;
            builder.mode(0o700);
        }
        dir.create_dir_with(name, &builder)
            .map_err(|_| Failure::Conflict)
    }
    pub fn info(&self, path: &str) -> Result<Value> {
        let (dir, name) = self.parent(path, true)?;
        self.regular(&dir, &name, path)?;
        let meta = dir
            .symlink_metadata(name)
            .map_err(|_| Failure::Unavailable)?;
        Ok(json!({"path":path,"bytes":meta.len(),"kind":"FILE"}))
    }
    /// Moves a regular file to a destination that does not exist; both stay confined.
    pub fn rename(&self, source: &str, destination: &str) -> Result<()> {
        if source == destination {
            return Err(Failure::Validation);
        }
        let (origin, origin_name) = self.parent(source, true)?;
        self.regular(&origin, &origin_name, source)?;
        let (target, target_name) = self.parent(destination, true)?;
        match target.symlink_metadata(&target_name) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            _ => return Err(Failure::Conflict),
        }
        origin
            .rename(origin_name, &target, target_name)
            .map_err(|_| Failure::Unavailable)
    }
    fn capacity(&self) -> Result<()> {
        if self.list()?.len() >= self.settings.max_entries {
            return Err(Failure::Validation);
        }
        Ok(())
    }
    /// Bounded deterministic recursive inventory; unsafe entries fail the entire operation.
    pub fn list(&self) -> Result<Vec<String>> {
        let mut output = Vec::new();
        self.walk(&self.root, "", 0, &mut output)?;
        output.sort();
        Ok(output)
    }
    fn walk(&self, dir: &Dir, prefix: &str, depth: usize, output: &mut Vec<String>) -> Result<()> {
        if depth > 16 {
            return Err(Failure::Validation);
        }
        for entry in dir.entries().map_err(|_| Failure::Unavailable)? {
            let entry = entry.map_err(|_| Failure::Unavailable)?;
            let name = entry
                .file_name()
                .into_string()
                .map_err(|_| Failure::Validation)?;
            let path = format!("{prefix}{name}");
            components(&path)?;
            let meta = dir
                .symlink_metadata(&name)
                .map_err(|_| Failure::Unavailable)?;
            if meta.is_dir() && !meta.file_type().is_symlink() {
                #[cfg(windows)]
                crate::storage::check_owned(&self.settings.root.join(&path), true)?;
                output.push(path.clone());
                let child = dir
                    .open_dir_nofollow(&name)
                    .map_err(|_| Failure::Unauthorized)?;
                self.walk(&child, &format!("{path}/"), depth + 1, output)?;
            } else {
                self.regular(dir, &name, &path)?;
                output.push(path);
            }
            if output.len() > self.settings.max_entries {
                return Err(Failure::Validation);
            }
        }
        Ok(())
    }
}
