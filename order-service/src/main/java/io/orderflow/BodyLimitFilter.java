package io.orderflow;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
final class BodyLimitFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(request.getContentLengthLong()>32768){response.setStatus(413);response.setContentType("application/json");response.getWriter().write("{\"code\":\"VALIDATION_ERROR\",\"message\":\"That request is too large.\",\"hint\":\"Send a smaller request and try again.\",\"details\":{}}");return;}
        chain.doFilter(request,response);
    }
}
