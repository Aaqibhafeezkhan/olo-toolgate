// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Strict `.tgpkg` archive reading and deterministic writing (plan.md §7.2 and §7.6).
//!
//! The reader accepts only what the packager itself could have written: regular file entries,
//! stored or deflated, laid out back to back from offset 0, followed by the central directory and
//! an end record with no comment. Everything is checked from the central directory before any
//! entry is inflated, so a rejected archive is never extracted.

use crate::Error;
use std::collections::{BTreeMap, HashSet};
use std::io::Read;
use unicode_normalization::{is_nfc, UnicodeNormalization};

/// Entry ceilings (zip-bomb protection). Operators may only lower them.
pub const MAX_ENTRIES: usize = 4096;
pub const MAX_ENTRY_BYTES: u64 = 512 * 1024 * 1024;
pub const MAX_TOTAL_BYTES: u64 = 1024 * 1024 * 1024;
/// Highest uncompressed-to-compressed ratio for an entry larger than [`RATIO_FLOOR_BYTES`].
pub const MAX_RATIO: u64 = 100;
pub const RATIO_FLOOR_BYTES: u64 = 1024 * 1024;

const LOCAL: u32 = 0x0403_4b50;
const CENTRAL: u32 = 0x0201_4b50;
const END: u32 = 0x0605_4b50;
const DESCRIPTOR: u32 = 0x0807_4b50;
const STORED: u16 = 0;
const DEFLATED: u16 = 8;
/// MS-DOS date and time of 1980-01-01 00:00:00, the fixed timestamp of every written entry.
const DOS_TIME: u16 = 0;
const DOS_DATE: u16 = (1 << 5) | 1;

/// A verified archive: entry names in NFC mapped to their exact bytes.
#[derive(Debug)]
pub struct Archive {
    pub files: BTreeMap<String, Vec<u8>>,
}

fn reject(reason: impl Into<String>) -> Error {
    Error::Rejected(reason.into())
}

fn u16_at(data: &[u8], at: usize) -> Result<u16, Error> {
    data.get(at..at + 2)
        .map(|b| u16::from_le_bytes([b[0], b[1]]))
        .ok_or_else(|| reject("truncated archive"))
}

