package com.yangdingshan.stockchose.domain;

import lombok.Data;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.math.BigDecimal;

/**
 * @Author: yangdingshan
 * @Date: 2022/2/15 15:17
 * @Description:
 */
@Entity
@Table(name = "stock")
@Data
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column
    private String code;

    @Column(name = "stock_market")
    private String stockMarket;

    @Column
    private String name;

    @Column
    private BigDecimal price;

    @Column
    private Float pe;

    @Column(name = "pe_rank")
    private Integer peRank;

    @Column
    private Float roe;

    @Column(name = "roe_rank")
    private Integer roeRank;

    @Column(name = "index_count")
    private Integer indexCount;

    @Column(name = "index_count_rank")
    private Integer indexCountRank;

    @Column(name = "final_rank")
    private Integer finalRank;
}
