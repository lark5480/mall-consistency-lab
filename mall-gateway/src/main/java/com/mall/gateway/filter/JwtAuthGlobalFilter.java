package com.mall.gateway.filter;

import com.mall.common.result.Result;
import com.mall.common.result.ResultCode;
import com.mall.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {
    private static final String USER_ID_HEADER = "X-User-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 统一剥离外部传入的可信头，防止伪造；鉴权成功后才写入可信值
        ServerWebExchange stripped = exchange.mutate()
                .request(request -> request.headers(headers -> headers.remove(USER_ID_HEADER)))
                .build();
        String path = stripped.getRequest().getPath().value();
        String method = stripped.getRequest().getMethod().name();
        if (path.startsWith("/internal/")) {
            return unauthorized(stripped);
        }
        boolean options = "OPTIONS".equals(method);
        boolean authPublic = path.equals("/api/v1/auth/register") || path.equals("/api/v1/auth/login");
        boolean productPublic = "GET".equals(method)
                && (path.equals("/api/v1/products") || path.matches("^/api/v1/products/\\d+$"));
        boolean categoryPublic = "GET".equals(method) && path.equals("/api/v1/categories");
        if (options || authPublic || productPublic || categoryPublic) {
            return chain.filter(stripped);
        }
        String authorization = stripped.getRequest().getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return unauthorized(stripped);
        }
        try {
            Claims claims = JwtUtil.parseToken(authorization.substring(7));
            boolean adminPath = path.startsWith("/api/v1/admin/");
            boolean productWrite = ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))
                    && path.startsWith("/api/v1/products");
            // B 端管理路径与商品写接口均强制 ADMIN
            if ((adminPath || productWrite) && !"ADMIN".equals(claims.get("role", String.class))) {
                return forbidden(stripped);
            }
            ServerWebExchange mutated = stripped.mutate()
                    .request(request -> request.headers(headers -> {
                        headers.set(USER_ID_HEADER, claims.getSubject());
                        headers.remove("Authorization");
                    }))
                    .build();
            return chain.filter(mutated);
        } catch (Exception exception) {
            return unauthorized(stripped);
        }
    }

    private Mono<Void> forbidden(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + ResultCode.FORBIDDEN.getCode() + ",\"message\":\"" + ResultCode.FORBIDDEN.getMessage() + "\",\"data\":null}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + ResultCode.UNAUTHORIZED.getCode() + ",\"message\":\"" + ResultCode.UNAUTHORIZED.getMessage() + "\",\"data\":null}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
