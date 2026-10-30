> Ten kontrakt dotyczy wariantu **IP/domain HTTPS z proxy**. Zwyczajowe wymaganie
> AccessKeyId/SecretAccessKey dla AWSS3 opisuje `TENCENT_IAM.md`.
> Nie mieszaj pary kluczy IAM z tokenem X-Origin-Verify. API nadal wymaga osobnego tokenu.

# Kontrakt AWS ↔ zespół Tencent

Konfigurację EdgeOne, reguły, certyfikat domeny użytkownika, WAF, DNS cutover i invalidacje wykonuje zespół Tencent. W repozytorium nie ma providera Tencent ani operacji w jego koncie.

Po wdrożeniu AWS przekazuje wynik `terraform -chdir=infra output -json tencent_handoff`. Wartości tokenów przekazuje osobnym zatwierdzonym kanałem sekretów (plik z `origin-tools tokens add --out`). Nie wysyłać ich w ticketach, logach, plikach tfvars ani komunikatach do użytkowników. Obie strony potwierdzają token **fingerprintem** (pierwsze 12 znaków SHA-256), nie wartością: `printf %s "$TOKEN" | sha256sum | cut -c1-12`.

| Parametr | Origin statyczny | Origin API |
|---|---|---|
| Typ EdgeOne | IP/domain, nie AWSS3 | IP/domain |
| Adres | `static_origin_hostname` | host z `api_endpoint`, bez `https://` |
| Protokół / port | HTTPS / 443 | HTTPS / 443 |
| HTTP Host | `static_origin_hostname` | dokładny host `*.execute-api.<region>.amazonaws.com` |
| TLS SNI | jak origin hostname | jak host API |
| Ścieżka | bez dodawania nazwy bucketa/prefiksu | bez zmiany `/api` |
| Sekret nagłówka | aktywny token z sekretu **static** | aktywny token z sekretu **API** (inny niż static) |
| Metody | GET, HEAD | wymagane metody backendu, w tym OPTIONS |
| Cache | jawna reguła dla statycznych publicznych plików (pkt 7) | BYPASS dla każdej metody i odpowiedzi |
| Dopuszczone źródła | origin-pull IP z Origin Protection | te same origin-pull IP — sprawdza je authorizer API |

## Wymagane reguły

