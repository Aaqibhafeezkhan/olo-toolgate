// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.Failure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Anonymous assets are allowlisted, bounded and hash-verified; never ambient files. */
final class ClientArtifactsTest {
    @TempDir Path directory;
    private final ContractCodec codec=new ContractCodec();
    private void bundle()throws Exception {
        bundle("0.7.0-dev");
    }
    private void bundle(String version)throws Exception {
        var artifacts=new ArrayList<Map<String,Object>>();
        for(var target:List.of("x86_64-unknown-linux-gnu","x86_64-pc-windows-msvc","x86_64-apple-darwin")){
            var platform=target.contains("windows")?"WINDOWS":target.contains("apple")?"MACOS":"LINUX";
            var filename="olo-toolgate-client-"+version+"-"+target+(platform.equals("WINDOWS")?".zip":".tar.gz");
            var bytes=("integrity-test-only:"+target).getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(directory.resolve(filename),bytes);
            var digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            artifacts.add(Map.of("platform",platform,"target",target,"filename",filename,"sha256",digest,"bytes",bytes.length));
        }
        Files.writeString(directory.resolve("manifest.json"),codec.json(Map.of("version",version,"artifacts",artifacts)));
    }
    @Test void missingReleaseIsUnavailable(){var assets=new ClientArtifacts(Optional.empty(),codec);assertThrows(Failure.class,assets::manifest);}
    @Test void chromePackageIsVersionedAllowlistedAndVerified()throws Exception{
        bundle();var filename="olo-toolgate-chrome-0.7.0-dev-23.zip";
        var bytes="extension-test-only".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(directory.resolve(filename),bytes);
        var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var metadata=new HashMap<String,Object>(Map.of("protocol",1,"version","0.7.0-dev","chromeVersion","0.7.0.23","extensionId","emmemldedebhbloibichmmdlbpjakfkf","storeUrl","","filename",filename,"sha256",hash,"bytes",bytes.length));
        Files.writeString(directory.resolve("extension.json"),codec.json(metadata));
        var assets=new ClientArtifacts(Optional.of(directory.toString()),codec);
        assertEquals(filename,assets.extension().get("filename"));
        assertThrows(Failure.class,()->assets.download(filename));
        metadata.put("storeUrl","https://evil.example");Files.writeString(directory.resolve("extension.json"),codec.json(metadata));
        assertThrows(IllegalStateException.class,()->new ClientArtifacts(Optional.of(directory.toString()),codec));
        metadata.put("storeUrl","");metadata.put("filename","../evil.zip");Files.writeString(directory.resolve("extension.json"),codec.json(metadata));
        assertThrows(IllegalStateException.class,()->new ClientArtifacts(Optional.of(directory.toString()),codec));
        Files.writeString(directory.resolve(filename),"corrupted");assertThrows(Failure.class,()->assets.download(filename));
    }
    private void installerBundle()throws Exception{
        var original=codec.model(Files.readString(directory.resolve("manifest.json")),io.ololabs.toolgate.contracts.ClientDownloadManifest.class);
        var installers=new ArrayList<Map<String,Object>>();
        for(var artifact:original.artifacts()){
            var extension=switch(artifact.platform()){case WINDOWS->"setup.exe";case MACOS->"dmg";case LINUX->"run";};
            var filename="olo-toolgate-client-"+original.version()+"-"+artifact.target()+"."+extension;
            Files.copy(directory.resolve(artifact.filename()),directory.resolve(filename));
            installers.add(Map.of("platform",artifact.platform(),"target",artifact.target(),"filename",filename,"sha256",artifact.sha256(),"bytes",artifact.bytes()));
        }
        Files.writeString(directory.resolve("installers.json"),codec.json(Map.of("version",original.version(),"artifacts",installers)));
    }
    @Test void installersAreAllowlistedAndHashVerified()throws Exception{
        bundle();installerBundle();
        var assets=new ClientArtifacts(Optional.of(directory.toString()),codec);assertEquals(3,assets.installers().artifacts().size());
        var configured=assets.configuredWindowsInstaller("x86_64-pc-windows-msvc","https://control.example.test");
        assertEquals("no-store",configured.getHeaderString("Cache-Control"));
        assertTrue(configured.getHeaderString("Content-Disposition").contains("--"+java.util.HexFormat.of().formatHex("https://control.example.test".getBytes(java.nio.charset.StandardCharsets.US_ASCII))+".setup.exe"));
        var configuredBytes=new java.io.ByteArrayOutputStream();
        ((jakarta.ws.rs.core.StreamingOutput)configured.getEntity()).write(configuredBytes);
        var windows=assets.installers().artifacts().stream().filter(a->a.platform()==io.ololabs.toolgate.contracts.ClientPlatform.WINDOWS).findFirst().orElseThrow();
        assertArrayEquals(Files.readAllBytes(directory.resolve(windows.filename())),configuredBytes.toByteArray());
        assertThrows(Failure.class,()->assets.configuredWindowsInstaller("x86_64-pc-windows-msvc","https://evil.example\"\r\n"));
        // A local Quickstart download names its loopback console, which publishes the gateway CA.
        var local=assets.configuredWindowsInstaller("x86_64-pc-windows-msvc","http://127.0.0.1:18091");
        assertTrue(local.getHeaderString("Content-Disposition").contains("--"+java.util.HexFormat.of().formatHex("http://127.0.0.1:18091".getBytes(java.nio.charset.StandardCharsets.US_ASCII))+".setup.exe"));
        ((jakarta.ws.rs.core.StreamingOutput)local.getEntity()).write(java.io.OutputStream.nullOutputStream());
        for(var remote:java.util.List.of("http://gate.example","http://127.0.0.1:18091/console","http://localhost.evil.example:18091"))
            assertThrows(Failure.class,()->assets.configuredWindowsInstaller("x86_64-pc-windows-msvc",remote));
        var installer=assets.installers().artifacts().getFirst();var response=assets.download(installer.filename());
        ((jakarta.ws.rs.core.StreamingOutput)response.getEntity()).write(java.io.OutputStream.nullOutputStream());
        var suffix=switch(installer.platform()){case WINDOWS->"setup.exe";case MACOS->"dmg";case LINUX->"run";};
        var alias="olo-toolgate-client-"+installer.target()+"."+suffix;
        var stable=assets.download(alias);
        assertEquals("no-store",stable.getHeaderString("Cache-Control"));
        assertEquals("attachment; filename=\""+alias+"\"",stable.getHeaderString("Content-Disposition"));
        var downloaded=new java.io.ByteArrayOutputStream();
        ((jakarta.ws.rs.core.StreamingOutput)stable.getEntity()).write(downloaded);
        assertArrayEquals(Files.readAllBytes(directory.resolve(installer.filename())),downloaded.toByteArray());
        assertThrows(Failure.class,()->assets.download("olo-toolgate-client-unknown.setup.exe"));
        Files.writeString(directory.resolve(installer.filename()),"corrupted");
        assertThrows(Failure.class,()->assets.download(alias));
        assertThrows(Failure.class,()->assets.download(installer.filename()));
        assertThrows(IllegalStateException.class,()->new ClientArtifacts(Optional.of(directory.toString()),codec));
    }
    @Test void onlyPublishedArtifactNamesCanBeDownloaded()throws Exception{
        bundle();var assets=new ClientArtifacts(Optional.of(directory.toString()),codec);assertEquals(3,assets.manifest().artifacts().size());
        assertThrows(Failure.class,()->assets.download("../../secret"));
        var windows=assets.manifest().artifacts().stream().filter(a->a.filename().endsWith(".zip")).findFirst().orElseThrow();
        assertThrows(Failure.class,()->assets.download(windows.filename()));
        var artifact=assets.manifest().artifacts().stream().filter(a->!a.filename().endsWith(".zip")).findFirst().orElseThrow();var response=assets.download(artifact.filename());
        assertEquals("no-store",response.getHeaderString("Cache-Control"));
        var output=new java.io.ByteArrayOutputStream();((jakarta.ws.rs.core.StreamingOutput)response.getEntity()).write(output);assertEquals(artifact.bytes(),output.size());
        Files.writeString(directory.resolve(artifact.filename()),"corrupted");assertThrows(Failure.class,()->assets.download(artifact.filename()));
    }
    @Test void cachePolicyDistinguishesPrereleasesFinalReleasesAndAliases()throws Exception{
        for(var policy:Map.of("0.7.0-dev","no-store","0.7.0-rc.1","no-store",
                             "0.7.0","public, max-age=31536000, immutable").entrySet()){
            bundle(policy.getKey());installerBundle();
            var assets=new ClientArtifacts(Optional.of(directory.toString()),codec);
            for(var artifact:assets.manifest().artifacts()){
                if(artifact.platform()!=io.ololabs.toolgate.contracts.ClientPlatform.WINDOWS)
                    assertCache(assets.download(artifact.filename()),policy.getValue());
            }
            for(var installer:assets.installers().artifacts()){
                assertCache(assets.download(installer.filename()),policy.getValue());
                var suffix=switch(installer.platform()){case WINDOWS->"setup.exe";case MACOS->"dmg";case LINUX->"run";};
                assertCache(assets.download("olo-toolgate-client-"+installer.target()+"."+suffix),"no-store");
            }
            assertCache(assets.configuredWindowsInstaller("x86_64-pc-windows-msvc","https://control.example.test"),"no-store");
        }
    }
    private void assertCache(jakarta.ws.rs.core.Response response,String expected)throws Exception{
        assertEquals(expected,response.getHeaderString("Cache-Control"));
        ((jakarta.ws.rs.core.StreamingOutput)response.getEntity()).write(java.io.OutputStream.nullOutputStream());
    }
    @Test void corruptionAndUnsafeManifestFailStartup()throws Exception{
        bundle();var document=Files.readString(directory.resolve("manifest.json"));Files.writeString(directory.resolve("manifest.json"),document.replace("olo-toolgate-client-0.7.0-dev-x86_64-unknown-linux-gnu.tar.gz","../../secret"));
        assertThrows(IllegalStateException.class,()->new ClientArtifacts(Optional.of(directory.toString()),codec));
    }
    @Test void transfersAreBoundedAndFailedOutputReleasesCapacity()throws Exception{
        bundle();var assets=new ClientArtifacts(Optional.of(directory.toString()),codec);
        var filename=assets.manifest().artifacts().stream().filter(a->!a.filename().endsWith(".zip")).findFirst().orElseThrow().filename();
        var first=assets.download(filename);var second=assets.download(filename);
        assertThrows(Failure.class,()->assets.download(filename));
        var broken=new java.io.OutputStream(){@Override public void write(int ignored)throws java.io.IOException{throw new java.io.IOException("closed test stream");}};
        assertThrows(java.io.IOException.class,()->((jakarta.ws.rs.core.StreamingOutput)first.getEntity()).write(broken));
        var third=assets.download(filename);
        ((jakarta.ws.rs.core.StreamingOutput)second.getEntity()).write(java.io.OutputStream.nullOutputStream());
        ((jakarta.ws.rs.core.StreamingOutput)third.getEntity()).write(java.io.OutputStream.nullOutputStream());
    }
}
