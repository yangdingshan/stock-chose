package com.yangdingshan.stockchose.service;

import com.yangdingshan.stockchose.domain.Stock;
import com.yangdingshan.stockchose.repository.StockRepository;
import com.yangdingshan.stockchose.util.LambadaTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockRankService {

    private final StockRepository stockRepository;

    /**
     * Set PE, PB, and ROE ranks for all stocks.
     * PE: ascending (lower is better)
     * PB: ascending (lower is better)
     * ROE: descending (higher is better)
     */
    public void setPeRankAndRoeRank() {
        List<Stock> stocks = stockRepository.findAll();
        stocks.stream().sorted(Comparator.comparing(Stock::getPe))
                .forEach(LambadaTools.forEachWithIndex(Stock::setPeRank));
        stocks.stream().sorted(Comparator.comparing(Stock::getPb))
                .forEach(LambadaTools.forEachWithIndex(Stock::setPbRank));
        stocks.stream().sorted(Comparator.comparing(Stock::getRoe).reversed())
                .forEach(LambadaTools.forEachWithIndex(Stock::setRoeRank));
        stockRepository.saveAll(stocks);
        log.info("PE/PB/ROE排名已更新: {} 条", stocks.size());
    }

    /**
     * Compute composite scores: indexCountRank, peAndRoeCount, peAndRoeRank, indexPeRoeRank.
     * Must be called after setPeRankAndRoeRank() and index coverage counting.
     */
    public void setCompositeRanks() {
        List<Stock> stocks = stockRepository.findAll();

        // PE + ROE count = PE rank + ROE rank
        stocks.forEach(s -> s.setPeAndRoeCount(s.getPeRank() + s.getRoeRank()));

        // PE + ROE rank
        stocks.stream().sorted(Comparator.comparing(Stock::getPeAndRoeCount))
                .forEach(LambadaTools.forEachWithIndex(Stock::setPeAndRoeRank));

        // Index count rank: more indices = better, tie-break by PE rank
        stocks.stream()
                .sorted(Comparator.comparing(Stock::getIndexCount).reversed()
                        .thenComparing(Stock::getPeRank))
                .forEach(LambadaTools.forEachWithIndex(Stock::setIndexCountRank));

        // Final composite: index rank + PE+ROE rank
        stocks.stream()
                .sorted(Comparator.comparing(s -> s.getIndexCountRank() + s.getPeAndRoeRank()))
                .forEach(LambadaTools.forEachWithIndex(Stock::setIndexPeRoeRank));

        stockRepository.saveAll(stocks);
        log.info("综合排名已更新: {} 条", stocks.size());
    }

    /**
     * Run full ranking pipeline (PE/PB/ROE ranks + composite ranks).
     */
    public void runFullRanking() {
        setPeRankAndRoeRank();
        setCompositeRanks();
    }

    public List<Stock> getTopStocks(int limit) {
        return stockRepository.findAll().stream()
                .sorted(Comparator.comparing(Stock::getIndexPeRoeRank))
                .limit(limit)
                .collect(Collectors.toList());
    }
}
