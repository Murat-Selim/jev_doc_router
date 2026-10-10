# jev_doc_router

A Spring Boot **PDF question-answering assistant** that routes each question to **RAG** (retrieve the most relevant chunks) or **CAG** (read the full cached document), using **Jev** routing decisions or local rules. Ask through a single endpoint: `POST /api/chat/auto`.

The user uploads a PDF → text is extracted → split into chunks → an embedding is generated for each chunk → stored in PostgreSQL + pgvector. When the user asks a question, the question's embedding is generated, the most relevant chunks are found via vector similarity search, and provided to the LLM as context.

## Technologies

| Layer | Technology |
|---|---|
| Language / Framework | Java 17, Spring Boot 3.5 |
| Web / Data | Spring Web, Spring Data JPA, Validation |
| Database | PostgreSQL 16 + pgvector |
| PDF | Apache PDFBox 3 |
| AI | OpenAI Responses + prompt prewarm (opt-in); mock embedding |
| Infrastructure | Docker Compose, Maven Wrapper |

## Architecture

```text
webApi/controllers        → REST endpoints
business/abstracts        → Service interfaces
business/concretes        → Service implementations (Managers)
business/requests         → Request DTOs
business/responses        → Response DTOs
business/rules            → Business validation rules
core/                     → Global exception handling, config
dataAccess/abstracts      → Repository interfaces
entities/concretes        → Entity models
```

Pipeline:

```text
DOCUMENT → TEXT → CHUNKS → EMBEDDINGS → VECTOR DB → RETRIEVAL → CONTEXT → LLM → ANSWER
```

## API

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/health` | Service status |
| POST | `/api/documents` | PDF upload (`multipart/form-data`, field: `file`) |
| POST | `/api/chat/auto` | Ask a question; RAG/CAG is selected automatically (Jev or local rules) |

Swagger UI: `http://localhost:8080/swagger-ui.html`

OpenAPI JSON: `http://localhost:8080/v3/api-docs`

### Examples

```bash
# Upload a PDF
curl -X POST http://localhost:8080/api/documents \
  -F "file=@document.pdf;type=application/pdf"

# Ask a question (the router selects RAG or CAG)
curl -X POST http://localhost:8080/api/chat/auto \
  -H "Content-Type: application/json" \
  -d '{"question": "Summarize and compare the document sections."}'
```

Upload response:

```json
{
  "message": "Document processed successfully",
  "documentName": "document.pdf",
  "chunks": 18
}
```

Chat response:

```json
{
  "answer": "According to the document, the annual leave period is 14 days."
}
```

## Setup and Running

### Requirements

- Java 17+
- Docker (for PostgreSQL/pgvector)

### Steps

1. Start PostgreSQL + pgvector:

   ```bash
   docker compose up -d
   ```

2. Start the application:

   ```bash
   ./mvnw spring-boot:run        # Linux/macOS
   .\mvnw.cmd spring-boot:run    # Windows
   ```

### Environment Variables

```bash
AI_PROVIDER=mock                     # Default: no external AI calls
CAG_MAX_CONTEXT_TOKENS=50000          # Estimated as character count / 4
CHAT_ROUTING_PROVIDER=rules           # Default: local routing, no Jev calls
AUTO_CAG_MAX_CONTEXT_TOKENS=8000      # Conservative auto-routing budget
JEV_MIN_CONFIDENCE=0.6               # Starting threshold; tune with labeled PDF questions

# Enable real Jev decisions explicitly (independent of the answer provider):
CHAT_ROUTING_PROVIDER=jev
TYPESAFE_API_KEY=your_key_here
JEV_MODEL=jev-latest

# Or use a Qwen model via Doubleword (OpenAI-compatible Chat Completions) for real answers:
AI_PROVIDER=qwen
QWEN_API_KEY=your_key_here           # DOUBLEWORD_API_KEY is accepted as an alias
QWEN_MODEL=Qwen/Qwen3-VL-30B-A3B-Instruct-FP8
QWEN_BASE_URL=https://api.doubleword.ai/v1   # Any OpenAI-compatible endpoint works

# Enable real answers and provider-side CAG prewarm explicitly:
AI_PROVIDER=openai
OPENAI_API_KEY=your_key_here
OPENAI_MODEL=gpt-6.1-sol              # Must support Responses prewarm and explicit cache breakpoints
OPENAI_MAX_OUTPUT_TOKENS=4096
```

Never commit a real key to the repository.

The application also reads the local `.env` file from its working directory using Spring's
properties-file loader. Copy `.env.example` to `.env`, put real keys only in `.env` (Git ignores
it), and use unquoted `NAME=value` entries. `JEV_API_KEY` and `JEVAPIKEY` are accepted aliases
for `TYPESAFE_API_KEY`. Set `CHAT_ROUTING_PROVIDER=jev` explicitly to enable paid Jev decisions;
adding a key alone keeps local routing enabled. Restart the application after changing `.env`.

