package dev.jaoow.cotatrack.api.classification;

import java.util.Locale;

/**
 * Data-driven classifier for financial assets traded on B3 and international exchanges.
 * Uses provider metadata (type, subtype, sector, industry, description) and B3 market
 * structure to classify assets into {@link AssetCategory} without hardcoded ticker lists.
 */
public final class AssetClassifier {

    private static final String MUTUAL_FUNDS_INDUSTRY = "investment trusts/mutual funds";
    private static final String MISCELLANEOUS_SECTOR = "miscellaneous";
    private static final String REAL_ESTATE_SECTOR = "real estate";

    private AssetClassifier() {}

    /**
     * Resolves the full {@link AssetClassification} for a given asset.
     *
     * @param symbol      ticker symbol (e.g., "BPAC11", "PETR4", "BOVA11", "HGLG11", "AAPL34")
     * @param rawType     provider asset type (e.g., "stock", "fund", "dr")
     * @param rawSubType  provider asset subtype (e.g., "common", "preferred", "etf", "closedend", "unit", "fii")
     * @param sector      provider sector (e.g., "Finance", "Utilities", "Miscellaneous", "Real Estate")
     * @param industry    provider industry (e.g., "Investment Banks/Brokers", "Investment Trusts/Mutual Funds")
     * @param description provider long name or description
     * @return resolved {@link AssetClassification}
     */
    public static AssetClassification classify(String symbol,
                                               String rawType,
                                               String rawSubType,
                                               String sector,
                                               String industry,
                                               String description) {

        String clean = normalizeSymbol(symbol);
        String typeLower = rawType != null ? rawType.trim().toLowerCase(Locale.ROOT) : "";
        String subTypeLower = rawSubType != null ? rawSubType.trim().toLowerCase(Locale.ROOT) : "";
        String sectorLower = sector != null ? sector.trim().toLowerCase(Locale.ROOT) : "";
        String industryLower = industry != null ? industry.trim().toLowerCase(Locale.ROOT) : "";
        String descLower = description != null ? description.trim().toLowerCase(Locale.ROOT) : "";

        // 1. Fixed Income & Treasury
        if ("fixed_income".equals(typeLower) || clean.startsWith("CDB") || clean.startsWith("LCI") || clean.startsWith("LCA")
                || clean.startsWith("CRI") || clean.startsWith("CRA")) {
            return AssetClassification.builder()
                    .category(AssetCategory.FIXED_INCOME)
                    .type("fixed_income")
                    .subType("fixed_income")
                    .sector(sector)
                    .industry(industry)
                    .build();
        }

        if ("treasury".equals(typeLower) || clean.startsWith("LFT") || clean.startsWith("NTN") || clean.startsWith("LTN")
                || clean.startsWith("TESOURO")) {
            return AssetClassification.builder()
                    .category(AssetCategory.TREASURY)
                    .type("treasury")
                    .subType("treasury")
                    .sector(sector)
                    .industry(industry)
                    .build();
        }

        // 2. BDRs (Brazilian Depositary Receipts)
        if ("dr".equals(typeLower) || "bdr".equals(typeLower) || "bdr".equals(subTypeLower)
                || clean.matches("^[A-Z]{4}(31|32|33|34|35|39)$") || descLower.contains("bdr")
                || descLower.contains("depository receipt")) {
            return AssetClassification.builder()
                    .category(AssetCategory.BDRS)
                    .type("bdr")
                    .subType("bdr")
                    .sector(sector)
                    .industry(industry)
                    .build();
        }

        // 3. Pure Stocks (ON, PN, etc. ending in 3, 4, 5, 6, 7, 8)
        if (clean.matches("^[A-Z]{4}[3-8]F?$") || "stock".equals(typeLower) && !clean.matches("^[A-Z]{4}11F?$")) {
            String subType = subTypeLower.isEmpty() ? (clean.contains("3") ? "common" : "preferred") : rawSubType;
            return AssetClassification.builder()
                    .category(AssetCategory.EQUITIES)
                    .type("stock")
                    .subType(subType)
                    .sector(sector)
                    .industry(industry)
                    .build();
        }

        // 4. B3 Assets ending in 11 (Units, FIIs or ETFs)
        if (clean.matches("^[A-Z]{4}11F?$")) {
            // A. Real Estate Funds (FIIs - closed-end funds on B3)
            boolean isFii = "fii".equals(subTypeLower)
                    || "closedend".equals(subTypeLower)
                    || REAL_ESTATE_SECTOR.equals(sectorLower)
                    || descLower.contains("fii")
                    || descLower.contains("fundo de investimento imobiliario")
                    || descLower.contains("imobiliario")
                    || descLower.contains("imobiliário");

            if (isFii) {
                return AssetClassification.builder()
                        .category(AssetCategory.REAL_ESTATE_FUNDS)
                        .type("fund")
                        .subType("fii")
                        .sector(sector != null ? sector : "Real Estate")
                        .industry(industry)
                        .build();
            }

            // B. Units of Stocks (e.g. BPAC11, TAEE11, SANB11, KLBN11, ALUP11, SAPR11)
            boolean isExplicitUnit = "unit".equals(subTypeLower)
                    || descLower.contains("unit")
                    || descLower.contains("units")
                    || descLower.contains("cons of")
                    || descLower.contains("ctf de deposito")
                    || descLower.contains("ctf de depósito")
                    || descLower.contains("certificado de deposito")
                    || descLower.contains("certificado de depósito");

            boolean isOperatingCompanyIndustry = !industryLower.isEmpty()
                    && !MUTUAL_FUNDS_INDUSTRY.equals(industryLower);

            boolean isOperatingSector = !sectorLower.isEmpty()
                    && !MISCELLANEOUS_SECTOR.equals(sectorLower)
                    && !REAL_ESTATE_SECTOR.equals(sectorLower);

            if (isExplicitUnit || ("etf".equals(subTypeLower) && (isOperatingCompanyIndustry || isOperatingSector))) {
                return AssetClassification.builder()
                        .category(AssetCategory.EQUITIES)
                        .type("stock")
                        .subType("unit")
                        .sector(sector)
                        .industry(industry)
                        .build();
            }

            // C. ETFs (Exchange Traded Funds)
            boolean isEtf = "etf".equals(subTypeLower)
                    || MUTUAL_FUNDS_INDUSTRY.equals(industryLower)
                    || MISCELLANEOUS_SECTOR.equals(sectorLower)
                    || descLower.contains("fundo de indice")
                    || descLower.contains("fundo de índice")
                    || descLower.contains("index fund")
                    || descLower.contains("indice")
                    || descLower.contains("índice")
                    || descLower.contains("etf");

            if (isEtf) {
                return AssetClassification.builder()
                        .category(AssetCategory.ETFS)
                        .type("fund")
                        .subType("etf")
                        .sector(sector != null ? sector : "Miscellaneous")
                        .industry(industry != null ? industry : "Investment Trusts/Mutual Funds")
                        .build();
            }

            // Fallback for code 11 if unclassified: B3 closed-end funds are predominantly FIIs
            return AssetClassification.builder()
                    .category(AssetCategory.REAL_ESTATE_FUNDS)
                    .type("fund")
                    .subType("fii")
                    .sector(sector)
                    .industry(industry)
                    .build();
        }

        // 5. General fallbacks
        if ("stock".equals(typeLower)) {
            return AssetClassification.builder()
                    .category(AssetCategory.EQUITIES)
                    .type("stock")
                    .subType(rawSubType != null ? rawSubType : "common")
                    .sector(sector)
                    .industry(industry)
                    .build();
        }

        if ("fund".equals(typeLower)) {
            if ("closedend".equals(subTypeLower)) {
                return AssetClassification.builder()
                        .category(AssetCategory.REAL_ESTATE_FUNDS)
                        .type("fund")
                        .subType("fii")
                        .sector(sector)
                        .industry(industry)
                        .build();
            }
            return AssetClassification.builder()
                    .category(AssetCategory.ETFS)
                    .type("fund")
                    .subType("etf")
                    .sector(sector)
                    .industry(industry)
                    .build();
        }

        return AssetClassification.builder()
                .category(AssetCategory.EQUITIES)
                .type("stock")
                .subType("common")
                .sector(sector)
                .industry(industry)
                .build();
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null) return "";
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("BMFBOVESPA:")) {
            s = s.substring("BMFBOVESPA:".length());
        }
        if (s.endsWith(".SA")) {
            s = s.substring(0, s.length() - 3);
        }
        return s;
    }
}
