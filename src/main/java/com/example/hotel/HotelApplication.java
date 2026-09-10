package com.example.hotel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Khởi tạo ứng dụng quản lý khách sạn bằng Spring Boot. */
@SpringBootApplication
public class HotelApplication {
  /** Khởi chạy ngữ cảnh Spring Boot của ứng dụng. */
  public static void main(String[] args) {
    SpringApplication.run(HotelApplication.class, args);
  }
}
