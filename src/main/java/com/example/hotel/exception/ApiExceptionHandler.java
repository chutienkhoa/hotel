package com.example.hotel.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Chuẩn hóa phản hồi lỗi phát sinh từ quá trình kiểm tra dữ liệu API. */
@RestControllerAdvice
public class ApiExceptionHandler {
    /**
     * Chuyển lỗi Bean Validation thành danh sách lỗi theo từng trường.
     *
     * @param exception ngoại lệ kiểm tra dữ liệu request
     * @return phản hồi HTTP 400 chứa lỗi của các trường không hợp lệ
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(errors);
    }
}
