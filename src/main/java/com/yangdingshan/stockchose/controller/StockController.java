package com.yangdingshan.stockchose.controller;

import com.yangdingshan.stockchose.domain.Stock;
import com.yangdingshan.stockchose.repository.StockRepository;
import com.yangdingshan.stockchose.service.DataDownloadService;
import com.yangdingshan.stockchose.service.IndexService;
import com.yangdingshan.stockchose.service.StockRankService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class StockController {

    private final StockRepository stockRepository;
    private final StockRankService stockRankService;
    private final DataDownloadService dataDownloadService;
    private final IndexService indexService;

    // ==================== Dashboard ====================

    @GetMapping("/")
    public String dashboard(Model model) {
        long totalStocks = stockRepository.count();

        List<Stock> top20 = stockRepository.findAll().stream()
                .filter(s -> s.getIndexPeRoeRank() != null)
                .sorted(Comparator.comparing(Stock::getIndexPeRoeRank))
                .limit(20)
                .collect(Collectors.toList());

        model.addAttribute("title", "仪表盘");
        model.addAttribute("currentPage", "dashboard");
        model.addAttribute("content", "pages/dashboard");
        model.addAttribute("totalStocks", totalStocks);
        model.addAttribute("qualifiedStocks", totalStocks);
        model.addAttribute("indexCount", indexService.getIndexCount());
        model.addAttribute("lastDownloadTime", dataDownloadService.getLastDownloadTime());
        model.addAttribute("lastDownloadSource", dataDownloadService.getLastDownloadSource());
        model.addAttribute("topStocks", top20);
        return "layout";
    }

    // ==================== Full Ranking ====================

    @GetMapping("/ranking")
    public String ranking(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String industry,
            @RequestParam(defaultValue = "") String market,
            @RequestParam(defaultValue = "composite") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(name = "pageSize", defaultValue = "50") int pageSize,
            Model model) {

        // Clamp page for safety
        if (page < 0) page = 0;

        List<Stock> allStocks = stockRepository.findAll();

        // Build industry list for dropdown (before filtering)
        List<String> industries = allStocks.stream()
                .map(Stock::getIndustry)
                .filter(s -> s != null && !s.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // Filter by search (code, name, or industry)
        if (!search.isEmpty()) {
            allStocks = allStocks.stream()
                    .filter(s -> s.getCode().contains(search)
                            || s.getName().contains(search)
                            || (s.getIndustry() != null && s.getIndustry().contains(search)))
                    .collect(Collectors.toList());
        }

        // Filter by industry
        if (!industry.isEmpty()) {
            allStocks = allStocks.stream()
                    .filter(s -> industry.equals(s.getIndustry()))
                    .collect(Collectors.toList());
        }

        // Filter by market
        if (!market.isEmpty()) {
            allStocks = allStocks.stream()
                    .filter(s -> s.getStockMarket().equals(market))
                    .collect(Collectors.toList());
        }

        // Sort
        switch (sort) {
            case "pe":
                allStocks.sort(Comparator.comparing(Stock::getPeRank));
                break;
            case "roe":
                allStocks.sort(Comparator.comparing(Stock::getRoeRank));
                break;
            case "coverage":
                allStocks.sort(Comparator.comparing(Stock::getIndexCount).reversed());
                break;
            default:
                allStocks.sort(Comparator.comparing(s ->
                        s.getIndexPeRoeRank() != null ? s.getIndexPeRoeRank() : Integer.MAX_VALUE));
                break;
        }

        int totalPages = (int) Math.ceil((double) allStocks.size() / pageSize);
        if (totalPages == 0) totalPages = 1;
        if (page >= totalPages) page = totalPages - 1;

        int fromIndex = page * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allStocks.size());
        List<Stock> pageStocks = allStocks.subList(
                Math.min(fromIndex, allStocks.size()), toIndex);

        // Build pagination window
        List<Map<String, Object>> pageNums = new ArrayList<>();
        int window = 2;
        for (int i = 0; i < totalPages; i++) {
            if (i == 0 || i == totalPages - 1
                    || (i >= page - window && i <= page + window)) {
                Map<String, Object> p = new HashMap<>();
                p.put("num", i);
                p.put("label", String.valueOf(i + 1));
                pageNums.add(p);
            } else if (pageNums.isEmpty() || !"...".equals(pageNums.get(pageNums.size() - 1).get("label"))) {
                Map<String, Object> p = new HashMap<>();
                p.put("num", -1);
                p.put("label", "...");
                pageNums.add(p);
            }
        }

        model.addAttribute("title", "全部排名");
        model.addAttribute("currentPage", "ranking");
        model.addAttribute("content", "pages/ranking");
        model.addAttribute("stocks", pageStocks);
        model.addAttribute("totalCount", allStocks.size());
        model.addAttribute("currentPageNum", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("pageNums", pageNums);
        model.addAttribute("search", search);
        model.addAttribute("industry", industry);
        model.addAttribute("industries", industries);
        model.addAttribute("market", market);
        model.addAttribute("sort", sort);
        return "layout";
    }

    // ==================== Download Management ====================

    @GetMapping("/download")
    public String downloadPage(Model model) {
        model.addAttribute("title", "下载管理");
        model.addAttribute("currentPage", "download");
        model.addAttribute("content", "pages/download");
        model.addAttribute("akshareAvailable", dataDownloadService.isAKShareAvailable());
        model.addAttribute("eastmoneyAvailable", dataDownloadService.isEastmoneyAvailable());
        model.addAttribute("cacheAvailable", dataDownloadService.isCacheAvailable());
        model.addAttribute("lastDownloadSource", dataDownloadService.getLastDownloadSource());
        model.addAttribute("lastDownloadTime", dataDownloadService.getLastDownloadTime());
        model.addAttribute("stockCount", dataDownloadService.getStockCount());
        model.addAttribute("indexCount", indexService.getIndexCount());
        model.addAttribute("lastIndexRefreshTime", indexService.getLastIndexRefreshTime());
        return "layout";
    }

    @PostMapping("/download/stocks")
    @ResponseBody
    public Map<String, Object> triggerStockDownload() {
        Map<String, Object> result = new HashMap<>();
        try {
            String source = dataDownloadService.downloadStockData();
            if (source != null) {
                stockRankService.runFullRanking();
            }
            result.put("success", source != null);
            result.put("source", source);
            result.put("count", dataDownloadService.getStockCount());
            result.put("error", dataDownloadService.getLastErrorMessage());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    @PostMapping("/download/indices")
    @ResponseBody
    public Map<String, Object> triggerIndexRefresh() {
        Map<String, Object> result = new HashMap<>();
        if (indexService.isIndexRefreshRunning()) {
            result.put("success", false);
            result.put("error", "指数刷新正在进行中，请稍后再试");
            return result;
        }
        try {
            // Start background refresh, composite ranks will be updated after completion
            indexService.runFullIndexPipelineAsync(() -> {
                stockRankService.setCompositeRanks();
            });
            result.put("success", true);
            result.put("message", "指数刷新已开始，请等待完成");
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    @GetMapping("/download/indices/status")
    @ResponseBody
    public Map<String, Object> indexRefreshStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("running", indexService.isIndexRefreshRunning());
        result.put("indexCount", indexService.getIndexCount());
        result.put("time", indexService.getLastIndexRefreshTime());
        result.put("progressCurrent", indexService.getRefreshProgressCurrent());
        result.put("progressTotal", indexService.getRefreshProgressTotal());
        result.put("progressName", indexService.getRefreshProgressName());
        result.put("progressStocks", indexService.getRefreshProgressStocks());
        result.put("progressStatus", indexService.getRefreshProgressStatus());
        return result;
    }

    @GetMapping("/download/indices/search")
    @ResponseBody
    public List<Map<String, Object>> searchIndices(@RequestParam String keyword) {
        return indexService.searchIndices(keyword);
    }

    @PostMapping("/download/indices/select")
    @ResponseBody
    public Map<String, Object> downloadSelectedIndices(@RequestBody Map<String, List<String>> body) {
        Map<String, Object> result = new HashMap<>();
        if (indexService.isIndexRefreshRunning()) {
            result.put("success", false);
            result.put("error", "指数刷新正在进行中，请稍后再试");
            return result;
        }
        try {
            List<String> codes = body.get("codes");
            if (codes == null || codes.isEmpty()) {
                result.put("success", false);
                result.put("error", "未选择任何指数");
                return result;
            }
            indexService.runSelectiveIndexPipeline(codes, () -> {
                stockRankService.setCompositeRanks();
            });
            result.put("success", true);
            result.put("message", "已开始下载 " + codes.size() + " 个指数");
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== Index Coverage ====================

    @GetMapping("/index")
    public String indexPage(Model model) {
        List<Stock> topByCoverage = stockRepository.findAll().stream()
                .filter(s -> s.getIndexCount() != null && s.getIndexCount() > 0)
                .sorted(Comparator.comparing(Stock::getIndexCount).reversed())
                .limit(20)
                .collect(Collectors.toList());

        model.addAttribute("title", "指数覆盖详情");
        model.addAttribute("currentPage", "index");
        model.addAttribute("content", "pages/index-detail");
        model.addAttribute("topByCoverage", topByCoverage);
        model.addAttribute("indexCount", indexService.getIndexCount());
        model.addAttribute("indexList", indexService.getIndexList());
        return "layout";
    }

    @GetMapping("/index/list")
    @ResponseBody
    public List<Map<String, Object>> indexList() {
        return indexService.getIndexList();
    }

    @GetMapping("/index/constituents")
    @ResponseBody
    public Map<String, Object> indexConstituents(@RequestParam String code) {
        Map<String, Object> result = new HashMap<>();
        List<String> codes = indexService.getIndexConstituents(code);
        List<Stock> stocks = stockRepository.findByCodeIn(codes);
        result.put("codes", codes);
        result.put("stocks", stocks);
        result.put("count", codes.size());
        return result;
    }
}
