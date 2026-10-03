package com.payflow.controller;

import com.payflow.dto.DepositRequest;
import com.payflow.dto.WalletResponse;
import com.payflow.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @PostMapping("/{userId}")
    public ResponseEntity<WalletResponse> createWallet(@PathVariable Long userId) {
        WalletResponse response = walletService.createWallet(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<WalletResponse> getWalletByUserId(@PathVariable Long userId) {
        WalletResponse response = walletService.getWalletByUserId(userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{userId}/deposit")
    public ResponseEntity<WalletResponse> deposit(
            @PathVariable Long userId,
            @Valid @RequestBody DepositRequest request
    ) {
        WalletResponse response = walletService.deposit(userId, request);
        return ResponseEntity.ok(response);
    }
}
