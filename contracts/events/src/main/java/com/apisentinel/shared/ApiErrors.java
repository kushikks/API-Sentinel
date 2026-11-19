package com.apisentinel.shared;

import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
    public static ProblemDetail body(HttpStatus status, String code, String detail) {
        var body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code);
        return body;
    }

    @ExceptionHandler(Problem.class)
    ProblemDetail problem(Problem error) {
        return body(error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail fields(MethodArgumentNotValidException error) {
        var body = body(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Correct the highlighted fields.");
        body.setProperty("fields", error.getBindingResult().getFieldErrors().stream()
                .map(field -> new FieldError(field.getField(), field.getDefaultMessage())).toList());
        return body;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class,
            ServletRequestBindingException.class, HandlerMethodValidationException.class, MultipartException.class})
    ProblemDetail invalid() {
        return body(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "The request is missing valid input.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge() {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "SPEC_TOO_LARGE", "Upload a specification of at most 1 MiB.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail missing() {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "This endpoint does not exist.");
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail database() {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "The data store could not process the request.");
    }

    private record FieldError(String field, String message) {}
}
