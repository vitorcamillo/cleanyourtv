package com.gathin.tvhome;

import android.app.Activity;
import android.util.Base64;
import org.json.JSONObject;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class WolfControlClientTest {
    private static final String HOST="11111111-2222-4333-8444-555555555555";
    private static final String TOKEN="fixture-test-only-"+String.join("",Collections.nCopies(32,"x"));
    private static final String REV=String.join("",Collections.nCopies(64,"a"));
    private static final char[] PASSWORD="fixture-only-password".toCharArray();
    static Path directory,primary,alternate;
    Activity activity;File configFile;javax.net.ssl.SSLServerSocket server;ExecutorService serverExecutor;WolfControlClient client;
    volatile int status=200;volatile String body,type="application/json",lastBody,lastAuthorization;
    AtomicInteger requests=new AtomicInteger();
    @BeforeClass public static void createCertificates() throws Exception {
        directory=Files.createTempDirectory("wolf-control-test-");primary=directory.resolve("primary.p12");alternate=directory.resolve("alternate.p12");
        for(Path path:new Path[]{primary,alternate}) {
            Process process=new ProcessBuilder(System.getProperty("java.home")+"/bin/keytool","-genkeypair","-alias","fixture","-keyalg","EC","-groupname","secp256r1","-dname","CN=Wolf Fixture","-ext","SAN=ip:127.0.0.1,ip:192.168.1.10","-validity","1","-storetype","PKCS12","-keystore",path.toString(),"-storepass",new String(PASSWORD),"-noprompt").redirectErrorStream(true).start();
            byte[] output=process.getInputStream().readAllBytes();assertEquals(new String(output,StandardCharsets.UTF_8),0,process.waitFor());
        }
    }
    @AfterClass public static void deleteCertificates() throws Exception {
        if(directory!=null)try(java.util.stream.Stream<Path> files=Files.walk(directory)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}});}
    }
    static KeyStore store(Path path) throws Exception {KeyStore result=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(path)){result.load(input,PASSWORD);}return result;}
    JSONObject config() throws Exception {
        Certificate certificate=store(primary).getCertificate("fixture");
        return new JSONObject().put("version",1).put("url","https://192.168.1.10:47991").put("host_uuid",HOST).put("token",TOKEN).put("server_certificate",Base64.encodeToString(certificate.getEncoded(),Base64.NO_WRAP));
    }
    void write(JSONObject value) throws Exception {Files.write(configFile.toPath(),value.toString().getBytes(StandardCharsets.UTF_8));}
    String state(boolean active) throws Exception {
        return new JSONObject().put("host_uuid",HOST).put("status",active?"active":"inactive")
            .put("app_id",active?"10":JSONObject.NULL).put("app_name",active?"Mario Kart":JSONObject.NULL).put("revision",active?REV:JSONObject.NULL).toString();
    }
    @Before public void setup() throws Exception {
        activity=Robolectric.buildActivity(Activity.class).setup().get();configFile=new File(activity.getFilesDir(),"wolf-control.json");write(config());body=state(false);
        openServer(primary);
    }
    void openServer(Path certificate) throws Exception {
        serverExecutor=Executors.newSingleThreadExecutor();
        KeyStore keys=store(certificate);KeyManagerFactory manager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());manager.init(keys,PASSWORD);
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(manager.getKeyManagers(),null,null);
        server=(javax.net.ssl.SSLServerSocket)tls.getServerSocketFactory().createServerSocket();server.bind(new InetSocketAddress("127.0.0.1",0));
        final javax.net.ssl.SSLServerSocket listener=server;
        serverExecutor.execute(()-> {
            while(!listener.isClosed()) {
                try(javax.net.ssl.SSLSocket socket=(javax.net.ssl.SSLSocket)listener.accept()) {
                    socket.setSoTimeout(3000);InputStream input=socket.getInputStream();java.io.ByteArrayOutputStream headerBytes=new java.io.ByteArrayOutputStream();
                    int value;String header="";
                    while(headerBytes.size()<8192 && (value=input.read())!=-1){headerBytes.write(value);header=new String(headerBytes.toByteArray(),StandardCharsets.UTF_8);if(header.endsWith("\r\n\r\n"))break;}
                    int length=0;lastAuthorization=null;
                    for(String line:header.split("\r\n")) {
                        if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                        if(line.toLowerCase(java.util.Locale.ROOT).startsWith("authorization:"))lastAuthorization=line.substring(14).trim();
                    }
                    if(length>4096)throw new java.io.IOException("fixture body limit");
                    byte[] requestBody=new byte[length];int offset=0,count;
                    while(offset<length && (count=input.read(requestBody,offset,length-offset))!=-1)offset+=count;
                    lastBody=new String(requestBody,StandardCharsets.UTF_8);requests.incrementAndGet();
                    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                    String headers="HTTP/1.1 "+status+" Fixture\r\nContent-Type: "+type+"\r\nContent-Length: "+bytes.length+"\r\nLocation: https://example.invalid/redirected\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                }catch(java.io.IOException|RuntimeException ignored){} // Fixture permits intentionally failed TLS/oversize probes.
            }
        });
        WolfControlClient loaded=WolfControlClient.load(activity);
        Field factory=WolfControlClient.class.getDeclaredField("ssl");factory.setAccessible(true);
        Constructor<WolfControlClient> constructor=WolfControlClient.class.getDeclaredConstructor(String.class,String.class,String.class,SSLSocketFactory.class);constructor.setAccessible(true);
        // Transport tests use a local fixture port. Production config still permits only its fixed LAN port.
        client=constructor.newInstance("https://127.0.0.1:"+server.getLocalPort(),HOST,TOKEN,factory.get(loaded));
    }
    @After public void cleanup() throws Exception {if(server!=null)server.close();if(serverExecutor!=null)serverExecutor.shutdownNow();activity.finish();}
    @Test public void validPrivateConfigLoadsAndSelectsOnlyItsHost() throws Exception {
        WolfControlClient loaded=WolfControlClient.load(activity);assertTrue(loaded.controls(HOST));assertFalse(loaded.controls("other"));assertFalse(loaded.controls(null));
    }
    @Test public void absentConfigIsDistinctFromMalformedConfig() throws Exception {
        assertTrue(configFile.delete());assertNull(WolfControlClient.load(activity));
        Files.write(configFile.toPath(),"broken-json".getBytes(StandardCharsets.UTF_8));assertEquals("config_error",assertThrows(WolfControlClient.ControlException.class,()->WolfControlClient.load(activity)).code);
        Files.write(configFile.toPath(),String.join("",Collections.nCopies(17000,"x")).getBytes(StandardCharsets.UTF_8));assertThrows(WolfControlClient.ControlException.class,()->WolfControlClient.load(activity));
    }
    @Test public void urlsAndCredentialsCannotRedirectOrInject() throws Exception {
        for(String url:new String[]{"http://192.168.1.10:47991","https://192.168.1.10:443","https://public.example:47991","https://127.0.0.1:47991","https://169.254.169.254:47991","https://192.168.1.10:47991/path","https://user@192.168.1.10:47991","https://192.168.1.10:47991?secret=x","https://192.168.1.10:47991#fragment","https://256.1.1.1:47991","https://192.0168.1.10:47991","https://172.15.1.1:47991","https://172.32.1.1:47991"}) {
            write(config().put("url",url));assertThrows(url,WolfControlClient.ControlException.class,()->WolfControlClient.load(activity));
        }
        for(String token:new String[]{"short",TOKEN+"\r\nheader",String.join("",Collections.nCopies(129,"a"))}) {
            write(config().put("token",token));assertThrows(WolfControlClient.ControlException.class,()->WolfControlClient.load(activity));
        }
        for(String url:new String[]{"https://10.0.0.1:47991/","https://172.16.0.1:47991","https://172.31.1.1:47991"}) {write(config().put("url",url));assertNotNull(WolfControlClient.load(activity));}
    }
    @Test public void badIdentityVersionAndCertificatesFailClosed() throws Exception {
        for(JSONObject value:new JSONObject[]{config().put("version",2),config().put("host_uuid","bad"),config().put("server_certificate","not-a-certificate"),config().put("server_certificate",String.join("",Collections.nCopies(12001,"x")))}) {
            write(value);assertEquals("config_error",assertThrows(WolfControlClient.ControlException.class,()->WolfControlClient.load(activity)).code);
        }
    }
    @Test public void authenticatedPinnedReadAndStopUseSafePayload() throws Exception {
        assertFalse(client.getSession().active);assertEquals("Bearer "+TOKEN,lastAuthorization);
        body=state(true);WolfSession active=client.getSession();assertEquals("10",active.appId);
        body=state(false);assertFalse(client.stop(active).active);
        JSONObject sent=new JSONObject(lastBody);assertEquals("10",sent.getString("expected_app_id"));assertEquals(REV,sent.getString("expected_revision"));assertFalse(lastBody.contains(TOKEN));
    }
    @Test public void untrustedCertificateCannotReadSession() throws Exception {
        server.close();serverExecutor.shutdownNow();requests.set(0);openServer(alternate);
        assertEquals("tls_error",assertThrows(WolfControlClient.ControlException.class,()->client.getSession()).code);assertEquals(0,requests.get());
    }
    @Test public void malformedOrRetargetedResponsesAreRejected() throws Exception {
        for(String bad:new String[]{"broken",new JSONObject(state(false)).put("host_uuid","other").toString(),new JSONObject(state(false)).put("status","unknown").toString(),new JSONObject(state(false)).put("app_name","unexpected").toString(),new JSONObject(state(true)).put("app_id",10).toString(),new JSONObject(state(true)).put("revision","bad").toString(),"{}"}) {
            body=bad;assertEquals("protocol_error",assertThrows(WolfControlClient.ControlException.class,()->client.getSession()).code);
        }
    }
    @Test public void sizeAndContentTypeAreBounded() throws Exception {
        body=String.join("",Collections.nCopies(17000,"x"));assertEquals("protocol_error",assertThrows(WolfControlClient.ControlException.class,()->client.getSession()).code);
        body=state(false);type="text/html";assertEquals("protocol_error",assertThrows(WolfControlClient.ControlException.class,()->client.getSession()).code);
    }
    @Test public void statusesProduceStableSafeErrorsAndNoRedirects() throws Exception {
        int[] statuses={401,409,409,504,502,503,500,404,302};String[] codes={"unauthorized","busy","session_changed","stop_timeout","upstream_unavailable","upstream_unavailable","upstream_unavailable","protocol_error","protocol_error"};
        for(int i=0;i<statuses.length;i++) {
            status=statuses[i];body=new JSONObject().put("error",new JSONObject().put("code",codes[i]).put("message","fixture-private-detail")).toString();int before=requests.get();
            assertEquals(codes[i],assertThrows(WolfControlClient.ControlException.class,()->client.getSession()).code);assertEquals(before+1,requests.get());
        }
    }
    @Test public void stopDoesNotPretendAnActiveResponseIsSuccess() throws Exception {
        body=state(true);assertEquals("protocol_error",assertThrows(WolfControlClient.ControlException.class,()->client.stop(new WolfSession(true,"10","Mario Kart",REV))).code);
        int before=requests.get();assertEquals("invalid_request",assertThrows(WolfControlClient.ControlException.class,()->client.stop(WolfSession.inactive())).code);assertEquals(before,requests.get());
        assertThrows(WolfControlClient.ControlException.class,()->client.stop(null));
    }
    @Test public void disconnectedServiceHasExplicitNetworkFailure() throws Exception {
        server.close();serverExecutor.shutdownNow();assertEquals("network_error",assertThrows(WolfControlClient.ControlException.class,()->client.getSession()).code);
    }
}
