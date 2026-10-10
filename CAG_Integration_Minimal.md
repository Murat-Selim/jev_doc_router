# CAG Integration — Existing Spring RAG Assistant

Bu doküman, mevcut Spring RAG Assistant projesine yalnızca **gerçek CAG (Cache-Augmented Generation)** desteği eklemek için gereken minimum yapıyı tanımlar.

Mevcut RAG yapısı korunur.

Uygulama durumu: `AI_PROVIDER=mock` varsayılanında yalnızca yerel bağlam snapshot'ı
tutulur. `AI_PROVIDER=openai` ile Responses API'nin `prompt_cache_options.prewarm`
özelliği bağlamı çıktı üretmeden provider tarafında önceden işler. Soru istekleri aynı
prefix'i, explicit cache breakpoint'i ve içerik hash'inden türetilen `prompt_cache_key`'i
kullanır. OpenAI kalıcı bir KV cache ID ile yalnızca soru gönderme API'si sunmadığından,
bağlam isteklerde tekrar taşınır; cache yeniden işleme maliyetini azaltır. Cache hit'i
minimum prefix uzunluğuna, süre dolumuna ve provider yönlendirmesine bağlıdır; gerçek
kullanım loglardaki `cached`/`cache_write` token sayılarıyla izlenir.
Kaynak: [OpenAI prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching).

Belge kaydı commit edildikten sonra yenileme arka plana alınır. Limit aşımı veya
prewarm hatası belge kaydını bozmaz; CAG devre dışı kalırken RAG endpoint'i kullanılabilir.
Kodun sağlayıcıya uyarlanmış bu davranışı aşağıdaki kavramsal cacheId örneklerinden
farklıdır. RAG embedding'i henüz mock'tur.

> Bu projede CAG, sadece tüm dokümanları her istekte prompt içine koymak anlamına gelmez. Amaç; küçük ve sınırları belli knowledge base'i model context'ine önceden yüklemek, provider/model tarafından oluşturulan **prompt cache / KV cache** yapısını tekrar kullanmak ve her soruda yeniden vector retrieval yapmamaktır.

---

## 1. RAG ve CAG Farkı

### Mevcut RAG

```text
Question
   |
   v
Question Embedding
   |
   v
Vector Search
   |
   v
Top-K Chunks
   |
   v
Prompt
   |
   v
LLM
   |
   v
Answer
```

### CAG

```text
Documents
   |
   v
Build Knowledge Context
   |
   v
Preload Context to LLM Provider
   |
   v
Prompt Cache / KV Cache
   |
   v
User Question
   |
   v
Cached Context + Question
   |
   v
LLM
   |
   v
Answer
```

CAG tarafında soru sırasında:

```text
Embedding Search yok
Vector Search yok
Top-K Retrieval yok
```

Bilgi daha önceden model context'ine yüklenmiş ve cache edilmiş olur.

---

## 2. Önemli Ayrım

Aşağıdaki yapı:

```java
@Cacheable
public String getCombinedContext() {
    ...
}
```

yalnızca Spring tarafında string/context oluşturmayı cache eder.

Bu faydalı olabilir ancak:

```text
Spring Cache != KV Cache
```

Gerçek CAG için tercih edilen yapı:

```text
Spring Boot
     |
     v
LLM Provider
     |
     v
Prompt Cache / KV Cache
```

KV cache veya provider-side prompt caching özelliği kullandığımız AI provider/model tarafından desteklenmelidir.

---

## 3. Mevcut Projeye Eklenecek Minimum Yapı

```text
business/
├── abstracts/
│   └── CagService.java
│
├── concretes/
│   └── CagManager.java
│
└── rules/
    └── CagBusinessRules.java

core/
└── ai/
    ├── prompt/
    │   └── CagPromptBuilder.java
    │
    └── cache/
        └── CagCacheProvider.java
```

Mevcut RAG sınıfları aynen korunur.

---

## 4. CagService

Konum:

```text
business/abstracts/CagService.java
```

Örnek:

```java
public interface CagService {

    ChatResponse ask(ChatRequest request);

}
```

---

## 5. CagManager

Konum:

```text
business/concretes/CagManager.java
```

Sorumluluğu:

```text
Question al
↓
Cached knowledge context'i kullan
↓
CAG prompt oluştur
↓
LLM'e cache/context referansı ile gönder
↓
ChatResponse döndür
```

