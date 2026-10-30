> Aktualizacja 2026-10-30: polecenia infrastruktury wykonuj z `infrastructure/`.
> Build/publikacja proxy odbywa się w osobnym repo; przekazujesz wyłącznie digest obrazu.
> Odbiór: `ACCEPTANCE.md`; alternatywa IAM user + klucze: `TENCENT_IAM.md`.

# Wdrożenie i utrzymanie

## Wymagania

Terraform >=1.7,<2, AWS provider 6.x (konkretna wersja w lockfile), AWS CLI v2, Docker z buildx, Python 3.13; JDK 25 i Maven 3.9+ tylko dla proxy i narzędzi operatora. Role wdrożeniowe muszą mieć uprawnienia do zarządzanych usług, `servicequotas:GetServiceQuota` (kontrola quoty w planie) i `iam:PassRole` ograniczone do tworzonych ról. Role aplikacyjne z tego kodu nie służą do wdrażania.

Logowanie operatora: profil SSO/Identity Center, np. `aws sso login --profile deployment`, następnie `export AWS_PROFILE=deployment`. CI: OIDC do roli z ograniczonymi warunkami issuer/audience/repo/environment. Nie twórz IAM usera. Sposób bootstrapowania trustu CI zależy od dostawcy CI i nie został zgadnięty.

Podaj istniejące VPC i po dwie podsieci publiczne/prywatne w różnych AZ. Publiczne muszą mieć IGW; prywatne potrzebują dostępu do ECR API/DKR, S3, Secrets Manager i CloudWatch Logs. Można użyć NAT albo odpowiednich interface endpoints (z Private DNS, SG TCP/443 od zadań). **S3 gateway endpoint przypięty do tabel routingu podsieci prywatnych jest wymagany operacyjnie:** bez niego każdy bajt czytany przez proxy z S3 przechodzi przez NAT i jest liczony jako przetwarzanie danych NAT. Stos nie tworzy routingu ani endpointów; blok `check "s3_gateway_endpoint"` wypisuje ostrzeżenie w planie, gdy endpointu brak. Zadania nie używają publicznych IP.

Backend musi być osiągalny z NLB, mieć stabilne adresy podane w `backend_ips`, obsługiwać HTTP na `backend_port` oraz własne uwierzytelnianie użytkowników. Przy ECS/EKS z dynamicznymi adresami rejestrację targetów musi przejąć kontroler usługi: nie utrzymuj ręcznie przemijających IP w Terraformie. Do startu produkcyjnego zapewnij backend w co najmniej dwóch AZ.

Certyfikaty ACM dla `static_origin_hostname` i `api_nlb_tls_hostname` muszą być ISSUED w tym samym regionie co load balancery. DNS walidacyjny ACM jest własnością zespołu infrastruktury. Dla NLB API Gateway wskazuje ARN listenera, więc nazwa używana do SNI nie wymaga oddzielnego rekordu routującego ruch; musi pasować do certyfikatu. Publiczna nazwa statycznego originu jest tworzona w Route53.

**Quota reguł security group.** Lista EdgeOne trafia do managed prefix list, a reguła SG odwołująca się do prefix list zużywa tyle reguł, ile wynosi jej `max_entries` (domyślnie 200). Domyślna quota „Inbound or outbound rules per security group” (Service Quotas, `vpc` / `L-0EA8095F`) to 60. Przed pierwszym planem podnieś ją co najmniej do `edgeone_prefix_list_max_entries`. Iloczyn tej quoty i „Security groups per network interface” (domyślnie 5) nie może przekroczyć 1000, więc 200 reguł × 5 SG mieści się w limicie. Quota dotyczy całego regionu konta — uzgodnij to z właścicielem konta. Plan zatrzyma się z czytelnym błędem, jeśli quota jest za niska.

## 1. Stan Terraform

