// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.*;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.openssl.*;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.*;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

/** PKCS#10 verification and server-owned X.509 extensions; CSR extensions never grant privileges. */
public final class X509DeviceIssuer implements DeviceIssuer {
    private final RSAPrivateCrtKey key;
    private final X509Certificate ca;
    private final String caPem;
    private final SecureRandom random=new SecureRandom();
    public X509DeviceIssuer(String keyPem,String certificatePem,long now) {
        try (var parser=new PEMParser(new StringReader(keyPem))) {
            var parsed=parser.readObject();
            if (!(parsed instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo info) || parser.readObject()!=null) throw new IllegalArgumentException();
            key=(RSAPrivateCrtKey)new JcaPEMKeyConverter().getPrivateKey(info);
            ca=(X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(certificatePem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            ca.checkValidity(new Date(now));
            var publicKey=(java.security.interfaces.RSAPublicKey)ca.getPublicKey();
            if (key.getModulus().bitLength()<3072 || !key.getModulus().equals(publicKey.getModulus()) || ca.getBasicConstraints()<0
                || ca.getKeyUsage()==null || !ca.getKeyUsage()[5]) throw new IllegalArgumentException();
            var proof=Signature.getInstance("SHA256withRSA");proof.initSign(key);proof.update(new byte[]{1,2,3});var signature=proof.sign();
            proof.initVerify(publicKey);proof.update(new byte[]{1,2,3});if (!proof.verify(signature)) throw new IllegalArgumentException();
            caPem=certificatePem;
        } catch (Exception e) {throw new IllegalArgumentException("Valid dedicated device CA required");}
    }
    private PKCS10CertificationRequest request(String pem) {
        try (var parser=new PEMParser(new StringReader(pem))) {
            if (pem.length()>16384 || !(parser.readObject() instanceof PKCS10CertificationRequest csr) || parser.readObject()!=null) throw new IllegalArgumentException();
            var algorithm=csr.getSubjectPublicKeyInfo().getAlgorithm();
            if (!algorithm.getAlgorithm().equals(org.bouncycastle.asn1.x9.X9ObjectIdentifiers.id_ecPublicKey)
                || !algorithm.getParameters().equals(org.bouncycastle.asn1.x9.X9ObjectIdentifiers.prime256v1)
                || !csr.getSignatureAlgorithm().getAlgorithm().equals(org.bouncycastle.asn1.x9.X9ObjectIdentifiers.ecdsa_with_SHA256)
                || !csr.isSignatureValid(new JcaContentVerifierProviderBuilder().build(csr.getSubjectPublicKeyInfo()))) throw new IllegalArgumentException();
            return csr;
        } catch (Exception e) {throw Failure.validation();}
    }
    public String fingerprint(String csr) {
        try {return DirectoryService.digest(request(csr).getSubjectPublicKeyInfo().getEncoded());}
        catch (java.io.IOException e) {throw Failure.validation();}
    }
    public String issuerCertificate() {return caPem;}
    public DeviceIdentity issue(String csr,String device,String tenant,String user,String server,long now) {
        return issue(csr,device,tenant,user,server,now,Long.MAX_VALUE);
    }
    public DeviceIdentity issue(String csr,String device,String tenant,String user,String server,long now,long connectionExpiresAt) {
        try {
            ca.checkValidity(new Date(now));var request=request(csr);
            long issuerLimit=Math.min(now+86400000,ca.getNotAfter().getTime());if(issuerLimit-now<3600000) throw Failure.unavailable();
            // X.509 validity has second precision. Never round beyond the human-approved deadline.
            long expires=Math.min(issuerLimit,connectionExpiresAt)/1000*1000;if(expires<=now)throw Failure.validation();
            var subject=new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN,device).addRDN(BCStyle.OU,tenant).addRDN(BCStyle.O,server).build();
            var builder=new org.bouncycastle.cert.X509v3CertificateBuilder(X500Name.getInstance(ca.getSubjectX500Principal().getEncoded()),new java.math.BigInteger(159,random).add(java.math.BigInteger.ONE),new Date(now),new Date(expires),subject,request.getSubjectPublicKeyInfo());
            builder.addExtension(Extension.basicConstraints,true,new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage,true,new KeyUsage(KeyUsage.digitalSignature));
            builder.addExtension(Extension.extendedKeyUsage,true,new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
            String identityUri="urn:toolgate:device:"+DirectoryService.digest(server+"\n"+tenant+"\n"+user+"\n"+device);
            builder.addExtension(Extension.subjectAlternativeName,false,new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier,identityUri)));
            var certificate=builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(key));
            var text=new StringWriter();try(var writer=new JcaPEMWriter(text)){writer.writeObject(certificate);}
            return new DeviceIdentity(device,tenant,user,server,text.toString(),caPem,expires);
        } catch(Failure failure){throw failure;} catch(Exception failure){throw Failure.unavailable();}
    }
    public SignedClientDiscovery discovery(String payload) {
        try {
            byte[] bytes=payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var signature=Signature.getInstance("SHA256withRSA");signature.initSign(key);signature.update("toolgate-client-discovery-v1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));signature.update(bytes);
            var encoder=Base64.getUrlEncoder().withoutPadding();return new SignedClientDiscovery(encoder.encodeToString(bytes),encoder.encodeToString(signature.sign()));
        } catch(GeneralSecurityException e){throw Failure.unavailable();}
    }
    public DeviceIssuer.Peer verifyPeer(X509Certificate peer,long now) {
        return verifyPeer(ca,peer,now);
    }
    static DeviceIssuer.Peer verifyPeer(X509Certificate ca,X509Certificate peer,long now) {
        try {
            ca.checkValidity(new Date(now));peer.checkValidity(new Date(now));peer.verify(ca.getPublicKey());
            if (!peer.getIssuerX500Principal().equals(ca.getSubjectX500Principal()) || peer.getBasicConstraints()!=-1
                || !java.util.List.of("1.3.6.1.5.5.7.3.2").equals(peer.getExtendedKeyUsage())
                || peer.getNotAfter().getTime()-peer.getNotBefore().getTime()>86400000
                || peer.getKeyUsage()==null || !peer.getKeyUsage()[0]) throw new IllegalArgumentException();
            var subject=X500Name.getInstance(peer.getSubjectX500Principal().getEncoded());
            if(subject.getRDNs().length!=3||java.util.Arrays.stream(subject.getRDNs()).anyMatch(rdn->rdn.size()!=1)
                ||subject.getRDNs(BCStyle.CN).length!=1||subject.getRDNs(BCStyle.OU).length!=1||subject.getRDNs(BCStyle.O).length!=1)throw new IllegalArgumentException();
            var device=io.ololabs.toolgate.control.domain.Ids.valid(org.bouncycastle.asn1.x500.style.IETFUtils.valueToString(subject.getRDNs(BCStyle.CN)[0].getFirst().getValue()));
            var tenant=new io.ololabs.toolgate.control.domain.Ids.TenantId(org.bouncycastle.asn1.x500.style.IETFUtils.valueToString(subject.getRDNs(BCStyle.OU)[0].getFirst().getValue()));
            var server=io.ololabs.toolgate.control.domain.Ids.valid(org.bouncycastle.asn1.x500.style.IETFUtils.valueToString(subject.getRDNs(BCStyle.O)[0].getFirst().getValue()));
            var names=peer.getSubjectAlternativeNames();if(names==null||names.size()!=1)throw new IllegalArgumentException();
            var name=names.iterator().next();if(name.size()!=2||!Integer.valueOf(6).equals(name.get(0))||!(name.get(1) instanceof String uri)||!uri.matches("urn:toolgate:device:[a-f0-9]{64}"))throw new IllegalArgumentException();
            return new DeviceIssuer.Peer(DirectoryService.digest(peer.getPublicKey().getEncoded()),device,tenant,server,uri);
        }catch(Exception failure){throw new Failure(ErrorCode.UNAUTHORIZED,401,"Device certificate required");}
    }
}
