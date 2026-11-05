package ledger.api;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BodyLimitFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES=64*1024;
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        long length=request.getContentLengthLong();
        if(length>MAX_BYTES) { reject(response); return; }
        ByteArrayOutputStream buffer=new ByteArrayOutputStream((int)Math.max(0,length));
        byte[] chunk=new byte[4096]; int read;
        try(InputStream input=request.getInputStream()) {
            while((read=input.read(chunk))!=-1) {
                if(buffer.size()+read>MAX_BYTES) { reject(response); return; }
                buffer.write(chunk,0,read);
            }
        }
        byte[] body=buffer.toByteArray();
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read(){return input.read();}
                    @Override public boolean isFinished(){return input.available()==0;}
                    @Override public boolean isReady(){return true;}
                    @Override public void setReadListener(ReadListener listener) {
                        try { if(isFinished()) listener.onAllDataRead(); else listener.onDataAvailable(); }
                        catch(IOException ex){listener.onError(ex);}
                    }
                };
            }
            @Override public BufferedReader getReader() throws IOException { return new BufferedReader(new InputStreamReader(getInputStream(),getCharacterEncoding()==null?"UTF-8":getCharacterEncoding())); }
            @Override public int getContentLength(){return body.length;}
            @Override public long getContentLengthLong(){return body.length;}
        },response);
    }
    private void reject(HttpServletResponse response)throws IOException {
        response.setStatus(413); response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Request refused\",\"status\":413,\"detail\":\"Request body exceeds 65536 bytes\",\"code\":\"REQUEST_TOO_LARGE\"}");
    }
}
