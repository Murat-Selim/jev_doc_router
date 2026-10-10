# Progress — Spring Boot Basic RAG Project

## Project Goal

Bu proje, mevcut `Devs_Backend` reposundaki katmanlı Spring Boot yaklaşımına benzer şekilde geliştirilecek basit bir **RAG (Retrieval-Augmented Generation) Doküman Asistanı**dır.

Hedef:

- PDF doküman yükleme
- PDF içeriğini text olarak çıkarma
- Text'i chunk'lara ayırma
- Chunk embedding'lerini oluşturma
- PostgreSQL + pgvector üzerinde saklama
- Kullanıcı sorusuna göre semantic search yapma
- En alakalı chunk'ları LLM'e context olarak gönderme
- Dokümana dayalı cevap üretme

Bu proje ilk etapta küçük ve öğretici tutulacaktır. Multi-agent, MCP, Kafka, Redis, Kubernetes veya karmaşık mikroservis mimarileri MVP'ye dahil edilmeyecektir.

---

# 1. Architecture Reference

Mevcut `Devs_Backend` reposundaki ana yaklaşım:

```text
Presentation / WebApi
        |
        v
Business
        |
        v
DataAccess
        |
        v
Database

Entities = Domain / persistence models
Core     = Ortak teknik bileşenler
```

RAG projesine uyarlanmış akış:

```text
Client / Postman
      |
      v
webApi/controllers
      |
      v
business/abstracts
      |
      v
business/concretes
      |
      +-----------------------------+
      |                             |
      v                             v
business/rules              dataAccess/abstracts
                                    |
                                    v
                           PostgreSQL + pgvector
```

---

# 2. Planned Project Structure

```text
RagAssistant/
├── src/main/java/com/example/ragassistant/
│   ├── business/
│   │   ├── abstracts/
│   │   │   ├── DocumentService.java
│   │   │   ├── ChunkingService.java
│   │   │   ├── EmbeddingService.java
│   │   │   └── RagService.java
│   │   ├── concretes/
│   │   │   ├── DocumentManager.java
│   │   │   ├── ChunkingManager.java
│   │   │   ├── EmbeddingManager.java
│   │   │   └── RagManager.java
│   │   ├── requests/
│   │   │   ├── chatRequest/
│   │   │   │   └── ChatRequest.java
│   │   │   └── documentRequest/
│   │   │       └── DocumentUploadRequest.java
│   │   ├── responses/
│   │   │   ├── chatResponse/
│   │   │   │   └── ChatResponse.java
│   │   │   └── documentResponse/
│   │   │       └── DocumentUploadResponse.java
│   │   └── rules/
│   │       ├── DocumentBusinessRules.java
│   │       └── RagBusinessRules.java
│   ├── core/
│   │   ├── exceptions/
│   │   │   └── handlers/
│   │   │       └── GlobalExceptionHandler.java
│   │   ├── mappers/
│   │   │   └── documentMapper/
│   │   │       └── DocumentMapper.java
│   │   └── ai/
│   │       ├── config/
│   │       │   └── AiConfig.java
│   │       └── prompt/
│   │           └── RagPromptBuilder.java
│   ├── dataAccess/
│   │   └── abstracts/
│   │       └── DocumentChunkRepository.java
│   ├── entities/
│   │   └── concretes/
│   │       └── DocumentChunk.java
│   ├── webApi/
│   │   └── controllers/
│   │       ├── DocumentsController.java
│   │       └── ChatController.java
│   └── RagAssistantApplication.java
├── src/main/resources/
│   ├── application.properties
│   └── db/
│       └── init.sql
├── src/test/java/com/example/ragassistant/
│   └── RagAssistantApplicationTests.java
├── docker-compose.yml
├── pom.xml
├── .gitignore
├── .env.example
└── README.md
```

Bu yapı `Devs_Backend` reposundaki `business/abstracts`, `business/concretes`, `business/requests`, `business/responses`, `business/rules`, `dataAccess/abstracts`, `entities/concretes` ve `webApi/controllers` organizasyonunu RAG projesine taşır.

