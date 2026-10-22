# 📊 CotaTrack — Documentação de Fontes de Dados e Catálogo do Modelo `Quote`

Este documento detalha as fontes de dados públicos, a matriz de cobertura de campos por provedor, os algoritmos de derivação matemática para enriquecimento de dados e as diretrizes de nulabilidade e serialização da API CotaTrack.

---

## 1. Arquitetura Multi-Provedor

A API CotaTrack opera através de uma camada orquestrada de provedores com fallback automático, monitoramento por Circuit Breaker e Rate Limiter (Resilience4j) e resgate de emergência via *stale cache*:

```
                     ┌──────────────────────────────┐
                     │         Cliente API          │
                     └──────────────┬───────────────┘
                                    │
                                    ▼
                     ┌──────────────────────────────┐
                     │    ProviderOrchestrator      │
                     └──────┬───────┬────────┬──────┘
                            │       │        │
               1. Primário  │       │ 2.     │ 3.
                (Em Lote)   │       │ Fallback (OHLCV)
                            ▼       ▼        ▼
                      TradingView Yahoo   brapi.dev
                        Scanner   Chart
                            │       │        │
                            └───────┼────────┘
                                    │
                                    ▼
                     ┌──────────────────────────────┐
                     │        QuoteEnricher         │
                     │  (Derivações + Averages SMA) │
                     └──────────────┬───────────────┘
                                    │ (se dividends=true)
                                    ▼
                     ┌──────────────────────────────┐
                     │    B3 Listed Proxy           │
                     │ (DividendsFetcher detalhado) │
                     └──────────────────────────────┘
```

---

## 2. Matriz Campo x Provedor (Código Real vs. Relatório Exploratório)

