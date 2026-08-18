package com.edwin.eventnotification.adapter.in.rest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.edwin.eventnotification.application.exception.NotificationNotFoundException;
import com.edwin.eventnotification.application.exception.NotificationNotReplayableException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotificationNotFoundException.class)
    public ProblemDetail handleNotFound(NotificationNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NotificationNotReplayableException.class)
    public ProblemDetail handleNotReplayable(NotificationNotReplayableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(TooManyReplayRequestsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyReplayRequests(TooManyReplayRequestsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem);
    }
}