---

# 3. Layer Responsibilities

## business/abstracts

Servis interface'lerini içerir:

```text
DocumentService
ChunkingService
EmbeddingService
RagService
```

Controller katmanı mümkün olduğunca concrete sınıflara değil interface'lere bağımlı olur.

## business/concretes

Interface'lerin implementation sınıflarıdır:

```text
DocumentService  -> DocumentManager
ChunkingService  -> ChunkingManager
EmbeddingService -> EmbeddingManager
RagService       -> RagManager
```

Business orchestration burada yapılır.

## business/requests

API'ye gelen request DTO'ları:

```text
ChatRequest
DocumentUploadRequest
```

## business/responses

API'den dönen response DTO'ları:

```text
ChatResponse
DocumentUploadResponse
```

Entity nesneleri doğrudan response olarak döndürülmez.

## business/rules

Business validation kuralları burada tutulur.

### DocumentBusinessRules

- Dosya boş olamaz
- Dosya PDF olmalıdır
- Dosya boyutu sınırı aşmamalıdır
- PDF'ten text çıkarılabilmelidir

### RagBusinessRules

- Soru boş olamaz
- Database'de sorgulanabilir chunk bulunmalıdır
- Retrieval sonucu context üretilebilmelidir

## core/exceptions

Global exception handling:

```text
core/exceptions/handlers/
└── GlobalExceptionHandler.java
```

## core/mappers

DTO / entity dönüşümleri için kullanılır. İlk MVP'de manuel mapping yeterlidir; proje büyürse MapStruct eklenebilir.

## core/ai

RAG projesine özgü teknik AI bileşenleri:

```text
core/ai/
├── config/
└── prompt/
```

`AiConfig` AI provider, embedding ve Spring AI ayarlarını; `RagPromptBuilder` ise LLM prompt yapısını yönetir.

## dataAccess/abstracts

Repository interface'leri:

```text
DocumentChunkRepository
```

pgvector similarity search burada tanımlanır.

## entities/concretes

İlk MVP entity'si:

```text
DocumentChunk
```

Alanlar:

```text
id
documentName
content
embedding
createdAt
```

## webApi/controllers

REST controller'ları:

```text
DocumentsController
ChatController
```

Controller içinde repository erişimi, embedding üretimi, PDF parsing veya LLM orchestration yapılmaz.

---

# 4. Document Upload Flow

Endpoint:

```http
POST /api/documents
```

Akış:

```text
DocumentsController
        |
        v
DocumentService
        |
        v
DocumentManager
        |
        +--> DocumentBusinessRules
        +--> PDF Text Extraction
        +--> ChunkingService
        +--> EmbeddingService
        |
        v
DocumentChunkRepository
        |
        v
PostgreSQL + pgvector
```

Örnek response:

```json
{
  "message": "Document processed successfully",
  "documentName": "sample.pdf",
  "chunks": 18
}
```

---

# 5. Chat / RAG Flow

Endpoint (güncel: RAG/CAG otomatik seçilir):

```http
POST /api/chat/auto
```

Request:

```json
{
  "question": "Yıllık izin kaç gündür?"
}
```

Akış:

```text
ChatController
      |
      v
RagService
      |
      v
RagManager
      |
      +--> RagBusinessRules
      +--> EmbeddingService
      +--> DocumentChunkRepository
      +--> Vector Search
      +--> Top-K Chunks
      +--> RagPromptBuilder
      +--> LLM
      |
      v
ChatResponse
```

Response:

```json
{
  "answer": "Dokümana göre yıllık izin süresi 14 gündür."
}
```

---

# 6. Database

```text
PostgreSQL + pgvector
```

