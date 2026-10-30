# EdgeOne → AWS: proxy i infrastruktura w osobnych repozytoriach

Paczka zawiera dwa niezależne katalogi przeznaczone do skopiowania do osobnych repozytoriów:

- `proxy-repo/`: Java 25, Jetty, obsługa S3, Dockerfile i testy HTTP/przeciążenia.
- `infrastructure/`: Terraform, authorizer Python 3.13, narzędzia operatora, monitoring i testy odbioru.

Infrastruktura otrzymuje obraz proxy przez `proxy_image` przypięty do `@sha256:…`.
Build Lambdy nie potrzebuje checkoutu proxy ani Javy. Narzędzia operatora pozostały w Javie
w `infrastructure/operator-tools/` i mają niezależny build Maven.
`origin-common` w obu repo jest lokalną kopią protokołu v1, nie zależnością między buildami.
Przy zmianie formatu tokenów należy aktualizować i testować oba repo; Python implementuje
identyczny kontrakt: JSON `{"tokens":[…]}`, 1–2 różne tokeny base64url o długości 32–256.

## Co poprawiono

1. Limit całego cyklu żądania proxy; kolejka 0, przeciążenie → 503 + Retry-After + no-store.
2. Slot jest zwalniany dopiero przy sukcesie/błędzie callbacka, również przy błędach auth/S3.
3. Metryki aktywnych żądań, szczytu, odrzuceń i oczekiwania na pulę S3; alarmy CloudWatch.
4. Authorizer: ograniczone retry, kontrola budżetu czasu i paginacji, cache z granicą świeżości,
   rozdzielone metryki odmowy i niedostępności. Nadal fail-closed i brak cache decyzji API Gateway.
5. Rotacja: jawny commit AWSCURRENT, odzyskiwanie po utracie odpowiedzi, niezależne sprzątanie,
   zachowanie pliku przekazania przy nieznanym wyniku. Zapis 0600 przed zmianą AWS.
6. IAM Lambdy ograniczony do ARN jednej prefix list.
7. Weryfikacja wszystkich efektywnych route tables podsieci ECS, także implicit main.
8. Testy lokalne, skrypty testów live i szczegółowe kryteria odbioru.

## Klucze IAM wymagane przez Tencent

Wariant główny pozostaje HTTPS proxy + token originu. Nie tworzy IAM usera ani access keys.
Wymóg AccessKeyId/SecretAccessKey odpowiada alternatywnemu wariantowi **bezpośredni origin S3**.
Oba warianty i rotację co 90 dni opisuje `infrastructure/docs/TENCENT_IAM.md`.
Opcjonalny przykład `infrastructure/examples/direct-s3-iam/` tworzy tylko dedykowanego
użytkownika i politykę read-only; nie jest wywoływany przez główny stos.
Nie jest to kompletny alternatywny deployment: konfiguracja EdgeOne i bucketu pozostaje osobna.

## Uruchomienie walidacji

```bash
(cd proxy-repo && ./scripts/validate.sh)
(cd infrastructure && ./scripts/validate.sh)
```

Wymagania: Python 3.13 w CI, JDK 25, Maven >=3.9, Terraform >=1.7 i dostęp do rejestrów.
Wyniki wykonane w tej sesji i ograniczenia: `VALIDATION.md`.
Scenariusze odbioru produkcyjnego: `infrastructure/docs/ACCEPTANCE.md`.
Żadnych zasobów AWS/Tencent ani kluczy IAM nie utworzono w ramach przygotowania paczki.