Pseudo yapı:

```java
@Service
public class CagManager implements CagService {

    private final CagCacheProvider cagCacheProvider;
    private final CagPromptBuilder cagPromptBuilder;
    private final ChatModel chatModel;

    @Override
    public ChatResponse ask(ChatRequest request) {

        String cacheId =
            cagCacheProvider.getActiveCacheId();

        String prompt =
            cagPromptBuilder.build(
                request.getQuestion()
            );

        String answer =
            chatModel.call(prompt);

        return new ChatResponse(answer);
    }
}
```

> `cacheId` kullanım şekli seçilen LLM provider'ın API'sine göre değişecektir. Bu nedenle cache işlemini `CagCacheProvider` arkasında soyutlamak daha temizdir.

---

## 6. CagCacheProvider

Konum:

```text
core/ai/cache/CagCacheProvider.java
```

Amaç:

```text
Knowledge Context
      |
      v
Provider Cache Creation
      |
      v
cacheId / cached context reference
```

Örnek interface:

```java
public interface CagCacheProvider {

    String createOrRefreshCache(
        String knowledgeContext
    );

    String getActiveCacheId();

    void invalidateCache();

}
```

Bu interface sayesinde uygulama doğrudan belirli bir AI provider'ın cache API'sine bağımlı olmaz.

İleride implementation örnekleri:

```text
OpenAiCagCacheProvider
AnthropicCagCacheProvider
GeminiCagCacheProvider
LocalLlmCagCacheProvider
```

şeklinde ayrılabilir.

---

## 7. Knowledge Context Oluşturma

CAG kullanabilmek için knowledge base'in tamamı veya seçilen curated data set model context window'una sığmalıdır.

Mevcut projede yalnızca `DocumentChunk` saklanıyorsa başlangıç için:

```text
DocumentChunkRepository
        |
        v
Load all chunks
        |
        v
Combine content
        |
        v
Knowledge Context
```

yeterlidir.

Daha temiz ikinci aşama:

```text
Document
```

entity'sinde full extracted text saklamaktır.

Önerilen ilerleme:

```text
MVP:
DocumentChunk içeriklerini birleştir

Daha sonra:
Document.content kullan
```

---

## 8. Cache Oluşturma Akışı

Doküman yüklendiğinde veya knowledge base değiştiğinde:

```text
PDF Upload
    |
    v
PDF Parsing
    |
    v
Knowledge Context Build
    |
    v
CagCacheProvider
    |
    v
LLM Provider
    |
    v
Prompt Cache / KV Cache
    |
    v
cacheId
```

Cache her kullanıcı sorusunda yeniden oluşturulmamalıdır.

---

## 9. Cache Invalidasyonu

Knowledge base değiştiğinde mevcut cache artık güncel olmayabilir.

Örnek durumlar:

```text
Yeni PDF yüklendi
PDF silindi
PDF içeriği güncellendi
Knowledge context değişti
```

Bu durumlarda:

```text
invalidate old cache
        |
        v
rebuild knowledge context
        |
        v
create new provider cache
```

yapılmalıdır.

---

## 10. CAG Prompt

Konum:

```text
core/ai/prompt/CagPromptBuilder.java
```

CAG prompt'unda knowledge context'in tamamını her seferinde tekrar concatenate etmek zorunda değiliz; cache edilen context model/provider tarafında tutulur.

Soru prompt'u basit kalabilir:

```text
You are a document assistant.

Answer the user's question only using
the cached knowledge context.

If the answer is not available in the
knowledge context, say that the information
could not be found.

Question:

{question}
```

---

## 11. CagBusinessRules

Konum:

```text
business/rules/CagBusinessRules.java
```

Kontroller:

```text
Question boş olamaz

Knowledge context boş olamaz

Knowledge context CAG limitini aşmamalı

Aktif cache bulunmalı
```

Örnek:

```java
@Component
public class CagBusinessRules {

    public void checkQuestion(String question) {

        if (question == null ||
            question.isBlank()) {

            throw new RuntimeException(
                "Question cannot be empty."
            );
        }
    }
}
```

---

## 12. Context Size Kontrolü

CAG sınırsız data için kullanılmamalıdır.

Configuration:

```yaml
ai:
  cag:
    max-context-tokens: 50000
```

Bu değer sabit bir evrensel kural değildir.

Kullanılan:

