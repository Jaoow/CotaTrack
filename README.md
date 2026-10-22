
# 📊 CotaTrack

## 📋 Descrição

CotaTrack é uma API RESTful construída em Java com Spring Boot, projetada para fornecer cotações de ações e forex (FX) em tempo real. O sistema permite realizar consultas detalhadas de cotações, listar ações disponíveis, e obter informações históricas com base em diferentes parâmetros. CotaTrack é ideal para desenvolvedores que desejam integrar dados financeiros em suas aplicações.

## ✨ Funcionalidades

- 🟢 Obtenção de cotações de ações em tempo real.
- 💱 Consulta de cotações de Forex (FX).
- 📄 Listagem de ações com filtros opcionais.
- 🔍 Suporte para múltiplos símbolos de ações e forex em uma única consulta.
- ⚙️ Parâmetros customizáveis para intervalos, datas e dividendos.

## 🛠️ Pré-requisitos

- **Java 17** ou superior
- **Maven** para gerenciamento de dependências

## 📝 Instalação

1. Clone o repositório:

   ```bash
   git clone https://github.com/Jaoow/CotaTrack.git
   cd CotaTrack
   ```

2. Compile o projeto a partir da raiz do repositório. O perfil `with-rest` inclui os módulos `api` e `rest` na compilação e instala primeiro o POM pai e a API, que são dependências do módulo REST:

   ```bash
   mvn clean install -P with-rest
   ```

3. Execute o aplicativo:

   ```bash
   cd rest
   mvn spring-boot:run
   ```

> Execute o comando Maven da etapa 2 em `CotaTrack/`, e não dentro de `api/` ou `rest/`.
> Se os módulos forem compilados separadamente, instale primeiro o projeto pai e a API:
>
> ```bash
> mvn clean install
> cd rest
> mvn clean install
> mvn spring-boot:run
> ```

### Coleção Bruno

Uma coleção Bruno com cenários de sucesso, filtros, paginação e respostas de erro está disponível em `bruno/cotatrack`. Importe essa pasta no Bruno, selecione o ambiente `local` e execute a aplicação em `http://localhost:8080`.

## 🚀 Como Usar

### 🔗 Endpoints

1. **Obter Cotações de Ações**

   ```
   GET /api/quote/{symbols}
   ```

    - **Parâmetros**:
        - `symbols`: (obrigatório) Símbolos das ações, separados por vírgula.
        - `from`: (opcional) Data de início (YYYY-MM-DD).
        - `to`: (opcional) Data de término (YYYY-MM-DD).
        - `interval`: (opcional) Intervalo de dados (ex: 1d, 1wk).
        - `range`: (opcional) Intervalo de tempo (ex: 1mo, 3mo).
        - `dividends`: (opcional) Incluir dividendos (true/false).
    - **Resposta**: JSON com as cotações das ações.

2. **Obter Cotações de Forex (FX)**

   ```
   GET /api/forex/{symbols}
   ```

    - **Parâmetros**:
        - `symbols`: (obrigatório) Símbolos das moedas, separados por vírgula.
    - **Resposta**: JSON com as cotações de forex.

3. **Listar Ações**

   ```
   GET /api/quote/list
   ```

    - **Parâmetros**:
        - `search`: (opcional) Termo de busca.
        - `type`: (opcional) Tipo de ação.
        - `sortBy`: (opcional) Ordenar por (ex: name, close).
        - `sortOrder`: (opcional) Ordem de classificação (asc/desc).
        - `limit`: (opcional) Limite de resultados.
    - **Resposta**: JSON com a lista de ações.


## 🌐 Arquitetura de Provedores e Ordem de Fallback

O CotaTrack conta com uma arquitetura orquestrada de alta disponibilidade e tolerância a falhas baseada em Resilience4j (Circuit Breakers e Rate Limiters), cache multi-camada e resgate de emergência via *stale cache*:

### 📈 Cotações de Ações e Histórico B3 (`/api/quote/{symbols}`)
1. **TradingView Scanner (Primário - Em Lote)**
   - **Tecnologia:** Scanner REST JSON aberto (`POST /brazil/scan`).
   - **Vantagem:** Busca dezenas de símbolos em uma **única requisição HTTP** (~350ms). Traz cotação intraday, volume diário, médias históricas (SMA200, volume 10d e 90d), extremos de 52 semanas, logomarca oficial e indicadores de proventos.
   - **Sem autenticação:** 100% gratuito e sem necessidade de chave de API.
2. **Yahoo Chart v8 (Fallback Secundário)**
   - **Tecnologia:** Endpoint JSON (`GET /v8/finance/chart/{symbol}`).
   - **Resiliência:** Rotação de headers `User-Agent` modernos e injeção resiliente de cookie/crumb de sessão para contornar bloqueios HTTP 429/401.
   - **Enriquecimento:** Fornece histórico de velas OHLCV e eventos de dividendos para cálculo em tempo de execução dos campos derivados.
