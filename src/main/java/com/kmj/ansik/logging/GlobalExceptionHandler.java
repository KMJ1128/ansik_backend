package com.kmj.ansik.logging;

import com.kmj.ansik.service.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> handleAuth(AuthException exception, HttpServletRequest request) {
        log.warn("[AUTH ERROR] path={}, status={}, message={}",
                request.getRequestURI(), exception.getStatus().value(), exception.getMessage());
        return ResponseEntity.status(exception.getStatus()).body(Map.of(
                "status", exception.getStatus().name(),
                "message", exception.getMessage()
        ));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception exception, HttpServletRequest request) {
        log.warn(
                "[API ERROR] 잘못된 요청 - path={}, errorType={}, message={}",
                request.getRequestURI(), exception.getClass().getSimpleName(), exception.getMessage()
        );
        return ResponseEntity.badRequest().body(Map.of(
                "status", "BAD_REQUEST",
                "message", "요청 값을 확인해 주세요."
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error(
                "[API ERROR] 처리되지 않은 서버 오류 - method={}, path={}, errorType={}",
                request.getMethod(), request.getRequestURI(), exception.getClass().getSimpleName(), exception
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", "INTERNAL_SERVER_ERROR",
                "message", "서버 처리 중 오류가 발생했습니다."
        ));
    }
}