1. `/api` **równe** oraz `/api/*` → origin API. Nie stosować `/api*`, bo obejmuje też `/apiculture`. Reguła API musi mieć skuteczny priorytet nad domyślną regułą statyczną. Testować również `/api/` i sposób normalizacji ścieżki.
2. Pozostałe ścieżki → statyczny origin. `/api` nie może awaryjnie przejść do S3. Zakazać fallbacku do starego publicznego endpointu, który omijałby kontrolę originu.
3. Ustaw nagłówek `X-Origin-Verify` operacją **Set** (EdgeOne: wartość nadpisana i unikalna), nie **Add**. Wartość klienta nie może dotrzeć do originu ani zostać zdublowana. Nie używać `Authorization` do tokenu originu.
4. Wymuś HTTPS do obu originów i włącz **Origin Certificate Validation** (plan Basic lub wyższy). **Ograniczenie:** według dokumentacji Tencent walidacja sprawdza wystawcę (zaufane CA) i ważność certyfikatu, nie nazwę hosta. Jeżeli zaufanym CA jest Amazon Root CA, przejdzie każdy certyfikat ACM dowolnego klienta AWS — ochrona przed podstawionym originem zależy wtedy od integralności DNS. Dla originu statycznego AWS może dostarczyć certyfikat z dedykowanego CA; wtedy w EdgeOne zaufany jest **tylko** ten CA (szczegóły w `WDROZENIE.md`, „Certyfikat originu statycznego”). Origin API używa certyfikatu `*.execute-api` wydanego przez Amazon; zaufaj Amazon Root CA. Sprawdź zachowanie przy nieprawidłowym certyfikacie (oczekiwane 525/555).
5. API: zachowaj metodę, body, query string, cookies, Authorization i nagłówki wymagane przez aplikację. Wyłącz cache, stale serving i prefetch API. Nie ponawiaj automatycznie nieidempotentnych żądań POST/PATCH bez mechanizmu idempotency key w backendzie.
6. Statyczne: przekazuj Range, If-None-Match, If-Match i nagłówki dat. Gdy klient wysyła If-Range, proxy może odpowiedzieć pełnym 200. Nie buforuj 401/403/404/5xx, nie zamieniaj ich na sukces HTML. `/_origin_health` zablokuj dla użytkowników na edge; ALB sprawdza je bezpośrednio.
7. Cache statyczny. Proxy przekazuje `Cache-Control` z metadanych S3. Dla obiektów **bez** tej metadanej wysyła wartość `default_cache_control` z Terraform — domyślnie `no-store`, czyli EdgeOne ich nie zbuforuje, jeśli respektuje nagłówki originu. Przed cutoverem AWS przekazuje wynik `origin-tools audit-cache-control` (liczba obiektów bez metadanej per rozszerzenie). Na tej podstawie zespoły ustalają jedną z opcji: (a) publisher uzupełnia metadane, (b) EdgeOne wymusza TTL dla uzgodnionych rozszerzeń/ścieżek, (c) AWS ustawia `default_cache_control`. Dla fingerprintowanych zasobów np. `public,max-age=31536000,immutable`, dla `index.html` krótki TTL. Uzgodnij cache key, query string, kompresję i Range; proxy ignoruje query, więc nie stosuj go jako mechanizmu wyboru treści. Nie wrzucaj osobistych treści do cache publicznego.
8. Token nie może być częścią odpowiedzi, logów nagłówków, diagnostycznych echo endpointów ani cache key. Nie wolno umożliwiać użytkownikowi wyboru dowolnego originu lub edycji reguł originowych.
9. **Origin Protection — lista origin-pull IPv4.** Wymagana **nowa wersja** Origin Protection (mniej niż 200 zakresów, zmiany średnio co 3–6 miesięcy, powiadomienie 14 dni przed zmianą). W nowej wersji zmiana wchodzi po 14 dniach **automatycznie**, bez potwierdzenia. Dlatego:
   - Tencent przekazuje AWS każde powiadomienie razem z listą bieżącą i nadchodzącą (pliki tekstowe, jeden CIDR w linii), najpóźniej 5 dni roboczych przed datą zmiany;
   - AWS wdraża **sumę** obu list, a po dacie zmiany samą nową listę (`origin-tools cidrs`);
   - obie strony porównują `tencent_handoff.origin_pull_cidrs.sha256` z sumą kontrolną listy po stronie Tencent.
   Ta sama managed prefix list chroni ALB i authorizer API (authorizer odświeża ją co 5 min): brakujący zakres oznacza odrzucone żądania na **obu** originach. Ten wariant ALB jest IPv4; nie konfiguruj IPv6-only origin pull.
10. CORS: dla wspólnej domeny frontendu i `/api` zwykle nie jest potrzebny. Dla innych domen backend musi implementować konkretną allowlistę i preflight. Nie włączamy globalnego CORS na API Gateway, żeby nie utworzyć oddzielnej ścieżki odpowiedzi omijającej kontrolę originu.
11. **Limity per klient dla `/api` są po stronie EdgeOne** (rate limiting/WAF). Każde żądanie dociera do API Gateway z adresu EdgeOne, więc throttling stage'u (`api_rate_limit`/`api_burst_limit`) jest jedną wspólną pulą dla wszystkich użytkowników: jeden nadużywający klient bez limitu na edge może ją wyczerpać i spowodować 429 dla pozostałych. Progi per IP/sesja uzgodnij z właścicielem API; limity EdgeOne muszą być niższe niż limit stage'u.

## Odbiór między zespołami

- Poprawny origin token dociera po usunięciu wartości klienta; nie jest widoczny w odpowiedzi ani backendzie API.
- Fingerprinty aktywnych tokenów po stronie EdgeOne zgadzają się z `origin-tools tokens status`.
- Bezpośrednie żądanie do `execute-api` z poprawnym tokenem, ale spoza listy origin-pull → 403 (authorizer sprawdza adres źródłowy).
- Suma kontrolna listy IP po obu stronach jest identyczna.
- Host i SNI nie są domeną widza, jeśli origin wymaga własnej domeny. Publiczna domena użytkownika nie może wskazywać z originu ponownie na EdgeOne (pętla).
- Nieprawidłowy certyfikat originu blokuje origin pull.
- API z dwoma użytkownikami nie współdzieli cache; sesje i Authorization docierają niezmienione.
- Limit per klient na edge działa, zanim zadziała throttling API Gateway.
- Test przepływu logowania, przekierowań i cookies: backend może potrzebować skonfigurowanego publicznego base URL. Domyślny Host integracji nie musi być domeną widza.
- Rotacja: potwierdzenie wdrożenia nowego tokenu na wszystkich edge (fingerprint) przed `retire`.
- Właściciel listy IP po obu stronach i kanał powiadomień o zmianach Origin Protection są uzgodnione przed produkcją.
