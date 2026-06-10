package com.yangdingshan.stockchose.domain;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * @Author: yangdingshan
 * @Date: 2022/2/15 17:03
 * @Description:
 */
@Data
public class IndexRead {

    @ExcelProperty(value = "日期Date")
    private String date;

    @ExcelProperty(value = "指数代码 Index Code")
    private String indexCode;

    @ExcelProperty(value = "指数名称 Index Name")
    private String indexName;

    @ExcelProperty(value = "指数英文名称Index Name(Eng)")
    private String indexNameEng;

    @ExcelProperty(value = "成份券代码Constituent Code")
    private String constituentCode;

    @ExcelProperty(value = "成份券名称Constituent Name")
    private String constituentName;

    @ExcelProperty(value = "成份券英文名称Constituent Name(Eng)")
    private String constituentNameEng;

    @ExcelProperty(value = "交易所Exchange")
    private String exchange;

    @ExcelProperty(value = "交易所英文名称Exchange(Eng)")
    private String exchangeEng;
}
