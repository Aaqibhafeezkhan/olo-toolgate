// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! One connection to one approved target. No path, DNS name or grant selector is accepted on the socket.
use crate::egress::AddressPolicy;
use std::io;
use std::os::unix::fs::{DirBuilderExt, PermissionsExt};
use std::path::{Path, PathBuf};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UnixListener, UnixStream};
use tokio::time::Instant;

pub(crate) struct SendWindow {
    directory: PathBuf,
    socket: PathBuf,
    task: tokio::task::JoinHandle<io::Result<()>>,
}
impl SendWindow {
    pub(crate) fn open(
        policy: AddressPolicy,
        send_until: Instant,
        response_until: Instant,
    ) -> io::Result<Self> {
        if send_until <= Instant::now() || response_until <= Instant::now() {
            return Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "effect grant expired",
            ));
        }
        let directory =
            std::env::temp_dir().join(format!("toolgate-effect-{}", crate::random_hex(16)));
        std::fs::DirBuilder::new().mode(0o700).create(&directory)?;
        let socket = directory.join("send.sock");
        let listener = match UnixListener::bind(&socket) {
            Ok(listener) => listener,
            Err(error) => {
                let _ = std::fs::remove_dir(&directory);
                return Err(error);
            }
        };
        if let Err(error) =
            std::fs::set_permissions(&socket, std::fs::Permissions::from_mode(0o600))
        {
            let _ = std::fs::remove_file(&socket);
            let _ = std::fs::remove_dir(&directory);
            return Err(error);
        }
        let task = tokio::spawn(async move {
            tokio::time::timeout_at(response_until, async move {
                let (local, _) = tokio::time::timeout_at(send_until, listener.accept()).await??;
                // Drop the listener before DNS. A second connection never gains a transport.
                drop(listener);
                let addresses = tokio::time::timeout_at(
                    send_until,
                    tokio::net::lookup_host((policy.host.trim_matches(['[', ']']), policy.port)),
                )
                .await??
                .collect();
                let approved = policy.checked_addresses(addresses).map_err(|_| {
                    io::Error::new(
                        io::ErrorKind::PermissionDenied,
                        "effect destination refused",
                    )
                })?;
                let peer =
                    tokio::time::timeout_at(send_until, TcpStream::connect(approved[0])).await??;
                policy.check(peer.peer_addr()?.ip()).map_err(|_| {
                    io::Error::new(io::ErrorKind::PermissionDenied, "effect peer refused")
                })?;
                relay(local, peer, send_until).await
            })
            .await?
        });
        Ok(Self {
            directory,
            socket,
            task,
        })
    }
    pub(crate) fn socket(&self) -> &Path {
        &self.socket
    }
}
impl Drop for SendWindow {
    fn drop(&mut self) {
        self.task.abort();
        let _ = std::fs::remove_file(&self.socket);
        let _ = std::fs::remove_dir(&self.directory);
    }
}
async fn relay(local: UnixStream, peer: TcpStream, send_until: Instant) -> io::Result<()> {
    let (mut local_read, mut local_write) = local.into_split();
    let (mut target_read, mut target_write) = peer.into_split();
    let upload = async move {
        let mut buffer = [0u8; 16384];
        loop {
            let count =
                match tokio::time::timeout_at(send_until, local_read.read(&mut buffer)).await {
                    Ok(result) => result?,
                    Err(_) => {
                        target_write.shutdown().await?;
                        return Ok::<(), io::Error>(());
                    }
                };
            if count == 0 {
                target_write.shutdown().await?;
                return Ok(());
            }
            let mut offset = 0;
            while offset < count {
                if Instant::now() >= send_until {
                    target_write.shutdown().await?;
                    return Ok(());
                }
                let written = match tokio::time::timeout_at(
                    send_until,
                    target_write.write(&buffer[offset..count]),
                )
                .await
                {
                    Ok(result) => result?,
                    Err(_) => {
                        target_write.shutdown().await?;
                        return Ok(());
                    }
                };
                if written == 0 {
                    return Err(io::Error::new(
                        io::ErrorKind::WriteZero,
                        "effect target closed",
                    ));
                }
                offset += written;
            }
        }
    };
    let download = async move {
        tokio::io::copy(&mut target_read, &mut local_write).await?;
        local_write.shutdown().await
    };
    tokio::try_join!(upload, download)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;
    use tokio::net::TcpListener;
    fn policy(port: u16) -> AddressPolicy {
        AddressPolicy {
            host: "127.0.0.1".into(),
            port,
            private_ranges: vec![],
            allow_loopback: true,
        }
    }
    #[tokio::test]
    async fn expiration_closes_outbound_writes_but_delivers_a_timely_requests_late_response() {
        let target = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let guard = SendWindow::open(
            policy(target.local_addr().unwrap().port()),
            Instant::now() + Duration::from_millis(250),
            Instant::now() + Duration::from_secs(3),
        )
        .unwrap();
        let socket = guard.socket().to_owned();
        let mut local = UnixStream::connect(&socket).await.unwrap();
        let witness = tokio::spawn(async move {
            let (mut peer, _) = target.accept().await.unwrap();
            let mut bytes = Vec::new();
            peer.read_to_end(&mut bytes).await.unwrap();
            assert_eq!(bytes, b"approved");
            peer.write_all(b"late response").await.unwrap();
        });
        local.write_all(b"approved").await.unwrap();
        tokio::time::sleep(Duration::from_millis(400)).await;
        // The write may enter the local socket buffer; it must never reach the TCP peer.
        let _ = local.write_all(b"expired").await;
        let mut reply = Vec::new();
        tokio::time::timeout(Duration::from_secs(2), local.read_to_end(&mut reply))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(reply, b"late response");
        witness.await.unwrap();
        drop(guard);
        assert!(!socket.exists());
    }
    #[tokio::test]
    async fn expired_window_and_unapproved_address_never_contact_the_target() {
        let target = TcpListener::bind("127.0.0.1:0").await.unwrap();
        assert!(SendWindow::open(
            policy(target.local_addr().unwrap().port()),
            Instant::now(),
            Instant::now() + Duration::from_secs(1)
        )
        .is_err());
        let mut forbidden = policy(target.local_addr().unwrap().port());
        forbidden.allow_loopback = false;
        let guard = SendWindow::open(
            forbidden,
            Instant::now() + Duration::from_secs(1),
            Instant::now() + Duration::from_secs(2),
        )
        .unwrap();
        let mut local = UnixStream::connect(guard.socket()).await.unwrap();
        local.write_all(b"never").await.unwrap();
        assert!(
            tokio::time::timeout(Duration::from_millis(200), target.accept())
                .await
                .is_err()
        );
    }
    #[tokio::test]
    async fn drop_cancels_the_only_transport_and_removes_private_socket() {
        let target = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let guard = SendWindow::open(
            policy(target.local_addr().unwrap().port()),
            Instant::now() + Duration::from_secs(1),
            Instant::now() + Duration::from_secs(2),
        )
        .unwrap();
        let socket = guard.socket().to_owned();
        let mut local = UnixStream::connect(&socket).await.unwrap();
        local.write_all(b"once").await.unwrap();
        let (mut peer, _) = target.accept().await.unwrap();
        let mut bytes = [0u8; 4];
        peer.read_exact(&mut bytes).await.unwrap();
        assert_eq!(&bytes, b"once");
        drop(guard);
        assert!(!socket.exists());
        let mut extra = Vec::new();
        tokio::time::timeout(Duration::from_secs(1), peer.read_to_end(&mut extra))
            .await
            .unwrap()
            .unwrap();
        assert!(extra.is_empty());
    }
}
