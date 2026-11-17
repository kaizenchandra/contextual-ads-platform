package io.contextads;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;

@RestControllerAdvice
class ApiProblems {
    // Handle explicitly rather than sendError: protected container /error dispatch must not mask public 404/410 as 401.
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> status(ResponseStatusException error){
        return ResponseEntity.status(error.getStatusCode()).header("Cache-Control","no-store")
                .body(ProblemDetail.forStatusAndDetail(error.getStatusCode(),error.getReason()==null?"Request denied":error.getReason()));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge(){return ResponseEntity.status(413).body(ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,"Maximum image upload is 2 MiB"));}
}