`bootstrap` i `infra` to dwa niezależne root modules. Domyślnie Terraform użyje stanu lokalnego; przed produkcją dodaj do obu plik `backend.tf` wskazujący istniejący, prywatny i wersjonowany backend S3 z szyfrowaniem oraz blokadą, z różnymi kluczami stanu. Bucket stanu nie jest bucketem publikowanych zasobów.

Przykład dla Terraform >=1.10 (dla tego przykładu podnieś również minimalną wersję root module):

```hcl
terraform {
  backend "s3" {
    bucket       = "YOUR-EXISTING-TFSTATE-BUCKET"
    key          = "edgeone/prod/infra.tfstate"
    region       = "eu-central-1"
    encrypt      = true
    use_lockfile = true
  }
}
```

Nie wpisuj poświadczeń w backend ani plan. Zapisane plany i state nadal traktuj jako dane poufne. `.terraform.lock.hcl` jest dołączony; przechowuj go w repozytorium. Po wybraniu platform CI użyj `terraform providers lock -platform=linux_amd64` oraz ewentualnie innych platform.

## 2. Build authorizera i narzędzi operatora

```bash
python3 scripts/build_authorizer.py
mvn -f operator-tools/pom.xml verify  # narzędzia operatora, niezależne od Lambdy
alias origin-tools='java -jar operator-tools/tools/target/origin-tools.jar'
```

Terraform wskazuje `build/authorizer.zip` (zmienna `authorizer_package`), więc build musi poprzedzić `plan` głównego stosu. CLI używa domyślnego łańcucha poświadczeń AWS SDK — profilu SSO (`AWS_PROFILE`) albo roli w CI.

## 3. ECR i sekrety

```bash
export AWS_REGION=eu-central-1
terraform -chdir=bootstrap init
terraform -chdir=bootstrap plan -out=bootstrap.tfplan
terraform -chdir=bootstrap apply bootstrap.tfplan

origin-tools tokens init "$(terraform -chdir=bootstrap output -raw static_secret_arn)"
origin-tools tokens init "$(terraform -chdir=bootstrap output -raw api_secret_arn)"
```

Każdy origin ma **osobny sekret**: proxy czyta tylko sekret static, authorizer tylko sekret API. Błędna edycja lub rotacja jednego sekretu nie wyłącza drugiego originu, a przejęcie jednego komponentu nie ujawnia tokenu drugiego. Terraform odrzuca konfigurację z tym samym ARN dla obu.

`init` generuje losowy token (384 bity, base64url), nie wyświetla go ani nie zapisuje na dysku, wypisuje tylko fingerprint i odmawia nadpisania istniejącej wartości. Format sekretu: `{"tokens":["<32–256 znaków base64url>"]}`, 1 lub 2 różne tokeny. Nie edytuj JSON-a ręcznie w konsoli — narzędzie waliduje go tymi samymi regułami co aplikacja i zapisuje z kontrolą współbieżności (compare-and-swap na `AWSCURRENT`). Uprawnienia operatora: `secretsmanager:GetSecretValue`, `PutSecretValue`, `UpdateSecretVersionStage`.

Wartość do przekazania zespołowi Tencent operator pobiera przy rotacji opcją `add --out PLIK` (plik tworzony wyłącznie z prawem 0600, odmowa nadpisania) albo z konsoli Secrets Manager. Bootstrap używa domyślnego klucza Secrets Manager. Dla własnego CMK zmień zasoby sekretów i podaj `secret_kms_key_arn`; upewnij się, że key policy dopuszcza role.

## 4. Obraz proxy

```bash
REPO=$(terraform -chdir=bootstrap output -raw repository_url)
REGISTRY=${REPO%%/*}
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$REGISTRY"
mvn verify   # uruchom w OSOBNYM repo proxy
docker buildx build --platform linux/amd64 --tag "$REPO:release-001" --push proxy
DIGEST=$(aws ecr describe-images --repository-name "${REPO#*/}" --image-ids imageTag=release-001 --query 'imageDetails[0].imageDigest' --output text)
printf '%s@%s\n' "$REPO" "$DIGEST"
```