3. **brapi.dev (Fallback Terciário)**
   - **Tecnologia:** REST JSON com suporte a token gratuito.
   - **Proteção:** Executado com semáforo de concorrência unitária (`Semaphore(1)`) e rate limiter para respeitar limites do plano gratuito.
4. **Stale Cache Disaster Recovery (Último Recurso)**
   - Caso todos os provedores remotos estejam inacessíveis ou sofram instabilidade temporária, o orquestrador resgata a última cotação conhecida mantida no cache de emergência de 24h, garantindo continuidade do serviço com a flag `"stale": true`.

### 💰 Proventos e Dividendos Detalhados (`includeDividends=true`)
- **Proxy B3 Empresas Listadas (`DividendsFetcher`):**
  - Consulta direta aos dados públicos estruturados da B3, retornando a árvore oficial e completa de proventos em dinheiro (Dividendos e JCP com datas COM, EX e Pagamento) e proventos em ações (Bonificações e Desdobramentos).

### 💱 Câmbio e Moedas Internacionais (`/api/forex/{symbols}`)
1. **Banco Central do Brasil - BCB PTAX & SGS (Primário Oficial)**
   - **Olinda OData:** Consulta oficial das taxas PTAX de fechamento diário e boletins das 10 principais moedas globais cotadas contra o Real (`USD`, `EUR`, `GBP`, `CAD`, `AUD`, `CHF`, `JPY`, `DKK`, `NOK`, `SEK`).
   - **SGS (Sistema Gerenciador de Séries Temporais):** Consulta automatizada à Série 1 para cotação diária do Dólar Comercial.
   - **Validação de Pares:** Símbolos não suportados pelo BCB retornam imediatamente erro HTTP 400 com mensagem clara e catálogo de moedas cobertas.
2. **Yahoo Finance FX (Fallback Secundário)**
   - Acionado automaticamente se a API do Banco Central estiver temporariamente indisponível.

---

## 🔒 Limites Gratuitos e Rate Limits (Free Tier)

| Provedor | Tipo de Dado | Limite Gratuito / Política | Proteção no CotaTrack |
| :--- | :--- | :--- | :--- |
| **TradingView Scanner** | Ações B3, Índices, FIIs | Sem chave de API; requisições ilimitadas em uso moderado | Coalescência em lote (reduz 90%+ das requisições) |
| **Yahoo Chart v8** | Ações B3, Candles OHLCV | Sem chave de API; controle de abuse/IP | Circuit Breaker (50% de erro em 10 reqs) + Rate Limiter (15 req/s) |
| **brapi.dev** | Ações B3 | Plano Gratuito (~1 req/s com token pessoal gratuito) | `Semaphore(1)` + Rate Limiter (1 req/s) |
| **Banco Central (BCB)** | Câmbio PTAX / SGS | Dados Abertos do Governo (Sem limites restritivos) | Cache local de 5 min por par cambial |
| **B3 Proxy de Listados** | Proventos corporativos | Endpoint público da B3 | Cache local de 24 horas por símbolo |

---

## ⚙️ Variáveis de Ambiente e Configuração

Todas as variáveis podem ser configuradas via variáveis de ambiente do sistema operacional ou no arquivo `application.properties`:

| Variável de Ambiente | Propriedade Spring | Padrão | Descrição |
| :--- | :--- | :--- | :--- |
| `COTATRACK_CACHE_INTRADAY_MINUTES` | `cotatrack.cache.quote-intraday-ttl` | `5m` | Tempo de vida (TTL) do cache de cotações em tempo real |
| `COTATRACK_CACHE_HISTORY_HOURS` | `cotatrack.cache.history-daily-ttl` | `12h` | TTL do cache de velas e séries temporais históricas |
| `COTATRACK_CACHE_DIVIDENDS_HOURS` | `cotatrack.cache.dividends-ttl` | `24h` | TTL do cache de eventos e proventos de empresas |
| `COTATRACK_CACHE_LOGO_DAYS` | `cotatrack.cache.logo-ttl` | `7d` | TTL do cache de logotipos corporativos |
| `COTATRACK_CACHE_STALE_HOURS` | `cotatrack.cache.stale-ttl` | `24h` | Janela de retenção do cache de emergência (*stale disaster recovery*) |
| `BRAPI_TOKEN` | `cotatrack.brapi.token` | *(vazio)* | Token gratuito do brapi.dev (opcional) |
| `TRADINGVIEW_ENABLED` | `cotatrack.provider.tradingview.enabled` | `true` | Habilita/desabilita o provedor TradingView |
| `YAHOO_CHART_ENABLED` | `cotatrack.provider.yahoo-chart.enabled` | `true` | Habilita/desabilita o provedor Yahoo Chart |
| `BRAPI_ENABLED` | `cotatrack.provider.brapi.enabled` | `true` | Habilita/desabilita o provedor brapi.dev |

