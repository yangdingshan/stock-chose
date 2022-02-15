package com.yangdingshan.stockchose.domain;

import com.alibaba.excel.annotation.ExcelProperty;
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
@Data
public class StockRead {

    @ExcelProperty(value = "代码")
    private String code;

    @ExcelProperty(value = "名称")
    private String name;

    @ExcelProperty(value = "最新")
    private BigDecimal price;

    @ExcelProperty(value = "市盈率")
    private Float pe;

    @ExcelProperty(value = "市盈率排名")
    private Integer peRank;

    @ExcelProperty(value = "加权净资产收益率")
    private Float roe;

    @ExcelProperty(value = "收益率排名")
    private Integer roeRank;


}
