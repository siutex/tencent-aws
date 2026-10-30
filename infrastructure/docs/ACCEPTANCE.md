# Odbiór przed produkcją

Status tej paczki: testy live i obciążeniowe AWS/Tencent **nie zostały wykonane**.
Poniżej znajdują się wykonywalne testy i procedury do uruchomienia na wydzielonym stagingu.
Nie zastępują ich wyniki testów z mockami. Zachowaj raporty, commity obu repo, digest obrazu,
wersję Lambdy, wersję prefix list oraz czas testów. Żaden raport nie powinien zawierać sekretów.

## 1. Walidacja bez AWS

W repo proxy: `mvn verify`. W repo infrastruktury:

```bash
python3 -m unittest discover -s tests -v
python3 scripts/build_authorizer.py
mvn -f operator-tools/pom.xml verify
terraform -chdir=infra init -backend=false
terraform -chdir=infra validate
terraform -chdir=infra test
```

Testy proxy blokują 16 równoległych wywołań fake S3, sprawdzają szybkie 503 dla kolejnego,
brak dodatkowego wywołania S3, dostępność health checku, pełne zakończenie i powrót do pracy.
Dodatkowe testy sprawdzają zwalnianie slotów po odmowie tokenu i błędach S3.
Testy authorizera obejmują pierwszą awarię, cache/backoff/stale/hard expiry, paginację,
przekroczenie budżetu czasu, zmianę CIDR, rotację tokenów i niewiarygodne nagłówki źródłowe.
Testy rotacji sprawdzają awarię cleanup po commit, utraconą odpowiedź commit, nieznany wynik,
race dwóch operatorów i zachowanie pliku odzyskiwania. Fake Secrets Manager uwzględnia
AWSCURRENT nadawane automatycznie pierwszej wersji.

## 2. Przeciążenie proxy — realne ECS/ALB/S3

W stagingu ustal limit i liczbę zadań. Na fazę deterministycznego przeciążenia czasowo
zamroź autoscaling w procedurze operatora, a potem przywróć go i sprawdź skalowanie.
Utwórz obiekty testowe 1 MiB i 64 MiB; znane SHA-256. Wyłącz cache wyłącznie dla testowego
prefiksu EdgeOne. Query string nie gwarantuje cache miss — konfiguracja cache musi to potwierdzać.
Uruchamiaj z EdgeOne albo z kontrolowanej sieci mającej dostęp do originu; nie otwieraj ALB na świat.

```bash
# LOAD_URL i LOAD_SHA256 ustaw dla własnego fixture HTTPS.
# LOAD_ORIGIN_TOKEN tylko jeśli test idzie bezpośrednio do originu; nie podawaj go jako argument CLI.
python3 acceptance/load_proxy.py --confirm-staging --requests 100 --concurrency 16
python3 acceptance/load_proxy.py --confirm-staging --requests 500 --concurrency 128 --slow-read-ms 100
```

Testuj też anulowanie pobierania w połowie (przerwanie klienta), utratę połączenia S3,
deployment/scale-in przy trwających transferach oraz powrót po zdjęciu obciążenia.
Powyższe komendy są przykładami; concurrency dobierz poniżej i powyżej łącznego limitu zadań.
`--slow-read-ms` spowalnia klienta, lecz ALB/EdgeOne może buforować — obserwuj rzeczywiste
Active/PeakActive i transfer z ECS. W razie buforowania powtórz kontrolowany test wewnątrz VPC.

Kryteria:
- poniżej uzgodnionej pojemności brak 5xx/503 i poprawne hashe wszystkich pobrań;
- przy celowym przeciążeniu nadmiarowe żądania dostają 503/Retry-After/no-store,
  bez narastającej kolejki, OOM i restartów; health check pozostaje 200;
- PeakActive na każdym zadaniu nie przekracza PROXY_MAX_CONCURRENCY;
- 503 nie są buforowane przez CDN; p95 czasu odrzucenia mieści się w uzgodnionym SLO;
- S3AcquireMaxMicros, pamięć, liczba deskryptorów i liczba połączeń stabilizują się;
- po anulowaniu/awarii Active wraca do wartości bazowej, pobrania ponownie działają;
- po powrocie do normalnego ruchu brak trwałych 503; alarm trafia do alarm_action_arns;
- po ponownym włączeniu autoscalingu osobno potwierdź scale-out i scale-in.