Wstaw wynik jako `proxy_image`. Tag ECR jest immutable; dla kolejnego builda użyj nowego tagu. Obraz uruchamiany jest po digest, jako UID 10001, z read-only root filesystem. Proxy działa na Jetty 12 z wątkami wirtualnymi; konfiguracja (bucket, prefiks, sekret, idle timeout, współbieżność S3, domyślny `Cache-Control`) przychodzi ze zmiennych środowiskowych ustawianych przez Terraform (`ProxyConfig`). JVM startuje z `-XX:MaxRAMPercentage=75 -XX:-UsePerfData -XX:+ExitOnOutOfMemoryError` (read-only root filesystem, zadanie po OOM jest zastępowane). Dockerfile używa obrazu `public.ecr.aws/amazoncorretto/amazoncorretto:25`; przed produkcją przypnij jego zatwierdzony digest. Wersje wszystkich zależności i pluginów Maven są przypięte w `pom.xml` repo proxy; skan CVE zależności (np. OWASP Dependency-Check lub skan ECR) pozostaje częścią waszego CI.

## 5. Lista IP EdgeOne

Zespół Tencent przekazuje listę origin-pull IPv4 z **nowej wersji** Origin Protection jako plik tekstowy (jeden adres lub CIDR w linii, `#` to komentarz):

```bash
origin-tools cidrs edgeone-current.txt
# zapisuje infra/edgeone_cidrs.auto.tfvars.json, wypisuje diff i sumę SHA-256
```

Narzędzie odrzuca adresy prywatne/zarezerwowane i prefiksy krótsze niż /8, normalizuje zapis, scala sąsiednie zakresy, pomija IPv6 z ostrzeżeniem i kończy się błędem, gdy liczba wpisów przekracza `--max-entries`. Wygenerowany plik commituj; zmiana listy przechodzi przez review i zwykły `plan`/`apply`. Lista trafia do managed prefix list, z której korzystają reguła SG ALB i authorizer API (odczyt w czasie działania, odświeżany co 5 min; przy niedostępnym EC2 API najwyżej godzinę używana jest poprzednia lista, potem odmowa). Zmiana listy nie wymaga ponownego wdrożenia Lambdy. Nie wpisuj `edgeone_ipv4_cidrs` do `terraform.tfvars`.

**Zmiana listy po stronie Tencent.** Nowa wersja Origin Protection ogłasza zmianę 14 dni wcześniej i wprowadza ją automatycznie. Procedura:

1. Po otrzymaniu powiadomienia uruchom `cidrs` z listą bieżącą **i** nadchodzącą (`origin-tools cidrs current.txt upcoming.txt`), zrób review diffu i wdroż przed datą zmiany.
2. Potwierdź z Tencent zgodność sumy `tencent_handoff.origin_pull_cidrs.sha256`.
3. Po dacie zmiany uruchom `cidrs` z samą nową listą, aby usunąć wycofane zakresy.

Ustal właściciela tej procedury i kanał powiadomień przed produkcją. Wycofany zakres pozostawiony na liście jest ryzykiem (adres może trafić do innego użytkownika), a brakujący zakres powoduje odrzucanie żądań na obu originach.

## 6. Cache-Control przed migracją

Proxy przekazuje `Cache-Control` z metadanych obiektu. Obiekty bez tej metadanej dostają `default_cache_control` (domyślnie `no-store`), więc EdgeOne ich nie zbuforuje, jeśli respektuje nagłówki originu. CloudFront buforował takie obiekty według Default TTL swojej cache policy (jeśli był większy od 0), więc bez audytu po cutoverze każde żądanie do nich trafiłoby do proxy (obciążenie originu i opóźnienia dla użytkowników). Przed migracją uruchom audyt rolą publishera/operatora (`s3:ListBucket`, `s3:GetObject`; nie rolą proxy):

