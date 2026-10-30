# Testy

Aktualny katalog testów, komendy i kryteria odbioru znajdują się w [ACCEPTANCE.md](ACCEPTANCE.md).
Status testów wykonanych podczas przygotowania paczki znajduje się w `../../VALIDATION.md`.
Historyczna deklaracja 77 testów starego monorepo nie stanowi walidacji tej wersji.

- Python authorizer: `python3 -m unittest discover -s tests -v`.
- Narzędzia rotacji: `mvn -f operator-tools/pom.xml verify`.
- Proxy: w osobnym repo `mvn verify`.
- Terraform: `terraform -chdir=infra test` (mock provider, brak zasobów AWS).
- Live EdgeOne: `python3 -m unittest discover -s acceptance -p 'test_edgeone_live.py' -v`.
  Domyślnie skipped; wymaga jawnego EDGEONE_LIVE=1 i danych stagingu.