Skrypt raportuje hashe, statusy i latencje (bez treści/sekretów), ale sam kod wyjścia 0
przy 503 nie oznacza odbioru. 503 są dopuszczalne wyłącznie w fazie celowego przeciążenia.
Nie zakładamy jednego progu p95 dla wszystkich rozmiarów obiektów; wpisz SLO do raportu przed testem.

## 3. Awaria zależności authorizera — staging

Przygotuj osobną wersję/alias staging i syntetyczny ruch przez EdgeOne.
Nie zmieniaj polityk produkcyjnych. W kontrolowanym oknie operator blokuje odczyt testowego
sekretu lub testowej prefix list (osobno); po teście usuwa blokadę.

| Scenariusz | Oczekiwany wynik |
|---|---|
| Zimne środowisko + EC2 niedostępne | odmowa, prefix_list_unavailable/dependency_denied; brak odczytu sekretu |
| Zimne środowisko + Secrets Manager niedostępny | odmowa + secret_unavailable |
| Ciepły cache tokenów, awaria SM | użycie stale tylko do wieku <90 s, potem odmowa |
| Ciepły cache CIDR, awaria EC2 | stale tylko do wieku <3600 s, potem odmowa |
| Max stale ustawione 0 | brak stale po 30 s / 300 s |
| Powolne odpowiedzi/retry/paginacja | kontrolowane zakończenie przed timeoutem Lambdy; żadnego allow po wyczerpaniu budżetu |
| Zależność ponownie dostępna | po backoff odświeżenie i powrót do pracy |
| Błędny token lub source IP | odrębne metryki odmowy, żadnego logowania tokenu/eventu |

Dla długiego testu CIDR można wywoływać pojedynczą testową instancję lokalnie z zegarem
wstrzykiwanym w unit testach; realny test godziny stale nie jest zastąpiony skróceniem zegara
na produkcji. Przy wielu środowiskach Lambdy powtarzaj sondy — każde ma własny cache.
Alarmy custom oraz AWS/Lambda Errors/Throttles muszą dotrzeć do operatora.

## 4. Rotacja częściowo zakończona

Używaj osobnego sekretu staging i testowej konfiguracji EdgeOne.

1. Init → jeden token, add --out → dwa tokeny. Plik 0600 zawiera nowy token i nie trafia do repo.
2. Przed operacją AWS wymuś lokalny błąd utworzenia pliku: brak mutacji sekretu.
3. Zasymuluj błąd sprzątania po commit w testach automatycznych; potwierdź komunikat
   `Committed AWSCURRENT version`, warning cleanup i nieusunięty plik. W realnym AWS sprawdź
   etykiety wersji i posprzątaj dopiero po uzgodnieniu ich aktualnego właściciela.
4. Utrata odpowiedzi commit: narzędzie odczytuje AWSCURRENT; gdy odczyt też nie działa,
   zgłasza UNKNOWN z version ID, zachowuje plik. Nie uruchamiaj kolejnego add w ciemno.
   Po przywróceniu AWS porównaj version ID i fingerprinty przez status/GetSecretValue.
5. Przy konflikcie dwóch operatorów nie nadpisuj zwycięskiej wersji. Plik z przegranej
   operacji nie jest dowodem aktywacji — nie przekazuj go Tencent bez potwierdzenia statusu.
6. Tencent wdraża nowy token częściowo. Oba działają podczas overlapu. Nie uruchamiaj retire
   przed potwierdzeniem zakończenia rollout'u i pozytywnymi sondami z różnych regionów.
7. Retire starego tokenu → po maksymalnym oknie cache 90 s stary jest odrzucany,
   nowy działa. Test musi dotrzeć do originu, nie odpowiedzi z cache statycznego.
