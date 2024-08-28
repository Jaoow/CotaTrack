
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

2. Compile o projeto usando Maven:

   ```bash
   mvn clean install -P with-rest
   ```

3. Execute o aplicativo:

   ```bash
   cd rest
   mvn spring-boot:run
   ```

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


## 🛠️ Tecnologias Utilizadas

- **Java 11**
- **Spring Boot**
- **Maven**
- **Lombok** para simplificação de código
- **Jackson** para manipulação de JSON
- **Caffeine** para cache de dados

## 🤝 Contribuição

1. Faça um fork do projeto.
2. Crie uma branch para sua feature (`git checkout -b feature/AmazingFeature`).
3. Faça commit de suas alterações (`git commit -m 'Add some AmazingFeature'`).
4. Envie para a branch (`git push origin feature/AmazingFeature`).
5. Abra um Pull Request.

## 📄 Licença

Este projeto é licenciado sob a [MIT License](LICENSE).