```bash
origin-tools audit-cache-control "$BUCKET" --prefix public/ --csv missing-cache-control.csv
```

Narzędzie tylko czyta (HEAD na każdym obiekcie; dla dużych bucketów użyj `--max-objects` do próbki). Wynik przekaż zespołowi Tencent i wybierzcie jedną z opcji:

- **uzupełnienie metadanych** przy publikacji, np. `aws s3 cp ... --cache-control 'public,max-age=31536000,immutable'` dla fingerprintowanych plików i krótki TTL dla `index.html` (opcja preferowana, bo decyzja jest per plik);
- **reguła TTL w EdgeOne** dla uzgodnionych rozszerzeń lub ścieżek;
- **`default_cache_control`** w Terraform, np. `public, max-age=300` — dotyczy wszystkich obiektów bez metadanej, łącznie z `index.html`; ustaw tylko wtedy, gdy pod prefiksem nie ma treści, której nie wolno buforować.

## 7. Główny stos

```bash
cp infra/terraform.tfvars.example infra/terraform.tfvars
# Uzupełnij identyfikatory, certyfikaty, digest, ARN-y obu sekretów i limity API.
# infra/edgeone_cidrs.auto.tfvars.json (krok 5) i jar authorizera (krok 2) muszą istnieć.
terraform -chdir=infra init
terraform -chdir=infra fmt -check
terraform -chdir=infra validate
terraform -chdir=infra test
terraform -chdir=infra plan -out=infra.tfplan
terraform -chdir=infra apply infra.tfplan
terraform -chdir=infra output -json tencent_handoff
```

Nie ma terraform apply do Tencent. `api_rate_limit` i `api_burst_limit` celowo nie mają wartości domyślnych: throttling stage'u jest jedną pulą dla wszystkich klientów, bo cały ruch przychodzi z EdgeOne. Ustaw je na podstawie metryki `Count` obecnego API (szczyt z zapasem); limity per klient konfiguruje Tencent (HANDOFF, pkt 11).

Dla nowego bucketa uploaduj pliki osobną rolą publishera (to nie rola proxy):

```bash
BUCKET=$(terraform -chdir=infra output -raw bucket_name)
aws s3 cp ./index.html "s3://$BUCKET/public/index.html" --content-type text/html --cache-control 'public,max-age=60'
```

Bucket ma Block Public Access, wyłączone ACL, wersjonowanie, SSE-S3, zakaz HTTP i ochronę przed usunięciem. S3 endpoint website nie jest używany. Prefix domyślny `public/` jest dodawany tylko przez proxy, nie przez EdgeOne.

## Certyfikat originu statycznego (opcjonalnie dedykowany CA)

Origin Certificate Validation w EdgeOne sprawdza wystawcę i ważność certyfikatu, nie nazwę hosta. Przy zaufanym Amazon Root CA EdgeOne zaakceptuje dowolny certyfikat ACM, więc podstawienie originu wymaga tylko przejęcia rekordu DNS `static_origin_hostname`. Wpływ jest ograniczony (token static daje dostęp wyłącznie do publikowanych plików i tylko z adresów EdgeOne), ale jeśli polityka wymaga silniejszego powiązania:

1. Wydaj certyfikat dla `static_origin_hostname` z **dedykowanego** CA używanego wyłącznie do tego celu (AWS Private CA albo własne CA w HSM/KMS), zaimportuj go do ACM i podaj jego ARN jako `static_certificate_arn`.
2. W EdgeOne ustaw jako zaufany **tylko** ten CA.
3. Certyfikaty importowane do ACM nie odnawiają się automatycznie: ustaw alarm na metryce ACM `DaysToExpiry` i właściciela odnowienia.

