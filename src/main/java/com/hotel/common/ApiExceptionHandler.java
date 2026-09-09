package com.hotel.common;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;

/** Chuẩn hóa phản hồi lỗi phát sinh từ quá trình kiểm tra dữ liệu API. */
@RestControllerAdvice
public class ApiExceptionHandler {
  /**
   * Chuyển lỗi Bean Validation thành danh sách lỗi theo từng trường.
   *
   * @param e ngoại lệ kiểm tra dữ liệu request
   * @return phản hồi HTTP 400 chứa lỗi của các trường không hợp lệ
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
    Map<String, String> errors = new LinkedHashMap<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(x -> errors.put(x.getField(), x.getDefaultMessage()));
    return ResponseEntity.badRequest().body(errors);
  }
}