---

## 🩺 Observabilidade e Endpoint de Diagnóstico

A aplicação expõe um endpoint dedicado para monitoramento de integridade e saúde de todos os provedores em tempo real:

```http
GET /api/health
GET /api/diagnostics
```

### Exemplo de Resposta:
```json
{
  "status": "UP",
  "timestamp": "2026-10-19T21:15:00Z",
  "providers": [
    {
      "name": "TRADING_VIEW",
      "status": "HEALTHY",
      "enabled": true,
      "circuitBreakerState": "CLOSED",
      "lastSuccess": "2026-10-19T21:14:58Z",
      "lastFailure": null,
      "totalRequests": 142,
      "successfulRequests": 142,
      "failedRequests": 0
    },
    {
      "name": "YAHOO_CHART",
      "status": "HEALTHY",
      "enabled": true,
      "circuitBreakerState": "CLOSED",
      "lastSuccess": "2026-10-19T20:30:12Z",
      "lastFailure": null,
      "totalRequests": 5,
      "successfulRequests": 5,
      "failedRequests": 0
    },
    {
      "name": "BCB_PTAX",
      "status": "HEALTHY",
      "enabled": true,
      "circuitBreakerState": "N/A",
      "lastSuccess": "2026-10-19T21:12:30Z",
      "lastFailure": null,
      "totalRequests": 18,
      "successfulRequests": 18,
      "failedRequests": 0
    }
  ],
  "cacheStats": {
    "intradayQuotes": 28,
    "historicalQuotes": 12,
    "dividends": 15,
    "logos": 28,
    "forexQuotes": 4,
    "staleDisasterQuotes": 28
  }
}
```

> **Garantia de Segurança:** Mensagens de erro e detalhes de falha são estritamente sanitizados pelo `ProviderHealthTracker` para garantir que tokens, cookies, crumbs e parâmetros sensíveis nunca sejam expostos no diagnóstico.

---

## 📚 Documentação de Fontes, Cache e Dados

### Ações americanas (STOCKS)

O suporte US usa Twelve Data em uma cadeia independente da B3. Os endpoints existentes não mudam.
Por padrão a integração está desabilitada; habilite-a somente com uma chave e direitos de uso adequados:

```powershell
$env:COTATRACK_STOCKS_ENABLED = "true"
$env:TWELVE_DATA_API_KEY = "<sua-chave>"
```

```http
GET /api/stocks/AAPL,MSFT
GET /api/stocks/BRK.B?range=1y&interval=1d
GET /api/stocks/AAPL?includeDividends=true
GET /api/stocks/list?search=apple&sortBy=name&limit=20&page=0
```

As respostas incluem `metadata` por símbolo (feed, origem, cache, `stale`) e `errors` para falhas parciais.
O feed intraday padrão **não é consolidado**. Fundamentos e eventos corporativos dependem do plano;
o limite Basic de 8 créditos/minuto não comporta endpoints com peso maior sem ajuste do plano e da configuração.
A distribuição externa por API exige direitos específicos do fornecedor.

Veja [STOCKS: contratos, configuração, cobertura e testes](docs/STOCKS.md) e os exemplos em
[Bruno US Stocks](bruno/cotatrack/05%20US%20Stocks).

Para detalhes completos sobre a arquitetura multi-provedor (TradingView, Yahoo Chart, brapi.dev), a estratégia multi-camada de cache (TTLs configuráveis para cotações intraday 5m, histórico 12h, proventos 24h, logos 7d e stale 24h), coalescência Single-Flight para prevenção de *thundering herd*, fórmulas de campos derivados (SMA200, médias de volume, faixas de preço) e regras de nulabilidade/serialização, consulte:
- [Documentação de Fontes de Dados e Catálogo do Modelo Quote](docs/DATA_SOURCES.md)

## 🛠️ Tecnologias Utilizadas

- **Java 17**
- **Spring Boot 3.3.2**
- **Resilience4j** (Circuit Breakers e Rate Limiters por provedor)
- **Maven**
- **Lombok** para simplificação de código
- **Jackson** para manipulação e serialização de JSON (`@JsonInclude(NON_NULL)`)
- **Caffeine** para cache multi-camada com TTLs configuráveis e Single-Flight
- **JUnit 5 & AssertJ** para testes automatizados offline

## 🤝 Contribuição

1. Faça um fork do projeto.
2. Crie uma branch para sua feature (`git checkout -b feature/AmazingFeature`).
3. Faça commit de suas alterações (`git commit -m 'Add some AmazingFeature'`).
4. Envie para a branch (`git push origin feature/AmazingFeature`).
5. Abra um Pull Request.

## 📄 Licença

Este projeto é licenciado sob a [MIT License](LICENSE).
