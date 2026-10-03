package com.payflow.repository;

import com.payflow.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, Long> {

    @Query("SELECT t FROM Transfer t WHERE t.fromWallet.user.id = :userId OR t.toWallet.user.id = :userId ORDER BY t.createdAt DESC")
    List<Transfer> findByUserId(@Param("userId") Long userId);
}