```text
LLM context window
Provider cache desteği
Prompt maliyeti
Knowledge size
```

dikkate alınarak değiştirilmelidir.

Basit başlangıç hesabı:

```text
estimatedTokens ~= characterCount / 4
```

---

## 13. Mevcut RAG ile Birlikte Kullanım

Mevcut:

```text
RagService
↓
RagManager
```

korunur.

Yeni:

```text
CagService
↓
CagManager
↓
CagCacheProvider
```

eklenir.

Sonuç:

```text
RAG

Question
↓
Embedding
↓
Vector Search
↓
Top-K
↓
LLM
```

ve:

```text
CAG

Preloaded Knowledge
↓
KV / Prompt Cache
↓
Question
↓
Cached Context
↓
LLM
```

aynı projede ayrı ayrı bulunabilir.

---

## 14. Basit CAG Endpoint

> **Güncel durum:** Ayrı `POST /api/chat/cag` ve `POST /api/chat` endpoint'leri
> kaldırıldı. Tek chat endpoint'i `POST /api/chat/auto`'dur (bkz. bölüm 16).
> Aşağıdaki örnek ilk tasarımı belgelemek için korunmuştur.

İlk etapta ayrı endpoint kullanılabilir:

```http
POST /api/chat/cag
```

Request:

```json
{
  "question": "Yıllık izin kaç gündür?"
}
```

Response:

```json
{
  "answer": "Dokümana göre yıllık izin 14 gündür."
}
```

Mevcut RAG endpoint'i:

```http
POST /api/chat
```

aynen korunabilir.

---

## 15. Güncel Minimal Klasör Yapısı

```text
business/
├── abstracts/
│   ├── RagService.java
│   └── CagService.java
│
├── concretes/
│   ├── RagManager.java
│   └── CagManager.java
│
└── rules/
    ├── RagBusinessRules.java
    └── CagBusinessRules.java

core/
└── ai/
    ├── prompt/
    │   ├── RagPromptBuilder.java
    │   └── CagPromptBuilder.java
    │
    └── cache/
        └── CagCacheProvider.java
```

Bu CAG entegrasyonu için yeterli minimum yapıdır.

---

## 16. Hybrid ve Jev Yönlendirmesi

`AutoChatService` / `AutoChatManager` ile `POST /api/chat/auto` eklendi.
Manuel RAG ve CAG endpoint'leri kaldırıldı; `RagService` ve `CagService`
yalnızca `AutoChatManager` tarafından kullanılır.

- CAG hazır değilse, upload sonrası refresh sürüyorsa veya bağlam otomatik CAG
  bütçesini aşıyorsa soru RAG'ye gider.
- `CHAT_ROUTING_PROVIDER=rules` modunda (application.yml varsayılanı) hazır ve küçük bağlam CAG'ye gider.
  `.env.example` varsayılanı `jev`'dir.
- `CHAT_ROUTING_PROVIDER=jev` ve `TYPESAFE_API_KEY` ile TypeSafe'ın resmi
  [Choice API'si](https://docs.typesafe.ai/api) soru, bağlam boyutu ve PDF metninden
  sınırlı örneklerle RAG/CAG seçer. Jev cevabı üretmez; seçilen servis üretir.
- Düşük karar güveni veya Jev hatası RAG'ye düşer. CAG seçilirse aynı immutable
  snapshot cevap üretiminde kullanılır.

Yanıtta `answer`, `strategy`, `decisionSource`, `reason` ve `confidence` bulunur.
`confidence` cevap doğruluğu değil, Jev'nin routing güvenidir. Eşikler ve örneklenen
bağlam gerçek PDF sorularıyla ölçülmelidir; hız, maliyet veya doğruluk kazancı
henüz garanti edilmez. Canlı mod TypeSafe'a soru/PDF metni gönderir ve API ücreti
oluşturur. Ayarlar ve örnekler README'dedir.

---

## 17. Kısa Özet

Gerçek CAG entegrasyonu:

```text
Documents
↓
Knowledge Context
↓
Provider-side Prompt Cache / KV Cache
↓
Question
↓
Cached Context + Question
↓
LLM
↓
Answer
```

Temel fark:

```text
RAG = retrieve at query time

CAG = preload + cache before query time
```

Spring tarafındaki normal application cache, yardımcı bir optimizasyon olabilir; ancak tek başına gerçek CAG/KV caching olarak değerlendirilmemelidir.
