package com.example.autoschedule.exception;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.charset.StandardCharsets;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final MediaType TEXT_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> handle(ApiException e) {
        return ResponseEntity.status(e.getStatus()).contentType(TEXT_UTF8).body(e.getMessage());
    }
}