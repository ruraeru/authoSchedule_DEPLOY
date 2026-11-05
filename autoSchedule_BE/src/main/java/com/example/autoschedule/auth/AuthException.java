package com.example.autoschedule.auth;

import com.example.autoschedule.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 인증 실패(401). 메시지는 화면에 그대로 보여준다. */
public class AuthException extends ApiException {

    public AuthException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
