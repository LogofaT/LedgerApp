package ledger.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class CorrelationFilter extends OncePerRequestFilter {
    private static final Pattern SAFE=Pattern.compile("[A-Za-z0-9._:-]{1,80}");
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String supplied=request.getHeader("X-Correlation-Id");
        if(supplied!=null&&!SAFE.matcher(supplied).matches()) {
            response.setStatus(400); response.setContentType("application/problem+json");
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Request refused\",\"status\":400,\"detail\":\"Invalid correlation ID\",\"code\":\"INVALID_CORRELATION_ID\",\"field\":\"X-Correlation-Id\"}");
            return;
        }
        String id=supplied==null?UUID.randomUUID().toString():supplied;
        MDC.put("correlationId",id); response.setHeader("X-Correlation-Id",id);
        HttpServletRequest wrapped=new HttpServletRequestWrapper(request) {
            @Override public String getHeader(String name) {
                return "X-Correlation-Id".equalsIgnoreCase(name)?id:super.getHeader(name);
            }
        };
        try { chain.doFilter(wrapped,response); } finally { MDC.remove("correlationId"); }
    }
}
