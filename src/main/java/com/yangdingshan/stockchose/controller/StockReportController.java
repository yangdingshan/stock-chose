package com.yangdingshan.stockchose.controller;

import com.yangdingshan.stockchose.domain.StockReport;
import com.yangdingshan.stockchose.service.StockReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class StockReportController {

    private final StockReportService stockReportService;

    @GetMapping("/stock-reports")
    public String stockList(@RequestParam(defaultValue = "") String search,
                            @RequestParam(defaultValue = "score_desc") String sort,
                            @RequestParam(defaultValue = "") String industry,
                            Model model) {
        List<Map<String, Object>> stocks = stockReportService.listStocks(
                search.isEmpty() ? null : search, sort, industry.isEmpty() ? null : industry);
        model.addAttribute("title", "个股报告");
        model.addAttribute("currentPage", "stock-reports");
        model.addAttribute("content", "pages/stock-reports");
        model.addAttribute("stocks", stocks);
        model.addAttribute("search", search);
        model.addAttribute("sort", sort);
        model.addAttribute("industry", industry);
        model.addAttribute("viewMode", "stockList");
        return "layout";
    }

    @GetMapping("/stock-reports/{code}")
    public String reportList(@PathVariable String code, Model model) {
        List<StockReport> reports = stockReportService.listReportsByStock(code);
        StockReport first = stockReportService.getFirstByCode(code);
        String stockName = first != null ? first.getStockName() : code;

        BigDecimal realTimePrice = stockReportService.getRealTimePrice(code);

        model.addAttribute("title", stockName + "(" + code + ") - 个股报告");
        model.addAttribute("currentPage", "stock-reports");
        model.addAttribute("content", "pages/stock-reports");
        model.addAttribute("stockCode", code);
        model.addAttribute("stockName", stockName);
        model.addAttribute("reports", reports);
        model.addAttribute("realTimePrice", realTimePrice);
        model.addAttribute("search", "");
        model.addAttribute("viewMode", "reportList");
        return "layout";
    }

    @GetMapping("/stock-reports/view")
    public String view(@RequestParam String file,
                       @RequestParam(defaultValue = "") String code, Model model) {
        String filename = validateFilename(file);

        StockReport report = stockReportService.getReport(filename);
        if (report == null) {
            return "redirect:/stock-reports";
        }

        String htmlContent = stockReportService.renderMarkdown(filename);
        String backUrl = code.isEmpty() ? "/stock-reports" : "/stock-reports/" + code;

        model.addAttribute("title", (report.getTitle() != null ? report.getTitle() : report.getStockName()) + " - 个股报告");
        model.addAttribute("currentPage", "stock-reports");
        model.addAttribute("content", "pages/stock-reports");
        model.addAttribute("viewMode", "detail");
        model.addAttribute("viewTitle", report.getTitle());
        model.addAttribute("viewFilename", filename);
        model.addAttribute("viewDateFormatted", report.getDateFormatted());
        model.addAttribute("viewHtml", htmlContent);
        model.addAttribute("backUrl", backUrl);
        model.addAttribute("stockCode", code);
        return "layout";
    }

    @PostMapping("/stock-reports/delete")
    @ResponseBody
    public Map<String, Object> delete(@RequestParam String file) {
        Map<String, Object> result = new HashMap<>();
        String filename = validateFilename(file);
        boolean deleted = stockReportService.deleteReport(filename);
        result.put("success", deleted);
        if (!deleted) {
            result.put("error", "文件不存在或删除失败");
        }
        return result;
    }

    private String validateFilename(String file) {
        try {
            String decoded = URLDecoder.decode(file, StandardCharsets.UTF_8.name());
            if (decoded.contains("..") || decoded.contains("/") || decoded.contains("\\")) {
                throw new IllegalArgumentException("非法文件路径");
            }
            return decoded;
        } catch (Exception e) {
            throw new IllegalArgumentException("非法文件路径", e);
        }
    }
}