With `AI_PROVIDER=openai`, the chat endpoint generate real answers. Startup and successful
uploads prewarm the CAG prefix on OpenAI; questions reuse the same prefix and content-derived
`prompt_cache_key`. This mode sends document text to OpenAI and incurs API charges, including
prewarm cache writes. Merely setting an API key does not enable it.

The implementation follows the [official OpenAI prompt caching guide](https://developers.openai.com/api/docs/guides/prompt-caching).
For supported GPT-5.6+ models, the reusable prefix must contain at least 1,024 visible input
tokens. Short documents still produce answers but may not receive cache hits. Provider cache
entries expire; later questions can rebuild the prefix cache. Full context is still included
in requests because OpenAI matches prefixes, rather than accepting a persistent KV-cache ID.
Logs report `input`, `cached` and `cache_write` token counts without recording document text.

After the document transaction commits, cache refresh is scheduled on Spring's background
executor, reads committed data in a new transaction and serializes refreshes within one
application instance. Uploads return without waiting for prewarm; CAG can temporarily use the
previous committed snapshot, or be unavailable before the first refresh completes. Empty/oversized context
or prewarm failures disable CAG without rejecting stored documents or stopping startup; `/api/chat/auto`
falls back to RAG in that case. Restart or a later successful upload retries prewarm.
The context token budget is an estimate, not an exact model token count.

### Automatic routing and Jev

`/api/chat/auto` is the only chat endpoint and accepts a `ChatRequest`. It returns `answer`,
`strategy` (`RAG` or `CAG`), `decisionSource`, `reason`, and `confidence`. Confidence is the
Jev routing confidence, not a guarantee that the generated answer is correct; it is null
for local rules and provider failures.

1. Missing/preparing cache or a document refresh still in progress: use RAG without calling Jev.
2. Context above the auto CAG budget or the hard CAG limit: use RAG without calling Jev.
3. Otherwise, local `rules` mode uses the ready compact CAG context.
4. In `jev` mode, Jev chooses `RAG` or `CAG` from the question, context size and PDF excerpts.
   Focused facts favor RAG; summaries/comparisons across sections favor CAG.
5. Low confidence, HTTP errors, timeouts or invalid Jev responses: use RAG and report the fallback reason.

Jev uses the [official TypeSafe Choice API](https://docs.typesafe.ai/api) at
`https://api.typesafe.ai/v1/systemone`. No additional SDK is required. Enabling it sends the
question and up to 6,000 characters sampled from the beginning, middle and end of the cached
document context to TypeSafe, and incurs API charges. It does not send the whole PDF or cache key.
Samples can omit relevant content. Jev requests have a 2-second connect timeout and a 3-second
read timeout, with no automatic retries. The routing threshold and token budget are initial
policy settings, not benchmarked quality/cost guarantees. Measure against manual RAG/CAG with
representative Turkish PDF questions before relying on them in production.

After an upload commits, automatic routing uses RAG until all scheduled cache refreshes finish.
The auto CAG
answer uses the exact snapshot supplied to the router. Answer-provider failures are returned
normally; the router does not silently make a second generation call.

Example routing metadata in default local mode (the actual answer is a labeled mock preview):

```json
{
  "answer": "[Mock answer; no LLM called] ...",
  "strategy": "CAG",
  "decisionSource": "rules",
  "reason": "READY_COMPACT_CONTEXT",
  "confidence": null
}
```

## Tests

```bash
./mvnw test                    # Linux/macOS
.\mvnw.cmd test                # Windows
```

## Status

- [x] Phase 1 — Spring Boot setup, health endpoint
- [x] Phase 2 — Entity + pgvector repository (DB connection will be activated in the final step)
- [x] Phase 3 — PDF upload + text extraction
- [x] Phase 4 — Chunking (700 characters / 100 overlap)
- [x] Phase 5 — Deterministic mock embedding (semantic embedding integration pending)
- [x] Phase 6 — Vector similarity search (top 3)
- [x] Phase 7 — RAG flow with mock or OpenAI answer generation
- [x] CAG flow — atomic context snapshot, post-commit refresh and opt-in OpenAI prewarm
- [x] Automatic RAG/CAG routing — local rules or opt-in Jev Choice decisions with fallback
- [x] Swagger/OpenAPI integration
- [ ] Final integration test (end-to-end with Docker + real DB)

## Notes

- RAG retrieval still uses deterministic mock embeddings; semantic embedding integration is a separate step. CAG does not use embeddings.
- `AI_PROVIDER=mock` returns a clearly labeled preview of the context; it does not generate real answers or create provider-side KV cache.
- Local cache snapshots and refresh serialization are scoped to a single application instance. Multiple instances need coordinated knowledge-base invalidation.