Abaixo está a validação campo a campo do modelo [`Quote`](file:///home/jaoow/IdeaProjects/CotaTrack/api/src/main/java/dev/jaoow/cotatrack/api/requests/quotes/model/Quote.java) contra as implementações reais dos provedores integrados:

| Campo em `Quote` | Tipo Java | Provedor Primário (`TRADING_VIEW`) | Provedor Fallback 1 (`YAHOO_CHART`) | Provedor Fallback 2 (`BRAPI`) | Provedor Proventos (`B3_PROXY`) | Status de Derivação / Observações |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `symbol` | `String` | **Nativo** (`name` + `.SA`) | **Nativo** (`meta.symbol`) | **Nativo** (`symbol` + `.SA`) | N/A | Normalizado com sufixo `.SA` padrão B3. |
| `currency` | `String` | **Nativo** (`currency` -> "BRL") | **Nativo** (`meta.currency`) | **Nativo** (`currency`) | N/A | Moeda padrão: `BRL`. |
| `shortName` | `String` | **Nativo** (`name`) | **Nativo** (`meta.shortName`) | **Nativo** (`shortName`) | N/A | Ticker ou código resumido. |
| `longName` | `String` | **Nativo** (`description`) | **Nativo** (`meta.longName`) | **Nativo** (`longName`) | N/A | Razão social ou descrição do ativo. |
| `logoUrl` | `String` | **Nativo** (`s3-symbol-logo`) | *Ausente* | **Nativo** (`icons.brapi.dev`) | N/A | URL direta para SVG/PNG do logo corporativo. |
| `regularMarketPrice` | `Double` | **Nativo** (`close`) | **Nativo** (`meta.regularMarketPrice`) | **Nativo** (`regularMarketPrice`) | N/A | Último preço de negociação (cotação atual). |
| `regularMarketChange` | `Double` | **Nativo** (`change_abs`) | **Nativo** (`meta.fulldayChange`) | **Nativo** (`regularMarketChange`) | N/A | Variação absoluta de preço em R$. |
| `regularMarketChangePercent` | `Double` | **Nativo** (`change`) | **Nativo** (`meta.regularMarketChangePercent`) | **Nativo** (`regularMarketChangePercent`) | N/A | Variação percentual do dia (ex: -2.4%). |
| `regularMarketTime` | `Date` | **Nativo** (`time` em epoch seg.) | **Nativo** (`meta.regularMarketTime`) | **Nativo** (`regularMarketTime` ISO) | N/A | Timestamp da última negociação. |
| `regularMarketDayHigh` | `Double` | **Nativo** (`high`) | **Nativo** (`meta.regularMarketDayHigh`) | **Nativo** (`regularMarketDayHigh`) | N/A | Máxima do pregão diário. |
| `regularMarketDayLow` | `Double` | **Nativo** (`low`) | **Nativo** (`meta.regularMarketDayLow`) | **Nativo** (`regularMarketDayLow`) | N/A | Mínima do pregão diário. |
| `regularMarketDayRange` | `String` | **Derivado** (`low - high`) | **Derivado** (`low - high`) | **Nativo / Derivado** | N/A | Faixa de preço do dia formatada `min - max`. |
| `regularMarketVolume` | `Long` | **Nativo** (`volume`) | **Nativo** (`meta.regularMarketVolume`) | **Nativo** (`regularMarketVolume`) | N/A | Volume de ações negociadas na sessão. |
| `regularMarketPreviousClose` | `Double` | **Derivado** (`close - change_abs`) | **Nativo** (`meta.chartPreviousClose`) | **Nativo** (`regularMarketPreviousClose`) | N/A | Preço de fechamento da sessão anterior. |
| `regularMarketOpen` | `Double` | **Nativo** (`open`) | **Nativo** (`indicators.quote.open`) | **Nativo** (`regularMarketOpen`) | N/A | Preço de abertura da sessão atual. |
| `averageDailyVolume10Day` | `Long` | **Nativo** (`average_volume_10d_calc`) | **Derivado** (últimos 10 candles diários) | *Ausente* | N/A | Média de volume diário dos últimos 10 pregões. |
| `averageDailyVolume3Month` | `Long` | **Nativo** (`average_volume_90d_calc`) | **Derivado** (últimos 63 candles diários) | *Ausente* | N/A | Média de volume diário de ~3 meses (63 pregões). |
| `twoHundredDayAverage` | `Double` | **Nativo** (`SMA200`) | **Derivado** (média de 200 candles diários) | *Ausente* | N/A | Média móvel simples de 200 pregões (SMA200). |
| `twoHundredDayAverageChange` | `Double` | **Derivado** (`price - SMA200`) | **Derivado** (`price - SMA200`) | *Ausente* | N/A | Distância em R$ do preço atual para a SMA200. |
| `twoHundredDayAverageChangePercent` | `Double` | **Derivado** (`(price - SMA200) / SMA200`) | **Derivado** (`(price - SMA200) / SMA200`) | *Ausente* | N/A | Distância percentual para a SMA200. |
| `fiftyTwoWeekHigh` | `Double` | **Nativo** (`price_52_week_high`) | **Nativo** (`meta.fiftyTwoWeekHigh`) | **Nativo** (`fiftyTwoWeekHigh`) | N/A | Preço máximo das últimas 52 semanas. |
| `fiftyTwoWeekLow` | `Double` | **Nativo** (`price_52_week_low`) | **Nativo** (`meta.fiftyTwoWeekLow`) | **Nativo** (`fiftyTwoWeekLow`) | N/A | Preço mínimo das últimas 52 semanas. |
| `fiftyTwoWeekRange` | `String` | **Derivado** (`52wLow - 52wHigh`) | **Derivado** (`52wLow - 52wHigh`) | **Nativo / Derivado** | N/A | Faixa de preço de 52 semanas formatada `min - max`. |
| `fiftyTwoWeekHighChange` | `Double` | **Derivado** (`price - 52wHigh`) | **Derivado** (`price - 52wHigh`) | **Derivado** (`price - 52wHigh`) | N/A | Variação em R$ em relação à máxima de 52 semanas. |
| `fiftyTwoWeekHighChangePercent` | `Double` | **Derivado** (`(price - 52wHigh) / 52wHigh`) | **Derivado** (`(price - 52wHigh) / 52wHigh`) | **Derivado** (`(price - 52wHigh) / 52wHigh`) | N/A | Variação percentual em relação à máxima de 52 semanas. |
| `fiftyTwoWeekLowChange` | `Double` | **Derivado** (`price - 52wLow`) | **Derivado** (`price - 52wLow`) | **Derivado** (`price - 52wLow`) | N/A | Variação em R$ em relação à mínima de 52 semanas. |
| `marketCap` | `Long` | **Nativo** (`market_cap_basic`) | *Ausente no v8 chart* | **Nativo** (`marketCap`) | N/A | Capitalização de mercado em R$ (retorna `null` para FIIs/ETFs). |
| `dividendRate` | `Double` | **Nativo** (`dps_common_stock_prim_issue_fy`) | **Derivado** (soma dos proventos dos últimos 12m) | *Ausente* | **Derivado** (soma trailing 12m) | Provento anualizado por ação em R$. |
| `dividendYield` | `Double` | **Nativo** (`dividends_yield_current` / `yield`) | **Derivado** (`(dividendRate / price) * 100`) | *Ausente* | **Derivado** (`(dividendRate / price) * 100`) | Dividend Yield anualizado em %. |
| `trailingAnnualDividendRate` | `Double` | **Nativo** (espelho de `dividendRate`) | **Derivado** (`events.dividends` 12m) | *Ausente* | **Derivado** (soma trailing 12m) | Provento dos últimos 12 meses em R$. |
| `trailingAnnualDividendYield` | `Double` | **Nativo** (espelho de `dividendYield`) | **Derivado** (`events.dividends` 12m) | *Ausente* | **Derivado** (soma trailing 12m) | Dividend Yield dos últimos 12 meses em %. |
| `historicalQuotes` | `List<HistoricalQuote>` | *Não suportado* | **Nativo** (série temporal diária OHLCV) | **Nativo** (quando solicitado via query) | N/A | Lista de candles históricos de preço e volume. |
| `dividends` | `DividendsData` | *Não suportado* | *Resumido* (`events.dividends`) | *Plano pago* | **Nativo e Completo** | Detalhamento oficial com proventos em dinheiro e ações (datas COM, EX e pagamento). |

### Conferência com o Relatório Preliminar:
1. **TradingView Scanner:** Confirmado como primário em lote mais eficiente (~350ms para dezenas de ações). Traz SMA200, médias de volume de 10 e 90 dias, extremos de 52 semanas e indicadores de dividendo direto para Ações e FIIs.
2. **Yahoo Chart v8:** Confirmado como fallback 1. Ao solicitar `range=1y&interval=1d&events=div`, fornece no mesmo request os metadados do pregão, as velas diárias para cálculo do SMA200 e médias de volume, e o histórico de proventos dos últimos 12 meses.
3. **brapi.dev:** Confirmado como fallback 2 (1 ticker por requisição, `Semaphore(1)` de concorrência).
4. **Proxy B3 (`DividendsFetcher`):** Confirmado como a única fonte gratuita 100% confiável para a árvore detalhada de proventos (`DividendsData`).

---

## 3. Catálogo de Campos Derivados e Regras de Negócio

Para garantir máxima completude mesmo quando um provedor não fornece diretamente um campo, o componente [`QuoteEnricher`](file:///home/jaoow/IdeaProjects/CotaTrack/api/src/main/java/dev/jaoow/cotatrack/api/provider/enricher/QuoteEnricher.java) aplica as seguintes derivações automatizadas:

### 3.1. Derivações Intra-Quote (Aritméticas)
- **`regularMarketDayRange`**:
  $$\text{dayRange} = \text{regularMarketDayLow} \text{ + " - " + } \text{regularMarketDayHigh}$$
- **`regularMarketPreviousClose`** (quando ausente):
  $$\text{previousClose} = \text{regularMarketPrice} - \text{regularMarketChange}$$
- **`regularMarketChange`** (quando ausente):
  $$\text{change} = \text{regularMarketPrice} - \text{regularMarketPreviousClose}$$
- **`regularMarketChangePercent`** (quando ausente):
  $$\text{changePercent} = \frac{\text{change}}{\text{previousClose}} \times 100$$
- **`fiftyTwoWeekRange`**:
  $$\text{52wRange} = \text{fiftyTwoWeekLow} \text{ + " - " + } \text{fiftyTwoWeekHigh}$$
- **`fiftyTwoWeekHighChange`**:
  $$\text{highChange} = \text{regularMarketPrice} - \text{fiftyTwoWeekHigh}$$
- **`fiftyTwoWeekHighChangePercent`**:
  $$\text{highChangePercent} = \frac{\text{regularMarketPrice} - \text{fiftyTwoWeekHigh}}{\text{fiftyTwoWeekHigh}}$$
- **`fiftyTwoWeekLowChange`**:
  $$\text{lowChange} = \text{regularMarketPrice} - \text{fiftyTwoWeekLow}$$
- **`twoHundredDayAverageChange`**:
  $$\text{sma200Change} = \text{regularMarketPrice} - \text{twoHundredDayAverage}$$
- **`twoHundredDayAverageChangePercent`**:
  $$\text{sma200ChangePercent} = \frac{\text{regularMarketPrice} - \text{twoHundredDayAverage}}{\text{twoHundredDayAverage}}$$

### 3.2. Derivações a partir do Histórico de Velas Diárias (Candles OHLCV)
Quando as cotações históricas estão disponíveis (obtidas via Yahoo Chart v8 ou parâmetros de histórico):
- **`twoHundredDayAverage` (SMA200)**:
  Média aritmética dos preços de fechamento das últimas 200 sessões de negociação válidas.
- **`averageDailyVolume10Day`**:
  Média aritmética do volume negociado nas últimas 10 sessões de negociação.
- **`averageDailyVolume3Month`**:
  Média aritmética do volume negociado nas últimas 63 sessões de negociação (~3 meses corridos de mercado).
- **`fiftyTwoWeekHigh` / `fiftyTwoWeekLow`**:
  Preço máximo e preço mínimo apurados nas últimas 252 sessões de negociação (~1 ano útil).

### 3.3. Derivação de Dividendos e Proventos
- Se o provedor fornecer `dividendYield` mas não `dividendRate`:
  $$\text{dividendRate} = \text{regularMarketPrice} \times \left(\frac{\text{dividendYield}}{100}\right)$$
- Se o provedor fornecer `dividendRate` mas não `dividendYield`:
  $$\text{dividendYield} = \left(\frac{\text{dividendRate}}{\text{regularMarketPrice}}\right) \times 100$$
- **Agregação B3 Proxy:** Se a consulta solicitar inclusão de dividendos (`includeDividends=true`) e a cotação não tiver os campos de proventos preenchidos, o [`QuoteEnricher`](file:///home/jaoow/IdeaProjects/CotaTrack/api/src/main/java/dev/jaoow/cotatrack/api/provider/enricher/QuoteEnricher.java) filtra os proventos em dinheiro (`CashDividend`) da classe de ação correspondente (ON/PN/Unit) aprovados ou com data COM nos últimos 365 dias, calculando a soma e a taxa anualizada.

---

## 4. Nulabilidade e Representação de Campos Ausentes

### 4.1. Diagnóstico do Problema Anterior
Anteriormente, o modelo [`Quote`](file:///home/jaoow/IdeaProjects/CotaTrack/api/src/main/java/dev/jaoow/cotatrack/api/requests/quotes/model/Quote.java) utilizava tipos primitivos do Java (`double`, `long`). Como primitivos em Java nunca são nulos, campos não preenchidos assumiam automaticamente `0.0` e `0L`.
Na serialização JSON, isso gerava respostas enganosas para o cliente consumidor:
- Ações ou fundos sem dividendos informados apareciam como `"dividendYield": 0.0` (parecendo que o yield da empresa era 0%).
- Fundos Imobiliários e ETFs que não possuem capitalização básica apareciam como `"marketCap": 0`.
- Ativos recentes (IPOs) sem 200 pregões apareciam como `"twoHundredDayAverage": 0.0`.

### 4.2. Solução Implementada: Object Wrappers (`Double`, `Long`)
Refatoramos todos os atributos numéricos de [`Quote`](file:///home/jaoow/IdeaProjects/CotaTrack/api/src/main/java/dev/jaoow/cotatrack/api/requests/quotes/model/Quote.java) para seus respectivos wrappers orientados a objeto:
- `double` $\rightarrow$ `Double`
- `long` $\rightarrow$ `Long`

Combinado com a anotação de nível de classe `@JsonInclude(JsonInclude.Include.NON_NULL)`:
- **Campos com dados reais** são serializados normalmente com seus valores numéricos.
- **Campos ausentes ou não aplicáveis** permanecem `null` em memória e são omitidos do payload JSON (ou serializados como `null` conforme a configuração de serialização do consumidor).

### 4.3. Comparativo de Alternativas Avaliadas

| Abordagem | Vantagens | Desvantagens | Veredito |
| :--- | :--- | :--- | :--- |
| **Object Wrappers (`Double`, `Long`)** | Mínima invasividade; 100% compatível com JavaBeans e Jackson; boxing automático no código Java; nulo expressa ausência. | Possibilidade de `NullPointerException` se o consumidor Java fizer unboxing sem checar nulidade. | **Adotada (Recomendada).** |
| **`Optional<Double>` / `Optional<Long>`** | Força em tempo de compilação o tratamento de valor ausente. | Invasivo; viola a convenção JavaBeans; requer módulo adicional `jackson-datatype-jdk8`; deserialização JSON complexa. | Descartada. |
| **Serializador Customizado (Custom Jackson Serializer)** | Mantém tipos primitivos na classe Java. | Complexo de manter; esconde o problema dentro da JVM; cálculos internos ainda tratariam ausência como `0.0`. | Descartada. |

### 4.4. Impacto em Quem Consome a API

1. **Clientes REST (JSON via Web / Mobile):**
   - **Impacto Positivo:** Clientes agora podem diferenciar com precisão se um valor é zero real (ex: variação de 0.0% em dia estável) ou se o dado não existe para aquele ativo (ex: `dividendYield` de um ETF como `BOVA11`).
   - **Compatibilidade:** Em TypeScript/JavaScript, verificações idiomáticas como `if (quote.dividendYield != null)` ou operadores de coalescência nula `quote.dividendYield ?? "N/D"` funcionam como esperado.
2. **Consumidores do SDK Java (Biblioteca `api`):**
   - Os métodos `getter` retornam instâncias de `Double` e `Long`.
   - Se o código consumidor realizar unboxing direto para primitivo (`double price = quote.getRegularMarketPrice()`), os campos essenciais de preço e volume estão sempre garantidos pelos provedores; no entanto, campos opcionais de dividendos ou médias móveis devem ser checados com `!= null` para prevenir `NullPointerException`.

---

## 5. Exemplo de Resposta JSON da API

### Ação com Dados Completos (`PETR4.SA`):
```json
{
  "symbol": "PETR4.SA",
  "currency": "BRL",
  "shortName": "PETR4",
  "longName": "PETROLEO BRASILEIRO S.A. PETROBRAS",
  "logoUrl": "https://s3-symbol-logo.tradingview.com/petrobras--big.svg",
  "regularMarketPrice": 58.08,
  "regularMarketChange": -1.6,
  "regularMarketChangePercent": -2.68,
  "regularMarketTime": "2026-10-19T18:45:00.000+00:00",
  "regularMarketOpen": 59.3,
  "regularMarketDayHigh": 59.3,
  "regularMarketDayLow": 58.02,
  "regularMarketDayRange": "58.02 - 59.30",
  "regularMarketVolume": 52693359,
  "regularMarketPreviousClose": 59.68,
  "averageDailyVolume10Day": 52693359,
  "averageDailyVolume3Month": 41439432,
  "twoHundredDayAverage": 43.033,
  "twoHundredDayAverageChange": 15.047,
  "twoHundredDayAverageChangePercent": 0.3497,
  "fiftyTwoWeekHigh": 59.68,
  "fiftyTwoWeekLow": 29.47,
  "fiftyTwoWeekRange": "29.47 - 59.68",
  "fiftyTwoWeekHighChange": -1.6,
  "fiftyTwoWeekHighChangePercent": -0.0268,
  "fiftyTwoWeekLowChange": 28.61,
  "dividendRate": 3.1768,
  "dividendYield": 6.11,
  "trailingAnnualDividendRate": 3.1768,
  "trailingAnnualDividendYield": 6.11,
  "marketCap": 811810341001
}
```

### ETF sem Proventos e sem MarketCap (`BOVA11.SA`):
Note que campos como `dividendYield`, `dividendRate` e `marketCap` não aparecem com falsos `0.0`:
```json
{
  "symbol": "BOVA11.SA",
  "currency": "BRL",
  "shortName": "BOVA11",
  "longName": "ISHARES IBOVESPA FUNDO DE INDICE",
  "logoUrl": "https://s3-symbol-logo.tradingview.com/ishares--big.svg",
  "regularMarketPrice": 201.54,
  "regularMarketChange": -0.76,
  "regularMarketChangePercent": -0.38,
  "regularMarketTime": "2026-10-19T18:45:00.000+00:00",
  "regularMarketOpen": 202.3,
  "regularMarketDayHigh": 204.24,
  "regularMarketDayLow": 201.36,
  "regularMarketDayRange": "201.36 - 204.24",
  "regularMarketVolume": 6782525,
  "regularMarketPreviousClose": 202.3,
  "averageDailyVolume10Day": 6782525,
  "averageDailyVolume3Month": 3794236,
  "twoHundredDayAverage": 177.1418,
  "twoHundredDayAverageChange": 24.3982,
  "twoHundredDayAverageChangePercent": 0.1377,
  "fiftyTwoWeekHigh": 207.0,
  "fiftyTwoWeekLow": 140.81,
  "fiftyTwoWeekRange": "140.81 - 207.00",
  "fiftyTwoWeekHighChange": -5.46,
  "fiftyTwoWeekHighChangePercent": -0.0264,
  "fiftyTwoWeekLowChange": 60.73
}
```

---

## 5. Arquitetura de Cache e Coalescência em Lote (Fase 4)

A partir da Fase 4, o CotaTrack adota uma estratégia multi-camada com o Caffeine Cache e SingleFlight:

### 5.1 TTLs por Tipo de Dado (Configuráveis)

| Categoria de Dado | TTL Padrão | Propriedade de Configuração | Finalidade |
| :--- | :--- | :--- | :--- |
| **Cotação Intraday** | `5m` (3-5 min) | `cotatrack.cache.quote-intraday-ttl` | Preços de negociação em tempo real na B3 |
| **Histórico Diário** | `12h` (6-12 h) | `cotatrack.cache.history-daily-ttl` | Velas OHLCV diárias para gráficos e cálculo de indicadores |
| **Proventos (Dividendos)** | `24h` | `cotatrack.cache.dividends-ttl` | Dados corporativos da B3 (JCP, dividendos, rendimentos FII) |
| **Logos & Cadastro** | `7d` (7 dias) | `cotatrack.cache.logo-ttl` | Metadados institucionais e logos SVG/PNG |
| **Disaster-Recovery (Stale)** | `24h` | `cotatrack.cache.stale-ttl` | Cotações anteriores servidas em caso de indisponibilidade geral |

Os valores suportam formatos legíveis por humanos: `"5m"`, `"300s"`, `"12h"`, `"24h"`, `"7d"`.

### 5.2 Agrupamento em Lote (Batching)
Ao consultar `/api/quote/{symbols}`:
1. Símbolos já presentes no cache intraday são devolvidos imediatamente sem chamadas de rede.
2. Símbolos **não cacheados** são agrupados e enviados em **uma única requisição POST** ao scanner do TradingView (`scanner.tradingview.com/brazil/scan` com `tickers: [...]`), evitando $N$ requisições pontuais.

### 5.3 SingleFlight (Thundering Herd / Cache Stampede Prevention)
Múltiplas requisições simultâneas para o mesmo símbolo (ex: 10 threads consultando `PETR4` ao mesmo tempo) disparam **apenas uma chamada ao provedor**.
As outras 9 threads aguardam e compartilham a mesma resposta do `CompletableFuture`, sem duplicar tráfego externo.

---

## 6. Provedor de Câmbio e Forex (BCB PTAX & Fallback) (Fase 5)

No endpoint `/api/forex/{symbols}`, as cotações são atendidas primariamente pelo **Banco Central do Brasil** através da API aberta **Olinda PTAX**, com fallback transparente para o **Yahoo Finance** e proteção por cache Caffeine.

### 6.1 Moedas Suportadas

São suportadas as 10 moedas oficiais da grade PTAX do Banco Central, negociadas contra o Real (`BRL`):

| Código | Moeda | Formatos e Aliases Aceitos | Par Yahoo (Fallback) |
| :--- | :--- | :--- | :--- |
| `USD` | Dólar dos Estados Unidos | `USD`, `USDBRL`, `USD-BRL`, `USDBRL=X`, `BRL=X` | `USDBRL=X` |
| `EUR` | Euro | `EUR`, `EURBRL`, `EUR-BRL`, `EURBRL=X` | `EURBRL=X` |
| `GBP` | Libra Esterlina | `GBP`, `GBPBRL`, `GBP-BRL`, `GBPBRL=X` | `GBPBRL=X` |
| `CAD` | Dólar Canadense | `CAD`, `CADBRL`, `CAD-BRL`, `CADBRL=X` | `CADBRL=X` |
| `AUD` | Dólar Australiano | `AUD`, `AUDBRL`, `AUD-BRL`, `AUDBRL=X` | `AUDBRL=X` |
| `CHF` | Franco Suíço | `CHF`, `CHFBRL`, `CHF-BRL`, `CHFBRL=X` | `CHFBRL=X` |
| `JPY` | Iene Japonês | `JPY`, `JPYBRL`, `JPY-BRL`, `JPYBRL=X` | `JPYBRL=X` |
| `DKK` | Coroa Dinamarquesa | `DKK`, `DKKBRL`, `DKK-BRL`, `DKKBRL=X` | `DKKBRL=X` |
| `NOK` | Coroa Norueguesa | `NOK`, `NOKBRL`, `NOK-BRL`, `NOKBRL=X` | `NOKBRL=X` |
| `SEK` | Coroa Sueca | `SEK`, `SEKBRL`, `SEK-BRL`, `SEKBRL=X` | `SEKBRL=X` |

### 6.2 Validação e Erro para Moedas Não Cobertas
Se um símbolo não suportado for enviado (ex: `BTC`, `CNY`, `XYZ`), a API rejeita com **HTTP 400 Bad Request** e mensagem descritiva:
```json
{
  "error": true,
  "message": "Moeda ou par não suportado: 'XYZ'. Pares suportados pelo Banco Central (PTAX): USD, EUR, GBP, CAD, AUD, CHF, JPY, DKK, NOK, SEK (ex: USD, USDBRL, EUR, EURBRL, GBP, JPY)."
}
```

### 6.3 Resiliência e Fallback
1. O provedor primário [`BcbPtaxFxProvider`](file:///home/jaoow/IdeaProjects/CotaTrack/api/src/main/java/dev/jaoow/cotatrack/api/provider/forex/BcbPtaxFxProvider.java) consulta o serviço Olinda PTAX (`CotacaoMoedaPeriodo`) com janela móvel retroativa de 7 dias, garantindo cotação oficial mesmo em fins de semana, feriados e manhãs antes do primeiro boletim diário.
2. Para o dólar (`USD`), existe fallback interno secundário via SGS série 1 (`api.bcb.gov.br/dados/serie/bcdata.sgs.1`).
3. Se o Banco Central estiver indisponível ou retornar erro, o [`FxOrchestrator`](file:///home/jaoow/IdeaProjects/CotaTrack/api/src/main/java/dev/jaoow/cotatrack/api/provider/forex/FxOrchestrator.java) redireciona imediatamente para o [`YahooFxProvider`](file:///home/jaoow/IdeaProjects/CotaTrack/api/src/main/java/dev/jaoow/cotatrack/api/provider/forex/YahooFxProvider.java) (Yahoo v8 Chart e v7 Quote), preenchendo o campo `provider: "YAHOO (fallback)"`.

---

## 7. Observabilidade, Saúde e Diagnóstico (Fase 6)

> STOCKS usa uma cadeia US independente com Twelve Data. A matriz B3 e seus fallbacks
> descritos acima não se aplicam a `/api/stocks/**`.
> Veja [STOCKS: cobertura, contratos e configuração](STOCKS.md) para a integração implementada,
> os campos equivalentes, limites por plano, feed parcial e direitos de redistribuição.

A API disponibiliza endpoints de diagnóstico e saúde em `/api/health` e `/api/diagnostics` gerenciados pelo [`DiagnosticsController`](file:///home/jaoow/IdeaProjects/CotaTrack/rest/src/main/java/dev/jaoow/cotatrack/rest/controller/DiagnosticsController.java) e monitorados em tempo de execução pelo [`ProviderHealthTracker`](file:///home/jaoow/IdeaProjects/CotaTrack/api/src/main/java/dev/jaoow/cotatrack/api/provider/health/ProviderHealthTracker.java):

### 7.1 Métricas Rastreadas por Provedor
Para cada provedor (TradingView, Yahoo Chart, brapi.dev, BCB PTAX, Yahoo FX):
- **Status Operacional:** `HEALTHY`, `DEGRADED`, `CIRCUIT_OPEN` ou `DISABLED`.
- **Circuit Breaker:** Estado em tempo real do disjuntor Resilience4j (`CLOSED`, `OPEN`, `HALF_OPEN`).
- **Timestamps:** Data/hora ISO-8601 da última requisição com sucesso (`lastSuccess`) e da última falha (`lastFailure`).
- **Motivo de Falha Sanitizado:** Descrição resumida da última exceção com remoção rigorosa de quaisquer parâmetros sensíveis (`token=`, `cookie=`, `crumb=`, etc.).
- **Contadores de Tráfego:** Total de requisições disparadas, requisições bem-sucedidas e requisições falhadas.

### 7.2 Métricas de Cache em Memória
Exposição em tempo real do tamanho estimado de todas as tabelas de cache Caffeine:
- `intradayQuotes` (TTL 3-5m)
- `historicalQuotes` (TTL 6-12h)
- `dividends` (TTL 24h)
- `logos` (TTL 7d)
- `forexQuotes` (TTL 5m)
- `staleDisasterQuotes` (TTL 24h)


