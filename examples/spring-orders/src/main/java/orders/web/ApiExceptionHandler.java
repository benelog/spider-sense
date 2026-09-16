package orders.web;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import orders.service.OrderException;
import orders.web.Dtos.ErrorView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Only the expected failures are mapped here.
 * Anything else (the flaky endpoint's IllegalStateException) propagates and becomes a 500,
 * which is the point: it has to be visible as an error in the trace.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(OrderException.class)
    public ResponseEntity<ErrorView> conflict(OrderException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorView(409, "Conflict", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorView> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorView(404, "Not Found", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorView> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorView(400, "Bad Request", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorView> invalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof FieldError field
                        ? field.getField() + ": " + field.getDefaultMessage()
                        : error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ErrorView(400, "Bad Request", message.isEmpty() ? "invalid request" : message));
    }
}
