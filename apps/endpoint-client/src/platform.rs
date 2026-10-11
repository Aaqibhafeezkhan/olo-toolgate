// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! OS custody/service abstractions. Privileged service and unprivileged browser/CLI stay separate.
use crate::{Failure, Result};
/// Current OS UTC offset, including daylight saving time; scheduling only.
pub fn utc_offset_minutes() -> Result<i64> {
    #[cfg(unix)]
    {
        let now = unsafe { libc::time(std::ptr::null_mut()) };
        let mut local = std::mem::MaybeUninit::<libc::tm>::uninit();
        if unsafe { libc::localtime_r(&now, local.as_mut_ptr()) }.is_null() {
            return Err(Failure::Unavailable);
        }
        Ok(unsafe { local.assume_init() }.tm_gmtoff / 60)
    }
    #[cfg(windows)]
    {
        use windows_sys::Win32::System::{
            SystemServices::{TIME_ZONE_ID_DAYLIGHT, TIME_ZONE_ID_STANDARD},
            Time::*,
        };
        let mut zone = TIME_ZONE_INFORMATION::default();
        let state = unsafe { GetTimeZoneInformation(&mut zone) };
        if state == TIME_ZONE_ID_INVALID {
            return Err(Failure::Unavailable);
        }
        let adjustment = match state {
            TIME_ZONE_ID_DAYLIGHT => zone.DaylightBias,
            TIME_ZONE_ID_STANDARD => zone.StandardBias,
            _ => 0,
        };
        Ok(-i64::from(zone.Bias + adjustment))
    }
}
pub fn utc_offset() -> String {
    let minutes = utc_offset_minutes().unwrap_or(0).clamp(-840, 840);
    format!(
        "{}{:02}:{:02}",
        if minutes < 0 { '-' } else { '+' },
        minutes.abs() / 60,
        minutes.abs() % 60
    )
}
pub(crate) fn next_local_hour_millis(now: u64, offset_minutes: i64) -> u64 {
    let local = i128::from(now) + i128::from(offset_minutes) * 60000;
    (3600000 - local.rem_euclid(3600000)) as u64
}
/// Informational hostname only; never used as device identity or an authorization input.
pub fn system_name() -> Option<String> {
    #[cfg(windows)]
    let name = std::env::var("COMPUTERNAME").ok()?;
    #[cfg(unix)]
    let name = {
        let mut buffer = [0u8; 256];
        if unsafe { libc::gethostname(buffer.as_mut_ptr().cast(), buffer.len()) } != 0 {
            return None;
        }
        let end = buffer.iter().position(|value| *value == 0)?;
        String::from_utf8(buffer[..end].to_vec()).ok()?
    };
    (!name.is_empty()
        && name.len() <= 255
        && name
            .bytes()
            .all(|value| value.is_ascii_alphanumeric() || b"._-".contains(&value)))
    .then_some(name)
}
pub fn current() -> olo_toolgate_contracts::ClientPlatform {
    #[cfg(target_os = "windows")]
    {
        olo_toolgate_contracts::ClientPlatform::Windows
    }
    #[cfg(target_os = "linux")]
    {
        olo_toolgate_contracts::ClientPlatform::Linux
    }
    #[cfg(target_os = "macos")]
    {
        olo_toolgate_contracts::ClientPlatform::Macos
    }
}
pub fn require_service_identity() -> Result<()> {
    #[cfg(unix)]
    if unsafe { libc::geteuid() } != 0 {
        return Err(Failure::Unauthorized);
    }
    #[cfg(windows)]
    if windows::current_sid()? != "S-1-5-18" {
        return Err(Failure::Unauthorized);
    }
    Ok(())
}
/// Only verified HTTPS enrollment pages can be opened. No shell interpolation.
pub fn open_browser(url: &str) -> Result<()> {
    let parsed = reqwest::Url::parse(url).map_err(|_| Failure::Validation)?;
    if parsed.scheme() != "https" || !parsed.username().is_empty() || parsed.password().is_some() {
        return Err(Failure::Validation);
    }
    #[cfg(target_os = "linux")]
    let status = std::process::Command::new("/usr/bin/xdg-open")
        .arg(url)
        .status();
    #[cfg(target_os = "macos")]
    let status = std::process::Command::new("/usr/bin/open")
        .arg(url)
        .status();
    #[cfg(windows)]
    {
        windows::open_browser(url)
    }
    #[cfg(unix)]
    {
        if status.map_err(|_| Failure::Unavailable)?.success() {
            Ok(())
        } else {
            Err(Failure::Unavailable)
        }
    }
}