fn u32_at(data: &[u8], at: usize) -> Result<u32, Error> {
    data.get(at..at + 4)
        .map(|b| u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
        .ok_or_else(|| reject("truncated archive"))
}

struct Central {
    name: String,
    flags: u16,
    method: u16,
    crc: u32,
    compressed: u64,
    size: u64,
    offset: u64,
}

/// Checks an entry name against the path rules: relative, `/`-separated, no `.` or `..` segments,
/// no backslash, drive letter or colon, no control characters, and already in NFC.
pub fn check_name(name: &str) -> Result<(), Error> {
    if name.is_empty() || name.len() > 512 {
        return Err(reject("entry name is empty or too long"));
    }
    if name.starts_with('/') || name.contains('\\') || name.contains(':') {
        return Err(reject(format!("unsafe entry path: {name:?}")));
    }
    if name.chars().any(char::is_control) {
        return Err(reject(format!("control character in entry path: {name:?}")));
    }
    if name
        .split('/')
        .any(|s| s.is_empty() || s == "." || s == "..")
    {
        return Err(reject(format!("unsafe entry path: {name:?}")));
    }
    if !is_nfc(name) {
        return Err(reject(format!("entry path is not NFC: {name:?}")));
    }
    Ok(())
}

/// Reads and verifies an archive's structure, returning every entry's bytes.
pub fn read(data: &[u8]) -> Result<Archive, Error> {
    if data.len() < 22 {
        return Err(reject("not a zip archive"));
    }
    // The end record must be the last 22 bytes: a comment or trailing data is refused.
    let end = data.len() - 22;
    if u32_at(data, end)? != END || u16_at(data, end + 20)? != 0 {
        return Err(reject(
            "archive does not end with an end-of-central-directory record",
        ));
    }
    if u16_at(data, end + 4)? != 0 || u16_at(data, end + 6)? != 0 {
        return Err(reject("multi-disk archives are refused"));
    }
    let count = u16_at(data, end + 10)? as usize;
    if u16_at(data, end + 8)? as usize != count {
        return Err(reject("inconsistent entry count"));
    }
    if count > MAX_ENTRIES {
        return Err(reject("too many entries"));
    }
    let cd_size = u32_at(data, end + 12)? as usize;
    let cd_offset = u32_at(data, end + 16)? as usize;
    if cd_offset.checked_add(cd_size) != Some(end) {
        return Err(reject("data outside the central directory"));
    }
    let mut entries = Vec::with_capacity(count);
    let mut at = cd_offset;
    for _ in 0..count {
        if u32_at(data, at)? != CENTRAL {
            return Err(reject("malformed central directory"));
        }
        let made_by = u16_at(data, at + 4)?;
        let flags = u16_at(data, at + 8)?;
        let method = u16_at(data, at + 10)?;
        let crc = u32_at(data, at + 16)?;
        let compressed = u32_at(data, at + 20)? as u64;
        let size = u32_at(data, at + 24)? as u64;
        let name_len = u16_at(data, at + 28)? as usize;
        let extra_len = u16_at(data, at + 30)? as usize;
        let comment_len = u16_at(data, at + 32)? as usize;
        let external = u32_at(data, at + 38)?;
        let offset = u32_at(data, at + 42)? as u64;
        let raw = data
            .get(at + 46..at + 46 + name_len)
            .ok_or_else(|| reject("truncated archive"))?;
        let name = std::str::from_utf8(raw)
            .map_err(|_| reject("entry name is not UTF-8"))?
            .to_owned();
        if flags & 0x1 != 0 || flags & 0x40 != 0 || flags & 0x2000 != 0 {
            return Err(reject(format!("encrypted entry: {name:?}")));
        }
        if method != STORED && method != DEFLATED {
            return Err(reject(format!(
                "unsupported compression method for {name:?}"
            )));
        }
        if compressed == 0xffff_ffff || size == 0xffff_ffff || offset == 0xffff_ffff {
            return Err(reject("zip64 archives are refused"));
        }
        // Unix hosts record the file type in the high bits; only regular files are accepted.
        let unix = made_by >> 8 == 3;
        let kind = (external >> 16) & 0o170000;
        if unix && kind != 0 && kind != 0o100000 {
            return Err(reject(format!("entry is not a regular file: {name:?}")));
        }
        if external & 0x10 != 0 || name.ends_with('/') {
            return Err(reject(format!("directory entry: {name:?}")));
        }
        check_name(&name)?;
        entries.push(Central {
            name,
            flags,
            method,
            crc,
            compressed,
            size,
            offset,
        });
        at += 46 + name_len + extra_len + comment_len;
    }
    if at != end {
        return Err(reject("malformed central directory"));
    }
    check_collisions(entries.iter().map(|e| e.name.as_str()))?;
    check_sizes(&entries)?;
    // Local entries must tile [0, central directory) exactly, in central-directory order.
    let mut expected = 0u64;
    let mut files = BTreeMap::new();
    for entry in &entries {
        if entry.offset != expected {
            return Err(reject("data outside the central directory"));
        }
        let start = entry.offset as usize;
        if u32_at(data, start)? != LOCAL {
            return Err(reject("malformed local header"));
        }
        let local_flags = u16_at(data, start + 6)?;
        let local_method = u16_at(data, start + 8)?;
        let local_name_len = u16_at(data, start + 26)? as usize;
        let local_extra_len = u16_at(data, start + 28)? as usize;
        let local_name = data
            .get(start + 30..start + 30 + local_name_len)
            .ok_or_else(|| reject("truncated archive"))?;
        if local_flags != entry.flags
            || local_method != entry.method
            || local_name != entry.name.as_bytes()
        {
            return Err(reject("local header disagrees with the central directory"));
        }
        let descriptor = entry.flags & 0x8 != 0;
        if !descriptor
            && (u32_at(data, start + 14)? != entry.crc
                || u32_at(data, start + 18)? as u64 != entry.compressed
                || u32_at(data, start + 22)? as u64 != entry.size)
        {
            return Err(reject("local header disagrees with the central directory"));
        }
        let body = start + 30 + local_name_len + local_extra_len;
        let body_end = body
            .checked_add(entry.compressed as usize)
            .filter(|e| *e <= cd_offset)
            .ok_or_else(|| reject("entry overruns the central directory"))?;
        let mut next = body_end;
        if descriptor {
            next += if u32_at(data, body_end)? == DESCRIPTOR {
                16
            } else {
                12
            };
        }
        expected = next as u64;
        let bytes = inflate(entry, &data[body..body_end])?;
        if crc32fast::hash(&bytes) != entry.crc {
            return Err(reject(format!("checksum mismatch in {:?}", entry.name)));
        }
        files.insert(entry.name.clone(), bytes);
    }
    if expected != cd_offset as u64 {
        return Err(reject("data outside the central directory"));
    }
    Ok(Archive { files })
}

fn check_sizes(entries: &[Central]) -> Result<(), Error> {
    let mut total = 0u64;
    for entry in entries {
        if entry.size > MAX_ENTRY_BYTES {
            return Err(reject(format!(
                "entry over the size ceiling: {:?}",
                entry.name
            )));
        }
        if entry.method == STORED && entry.compressed != entry.size {
            return Err(reject("stored entry sizes disagree"));
        }
        if entry.size > RATIO_FLOOR_BYTES && entry.size > entry.compressed.saturating_mul(MAX_RATIO)
        {
            return Err(reject(format!(
                "entry over the compression-ratio ceiling: {:?}",
                entry.name
            )));
        }
        total += entry.size;
    }
    if total > MAX_TOTAL_BYTES {
        return Err(reject("archive over the total size ceiling"));
    }
    Ok(())
}

/// Rejects names that are equal exactly, after NFC normalisation, or after case folding.
pub fn check_collisions<'a>(names: impl IntoIterator<Item = &'a str>) -> Result<(), Error> {
    let mut seen = HashSet::new();
    for name in names {
        let folded: String = name
            .nfc()
            .collect::<String>()
            .to_lowercase()
            .nfc()
            .collect();
        if !seen.insert(folded) {
            return Err(reject(format!("entry names collide: {name:?}")));
        }
    }
    Ok(())
}

