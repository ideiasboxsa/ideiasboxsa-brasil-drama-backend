package br.com.brasildrama.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            jwtService.parse(header.substring(7).trim()).ifPresent(token -> {
                var authorities = "ADMIN".equals(token.tokenType()) && token.role() != null
                    ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_" + token.role()))
                    : List.<SimpleGrantedAuthority>of();
                var auth = new UsernamePasswordAuthenticationToken(token.subjectId().toString(), null, authorities);
                auth.setDetails(token.tokenType());
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }
}
