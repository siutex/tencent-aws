# Poprawki z review (2026-10-30)

Tylko zmiany bez decyzji architektonicznych. Decyzje (język authorizera, wariant direct S3) opisuje review.

| Obszar | Problem | Poprawka |
|---|---|---|
| proxy `AdmissionHandler` | Anonimowy `Callback` raportuje `InvocationType.BLOCKING` niezależnie od opakowanego callbacku | `Callback.Nested` + `completed()`; zachowany typ wywołania, zwolnienie slotu dokładnie raz |
| proxy testy | Wyścig: `active()==0` sprawdzane przed zakończeniem callbacku → niestabilne testy | `awaitIdle()` z pollingiem do 2 s |
| `build_authorizer.py` | DEFLATE daje różne bajty w zlib/zlib-ng → inny hash → nowa wersja Lambdy zależnie od maszyny | `ZIP_STORED`; test sprawdza metodę kompresji |
| authorizer (Python) | Przy pustym cache 5 s backoffu po jednym błędzie = odmowa wszystkich żądań środowiska przez 5 s | 1 s, gdy nie ma czego serwować; 5 s tylko przy dostępnym stale |
| authorizer (Python) | Udany, ale wolny odczyt był odrzucany i liczony jako awaria (`budget.check()` przed zapisem) | Wartość zapisywana do cache; odmowa tylko bieżącego żądania |
| `tokens add` | Plik przekazania z tokenem powstawał pod docelową nazwą także gdy rotacja się nie udała → ryzyko przekazania nieaktywnego tokenu | Zapis do `FILE.pending`, zmiana nazwy na `FILE` dopiero po potwierdzeniu AWSCURRENT; kody wyjścia 3 (UNKNOWN) i 4 (NOT applied) zamiast stack trace; nierozwiązany `.pending` blokuje kolejne `add` |
| `examples/direct-s3-iam` | Inline user policy ma limit 2 048 znaków; ~100 CIDR go przekracza (200 CIDR ≈ 3 750) | Managed policy (limit 6 144) + precondition na rozmiar + test planu |
| `plan.tftest.hcl` | `override_data` z kluczem instancji — odrzucane (OpenTofu) | Override całego `explicit_private` (wszystkie podsieci → main route table) |
| `validate.sh` | `terraform fmt -recursive` przepisywał pliki zamiast failować CI; 4 pliki niesformatowane | `fmt -check`; pliki sformatowane; dodany `test` dla przykładu |
| `.gitignore` | `*.auto.tfvars.json` ignorował przeglądaną listę CIDR EdgeOne | Wyjątek `!infra/edgeone_cidrs.auto.tfvars.json` |
| monitoring | Wszystkie alarmy `notBreaching`: brak EMF = cisza; brak alarmów ALB/API | Heartbeat `proxy-metrics-missing` (breaching), ALB unhealthy/target 5xx/ELB 5xx, API 5xx |
| docs | Nagłówek „Build Java” dla kroku budującego Python | Poprawiony |