fn inflate(entry: &Central, body: &[u8]) -> Result<Vec<u8>, Error> {
    if entry.method == STORED {
        return Ok(body.to_vec());
    }
    let mut out = Vec::with_capacity(entry.size as usize);
    // Read at most one byte beyond the declared size, so a lying header cannot expand further.
    flate2::read::DeflateDecoder::new(body)
        .take(entry.size + 1)
        .read_to_end(&mut out)
        .map_err(|_| reject(format!("corrupt deflate data in {:?}", entry.name)))?;
    if out.len() as u64 != entry.size {
        return Err(reject(format!(
            "entry size disagrees with its data: {:?}",
            entry.name
        )));
    }
    Ok(out)
}

/// Writes a deterministic archive: entries sorted bytewise by name, stored, at a fixed timestamp,
/// as regular files with mode 0644.
pub fn write(files: &BTreeMap<String, Vec<u8>>) -> Result<Vec<u8>, Error> {
    if files.len() > MAX_ENTRIES {
        return Err(Error::Invalid("too many files for one package".into()));
    }
    check_collisions(files.keys().map(String::as_str))
        .map_err(|e| Error::Invalid(e.to_string()))?;
    let mut out = Vec::new();
    let mut central = Vec::new();
    for (name, bytes) in files {
        check_name(name).map_err(|e| Error::Invalid(e.to_string()))?;
        if bytes.len() as u64 > MAX_ENTRY_BYTES {
            return Err(Error::Invalid(format!(
                "{name} is over the entry size ceiling"
            )));
        }
        let offset =
            u32::try_from(out.len()).map_err(|_| Error::Invalid("package too large".into()))?;
        let crc = crc32fast::hash(bytes);
        let size = bytes.len() as u32;
        let flags: u16 = if name.is_ascii() { 0 } else { 0x0800 };
        out.extend_from_slice(&LOCAL.to_le_bytes());
        for v in [20u16, flags, STORED, DOS_TIME, DOS_DATE] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        for v in [crc, size, size] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        out.extend_from_slice(&(name.len() as u16).to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(name.as_bytes());
        out.extend_from_slice(bytes);

        central.extend_from_slice(&CENTRAL.to_le_bytes());
        for v in [(3u16 << 8) | 20, 20, flags, STORED, DOS_TIME, DOS_DATE] {
            central.extend_from_slice(&v.to_le_bytes());
        }
        for v in [crc, size, size] {
            central.extend_from_slice(&v.to_le_bytes());
        }
        for v in [name.len() as u16, 0, 0, 0, 0] {
            central.extend_from_slice(&v.to_le_bytes());
        }
        central.extend_from_slice(&(0o100644u32 << 16).to_le_bytes());
        central.extend_from_slice(&offset.to_le_bytes());
        central.extend_from_slice(name.as_bytes());
    }
    let cd_offset =
        u32::try_from(out.len()).map_err(|_| Error::Invalid("package too large".into()))?;
    let cd_size = central.len() as u32;
    out.extend_from_slice(&central);
    out.extend_from_slice(&END.to_le_bytes());
    for v in [0u16, 0, files.len() as u16, files.len() as u16] {
        out.extend_from_slice(&v.to_le_bytes());
    }
    out.extend_from_slice(&cd_size.to_le_bytes());
    out.extend_from_slice(&cd_offset.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    Ok(out)
}
