package com.example.hotel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Khởi tạo ứng dụng quản lý khách sạn bằng Spring Boot. */
@SpringBootApplication
public class HotelApplication {
    /**
     * Starts the Spring Boot application context.
     *
     * @param args command-line arguments supplied when starting the application
     */
    public static void main(String[] args) {
        SpringApplication.run(HotelApplication.class, args);
    }
}
