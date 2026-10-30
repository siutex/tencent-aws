# Tencent: użytkownik IAM i rotacja access keys co 90 dni

Wymaganie zespołu Tencent dotyczy podpisywania odczytów **bezpośrednio z S3**.
AccessKeyId i SecretAccessKey nie są tokenem HTTP i nie autoryzują istniejącej ścieżki
API Gateway z Lambda authorizerem. Nie wkładamy ich do X-Origin-Verify ani proxy.

| Wariant | Dane przekazane Tencent | Statyczna ścieżka | Wpływ na API |
|---|---|---|---|
| Główny w tej paczce | Token originu | EdgeOne → ALB → ECS proxy → S3 (task role) | Osobny token API + CIDR |
| Bezpośredni S3 | Dedykowany IAM user + para kluczy | EdgeOne → prywatny S3, SigV4 | Nadal osobny token API + CIDR |

Jeżeli długoterminowe klucze są akceptowalne organizacyjnie i Tencent wymaga typu AWSS3,
bezpośredni S3 usuwa koszt i utrzymanie ALB/ECS/proxy. Wtedy proxy nie jest potrzebne
na ścieżce statycznej. To osobna decyzja architektoniczna, nie przełącznik logowania w proxy.
Brak w tej paczce automatycznej migracji: trzeba potwierdzić mapowanie / → public/index.html,
prefiks public/, metadane cache, Range/HEAD, brak fallbacku oraz obsługę błędów w EdgeOne.

## Uprawnienia i odpowiedzialność

- Jeden dedykowany użytkownik na środowisko/integrację, bez console login.
- `s3:GetObject` tylko na `bucket/public/*`; brak ListBucket/PutObject/DeleteObject/IAM.
- `kms:Decrypt` wyłącznie na konkretnym kluczu, przez S3, jeśli obiekty używają SSE-KMS.
  Key policy również musi pozwalać na operację. S3 Bucket Keys mogą zmieniać encryption context;
  przykład ogranicza kms:ViaService, nie narzuca niezweryfikowanego context prefix.
- Bucket pozostaje prywatny z Block Public Access, bez public ACL/policy; wymuszone HTTPS.
- Ograniczenie aws:SourceIp do rzeczywistych origin-pull CIDR dodatkowo ogranicza użycie klucza.
  Lista jest aktualizowana z wyprzedzeniem (suma starej i nowej). Testy bezpośrednie S3 z innej
  sieci mają odmówić dostępu. Weryfikacja zakresu uprawnień wymaga symulatora lub kontrolowanego
  środowiska z dozwolonym source IP, inaczej każda próba będzie odrzucona przez warunek IP.
- Przykład `examples/direct-s3-iam/` tworzy user/policy, lecz nie klucze. Jest osobnym rootem
  Terraform, bez wywołania z głównego stosu. Nie uruchamiaj go automatycznie przy deployu proxy.
- Nie używaj `aws_iam_access_key` w tym projekcie: sekret trafiłby do stanu Terraform.
  Utworzenie i przekazanie klucza wykonuje uprawniony operator/proces sekretów poza stanem.
- Tencent otrzymuje klucz szyfrowanym kanałem sekretów, nigdy w tickecie, logach czy tfvars.
  Rola rotacyjna należy do operatora AWS; użytkownik originu nie rotuje sam swoich kluczy.

## Rotacja bez przerwy, maksymalnie 90 dni

IAM access keys **nie wygasają automatycznie po 90 dniach**. Data w tagu również niczego
nie egzekwuje. Organizacja musi monitorować wiek (credential report/AWS Config
`ACCESS_KEYS_ROTATED`, maxAccessKeyAge=90 przy istniejącym recorderze), alarmować i wyłączyć
starą parę w terminie. Nie włączono automatycznego wyłączania kluczy ani harmonogramu.

1. Najpóźniej około dnia 75 rozpocznij rotację; sprawdź listę kluczy i wiek.
   IAM dopuszcza maksymalnie dwa klucze użytkownika. Jeśli oba sloty są zajęte, ustal ich
   użycie przed jakimkolwiek usuwaniem. Nie usuwaj klucza „żeby zrobić miejsce” bez weryfikacji.
2. Utwórz drugi klucz. Zachowaj SecretAccessKey w zatwierdzonym managerze sekretów podczas
   tworzenia; później AWS go nie zwróci. Przy utracie odpowiedzi uzgodnij stan użytkownika,
   nie twórz kolejnych kluczy w ciemno. Utracony sekret wymaga usunięcia tej pary i utworzenia nowej.
3. Tencent wdraża nową parę we wszystkich konfiguracjach/regionach. Oba klucze pozostają aktywne.
   Potwierdza AccessKeyId (identyfikator, nie sekret) oraz zakończenie propagacji.
4. Wykonaj nowe cache missy GET/HEAD/Range na dedykowanych obiektach. Sam cache HIT nie dowodzi
   użycia nowego klucza. Sprawdź CloudTrail **S3 data events** i używany accessKeyId;
   `GetAccessKeyLastUsed` jest pomocnicze i nie jest dowodem pełnego rollout'u.
5. Najpóźniej do 90 dni wieku starego klucza ustaw go Inactive. Jeśli rollout nie jest gotowy,
   eskaluj przed terminem; nie przedłużaj ważności automatycznie.
6. Sprawdź działanie nowego klucza i odmowę starego na fresh origin fetch. W razie awarii
   rollback wymaga jawnej decyzji operatora i uwzględnienia polityki 90 dni.
7. Po uzgodnionym oknie obserwacji usuń nieaktywny klucz; zachowaj audyt dat i potwierdzeń,
   bez SecretAccessKey. Zamknij zgłoszenie rotacyjne.

## Testy odbioru wariantu S3

- Pozytywne: obiekt w dozwolonym prefiksie GET/HEAD/Range z EdgeOne; poprawne TLS/SigV4/region.
- Negatywne: odczyt innego prefiksu, PUT, DELETE, listowanie, inny bucket i source IP są odrzucane.
- Stary i nowy klucz działają podczas overlapu; po Inactive stary nie działa, nowy działa.
- Rollout przerwany w połowie nie skutkuje przedwczesnym wyłączeniem starego klucza.
- Złe nowe credentials/region dają błąd originu, nie fałszywy sukces z wcześniej zbuforowanej odpowiedzi.
- API nadal działa z własnym tokenem; klucze S3 nie docierają do API ani logów/odpowiedzi.

Źródła sprawdzone podczas implementacji:
- https://edgeone.ai/document/74785 — origin AWSS3 i jego konfiguracja.
- https://intl.cloud.tencent.com/ind/document/product/1145/72828 — autoryzacja originu S3-compatible.
- https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_access-keys.html
- https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_access-keys-update.html
