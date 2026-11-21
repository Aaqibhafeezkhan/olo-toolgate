// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.contracts.v2.ApplicationEvent;
import javax.net.ssl.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** Direct sockets pin the validated address while TLS still verifies the configured hostname. */
public final class HttpsApplicationWebhookTransport implements ApplicationWebhookTransport {
    private static final java.util.concurrent.Semaphore SLOTS=new java.util.concurrent.Semaphore(4);
    private static final java.util.concurrent.ThreadPoolExecutor DNS=new java.util.concurrent.ThreadPoolExecutor(2,2,0,
        java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"toolgate-webhook-dns");t.setDaemon(true);return t;});
    private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES=new java.util.concurrent.ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"toolgate-webhook-deadline");t.setDaemon(true);return t;});
    static {DEADLINES.setRemoveOnCancelPolicy(true);}
    private final Set<String> hosts; private final Codec codec; private final Clock clock;
    private final java.util.function.Function<String,InetAddress[]> resolver;
    private final java.util.function.Supplier<Socket> sockets;
    private final SSLSocketFactory tlsFactory;
    public HttpsApplicationWebhookTransport(Set<String> hosts,Codec codec,Clock clock) {
        this(hosts,codec,clock,host->{try{return InetAddress.getAllByName(host);}catch(UnknownHostException invalid){throw Failure.unavailable();}},Socket::new,(SSLSocketFactory)SSLSocketFactory.getDefault());
    }
    HttpsApplicationWebhookTransport(Set<String> hosts,Codec codec,Clock clock,java.util.function.Function<String,InetAddress[]> resolver,
                                    java.util.function.Supplier<Socket> sockets,SSLSocketFactory tlsFactory) {
        this.hosts=Set.copyOf(hosts);this.codec=codec;this.clock=clock;this.resolver=resolver;this.sockets=sockets;this.tlsFactory=tlsFactory;
    }
    private URI endpoint(String endpoint) {
        if(hosts.isEmpty())throw Failure.unavailable();
        try {
            var uri=URI.create(endpoint);
            String host=uri.getHost();
            if(!"https".equals(uri.getScheme())||host==null||!host.equals(host.toLowerCase(Locale.ROOT))
                ||!host.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?\\.[a-z]{2,63}")
                ||!hosts.contains(host)||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
                ||uri.getPort()!=-1&&uri.getPort()!=443||endpoint.length()>2048
                ||endpoint.chars().anyMatch(c->c<=32||c>=127)||uri.getRawPath().contains("\\"))throw Failure.validation();
            return uri;
        }catch(IllegalArgumentException invalid){throw Failure.validation();}
    }
    public void validate(String endpoint){endpoint(endpoint);}
    static boolean publicAddress(InetAddress address) {
        byte[] b=address.getAddress();
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        if(b.length==4) {
            int a=b[0]&255,c=b[1]&255,d=b[2]&255;
            return a!=0&&a!=10&&a!=127&&a<224&&!(a==100&&c>=64&&c<=127)&&!(a==169&&c==254)
                &&!(a==172&&c>=16&&c<=31)&&!(a==192&&(c==168||c==0||c==2))
                &&!(a==198&&(c==18||c==19||c==51&&d==100))&&!(a==203&&c==0&&d==113);
        }
        return b.length==16&&(b[0]&0xe0)==0x20
            &&!((b[0]&255)==0x20&&(b[1]&255)==0x02)
            &&!((b[0]&255)==0x20&&(b[1]&255)==0x01&&b[2]==0&&b[3]==0)
            &&!((b[0]&255)==0x20&&(b[1]&255)==0x01&&(b[2]&255)==0x0d&&(b[3]&255)==0xb8);
    }
    public static String signature(byte[] secret,String content) {
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.GeneralSecurityException failure){throw Failure.unavailable();}
    }
    public boolean verify(String endpoint,String subscriptionId,String challenge,byte[] secret) {
        var body=codec.json(Map.of("type","VERIFY","subscriptionId",subscriptionId,"challenge",challenge));
        var response=post(endpoint,"verification",body,secret,true);
        if(response==null)return false;
        try {
            var node=(com.fasterxml.jackson.databind.JsonNode)codec.value(response);
            String expected=signature(secret,"verify."+subscriptionId+"."+challenge);
            return node.isObject()&&node.size()==1&&node.path("proof").isTextual()
                &&MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),node.path("proof").asText().getBytes(StandardCharsets.US_ASCII));
        }catch(Failure invalid){return false;}
    }
    public boolean deliver(String endpoint,String subscriptionId,ApplicationEvent event,byte[] secret) {
        return post(endpoint,event.eventId(),codec.json(new io.ololabs.toolgate.contracts.v2.ApplicationWebhookDelivery(subscriptionId,event)),secret,false)!=null;
    }
    private String post(String endpoint,String eventId,String body,byte[] secret,boolean verification) {
        if(!SLOTS.tryAcquire())throw Failure.unavailable();
        try{return request(endpoint,eventId,body,secret,verification);}finally{SLOTS.release();}
    }
    private InetAddress[] addresses(String host) {
        java.util.concurrent.Future<InetAddress[]> future=null;
        try{future=DNS.submit(()->resolver.apply(host));return future.get(2,java.util.concurrent.TimeUnit.SECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw Failure.unavailable();}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException|java.util.concurrent.RejectedExecutionException failure){throw Failure.unavailable();}
        finally{if(future!=null&&!future.isDone())future.cancel(true);DNS.purge();}
    }
    private String request(String endpoint,String eventId,String body,byte[] secret,boolean verification) {
        var uri=endpoint(endpoint);var addresses=addresses(uri.getHost());
        if(addresses.length==0||Arrays.stream(addresses).anyMatch(a->!publicAddress(a)))throw Failure.validation();
        // No second hostname resolution, redirects or inherited HTTP proxy can change this destination.
        try(var raw=sockets.get()) {
            var deadline=DEADLINES.schedule(()->close(raw),10,java.util.concurrent.TimeUnit.SECONDS);
            try {
            raw.connect(new InetSocketAddress(addresses[0],443),3000);raw.setSoTimeout(5000);
            try(var tls=(SSLSocket)tlsFactory.createSocket(raw,uri.getHost(),443,true)) {
                tls.setEnabledProtocols(new String[]{"TLSv1.3","TLSv1.2"});
                tls.setSoTimeout(5000);var parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));tls.setSSLParameters(parameters);tls.startHandshake();
                String timestamp=Long.toString(clock.millis()/1000);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                String path=uri.getRawPath().isEmpty()?"/":uri.getRawPath();
                String headers="POST "+path+" HTTP/1.1\r\nHost: "+uri.getHost()+"\r\nContent-Type: application/json\r\nAccept: application/json\r\nConnection: close\r\nContent-Length: "+bytes.length
                    +"\r\nToolgate-Event-ID: "+eventId+"\r\nToolgate-Event-Timestamp: "+timestamp
                    +"\r\nToolgate-Event-Signature: sha256="+signature(secret,timestamp+"."+body)+"\r\n\r\n";
                var output=tls.getOutputStream();output.write(headers.getBytes(StandardCharsets.US_ASCII));output.write(bytes);output.flush();
                var input=new BufferedInputStream(tls.getInputStream());String status=line(input);
                if(!status.matches("HTTP/1\\.[01] 2[0-9]{2}(?: .*)?"))return null;
                int count=0,length=-1;boolean chunked=false;String type="";
                while(true){String line=line(input);count+=line.length()+2;if(count>16384)throw new IOException("Headers too large");if(line.isEmpty())break;
                    int at=line.indexOf(':');if(at<=0)throw new IOException("Invalid header");String name=line.substring(0,at).toLowerCase(Locale.ROOT),value=line.substring(at+1).strip();
                    if(name.equals("content-length")){if(length!=-1||!value.matches("[0-9]{1,4}"))throw new IOException("Invalid length");length=Integer.parseInt(value);}
                    if(name.equals("transfer-encoding"))chunked=true;if(name.equals("content-type"))type=value;
                }
                if(!verification)return ""; // An ACK has no authority-bearing response body.
                if(chunked||length<0||length>4096||!type.split(";",2)[0].equalsIgnoreCase("application/json"))return null;
                byte[] answer=input.readNBytes(length);if(answer.length!=length)return null;
                return new String(answer,StandardCharsets.UTF_8);
            }
            }finally{deadline.cancel(false);}
        }catch(IOException failure){return null;}
    }
    private static String line(InputStream input)throws IOException {
        var bytes=new ByteArrayOutputStream();int previous=-1;
        for(int i=0;i<4096;i++){int c=input.read();if(c<0)throw new EOFException();if(previous=='\r'&&c=='\n')return bytes.toString(StandardCharsets.US_ASCII).substring(0,bytes.size()-1);if(c<32&&c!='\r'||c>126)throw new IOException("Invalid header");bytes.write(c);previous=c;}
        throw new IOException("Header too large");
    }
    private static void close(Socket socket){try{socket.close();}catch(IOException ignored){}}
}