Niezależnie od wyboru chroń strefę Route53 (ograniczone uprawnienia IAM do zmiany rekordów, CloudTrail i alarm na `ChangeResourceRecordSets`).

## Istniejące S3, API Gateway i NLB

**Istniejący bucket:** ustaw `create_bucket=false`, nazwę i prefix. Terraform nie modyfikuje jego polityki ani public access block. Przed wdrożeniem potwierdź Block Public Access, BucketOwnerEnforced, TLS-only, szyfrowanie, region i brak konfliktującego explicit Deny. Przy SSE-KMS ustaw ARN klucza i key policy. To wariant tego samego konta; cross-account wymaga dodatkowej bucket/key policy i nie jest automatycznie konfigurowany. Nie przełączaj istniejącego zarządzanego bucketa z true na false bez zaplanowanej operacji stanu.

**Istniejący NLB:** domyślny kod tworzy nowy, więc nie zastępuje po cichu starego. Aby użyć istniejącego, w `api.tf` zastąp `aws_lb_listener.api.arn` konkretnym wejściem ARN listenera i usuń zasoby NLB/target group/attachments/listenera z tego root module; w `network.tf` użyj SG istniejącego NLB. Pozostaw VPC Link, jego SG i dozwolony ruch do listenera. Istniejący NLB musi być internal, w tym samym koncie/regionie/VPC, z TLS listenerem i SG nadanym już przy utworzeniu. NLB utworzony bez SG może wymagać przebudowy. Nie wyłączaj kontroli SG PrivateLink automatycznie — opisany HTTP API używa VPC Link v2.

**Istniejące API Gateway:** wdrażaj zmianę w jego repozytorium/stanie albo przeprowadź jawny import zasobów po przeglądzie planu. Nowy root nie przejmuje dotychczasowych tras, authorizerów, custom domain, JWT ani rate limits. REST API wymaga innych zasobów Terraform i nie powinno być importowane jako `aws_apigatewayv2_api` typu HTTP. Należy zachować dotychczasowe uwierzytelnianie użytkownika, mapowanie ścieżek i CORS. Ten pakiet stanowi kompletny wariant nowego HTTP API/NLB, nie automatyczny migrator dowolnego API.

## Rotacja tokenów

Dla każdego originu osobno (`ARN` = sekret static albo API):

1. `origin-tools tokens add ARN --out /bezpieczna/sciezka/nowy-token` — sekret zawiera teraz `[stary, nowy]`; narzędzie wypisuje fingerprinty.
2. Odczekaj do 90 s (patrz niżej) i przetestuj nowy token bezpośrednio na originie z dopuszczonego adresu.
3. Przekaż plik zatwierdzonym kanałem i usuń go. Zespół Tencent ustawia nowy token, potwierdza globalne wdrożenie i **fingerprint**.
4. `origin-tools tokens retire ARN --keep <fingerprint nowego>`; po 90 s wykonaj test odrzucenia starej wartości.

`origin-tools tokens status ARN` pokazuje liczbę aktywnych tokenów i ich fingerprinty. Narzędzie odmawia dodania trzeciego tokenu i usunięcia, gdy rotacja nie trwa.

**Cache tokenów w procesach.** Proxy i authorizer trzymają tokeny w pamięci: przez 30 s bez odwołań do Secrets Manager, potem odświeżają je w tle jednego żądania, a pozostałe żądania korzystają z poprzedniej wartości. Jeśli odświeżenie się nie uda, poprzednia wartość działa najdłużej do 90 s od ostatniego udanego odczytu, a potem dostęp jest odmawiany (fail-closed). Po błędzie kolejna próba następuje po 5 s, więc awaria Secrets Manager nie ustawia żądań w kolejce. Usunięty token przestaje działać zwykle po ~30 s, najpóźniej po 90 s. API Gateway ma wyłączony cache decyzji authorizera. W razie wycieku cofnięcie tokenu nie usuwa wcześniej zbuforowanych publicznych plików — to osobna invalidacja CDN.

