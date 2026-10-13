// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Owner-protected storage with no symlinks, bounded reads and durable atomic replacement.
use crate::{Failure, Result};
use std::{
    fs::{self, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};

pub fn check_owned(path: &Path, private: bool) -> Result<()> {
    let metadata = fs::symlink_metadata(path).map_err(|_| Failure::Unavailable)?;
    if metadata.file_type().is_symlink() {
        return Err(Failure::Unauthorized);
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        // A protected service is root; unit tests exercise equivalent custody for their OS identity.
        let owner = unsafe { libc::geteuid() };
        if metadata.uid() != owner && metadata.uid() != 0
            || metadata.mode() & 0o022 != 0
            || private && metadata.mode() & 0o077 != 0
        {
            return Err(Failure::Unauthorized);
        }
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        if metadata.file_attributes() & 0x400 != 0 {
            return Err(Failure::Unauthorized);
        }
        crate::platform::windows::check_acl(path, private)?;
    }
    Ok(())
}
pub fn check_parents(path: &Path) -> Result<()> {
    let mut current = path.parent();
    while let Some(parent) = current {
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            let meta = fs::symlink_metadata(parent).map_err(|_| Failure::Unavailable)?;
            // A root-owned sticky /tmp is safe for a uniquely created owner-only child.
            if !(meta.uid() == 0 && meta.mode() & 0o1000 != 0 && !meta.file_type().is_symlink()) {
                check_owned(parent, false)?;
            }
        }
        #[cfg(windows)]
        {
            check_owned(parent, false)?;
        }
        current = parent.parent();
    }
    Ok(())
}
pub fn read_owned(path: &Path, max: usize, private: bool) -> Result<Vec<u8>> {
    if !path.is_absolute() {
        return Err(Failure::Validation);
    }
    check_parents(path)?;
    check_owned(path, private)?;
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
    }
    let mut file = options.open(path).map_err(|_| Failure::Unavailable)?;
    if !file.metadata().map_err(|_| Failure::Unavailable)?.is_file() {
        return Err(Failure::Unauthorized);
    }
    let mut bytes = Vec::new();
    Read::by_ref(&mut file)
        .take((max + 1) as u64)
        .read_to_end(&mut bytes)
        .map_err(|_| Failure::Unavailable)?;
    if bytes.len() > max {
        return Err(Failure::Validation);
    }
    Ok(bytes)
}
pub struct ProtectedStore {
    directory: PathBuf,
}
impl ProtectedStore {
    pub fn open(directory: PathBuf) -> Result<Self> {
        if !directory.is_absolute() {
            return Err(Failure::Validation);
        }
        check_parents(&directory)?;
        if !directory.exists() {
            let builder = fs::DirBuilder::new();
            #[cfg(unix)]
            let mut builder = builder;
            #[cfg(unix)]
            {
                use std::os::unix::fs::DirBuilderExt;
                builder.mode(0o700);
            }
            builder
                .create(&directory)
                .map_err(|_| Failure::Unavailable)?;
            #[cfg(windows)]
            crate::platform::windows::protect_acl(&directory)?;
        }
        check_owned(&directory, true)?;
        Ok(Self { directory })
    }
    fn path(&self, name: &str) -> Result<PathBuf> {
        if !matches!(
            name,
            "device-key"
                | "packets.jsonl"
                | "activity.json"
                | "journal.json"
                | "adoption.json"
                | "remote-journal.json"
                | "effect-journal.json"
                | "service.lock"
                | "fleet-intent.json"
                | "fleet-active.json"
                | "local-tools.json"
                | "local-tools-token"
                | "power.json"
        ) {
            return Err(Failure::Validation);
        }
        Ok(self.directory.join(name))
    }
    pub fn read(&self, name: &str) -> Result<Option<Vec<u8>>> {
        let path = self.path(name)?;
        if !path.try_exists().map_err(|_| Failure::Unavailable)? {
            return Ok(None);
        }
        read_owned(&path, 131072, true).map(Some)
    }
    pub fn write(&self, name: &str, bytes: &[u8]) -> Result<()> {
        if bytes.len() > 131072 {
            return Err(Failure::Validation);
        }
        check_owned(&self.directory, true)?;
        let path = self.path(name)?;
        match fs::symlink_metadata(&path) {
            Ok(_) => check_owned(&path, true)?,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return Err(Failure::Unavailable),
        }
        let temporary = self
            .directory
            .join(format!(".{}-{}", name, crate::identity::nonce()?));
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options
                .mode(0o600)
                .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
        }
        let result = (|| {
            let mut file = options.open(&temporary).map_err(|_| Failure::Unavailable)?;
            #[cfg(windows)]
            crate::platform::windows::protect_acl(&temporary)?;
            file.write_all(bytes)
                .and_then(|_| file.sync_all())
                .map_err(|_| Failure::Unavailable)?;
            drop(file);
            fs::rename(&temporary, &path).map_err(|_| Failure::Unavailable)?;
            #[cfg(unix)]
            fs::File::open(&self.directory)
                .and_then(|d| d.sync_all())
                .map_err(|_| Failure::Unavailable)?;
            Ok(())
        })();
        if temporary.exists() {
            let _ = fs::remove_file(temporary);
        }
        result
    }
    /// Exclusive service lease prevents two processes spending the local report sequence.
    pub fn lease(&self) -> Result<ServiceLease> {
        let path = self.path("service.lock")?;
        if path.exists() {
            check_owned(&path, true)?;
        }
        let mut options = OpenOptions::new();
        options.read(true).write(true).create(true).truncate(false);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options
                .mode(0o600)
                .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
        }
        #[cfg(windows)]
        {
            use std::os::windows::fs::OpenOptionsExt;
            options.share_mode(0);
        }
        let file = options.open(path).map_err(|_| Failure::Conflict)?;
        #[cfg(unix)]
        if unsafe {
            libc::flock(
                std::os::fd::AsRawFd::as_raw_fd(&file),
                libc::LOCK_EX | libc::LOCK_NB,
            )
        } != 0
        {
            return Err(Failure::Conflict);
        }
        Ok(ServiceLease { _file: file })
    }
}
pub struct ServiceLease {
    _file: fs::File,
}

/// A fresh owner-protected directory for unit tests, created through the store's own custody rules. The base matches
/// the integration tests: macOS `/tmp` and `/var` are symlinks, so it uses `/private/tmp`; Windows uses `ProgramData`,
/// whose ACLs the custody checks accept, instead of the per-user temp folder.
#[cfg(test)]
pub(crate) fn test_directory(prefix: &str) -> PathBuf {
    #[cfg(windows)]
    let base = PathBuf::from(std::env::var_os("ProgramData").unwrap());
    #[cfg(target_os = "macos")]
    let base = PathBuf::from("/private/tmp");
    #[cfg(all(unix, not(target_os = "macos")))]
    let base = std::env::temp_dir();
    let path = base.join(format!("{prefix}-{}", crate::identity::nonce().unwrap()));
    ProtectedStore::open(path.clone()).unwrap();
    path
}
