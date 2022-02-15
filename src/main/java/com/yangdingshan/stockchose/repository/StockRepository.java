package com.yangdingshan.stockchose.repository;

import com.yangdingshan.stockchose.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * @Author: yangdingshan
 * @Date: 2022/2/15 15:24
 * @Description:
 */
@Repository
public interface StockRepository extends JpaRepository<Stock,Integer> {
}
