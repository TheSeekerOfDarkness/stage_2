# Stage 2: Building a Service-Oriented Architecture - Search Engine Project

**Big Data — Grado en Ciencia e Ingeniería de Datos (ULPGC)**

## Overview

This repository implements **Stage 2 (Building a Service-Oriented Architecture)** of the Big Data Search Engine project. The monolithic data pipeline from Stage 1 has been re-engineered into a distributed, service-oriented architecture (SOA). The system now consists of independent, cooperative microservices built with Java 17 and Javalin, communicating via RESTful APIs and coordinated through a time-based orchestration mechanism.

---

## Architecture

```text
┌───────────────┐       ┌───────────────────────────┐
│ P. Gutenberg  │ ───>  │     Ingestion Service     │ (Port 7001)
└───────────────┘       │  (Downloads & splitting)  │
                        └─────────────┬─────────────┘
                                      │
                                      ▼
                        ┌───────────────────────────┐
                        │         Datalake          │
                        │     (Unstructured)        │
                        │     YYYYMMDD/HH/*.txt     │
                        └─────────────┬─────────────┘
                                      │
                         [Time-based Coordination]
                         (Background polling sync)
                                      │
                                      ▼
                        ┌───────────────────────────┐
                        │     Indexing Service      │ (Port 7002)
                        │ (Tokenizer & extraction)  │
                        └─────────────┬─────────────┘
                                      │
                                      ▼
                        ┌───────────────────────────┐
                        │         Datamarts         │
                        │       (datamart.db)       │
                        │  books & inverted_index   │
                        └─────────────┬─────────────┘
                                      │
                                      ▼
┌───────────────┐       ┌───────────────────────────┐
│   Front-End   │ <───  │      Search Service       │ (Port 7003)
│    (User)     │       │ (REST API with filters)   │
└───────────────┘       └───────────────────────────┘
```

### 1. Microservices

The architecture decomposes the monolithic pipeline into three standalone Java/Javalin microservices, each packaged as an executable fat-JAR:

- **Ingestion Service**: Exposes internal HTTP endpoints to download books from Project Gutenberg. It processes the raw text into `header` and `body` files and writes them atomically to the datalake.
- **Indexing Service**: Coordinates the pipeline by polling the datalake for new, unindexed books. It parses metadata, tokenizes the text (filtering 127 stop words), and safely updates the datamart using SQLite transactions and WAL mode.
- **Search Service**: Exposes the Front-End-facing API. It allows keyword searching and supports combinatorial filtering (by `author`, `language`, and `year`) and result pagination.

### 2. Coordination Mechanism

The system implements a **Time-based Coordination (Hybrid)** mechanism. 
The `Indexing Service` runs a background `ScheduledExecutorService` that periodically polls the datalake directory structure. It detects newly fully-downloaded books, compares them against the indexed records in the SQLite database, and automatically indexes any pending books. This removes the need for manual indexing triggers while keeping services decoupled.

### 3. Storage Layer

- **Datalake (`datalake/`)**: Preserves the time-based hierarchical structure (`YYYYMMDD/HH/`) selected as the optimal physical design during Stage 1. 
- **Datamart (`datamarts/datamart.db`)**: Unified SQLite database operating in `WAL` (Write-Ahead Logging) mode to allow concurrent reads from the Search Service while the Indexing Service writes. It contains two tables: `books` (metadata) and `inverted_index` (terms and book references).

---

## Directory Structure

```text
.
├── datalake/                       # Unstructured raw text storage (Time-based)
├── datamarts/                      # Structured SQL storage (datamart.db)
├── ingestion-service/              # Maven project for Ingestion
│   ├── pom.xml
│   └── src/main/java/.../ingestion/App.java
├── indexing-service/               # Maven project for Indexing
│   ├── pom.xml
│   ├── src/main/java/.../indexing/App.java
│   └── src/test/java/.../indexing/IndexerBenchmark.java
├── search-service/                 # Maven project for Search
│   ├── pom.xml
│   ├── src/main/java/.../search/App.java
│   └── src/test/java/.../search/SearchBenchmark.java
├── openapi.yaml                    # API Specification for all services
├── docker-compose.yml              # Optional containerization config
└── README.md
```

---

## Setup & Installation

### Requirements
- Java 17+ (JDK)
- Apache Maven (3.8+)

### Build the Microservices
Navigate to the root directory and package the three independent fat-JARs using Maven:

```bash
mvn -f ingestion-service/pom.xml clean package
mvn -f indexing-service/pom.xml clean package
mvn -f search-service/pom.xml clean package
```

---

## Usage (Running the SOA)

Because the services share the `datalake` and `datamarts` folders, **all services must be executed from the repository's root directory** in separate terminal windows.

### 1. Start the Services

**Terminal 1 (Ingestion Service - Port 7001):**
```bash
java -jar ingestion-service/target/ingestion-service-1.0.0.jar
```

**Terminal 2 (Indexing Service & Coordinator - Port 7002):**
```bash
java -jar indexing-service/target/indexing-service-1.0.0.jar
```

**Terminal 3 (Search Service - Port 7003):**
```bash
java -jar search-service/target/search-service-1.0.0.jar
```

### 2. Pipeline Execution Example

**A. Ingest a new book:**
Trigger the ingestion service to download a book (e.g., *Alice in Wonderland*, ID: 11):
```bash
curl -X POST http://localhost:7001/ingest/11
```

**B. Automatic Coordination:**
Wait up to 30 seconds. The background coordinator in the Indexing Service will automatically detect the new book in the datalake, extract its metadata, build the inverted index, and safely commit it to `datamart.db`. Check the status:
```bash
curl http://localhost:7002/index/status/11
```

**C. Search via Front-End API:**
Query the search service using keywords and optional filters (pagination, language, author, year):
```bash
curl "http://localhost:7003/search?q=alice&language=en&limit=5"
```

---

## API Specification

The full API contract for both internal (Service-to-Service) and external (Front-End) interactions is documented in the **`openapi.yaml`** file included in the root of the repository. It can be visualized using Swagger UI or any OpenAPI-compatible viewer.

---

## Benchmarking & Tests (JMH)

Stage 2 replaces Python benchmarks with standard Java testing and the **Java Microbenchmark Harness (JMH)** to evaluate isolated computational efficiency and concurrency behavior.

### 1. Running Unit Tests
Execute standard JUnit tests for metadata parsing, tokenization, and SQL engine logic:
```bash
mvn -f indexing-service/pom.xml test
mvn -f search-service/pom.xml test
```

### 2. Running JMH Microbenchmarks
Microbenchmarks measure pure algorithmic throughput (e.g., indexing speed, query latency). You can run them directly from VSCode (by clicking "Run Benchmark" over the class declaration) or via Maven:
```bash
mvn -f indexing-service/pom.xml test -Dtest=IndexerBenchmark
mvn -f search-service/pom.xml test -Dtest=SearchBenchmark
```
