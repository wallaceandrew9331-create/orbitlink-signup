package com.orbitlink.signup.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.orbitlink.signup.OrbitlinkProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A shared bearer token for the admin API. Enough for a demo; a real back office would sit
 * behind SSO with per-user roles and an audit log of capacity changes.
 */
@Component
@Order(0)
public class AdminTokenFilter extends OncePerRequestFilter {

    private final byte[] token;

    public AdminTokenFilter(OrbitlinkProperties props) {
        this.token = ("Bearer " + props.adminToken()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        // Constant-time comparison so response timing doesn't leak how much of the token matched.
        if (header == null || !MessageDigest.isEqual(token, header.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401}");
            return;
        }
        chain.doFilter(request, response);
    }
}
