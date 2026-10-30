# Infrastruktura EdgeOne origin

```bash
python3 -m unittest discover -s tests -v
python3 scripts/build_authorizer.py
terraform -chdir=infra init
terraform -chdir=infra test
# Następnie plan z własnymi tfvars; apply dopiero w procesie wdrożeniowym organizacji.
```

Lambda jest budowana tutaj, używa runtime `python3.13`, handlera `authorizer.handler`,
wersji i aliasu `live`. Nie używa SnapStart. ZIP jest deterministyczny i zawiera źródło;
AWS SDK pochodzi z zarządzanego runtime. To świadoma zależność od wersji runtime:
przed aktualizacją runtime wykonaj testy live. Jeśli organizacja wymaga pełnego locka SDK,
vendoruj razem boto3/botocore i zależności z zatwierdzonego lockfile w etapie pakowania.

Authorizer sprawdza wyłącznie pochodzenie żądania. Uwierzytelnienie użytkownika pozostaje
w backendzie. Metryki EMF rozróżniają błędny token, niedozwolone źródło, awarię EC2,
awarię Secrets Manager, użycie starego cache i niedostępność inicjalizacji.
Cache tokenów: 30 s + maksymalnie 60 s stale. CIDR: 300 s + maksymalnie 3300 s stale.
Zmienne max_stale można obniżyć do 0. Wdrożenie nowej wersji czyści cache nowych środowisk;
nie cofa już autoryzowanych żądań będących w toku. Awaryjnie stosuj też blokadę na edge.

Narzędzia operatora: `mvn -f operator-tools/pom.xml verify`.
Uruchomienie: `java -jar operator-tools/tools/target/origin-tools.jar --help`.
Narzędzia nie są zależnością paczki Lambdy. Rotacja IAM access keys to odrębny proces
od komend `tokens`; opisano go w `docs/TENCENT_IAM.md`.

Przed produkcją ustaw `alarm_action_arns` i potwierdź odbiór powiadomienia.
Kontrole `check` Terraform emitują ostrzeżenia, nie blokują apply: proces wdrożenia musi
traktować niespełnione kontrole routingu/alarmów jako blokujące odbiór.
