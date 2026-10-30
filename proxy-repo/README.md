# Origin proxy

```bash
mvn verify
# Zatwierdzony digest obrazu bazowego należy podać w Dockerfile przed produkcją.
docker buildx build --platform linux/amd64 -f proxy/Dockerfile proxy -t YOUR_ECR_IMAGE
```

Źródła i testy w `proxy/`, lokalna biblioteka kontraktu tokenów w `origin-common/`.
Żadne pliki z repo infrastruktury nie są wymagane podczas builda.
Pipeline publikuje obraz w ECR, skanuje go i przekazuje digest do PR w repo infrastruktury.
Wdrożenia nie mogą zależeć od mutowalnego tagu.

`PROXY_MAX_CONCURRENCY` ogranicza obsługiwane żądania (auth + S3 + transfer do odbiorcy)
i pulę połączeń S3. Bez kolejki; przekroczenie limitu daje 503/Retry-After/no-store.
`/_origin_health` nie zużywa slotów. SDK czeka maksymalnie 250 ms na połączenie z puli.
Timeout całego wywołania SDK dotyczy uzyskania odpowiedzi, nie stanowi limitu długości
strumienia po przekazaniu ResponseInputStream. Transfer chronią timeouty socketów/Jetty/ALB;
profil dużych plików i wolnych odbiorców wymaga testu na rzeczywistym środowisku.

EMF: `EdgeOne/Proxy`, wymiar `Service=METRICS_SERVICE` (Terraform ustawia nazwę stosu),
co 30 s: Active, PeakActive, Rejected, S3AcquireMaxMicros, S3AcquireSamples.
Brak próbki SDK nie oznacza zerowego oczekiwania — sprawdzaj S3AcquireSamples.
Obecny autoscaling nadal korzysta z CPU i liczby requestów; alarm zajętości strumieni
wskazuje konieczność dostrojenia po teście. Nie przyjmuj domyślnych progów za sizing produkcji.

Testy JUnit obejmują HTTP → Jetty → prawdziwy AWS SDK → lokalny fake S3,
przeciążenie, brak wywołania S3 po odrzuceniu, działanie health checku przy pełnym obciążeniu,
powrót do pracy oraz zwalnianie slotów po błędach.
