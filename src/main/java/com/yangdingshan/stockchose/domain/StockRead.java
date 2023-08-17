package com.yangdingshan.stockchose.domain;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

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
    private String price;

    @ExcelProperty(value = "市盈率")
    private String pe;

    @ExcelProperty(value = "市净率")
    private String pb;

    @ExcelProperty(value = "加权净资产收益率")
    private String roe;

}