İlk tablo:

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE document_chunks (
    id BIGSERIAL PRIMARY KEY,
    document_name VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    embedding VECTOR(1536),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

Embedding dimension kullanılan modele göre güncellenecektir.

---

# 7. Planned Dependencies

```text
Java 21
Spring Boot 3.x
Spring Web
Spring Data JPA
PostgreSQL Driver
Validation
Apache PDFBox
Spring AI
pgvector
Lombok
```

İleride eklenebilir:

```text
MapStruct
SpringDoc OpenAPI
```

JWT / Spring Security MVP'ye dahil değildir.

---

# 8. Development Progress

## Phase 0 — Architecture

- [x] Proje amacı belirlendi
- [x] Basic RAG scope belirlendi
- [x] `Devs_Backend` mimarisi referans olarak seçildi
- [x] Layered monolith yaklaşımı seçildi
- [x] Business abstract / concrete ayrımı belirlendi
- [x] Request / Response DTO yapısı belirlendi
- [x] Business Rules katmanı belirlendi
- [x] DataAccess ve Entity katmanları belirlendi
- [x] WebApi controller katmanı belirlendi
- [x] RAG için `core/ai` alanı planlandı

## Phase 1 — Project Bootstrap

- [ ] Spring Initializr projesini oluştur
- [ ] Java 21 seç
- [ ] Spring Boot 3.x kullan
- [ ] Maven yapılandır
- [ ] Base package oluştur
- [ ] Package yapısını oluştur
- [ ] Git repository initialize et
- [ ] `.gitignore` ekle
- [ ] Application startup testini yap

## Phase 2 — PostgreSQL + pgvector

- [ ] `docker-compose.yml` oluştur
- [ ] PostgreSQL + pgvector container ekle
- [ ] Database bağlantısını yapılandır
- [ ] pgvector extension aktif et
- [ ] `DocumentChunk` entity oluştur
- [ ] `DocumentChunkRepository` oluştur
- [ ] Database connection test et

## Phase 3 — Document Upload

- [ ] `DocumentsController` oluştur
- [ ] `DocumentService` interface oluştur
- [ ] `DocumentManager` oluştur
- [ ] `DocumentBusinessRules` oluştur
- [ ] PDF upload endpoint oluştur
- [ ] Apache PDFBox entegre et
- [ ] PDF text extraction yap
- [ ] `DocumentUploadResponse` oluştur

## Phase 4 — Chunking

- [ ] `ChunkingService` interface oluştur
- [ ] `ChunkingManager` oluştur
- [ ] Character-based chunking yap
- [ ] 100 karakter overlap ekle
- [ ] Unit test yaz

Başlangıç değeri:

```text
Chunk size: 700
Overlap: 100
```

## Phase 5 — Embedding

- [ ] Spring AI dependency ekle
- [ ] AI provider configuration oluştur
- [ ] `EmbeddingService` interface oluştur
- [ ] `EmbeddingManager` oluştur
- [ ] Chunk embedding üret
- [ ] Embedding'i pgvector alanında kaydet
- [ ] API key'i environment variable ile yönet

## Phase 6 — Vector Search

- [ ] Question embedding oluştur
- [ ] Repository similarity query tanımla
- [ ] Top-K retrieval yap
- [ ] İlk etapta Top 3 chunk kullan
- [ ] Retrieval sonucunu test et

## Phase 7 — RAG

- [ ] `ChatRequest` oluştur
- [ ] `ChatResponse` oluştur
- [ ] `ChatController` oluştur
- [ ] `RagService` interface oluştur
- [ ] `RagManager` oluştur
- [ ] `RagBusinessRules` oluştur
- [ ] `RagPromptBuilder` oluştur
- [ ] Retrieved chunks ile context oluştur
- [ ] Context + question LLM'e gönder
- [ ] Answer response döndür

## Phase 8 — Validation & Exception Handling

- [ ] `GlobalExceptionHandler` oluştur
- [ ] Invalid PDF exception
- [ ] Empty question exception
- [ ] No document data exception
- [ ] AI provider exception
- [ ] Database/vector search exception
- [ ] Tutarlı error response oluştur

## Phase 9 — API Documentation

- [x] SpringDoc OpenAPI ekle
- [x] Swagger UI aktif et
- [x] `/api/documents` dokümante et
- [x] `/api/chat/auto` dokümante et (`/api/chat` ve `/api/chat/cag` kaldırıldı)

## Phase 10 — Testing

- [ ] Controller testleri
- [ ] Business rules testleri
- [ ] ChunkingService testleri
- [ ] Repository integration testi
- [ ] RAG basic integration testi
- [ ] PDF upload senaryosu
- [ ] Context içinde cevabı olmayan soru senaryosu

---

# 9. MVP Definition of Done

- [ ] Spring Boot uygulaması çalışıyor
- [ ] Devs benzeri layered package yapısı uygulanmış
- [ ] PostgreSQL + pgvector çalışıyor
- [ ] PDF yüklenebiliyor
- [ ] PDF text çıkarılıyor
- [ ] Text chunk'lara ayrılıyor
- [ ] Embedding oluşturuluyor
- [ ] Chunk + embedding database'e kaydediliyor
- [ ] Question embedding oluşturuluyor
- [ ] Semantic search çalışıyor
- [ ] Top-K relevant chunk bulunuyor
- [ ] LLM'e context gönderiliyor
- [ ] Dokümana dayalı cevap dönüyor
- [ ] Business Rules aktif
- [ ] Request / Response DTO ayrımı uygulanmış
- [ ] Controller -> Service -> Manager -> Repository akışı korunmuş
- [ ] Global exception handling mevcut
- [x] Swagger ile endpoint'ler test edilebiliyor

---

# 10. Architecture Rules

```text
Controller -> Business Abstract -> Business Concrete -> DataAccess
```

Kurallar:

- Controller doğrudan Repository kullanmaz
- Controller business logic içermez
- Business concrete sınıfları interface implement eder
- Validation kuralları `business/rules` altında tutulur
- Request modelleri `business/requests` altında tutulur
- Response modelleri `business/responses` altında tutulur
- Repository interface'leri `dataAccess/abstracts` altında tutulur
- Entity modelleri `entities/concretes` altında tutulur
- Ortak teknik ihtiyaçlar `core` altında tutulur
- Entity doğrudan API response olarak döndürülmez
- LLM ve embedding orchestration Business katmanında kalır
- AI config ve prompt yardımcıları `core/ai` altında tutulur

---

# 11. Out of Scope for MVP

```text
JWT Authentication
Spring Security
User management
Multi-agent
MCP
Kafka
Redis
Microservices
Kubernetes
Azure deployment
Fine-tuning
Frontend
Conversation memory
Multiple AI providers
```

---

# 12. Next Immediate Task

İlk kodlama adımı:

```text
Phase 1 — Project Bootstrap
```

Sıra:

1. Spring Initializr projesini oluştur
2. Package yapısını oluştur
3. `pom.xml` dependency'lerini belirle
4. PostgreSQL + pgvector Docker Compose ekle
5. Uygulamayı ayağa kaldır
6. İlk commit'i at

Önerilen ilk commit:

```text
chore: initialize layered Spring Boot RAG project
```

---

# 13. Core RAG Pipeline

```text
PDF
 |
 v
PDFBox
 |
 v
Text
 |
 v
ChunkingService
 |
 v
Chunks
 |
 v
EmbeddingService
 |
 v
Vectors
 |
 v
PostgreSQL + pgvector
 |
 v
Question
 |
 v
Question Embedding
 |
 v
Similarity Search
 |
 v
Top-K Chunks
 |
 v
RAG Prompt
 |
 v
LLM
 |
 v
Answer
```

Bu pipeline projenin ana omurgasıdır. İleri özelliklerden önce bu akışın temiz, test edilebilir ve `Devs_Backend` reposundaki katmanlama yaklaşımına uygun şekilde tamamlanması hedeflenmektedir.
