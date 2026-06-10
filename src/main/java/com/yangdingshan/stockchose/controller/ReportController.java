package com.yangdingshan.stockchose.controller;

import com.yangdingshan.stockchose.domain.Report;
import com.yangdingshan.stockchose.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/reports")
    public String list(@RequestParam(defaultValue = "") String search, Model model) {
        List<Report> reports = reportService.listReports(search.isEmpty() ? null : search);
        model.addAttribute("title", "行业报告");
        model.addAttribute("currentPage", "reports");
        model.addAttribute("content", "pages/reports");
        model.addAttribute("reports", reports);
        model.addAttribute("search", search);
        model.addAttribute("viewMode", false);
        return "layout";
    }

    @GetMapping("/reports/view")
    public String view(@RequestParam String file, Model model) {
        String filename = validateFilename(file);

        Report report = reportService.getReport(filename);
        if (report == null) {
            return "redirect:/reports";
        }

        String htmlContent = reportService.renderMarkdown(filename);

        model.addAttribute("title", report.getTitle() + " - 行业报告");
        model.addAttribute("currentPage", "reports");
        model.addAttribute("content", "pages/reports");
        model.addAttribute("reports", reportService.listReports(null));
        model.addAttribute("search", "");
        model.addAttribute("viewMode", true);
        model.addAttribute("viewTitle", report.getTitle());
        model.addAttribute("viewFilename", filename);
        model.addAttribute("viewDateFormatted", report.getDateFormatted());
        model.addAttribute("viewHtml", htmlContent);
        return "layout";
    }

    @PostMapping("/reports/delete")
    @ResponseBody
    public Map<String, Object> delete(@RequestParam String file) {
        Map<String, Object> result = new HashMap<>();
        String filename = validateFilename(file);
        boolean deleted = reportService.deleteReport(filename);
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