8. Przy awarii Secrets Manager po retire weryfikuj hard expiry: stare dane nie pozostają
   akceptowane po granicy czasu. Zachowaj czas i fingerprint, nigdy wartość tokenu.

Przy UNKNOWN/NOT CURRENT narzędzie zachowuje plik celowo. Usuwa go operator dopiero po
rozstrzygnięciu stanu. Cleanup AWSPENDING nie jest warunkiem ważności AWSCURRENT.

## 5. Rzeczywisty przepływ EdgeOne → AWS

W stagingu przygotuj mały (16 B–1 MiB) obiekt testowy z ETag i znanym SHA-256.
Dla testowego prefiksu wyłącz cache EdgeOne, aby test nadpisania nagłówka dotarł do originu.
Backend udostępnia tymczasowy, uwierzytelniony endpoint `/api/_acceptance`:

```json
{"subject":"identyfikator testowego użytkownika","nonce":"parametr query nonce","origin_token_present":false}
```

Endpoint zwraca `Cache-Control: no-store`; nie zwraca nagłówków Authorization/cookies/tokenów.
`origin_token_present` oznacza tylko obecność X-Origin-Verify w backendzie, nigdy jego wartość.
Nie jest częścią proxy; wdraża go właściciel backendu wyłącznie w stagingu.

Zmienne środowiskowe do `acceptance/test_edgeone_live.py`:

| Nazwa | Zawartość |
|---|---|
| EDGEONE_LIVE | `1`, jawne włączenie testów sieciowych |
| EDGEONE_STATIC_URL | pełny URL obiektu testowego przez EdgeOne |
| STATIC_SHA256 | SHA-256 obiektu |
| EDGEONE_API_URL | pełny URL endpointu acceptance przez EdgeOne |
| DIRECT_API_URL | ten sam endpoint przez execute-api |
| API_ORIGIN_TOKEN | poprawny token originu API |
| STATIC_ORIGIN_TOKEN | opcjonalnie do wykrywania wycieku w odpowiedzi |
| VIEWER_AUTH_A, VIEWER_AUTH_B | kompletna wartość Authorization dwóch testowych użytkowników |
| VIEWER_SUBJECT_A, VIEWER_SUBJECT_B | oczekiwane różne identyfikatory |

```bash
python3 -m unittest discover -s acceptance -p 'test_edgeone_live.py' -v
```

Runner spoza sieci origin-pull EdgeOne musi dostać 403 od execute-api także z poprawnym
tokenem originu. Połączenie do originu statycznego spoza allowlisty ma być blokowane przez SG;
sprawdź VPC Flow Logs/Reachability Analyzer, aby nie uznać błędu DNS/TLS za dowód blokady SG.

Dodatkowy odbiór wymagający operacji zespołu Tencent:
- certyfikat testowego originu nieprawidłowy → origin pull zablokowany, bez fallbacku;
- źródłowy X-Origin-Verify klienta zastąpiony (Set, nie Add), duplikaty nie obchodzą ochrony;
- `/api`, `/api/`, `/api/x` trafiają do backendu; `/apiculture` do originu statycznego;
- method/body/query/cookies/Authorization zachowane; POST/PATCH bez automatycznego retry;
- API BYPASS: naprzemienne żądania użytkowników i świeże nonce, brak współdzielenia odpowiedzi;
- 401/403/404/5xx nie są buforowane ani zmieniane w 200 HTML;
- normalizacja ścieżek i próby traversal nie ujawniają obiektów spoza public/;
- testy różnych punktów EdgeOne podczas rotacji tokenów/CIDR;
- cache HIT/MISS oraz kompresja/Range sprawdzone ponownie po przywróceniu docelowych reguł cache;
- logowanie/redirect/cookies działają dla publicznej domeny; CORS zgodny z kontraktem aplikacji.

Raport odbioru: wynik każdego przypadku, dowód origin fetch (request ID/CloudWatch),
metryki, alarmy, zatwierdzony rollback i podpis właścicieli AWS/Tencent/backendu.
Brak danych środowiska lub skipped test jest statusem NOT RUN, nigdy PASS.
