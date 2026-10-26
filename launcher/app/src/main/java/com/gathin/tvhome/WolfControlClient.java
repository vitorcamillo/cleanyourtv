package com.gathin.tvhome;

import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** HTTPS client for an operator-provisioned, certificate-pinned, single-TV session controller. */
final class WolfControlClient implements WolfControl {
    private static final int MAX_BYTES=16384;
    private final String baseUrl,hostUuid,token;
    private final SSLSocketFactory ssl;
    static final class ControlException extends Exception {
        final String code;
        ControlException(String code) { super(code);this.code=code; }
    }
    private WolfControlClient(String url,String host,String token,SSLSocketFactory ssl) {
        baseUrl=url;hostUuid=host;this.token=token;this.ssl=ssl;
    }
    static WolfControlClient load(Context context) throws ControlException {
        File file=new File(context.getFilesDir(),"wolf-control.json");
        if(!file.exists())return null;
        try {
            if(!file.isFile() || file.length()>MAX_BYTES)throw new ControlException("config_error");
            JSONObject json;
            try(InputStream input=new FileInputStream(file)) {json=new JSONObject(read(input));}
            if(json.getInt("version")!=1)throw new ControlException("config_error");
            String address=json.getString("url"),host=json.getString("host_uuid"),token=json.getString("token");
            URL url=new URL(address);
            if(!"https".equals(url.getProtocol()) || url.getPort()!=47991 || url.getUserInfo()!=null || url.getQuery()!=null || url.getRef()!=null
                    || !(url.getPath().isEmpty() || "/".equals(url.getPath())) || !privateAddress(url.getHost()))throw new ControlException("config_error");
            if(!host.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}") || !token.matches("[A-Za-z0-9_-]{32,128}"))throw new ControlException("config_error");
            String certificate=json.getString("server_certificate");
            if(certificate.length()>12000)throw new ControlException("config_error");
            ByteArrayInputStream encoded=new ByteArrayInputStream(Base64.decode(certificate,Base64.DEFAULT));
            X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(encoded);
            if(encoded.available()!=0)throw new ControlException("config_error");
            cert.checkValidity();
            KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);store.setCertificateEntry("wolf-session-control",cert);
            TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,trust.getTrustManagers(),null);
            return new WolfControlClient(address.endsWith("/")?address.substring(0,address.length()-1):address,UUID.fromString(host).toString(),token,tls.getSocketFactory());
        } catch(ControlException error) {throw error;}
        catch(Exception error) {throw new ControlException("config_error");}
    }
    private static boolean privateAddress(String host) {
        if(!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return false;
        String[] pieces=host.split("\\.");int[] octets=new int[4];
        for(int i=0;i<4;i++){octets[i]=Integer.parseInt(pieces[i]);if(octets[i]>255 || !pieces[i].equals(String.valueOf(octets[i])))return false;}
        return octets[0]==10 || octets[0]==192&&octets[1]==168 || octets[0]==172&&octets[1]>=16&&octets[1]<=31;
    }
    boolean controls(String host) {return hostUuid.equalsIgnoreCase(host);}
    public WolfSession getSession() throws ControlException {return request("GET","/v1/session",null);}
    public WolfSession stop(WolfSession expected) throws ControlException {
        if(expected==null || !expected.active)throw new ControlException("invalid_request");
        try {
            JSONObject body=new JSONObject().put("expected_app_id",expected.appId).put("expected_revision",expected.revision);
            WolfSession result=request("POST","/v1/session/stop",body);
            if(result.active)throw new ControlException("protocol_error");
            return result;
        } catch(ControlException error) {throw error;}
        catch(Exception error) {throw new ControlException("protocol_error");}
    }
    private WolfSession request(String method,String path,JSONObject body) throws ControlException {
        HttpsURLConnection connection=null;
        try {
            connection=(HttpsURLConnection)new URL(baseUrl+path).openConnection();
            connection.setSSLSocketFactory(ssl); // Default hostname verification remains enabled (certificate SAN).
            connection.setConnectTimeout(2000);connection.setReadTimeout(8000);connection.setUseCaches(false);connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);connection.setRequestProperty("Authorization","Bearer "+token);connection.setRequestProperty("Accept","application/json");
            if(body!=null) {
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out=connection.getOutputStream()){out.write(bytes);}
            }
            int status=connection.getResponseCode();String type=connection.getContentType();
            if(type==null || !"application/json".equalsIgnoreCase(type.split(";",2)[0].trim()))throw new ControlException("protocol_error");
            JSONObject json;
            try(InputStream input=status>=400?connection.getErrorStream():connection.getInputStream()){json=new JSONObject(read(input));}
            if(status!=200) {
                if(status==401)throw new ControlException("unauthorized");
                String code=json.optJSONObject("error")==null?"":json.getJSONObject("error").optString("code","");
                if(status==409 && (code.equals("session_changed") || code.equals("busy")))throw new ControlException(code);
                if(status==504)throw new ControlException("stop_timeout");
                if(status==502 || status==503 || status==500)throw new ControlException("upstream_unavailable");
                throw new ControlException("protocol_error");
            }
            if(!hostUuid.equals(json.getString("host_uuid")))throw new ControlException("protocol_error");
            for(String key:new String[]{"status","app_id","app_name","revision"})if(!json.has(key))throw new ControlException("protocol_error");
            String state=json.getString("status");
            if(state.equals("inactive")) {
                if(!json.isNull("app_id") || !json.isNull("app_name") || !json.isNull("revision"))throw new ControlException("protocol_error");
                return WolfSession.inactive();
            }
            if(!state.equals("active") || !(json.get("app_id") instanceof String) || !(json.get("app_name") instanceof String) || !(json.get("revision") instanceof String))throw new ControlException("protocol_error");
            return new WolfSession(true,json.getString("app_id"),json.getString("app_name"),json.getString("revision"));
        } catch(ControlException error) {throw error;}
        catch(SSLException error) {throw new ControlException("tls_error");}
        catch(java.io.IOException error) {throw new ControlException("network_error");}
        catch(Exception error) {throw new ControlException("protocol_error");}
        finally {if(connection!=null)connection.disconnect();}
    }
    private static String read(InputStream input) throws java.io.IOException,ControlException {
        if(input==null)throw new ControlException("protocol_error");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int count;
        while((count=input.read(buffer))!=-1){if(bytes.size()+count>MAX_BYTES)throw new ControlException("protocol_error");bytes.write(buffer,0,count);}
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
}
