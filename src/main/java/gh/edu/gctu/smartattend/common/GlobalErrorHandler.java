package gh.edu.gctu.smartattend.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public class GlobalErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestPartException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> invalid(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request was not valid. Please check your input.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(Exception e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "The uploaded file is too large.");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> dup(DuplicateKeyException e) {
        return body(HttpStatus.CONFLICT, "DUPLICATE", "This record already exists.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> method(Exception e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Operation not allowed.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> nf(Exception e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR", "Something went wrong on our side. Please try again.");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus s, String code, String msg) {
        return ResponseEntity.status(s).body(Map.of("error", Map.of("code", code, "message", msg)));
    }
}
