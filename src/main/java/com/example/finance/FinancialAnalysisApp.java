package com.example.finance;

import com.example.finance.analysis.FinancialDataFrame;
import com.example.finance.analysis.ForecastService;
import com.example.finance.analysis.InsightsService;
import com.example.finance.analysis.RiskService;
import com.example.finance.analysis.ReportService;
import com.example.finance.analysis.SummaryExportService;
import com.example.finance.analysis.VisualizationService;
import com.example.finance.io.FinancialCsvReader;
import com.example.finance.ask.AskService;
import com.example.finance.ask.LlmPlanner;
import com.example.finance.web.WebServer;


public final class FinancialAnalysisApp {
    private FinancialAnalysisApp() {
    }

    public static void main(String[] args) {
        CliOptions options = CliOptions.parse(args);
        FinancialDataFrame financialData = FinancialCsvReader.readTransactions(options.csvPath());

        if (options.serve()) {
            new WebServer(financialData, new AskService(LlmPlanner.fromEnv(), options.currencySymbol()), options.currencySymbol())
                    .start(options.port());
            return;
        }
        if (options.question() != null) {
            var answer = new AskService(LlmPlanner.fromEnv(), options.currencySymbol()).ask(options.question(), financialData.transactions());
            System.out.println(answer.get("answer"));
            System.out.println("Query (" + answer.get("planned_by") + "): " + com.example.finance.util.Json.write(answer.get("query")));
            return;
        }

        System.out.println("Financial Analysis CLI");
        System.out.println("======================");

        ReportService reportService = new ReportService();
        VisualizationService visualizationService = new VisualizationService();
        InsightsService insightsService = new InsightsService();
        RiskService riskService = new RiskService(options.currency());
        ForecastService forecastService = new ForecastService(options.currency());
        SummaryExportService exportService = new SummaryExportService(options.currency());

        reportService.analyzeExpenses(financialData);
        reportService.identifyHighValueTransactions(financialData, options.threshold());
        reportService.categorizeTransactions(financialData);
        reportService.generateMonthlyReport(financialData);
        reportService.generateCustomReport(financialData);

        visualizationService.visualizeExpenseTrends(financialData);
        visualizationService.visualizeTransactionCategories(financialData);
        insightsService.analyzeRecurringCharges(financialData);
        riskService.printRiskSummary(financialData);
        forecastService.printForecast(financialData);

        financialData.displayColumns();
        financialData.displayHead(options.previewLimit());
        financialData.displaySample(options.previewLimit());
        financialData.displayTransactionTypes();
        financialData.displayDestinationCounts();
        financialData.filterHighValueTransactions(options.threshold()).forEach(System.out::println);
        financialData.averageBalanceByDestination().forEach((destination, average) ->
                System.out.printf("Average balance for %-20s : %.2f%n", destination, average));

        exportService.writeSummaryReports(financialData, options.exportDir());
    }
}
