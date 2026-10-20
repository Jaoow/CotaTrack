package dev.jaoow.cotatrack.api.classification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssetClassifierTest {

    @Test
    @DisplayName("BPAC11 (Unit) should be classified as EQUITIES and subType unit via TradingView metadata")
    void testBpac11TradingView() {
        AssetClassification c = AssetClassifier.classify(
                "BMFBOVESPA:BPAC11",
                "fund",
                "etf",
                "Finance",
                "Investment Banks/Brokers",
                "Banco BTG Pactual SA Units Cons of 1 Sh + 2 Pfd Shs A"
        );

        assertEquals(AssetCategory.EQUITIES, c.getCategory());
        assertEquals("stock", c.getType());
        assertEquals("unit", c.getSubType());
    }

    @Test
    @DisplayName("TAEE11 (Unit) should be classified as EQUITIES and subType unit via TradingView metadata")
    void testTaee11TradingView() {
        AssetClassification c = AssetClassifier.classify(
                "TAEE11",
                "fund",
                "etf",
                "Industrial Services",
                "Engineering & Construction",
                "Transmissora Alianca De Energia Eletrica S.A. Unit"
        );

        assertEquals(AssetCategory.EQUITIES, c.getCategory());
        assertEquals("stock", c.getType());
        assertEquals("unit", c.getSubType());
    }

    @Test
    @DisplayName("BPAC11 via Brapi metadata (type=stock, subType=unit)")
    void testBpac11Brapi() {
        AssetClassification c = AssetClassifier.classify(
                "BPAC11",
                "stock",
                "unit",
                "Finance",
                null,
                "Banco BTG Pactual SA"
        );

        assertEquals(AssetCategory.EQUITIES, c.getCategory());
        assertEquals("stock", c.getType());
        assertEquals("unit", c.getSubType());
    }

    @Test
    @DisplayName("PETR4 and VALE3 should be classified as EQUITIES")
    void testStocks() {
        AssetClassification petr = AssetClassifier.classify(
                "PETR4", "stock", "preferred", "Energy Minerals", "Integrated Oil", "Petroleo Brasileiro SA Pfd"
        );
        assertEquals(AssetCategory.EQUITIES, petr.getCategory());
        assertEquals("stock", petr.getType());

        AssetClassification vale = AssetClassifier.classify(
                "VALE3", "stock", "common", "Non-Energy Minerals", "Steel", "Vale S.A."
        );
        assertEquals(AssetCategory.EQUITIES, vale.getCategory());
        assertEquals("stock", vale.getType());
    }

    @Test
    @DisplayName("HGLG11 and MXRF11 should be classified as REAL_ESTATE_FUNDS")
    void testFIIs() {
        AssetClassification hglg = AssetClassifier.classify(
                "HGLG11", "fund", "closedend", "Finance", "Real Estate Development",
                "Patria Log - Fundo de Investimento Imobiliario - Responsabilidade Limitada Cotas"
        );
        assertEquals(AssetCategory.REAL_ESTATE_FUNDS, hglg.getCategory());
        assertEquals("fund", hglg.getType());
        assertEquals("fii", hglg.getSubType());

        AssetClassification mxrf = AssetClassifier.classify(
                "MXRF11", "fund", "closedend", "Miscellaneous", "Investment Trusts/Mutual Funds",
                "Maxi Renda Fundo de Investimento Imobiliario Cotas"
        );
        assertEquals(AssetCategory.REAL_ESTATE_FUNDS, mxrf.getCategory());
        assertEquals("fund", mxrf.getType());
        assertEquals("fii", mxrf.getSubType());
    }

    @Test
    @DisplayName("BOVA11 and SMAL11 should be classified as ETFS")
    void testETFs() {
        AssetClassification bova = AssetClassifier.classify(
                "BOVA11", "fund", "etf", "Miscellaneous", "Investment Trusts/Mutual Funds",
                "iShares Ibovespa Fundo de Indice"
        );
        assertEquals(AssetCategory.ETFS, bova.getCategory());
        assertEquals("fund", bova.getType());
        assertEquals("etf", bova.getSubType());

        AssetClassification smal = AssetClassifier.classify(
                "SMAL11", "fund", "etf", "Miscellaneous", "Investment Trusts/Mutual Funds",
                "iShares BM&FBOVESPA Small Cap Index Fund"
        );
        assertEquals(AssetCategory.ETFS, smal.getCategory());
        assertEquals("fund", smal.getType());
        assertEquals("etf", smal.getSubType());
    }

    @Test
    @DisplayName("AAPL34 should be classified as BDRS")
    void testBDRs() {
        AssetClassification aapl = AssetClassifier.classify(
                "AAPL34", "dr", "", "Electronic Technology", "Telecommunications Equipment",
                "Apple Inc. Shs Unsponsored Brazilian Depository Receipt Repr 0.05 Sh"
        );
        assertEquals(AssetCategory.BDRS, aapl.getCategory());
        assertEquals("bdr", aapl.getType());
    }
}