## Migracja i rollback

Najpierw uruchom nowe originy i testy w domenie testowej EdgeOne. Zachowaj CloudFront i jego OAC do końca okna migracyjnego. Nowa rola ECS nie wymaga usunięcia istniejącej polityki OAC z bucketa. Przed cutoverem wykonaj audyt Cache-Control (krok 6). Zweryfikuj cache, ścieżki, cookies, logowanie i obsługę błędów; następnie obniż TTL DNS i wykonaj uzgodniony cutover.

Rollback: przywróć DNS/routing do działającego CloudFront, bez usuwania danych S3 i dotychczasowych uprawnień OAC. Originy AWS pozostają do diagnozy. Dopiero po okresie obserwacji usuń starą dystrybucję i niepotrzebne granty. Nowy bucket ma prevent_destroy, ALB/NLB deletion protection — sprzątanie wymaga osobnej świadomej zmiany.

## Utrzymanie

Logi są w CloudWatch, retention 30 dni. Proxy loguje status, rozmiar i czas, bez URL/query/headerów. API loguje route key, status, czas, adres źródłowy połączenia i błąd authorizera, bez tokenu; adres spoza listy EdgeOne oznacza próbę bezpośredniego wejścia na `execute-api`. Ustaw alarmy i kanał on-call dla ALB/NLB unhealthy targets i 5xx, ECS running tasks, API 4xx/5xx/latency/throttle, authorizer errors oraz błędów Secrets Manager/S3/EC2 (`refresh_failed`, `origin_auth_unavailable`, `s3_origin_failure` w logach). Kanał alarmowy i progi zależne od SLO nie są tworzone automatycznie.

**Pojemność i skalowanie proxy.** Żądania działają na wątkach wirtualnych, więc ograniczeniem nie jest liczba wątków, tylko pula połączeń do S3: `proxy_max_concurrency` (domyślnie 256) równoległych odczytów na zadanie; nadmiarowe żądania czekają na połączenie do 10 s, potem dostają 502. Zadania skalują się 2–6 według dwóch polityk target tracking: CPU 60% oraz `ALBRequestCountPerTarget` (`proxy_target_requests_per_minute`, domyślnie 1500). Skalowanie w górę następuje, gdy którakolwiek polityka tego wymaga, w dół — gdy obie na to pozwalają. Streaming jest ograniczony I/O, więc pula połączeń i sieć zwykle kończą się przed CPU. Wartość docelową dobierz z testu obciążenia na rzeczywistych rozmiarach plików: zmierz liczbę żądań na minutę, przy której p99 czasu odpowiedzi zaczyna rosnąć, i ustaw ok. 60–70% tej wartości.

Idle timeout połączeń Jetty jest ustawiany na idle timeout ALB + 5 s (125 s). Krótszy timeout po stronie celu powoduje sporadyczne 502, gdy ALB użyje połączenia zamkniętego przez proxy. Deregistration delay wynosi 120 s: przy scale-in i deployu trwające strumienie mają czas się zakończyć, zanim ECS wyśle SIGTERM. Rolling deploy trwa przez to dłużej.

**Authorizer (Python 3.13).** ZIP buduje `python3 scripts/build_authorizer.py` w repo
infrastruktury. Zmiana hasha publikuje wersję i przestawia alias `live`. Bez SnapStart.
Pamięć domyślnie 256 MB. SDK boto3 pochodzi z runtime; testy live obowiązują przy aktualizacji
runtime. Własny cache jest lokalny dla środowiska wykonania. Metryki i alarmy opisuje README.
Stare wersje usuwaj dopiero po oknie rollbacku.

Dodaj syntetyczny odczyt kontrolnego obiektu: sam health check procesu nie wykrywa utraty uprawnień S3. Aktualizacje listy IP Tencent oraz certyfikatów muszą mieć właścicieli.
