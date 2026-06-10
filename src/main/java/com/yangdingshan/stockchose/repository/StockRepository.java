package com.yangdingshan.stockchose.repository;

import com.yangdingshan.stockchose.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockRepository extends JpaRepository<Stock,Integer> {
    Stock findByCode(String code);
    List<Stock> findByCodeIn(List<String> codes);
}
