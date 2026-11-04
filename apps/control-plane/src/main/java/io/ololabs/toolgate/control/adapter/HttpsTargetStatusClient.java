// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.application.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** One pinned HTTPS GET. No inherited proxy, redirect, pooled connection or automatic retry. */
public final class HttpsTargetStatusClient implements TargetStatusClient {
    @FunctionalInterface interface Resolver { InetAddress[] resolve(String host)throws IOException; }
    @FunctionalInterface interface Dialer { Socket connect(InetAddress address,int port)throws IOException; }
    private static final ThreadFactory DAEMON=task->{var thread=new Thread(task,"target-status-boundary");thread.setDaemon(true);return thread;};
    private static final ThreadPoolExecutor DNS=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),DAEMON,new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(DAEMON);
    private static final List<NetworkRange> DENIED=List.of("0.0.0.0/8","127.0.0.0/8","169.254.0.0/16",
        "224.0.0.0/4","240.0.0.0/4","100.100.100.200/32","::/128","::1/128","fe80::/10","ff00::/8","fd00:ec2::254/128")
        .stream().map(NetworkRange::parse).toList();
    private static final List<NetworkRange> PUBLIC_EXCEPTIONS=List.of("10.0.0.0/8","172.16.0.0/12","192.168.0.0/16",
        "100.64.0.0/10","198.18.0.0/15","192.0.0.0/24","192.0.2.0/24","198.51.100.0/24","203.0.113.0/24","fc00::/7")
        .stream().map(NetworkRange::parse).toList();
    private final Codec codec;
    private final Resolver resolver;
    private final Dialer dialer;
    private final Semaphore slots=new Semaphore(2);
    public HttpsTargetStatusClient(Codec codec){
        this(codec,InetAddress::getAllByName,(address,port)->{
            var socket=new Socket(Proxy.NO_PROXY);
            try{socket.connect(new InetSocketAddress(address,port),1000);return socket;}
            catch(IOException failure){socket.close();throw failure;}
        });
    }
    /** Test seams preserve address validation and actual hostname/CA/pin verification. */
    HttpsTargetStatusClient(Codec codec,Resolver resolver,Dialer dialer){
        this.codec=Objects.requireNonNull(codec);this.resolver=Objects.requireNonNull(resolver);this.dialer=Objects.requireNonNull(dialer);
    }
    static RouteDeclaration route(Request request){
        if(request.targetKey()==null||!request.targetKey().matches("tg-[a-f0-9]{64}"))throw Failure.validation();
        return TargetLookupRoutes.status(request.profile(),request.routeId());
    }
    private static ServiceDestination destination(Request request,RouteDeclaration route){
        var matches=request.profile().destinations().stream().filter(d->d.id().equals(route.destination())).toList();
        if(matches.size()!=1)throw Failure.validation();
        var d=matches.getFirst();
        if(d.scheme()!=DestinationScheme.https||d.mode()!=EgressMode.BROKERED||d.port()<1||d.port()>65535
            ||!d.host().matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")||d.host().contains(".."))
            throw Failure.validation();
        return d;
    }
    static boolean allowed(InetAddress address,ServiceDestination destination,ServiceProfile profile){
        String numeric=address.getHostAddress();
        if(DENIED.stream().anyMatch(r->r.contains(numeric)))return false;
        boolean special=address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()
            ||address.isMulticastAddress()||address.isSiteLocalAddress()
            ||PUBLIC_EXCEPTIONS.stream().anyMatch(r->r.contains(numeric))
            ||address.getAddress().length==16&&!NetworkRange.parse("2000::/3").contains(numeric);
        return !special||profile.privateRanges().stream().anyMatch(r->r.host().equals(destination.host())
            &&r.ports().contains(destination.port())&&NetworkRange.parse(r.cidr()).contains(numeric));
    }
    private InetAddress resolve(ServiceDestination destination,ServiceProfile profile)throws IOException,InterruptedException{
        Future<InetAddress[]> future;
        try{future=DNS.submit(()->resolver.resolve(destination.host()));}catch(RejectedExecutionException busy){throw new IOException("DNS capacity");}
        InetAddress[] addresses;
        try{addresses=future.get(700,TimeUnit.MILLISECONDS);}
        catch(ExecutionException|TimeoutException failure){future.cancel(true);throw new IOException("DNS unavailable");}
        catch(InterruptedException interrupted){future.cancel(true);throw interrupted;}
        if(addresses==null||addresses.length<1||addresses.length>16)throw new IOException("DNS bounds");
        for(var address:addresses)if(address==null||!allowed(address,destination,profile))throw new IOException("DNS address refused");
        return addresses[0]; // Connect once to the already checked address; never re-resolve/fail over.
    }
    private static SSLContext tls(TlsSettings settings)throws GeneralSecurityException,IOException{
        if(settings.caBundle()==null)return SSLContext.getDefault();
        byte[] pem=settings.caBundle().getBytes(StandardCharsets.US_ASCII);
        if(pem.length<1||pem.length>65536)throw new IOException("CA bounds");
        var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem));
        if(certificates.isEmpty()||certificates.size()>16)throw new IOException("CA bounds");
        var keys=KeyStore.getInstance(KeyStore.getDefaultType());keys.load(null,null);int n=0;
        for(var certificate:certificates)keys.setCertificateEntry("target-"+n++,certificate);
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(keys);
        var context=SSLContext.getInstance("TLS");context.init(null,trust.getTrustManagers(),null);return context;
    }
    private static void pins(SSLSocket socket,TlsSettings settings)throws GeneralSecurityException,IOException{
        if(settings.spkiPins().isEmpty())return;
        if(settings.spkiPins().size()>16)throw new IOException("Pin bounds");
        // Profile pins follow the broker's existing verified-leaf certificate DER semantics.
        String actual="sha256/"+Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(socket.getSession().getPeerCertificates()[0].getEncoded()));
        if(!settings.spkiPins().contains(actual))throw new IOException("Target pin mismatch");
    }
    @Override public TargetEffectStatusReply lookup(Request request){
        var route=route(request);var destination=destination(request,route);
        if(request.credentials().size()>2)throw Failure.validation();
        for(var header:request.credentials().entrySet())
            if(!header.getKey().matches("[a-z][a-z0-9-]{0,63}")
                ||Set.of("host","accept","connection","content-length","transfer-encoding","proxy-authorization","cookie").contains(header.getKey())
                ||header.getValue()==null||!header.getValue().matches("[\\x20-\\x7e]{1,8192}"))throw Failure.validation();
        if(!slots.tryAcquire())throw Failure.unavailable();
        try{
            var address=resolve(destination,request.profile());
            try(var raw=dialer.connect(address,destination.port().intValue())){
                var timeout=DEADLINES.schedule(()->expire(raw),4,TimeUnit.SECONDS);
                try(var socket=(SSLSocket)tls(destination.tls()).getSocketFactory().createSocket(raw,destination.host(),destination.port().intValue(),true)){
                    socket.setSoTimeout(3000);
                    var parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    parameters.setApplicationProtocols(new String[]{"http/1.1"});
                    parameters.setProtocols(new String[]{"TLSv1.3","TLSv1.2"});socket.setSSLParameters(parameters);
                    socket.startHandshake();pins(socket,destination.tls());
                    String negotiated=socket.getApplicationProtocol();
                    if(!negotiated.isEmpty()&&!negotiated.equals("http/1.1"))throw new IOException("Protocol refused");
                    String path=route.path().replace(TargetLookupRoutes.KEY_SLOT,request.targetKey());
                    var head=new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\nHost: ").append(destination.host());
                    if(destination.port()!=443)head.append(':').append(destination.port());
                    head.append("\r\nAccept: application/json\r\nConnection: close\r\n");
                    request.credentials().forEach((name,value)->head.append(name).append(": ").append(value).append("\r\n"));
                    head.append("\r\n");socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
                    byte[] body=response(socket.getInputStream(),route.maxResponseBytes().intValue());
                    String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(body)).toString();
                    TargetEffectStatusReply reply;
                    try{reply=codec.model(json,TargetEffectStatusReply.class);}catch(Failure malformed){throw Failure.unavailable();}
                    boolean terminal=reply.state()==TargetEffectStatus.COMPLETED||reply.state()==TargetEffectStatus.FAILED;
                    if(!reply.key().equals(request.targetKey())||terminal&&(reply.bodyDigest()==null||reply.resultDigest()==null
                        ||reply.businessReceipt()==null||reply.businessReceipt().isBlank())
                        ||!terminal&&(reply.businessReceipt()!=null||reply.resultDigest()!=null))throw Failure.unavailable();
                    return reply;
                }finally{timeout.cancel(false);}
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw Failure.unavailable();}
        catch(IOException|GeneralSecurityException|IllegalArgumentException invalid){throw Failure.unavailable();}
        finally{slots.release();}
    }
    private static void expire(Socket socket){try{socket.close();}catch(IOException ignored){}}
    private static byte[] response(InputStream input,int limit)throws IOException{
        var bytes=new ByteArrayOutputStream();int tail=0;
        while(tail!=0x0d0a0d0a){
            int b=input.read();if(b<0||bytes.size()>=16384||b>127||b==0)throw new IOException("Response header bounds");
            bytes.write(b);tail=(tail<<8)|b;
        }
        var lines=bytes.toString(StandardCharsets.US_ASCII).split("\r\n",-1);
        if(lines.length>132||!lines[0].matches("HTTP/1\\.[01] 200(?: [\\x20-\\x7e]*)?"))throw new IOException("Response status");
        var headers=new HashMap<String,String>();
        for(int n=1;n<lines.length;n++){
            if(lines[n].isEmpty())continue;
            int colon=lines[n].indexOf(':');
            if(colon<1)throw new IOException("Malformed header");
            String name=lines[n].substring(0,colon).toLowerCase(Locale.ROOT),value=lines[n].substring(colon+1).strip();
            if(!name.matches("[a-z0-9-]+")||headers.putIfAbsent(name,value)!=null)throw new IOException("Duplicate header");
        }
        if(headers.containsKey("transfer-encoding")||headers.containsKey("content-encoding")
            ||!"application/json".equalsIgnoreCase(headers.getOrDefault("content-type","").split(";",2)[0].strip()))
            throw new IOException("Response representation");
        String length=headers.get("content-length");
        if(length==null||!length.matches("[0-9]{1,6}"))throw new IOException("Response length");
        int size=Integer.parseInt(length);if(size<1||size>limit)throw new IOException("Response body bounds");
        byte[] body=input.readNBytes(size);if(body.length!=size)throw new IOException("Truncated response");return body;
    }
}
