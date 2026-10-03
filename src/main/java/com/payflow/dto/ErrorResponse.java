package com.payflow.dto;

public record ErrorResponse(
        int status,
        String message
) {
}