#[cfg(windows)]
pub mod windows {
    use crate::{Failure, Result};
    use std::{ffi::c_void, path::Path, ptr};
    use windows_sys::Win32::{
        Foundation::*,
        Security::{Authorization::*, Cryptography::*, *},
        System::{Pipes::*, Services::*, Threading::*},
    };
    fn wide(value: &str) -> Vec<u16> {
        value.encode_utf16().chain(Some(0)).collect()
    }
    struct Allocation(*mut c_void);
    impl Drop for Allocation {
        fn drop(&mut self) {
            unsafe {
                LocalFree(self.0);
            }
        }
    }
    struct Token(HANDLE);
    impl Drop for Token {
        fn drop(&mut self) {
            unsafe {
                CloseHandle(self.0);
            }
        }
    }
    fn sid_text(sid: PSID) -> Result<String> {
        unsafe {
            let mut text = ptr::null_mut();
            if ConvertSidToStringSidW(sid, &mut text) == 0 {
                return Err(Failure::Unauthorized);
            }
            let allocation = Allocation(text.cast());
            let mut len = 0;
            while *text.add(len) != 0 {
                len += 1;
            }
            let result = String::from_utf16(std::slice::from_raw_parts(text, len))
                .map_err(|_| Failure::Unauthorized);
            drop(allocation);
            result
        }
    }
    fn token_sid(handle: HANDLE) -> Result<String> {
        unsafe {
            let token = Token(handle);
            let mut size = 0;
            GetTokenInformation(token.0, TokenUser, ptr::null_mut(), 0, &mut size);
            if size == 0 || size > 65536 {
                return Err(Failure::Unauthorized);
            }
            let mut storage = vec![0_u64; (size as usize).div_ceil(8)];
            if GetTokenInformation(
                token.0,
                TokenUser,
                storage.as_mut_ptr().cast(),
                size,
                &mut size,
            ) == 0
            {
                return Err(Failure::Unauthorized);
            }
            sid_text((*(storage.as_ptr().cast::<TOKEN_USER>())).User.Sid)
        }
    }
    pub fn current_sid() -> Result<String> {
        unsafe {
            let mut handle = ptr::null_mut();
            if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut handle) == 0 {
                return Err(Failure::Unauthorized);
            }
            token_sid(handle)
        }
    }
    /// Authenticate the fixed LocalSystem service behind a pipe through the SCM.
    /// Normal accounts cannot inspect the token or process of a SYSTEM service.
    /// # Safety
    /// `pipe` must be a live named pipe handle for the duration of this call.
    pub unsafe fn verify_service_pipe(pipe: HANDLE) -> Result<()> {
        unsafe {
            let mut pid = 0;
            if GetNamedPipeServerProcessId(pipe, &mut pid) == 0 || pid == 0 {
                return Err(Failure::Unauthorized);
            }
            struct ServiceHandle(SC_HANDLE);
            impl Drop for ServiceHandle {
                fn drop(&mut self) {
                    unsafe { CloseServiceHandle(self.0) };
                }
            }
            let manager =
                ServiceHandle(OpenSCManagerW(ptr::null(), ptr::null(), SC_MANAGER_CONNECT));
            if manager.0.is_null() {
                return Err(Failure::Unauthorized);
            }
            let service = ServiceHandle(OpenServiceW(
                manager.0,
                wide("OloToolGateClient").as_ptr(),
                SERVICE_QUERY_CONFIG | SERVICE_QUERY_STATUS,
            ));
            if service.0.is_null() {
                return Err(Failure::Unauthorized);
            }
            let mut status: SERVICE_STATUS_PROCESS = std::mem::zeroed();
            let mut needed = 0;
            if QueryServiceStatusEx(
                service.0,
                SC_STATUS_PROCESS_INFO,
                (&mut status as *mut SERVICE_STATUS_PROCESS).cast(),
                std::mem::size_of::<SERVICE_STATUS_PROCESS>() as u32,
                &mut needed,
            ) == 0
                || status.dwCurrentState != SERVICE_RUNNING
                || status.dwProcessId != pid
                || status.dwServiceType != SERVICE_WIN32_OWN_PROCESS
            {
                return Err(Failure::Unauthorized);
            }
            QueryServiceConfigW(service.0, ptr::null_mut(), 0, &mut needed);
            if needed == 0 || needed > 8192 {
                return Err(Failure::Unauthorized);
            }
            let mut storage = vec![0_u64; (needed as usize).div_ceil(8)];
            if QueryServiceConfigW(service.0, storage.as_mut_ptr().cast(), needed, &mut needed) == 0
            {
                return Err(Failure::Unauthorized);
            }
            let configuration = &*storage.as_ptr().cast::<QUERY_SERVICE_CONFIGW>();
            let account = service_config_string(&storage, configuration.lpServiceStartName)?;
            let command = service_config_string(&storage, configuration.lpBinaryPathName)?;
            let expected = format!("\"{}\" service", crate::install::binary_path().display());
            if !account.eq_ignore_ascii_case("LocalSystem")
                || !command.eq_ignore_ascii_case(&expected)
            {
                return Err(Failure::Unauthorized);
            }
            Ok(())
        }
    }
    fn service_config_string(storage: &[u64], value: *const u16) -> Result<String> {
        let start = value as usize;
        let base = storage.as_ptr() as usize;
        let end = base + std::mem::size_of_val(storage);
        if start < base || start >= end || !start.is_multiple_of(2) {
            return Err(Failure::Unauthorized);
        }
        // SAFETY: the pointer and bounded UTF-16 slice are inside the live SCM buffer.
        let text = unsafe { std::slice::from_raw_parts(value, (end - start) / 2) };
        let length = text
            .iter()
            .position(|unit| *unit == 0)
            .ok_or(Failure::Unauthorized)?;
        String::from_utf16(&text[..length]).map_err(|_| Failure::Unauthorized)
    }
    pub fn require_administrator() -> Result<()> {
        unsafe {
            let mut handle = ptr::null_mut();
            if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut handle) == 0 {
                return Err(Failure::Unauthorized);
            }
            let token = Token(handle);
            let mut elevation = TOKEN_ELEVATION { TokenIsElevated: 0 };
            let mut size = 0;
            if GetTokenInformation(
                token.0,
                TokenElevation,
                (&mut elevation as *mut TOKEN_ELEVATION).cast(),
                std::mem::size_of::<TOKEN_ELEVATION>() as u32,
                &mut size,
            ) == 0
                || elevation.TokenIsElevated == 0
            {
                return Err(Failure::Unauthorized);
            }
            Ok(())
        }
    }
    /// Named pipe peer is identified by the impersonation token, never caller-supplied bytes.
    /// # Safety
    /// `pipe` must be a live connected server pipe handle for the duration of this call.
    pub unsafe fn peer_sid(pipe: HANDLE) -> Result<String> {
        unsafe {
            if ImpersonateNamedPipeClient(pipe) == 0 {
                return Err(Failure::Unauthorized);
            }
            struct Revert;
            impl Drop for Revert {
                fn drop(&mut self) {
                    unsafe {
                        RevertToSelf();
                    }
                }
            }
            let revert = Revert;
            let mut handle = ptr::null_mut();
            let result = if OpenThreadToken(GetCurrentThread(), TOKEN_QUERY, 1, &mut handle) == 0 {
                Err(Failure::Unauthorized)
            } else {
                token_sid(handle)
            };
            drop(revert);
            result
        }
    }
    pub struct Descriptor {
        allocation: *mut c_void,
    }
    impl Descriptor {
        pub fn new(peers: &[String]) -> Result<Self> {
            let mut sddl = "D:P(A;;GA;;;SY)(A;;GA;;;BA)".to_string();
            for peer in peers {
                if !peer.starts_with("S-1-")
                    || !peer
                        .bytes()
                        .all(|b| b.is_ascii_digit() || b == b'S' || b == b'-')
                {
                    return Err(Failure::Validation);
                }
                sddl.push_str(&format!("(A;;GRGW;;;{peer})"));
            }
            unsafe {
                let mut allocation = ptr::null_mut();
                if ConvertStringSecurityDescriptorToSecurityDescriptorW(
                    wide(&sddl).as_ptr(),
                    SDDL_REVISION_1,
                    &mut allocation,
                    ptr::null_mut(),
                ) == 0
                {
                    return Err(Failure::Unauthorized);
                }
                Ok(Self { allocation })
            }
        }
        pub fn attributes(&self) -> SECURITY_ATTRIBUTES {
            SECURITY_ATTRIBUTES {
                nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
                lpSecurityDescriptor: self.allocation,
                bInheritHandle: 0,
            }
        }
    }
    impl Drop for Descriptor {
        fn drop(&mut self) {
            unsafe {
                LocalFree(self.allocation);
            }
        }
    }
    pub fn protect_acl(path: &Path) -> Result<()> {
        // Files created under a protected directory inherit custody, never a user's default DACL.
        let sid = current_sid()?;
        let sddl = format!("D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FA;;;{sid})");
        let mut raw = ptr::null_mut();
        if unsafe {
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                wide(&sddl).as_ptr(),
                SDDL_REVISION_1,
                &mut raw,
                ptr::null_mut(),
            )
        } == 0
        {
            return Err(Failure::Unauthorized);
        }
        let descriptor = Descriptor { allocation: raw };
        unsafe {
            let mut present = 0;
            let mut defaulted = 0;
            let mut dacl = ptr::null_mut();
            if GetSecurityDescriptorDacl(
                descriptor.allocation,
                &mut present,
                &mut dacl,
                &mut defaulted,
            ) == 0
                || present == 0
                || dacl.is_null()
            {
                return Err(Failure::Unauthorized);
            }
            let status = SetNamedSecurityInfoW(
                wide(&path.to_string_lossy()).as_ptr(),
                SE_FILE_OBJECT,
                DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                ptr::null_mut(),
                ptr::null_mut(),
                dacl,
                ptr::null_mut(),
            );
            if status != 0 {
                return Err(Failure::Unauthorized);
            }
            Ok(())
        }
    }
    /// Installed privileged files are administrator-owned; a filtered installer token cannot rewrite them.
    pub fn protect_install_acl(path: &Path, public: bool) -> Result<()> {
        unsafe {
            let sddl = if public {
                "O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;GRGX;;;BU)"
            } else {
                "O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)"
            };
            let mut raw = ptr::null_mut();
            if ConvertStringSecurityDescriptorToSecurityDescriptorW(
                wide(sddl).as_ptr(),
                SDDL_REVISION_1,
                &mut raw,
                ptr::null_mut(),
            ) == 0
            {
                return Err(Failure::Unauthorized);
            }
            let allocation = Allocation(raw);
            let mut owner = ptr::null_mut();
            let mut dacl = ptr::null_mut();
            let mut present = 0;
            let mut defaulted = 0;
            if GetSecurityDescriptorOwner(raw, &mut owner, &mut defaulted) == 0
                || GetSecurityDescriptorDacl(raw, &mut present, &mut dacl, &mut defaulted) == 0
                || present == 0
                || dacl.is_null()
            {
                return Err(Failure::Unauthorized);
            }
            let result = SetNamedSecurityInfoW(
                wide(&path.to_string_lossy()).as_ptr(),
                SE_FILE_OBJECT,
                OWNER_SECURITY_INFORMATION
                    | DACL_SECURITY_INFORMATION
                    | PROTECTED_DACL_SECURITY_INFORMATION,
                owner,
                ptr::null_mut(),
                dacl,
                ptr::null_mut(),
            );
            drop(allocation);
            if result != 0 {
                return Err(Failure::Unauthorized);
            }
            Ok(())
        }
    }
    pub fn check_acl(path: &Path, private: bool) -> Result<()> {
        check_acl_for(path, private, true)
    }
    /// Elevated installation must not trust directories writable by the installer's
    /// ordinary user token. Only OS/admin custody may contain a LocalSystem executable.
    pub fn check_install_acl(path: &Path, private: bool) -> Result<()> {
        check_acl_for(path, private, false)
    }
    fn check_acl_for(path: &Path, private: bool, allow_caller: bool) -> Result<()> {
        unsafe {
            let mut owner = ptr::null_mut();
            let mut dacl = ptr::null_mut();
            let mut descriptor = ptr::null_mut();
            if GetNamedSecurityInfoW(
                wide(&path.to_string_lossy()).as_ptr(),
                SE_FILE_OBJECT,
                OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
                &mut owner,
                ptr::null_mut(),
                &mut dacl,
                ptr::null_mut(),
                &mut descriptor,
            ) != 0
            {
                return Err(Failure::Unauthorized);
            }
            let allocation = Allocation(descriptor);
            let mut allowed = vec![
                "S-1-5-18".to_string(),
                "S-1-5-32-544".to_string(),
                // Windows Resource Protection owns the system volume on supported Windows.
                "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464".to_string(),
            ];
            if allow_caller {
                allowed.push(current_sid()?);
            }
            if !allowed.contains(&sid_text(owner)?) || dacl.is_null() {
                return Err(Failure::Unauthorized);
            }
            for index in 0..(*dacl).AceCount as u32 {
                let mut raw = ptr::null_mut();
                if GetAce(dacl, index, &mut raw) == 0 {
                    return Err(Failure::Unauthorized);
                }
                let header = &*raw.cast::<ACE_HEADER>();
                // INHERIT_ONLY_ACE grants no access to this ancestor itself.
                if header.AceFlags & 0x08 != 0 {
                    continue;
                }
                // Win32 ACE_HEADER values: ACCESS_ALLOWED_ACE_TYPE=0, ACCESS_DENIED_ACE_TYPE=1.
                if header.AceType == 0 {
                    let ace = &*raw.cast::<ACCESS_ALLOWED_ACE>();
                    let sid = ptr::addr_of!(ace.SidStart).cast_mut().cast();
                    let directory = path.is_dir();
                    let protected = 0x10000000
                        | 0x40000000
                        | 0x000d0000
                        | if directory && !private {
                            0x00000040
                        } else {
                            0x00000006
                        }
                        | if private { 0x80000000 | 0x00000001 } else { 0 };
                    if ace.Mask & protected != 0 && !allowed.contains(&sid_text(sid)?) {
                        return Err(Failure::Unauthorized);
                    }
                } else if header.AceType != 1 {
                    return Err(Failure::Unauthorized);
                }
            }
            drop(allocation);
            Ok(())
        }
    }
    fn crypt(bytes: &[u8], protect: bool) -> Result<Vec<u8>> {
        unsafe {
            let input = CRYPT_INTEGER_BLOB {
                cbData: bytes.len() as u32,
                pbData: bytes.as_ptr().cast_mut(),
            };
            let mut output = CRYPT_INTEGER_BLOB {
                cbData: 0,
                pbData: ptr::null_mut(),
            };
            let result = if protect {
                CryptProtectData(
                    &input,
                    ptr::null(),
                    ptr::null(),
                    ptr::null(),
                    ptr::null(),
                    CRYPTPROTECT_UI_FORBIDDEN,
                    &mut output,
                )
            } else {
                CryptUnprotectData(
                    &input,
                    ptr::null_mut(),
                    ptr::null(),
                    ptr::null(),
                    ptr::null(),
                    CRYPTPROTECT_UI_FORBIDDEN,
                    &mut output,
                )
            };
            if result == 0 {
                return Err(Failure::Unauthorized);
            }
            let value = std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
            ptr::write_bytes(output.pbData, 0, output.cbData as usize);
            LocalFree(output.pbData.cast());
            Ok(value)
        }
    }
    pub fn protect(bytes: &[u8]) -> Result<Vec<u8>> {
        crypt(bytes, true)
    }
    pub fn unprotect(bytes: &[u8]) -> Result<Vec<u8>> {
        crypt(bytes, false)
    }
    pub fn open_browser(url: &str) -> Result<()> {
        // ShellExecute receives an HTTPS URL as data; never run cmd.exe or concatenate commands.
        unsafe {
            let result = windows_sys::Win32::UI::Shell::ShellExecuteW(
                ptr::null_mut(),
                wide("open").as_ptr(),
                wide(url).as_ptr(),
                ptr::null(),
                ptr::null(),
                1,
            );
            if result as usize <= 32 {
                Err(Failure::Unavailable)
            } else {
                Ok(())
            }
        }
    }
}

#[cfg(test)]
mod schedule_tests {
    #[test]
    fn revoked_schedule_uses_local_hour_boundaries() {
        let now = 1700000000000;
        for (offset, delay) in [(0, 2800000), (330, 1000000), (345, 100000), (-210, 1000000)] {
            assert_eq!(super::next_local_hour_millis(now, offset), delay);
            assert_eq!(super::next_local_hour_millis(now + delay, offset), 3600000);
            assert_eq!(super::next_local_hour_millis(now + delay - 1, offset), 1);
        }
    }
}
