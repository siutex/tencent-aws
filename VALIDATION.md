# Walidacja — 2026-10-30

## Wykonane w tej sesji

- PASS: 18 testów `unittest` (17 przypadków authorizera + deterministyczne pakowanie).
- PASS: `compileall` dla plików Python, w tym runnerów acceptance/load.
- PASS: składnia Bash obu skryptów walidacji (`bash -n`).
- PASS: parsowanie XML wszystkich 6 plików POM.
- PASS: ZIP Lambdy zawiera dokładnie `authorizer.py` zgodny ze źródłem; powtarzany build daje identyczne bajty.
- SKIPPED: 6 testów live EdgeOne, celowo bez EDGEONE_LIVE i danych środowiska.

## Niewykonane — wymagane przed merge/produkcją

- Kompilacja Java 25 i testy JUnit proxy/narzędzi, w tym nowe przypadki przeciążenia i rotacji.
  Brak Maven/JDK 25 w środowisku; dostępny launcher Java 17 nie startuje standardowo (libjli.so).
- Terraform fmt/validate/test i rzeczywisty plan. Brak CLI; próba pobrania CLI zakończyła się
  timeoutem proxy sieciowego. Nie sprawdzono schema/plan przez AWS provider w tej sesji.
- Docker build i skan zależności/obrazu; digest bazowego Corretto należy przypiąć w CI.
- Lambda runtime boto3/botocore i testy AWS SDK na rzeczywistym runtime Python 3.13.
  Lokalne testy wstrzykują klientów AWS i nie wymagają boto3.
- Rzeczywisty przepływ EdgeOne → AWS, test obciążeniowy, timeouty i alert delivery.
- Optional direct-s3-iam: przykład polityki/usera nie został zastosowany ani zweryfikowany
  w symulatorze IAM. Nie zawiera kluczy i nie jest wywoływany przez główny root.

Workflow `.github/workflows/validate.yml` w każdym repo oraz skrypty `scripts/validate.sh`
przygotowano do walidacji w CI z wymaganymi narzędziami. Wynik CI musi być zielony przed merge.
Skrypt infrastruktury formatuje HCL (`terraform fmt -recursive`) przed validate/test;
wynik formatowania należy zachować w repo przy pierwszym uruchomieniu lokalnym.
Nie przedstawiamy walidacji poprzedniej wersji z ZIP-a jako wyników tej implementacji.

## Zakres zmian

Punktem odniesienia jest załączona paczka `edgeone-aws-java25.zip`.
Nowy układ repo opisuje README. Główne nowe pliki:
- proxy: AdmissionHandler.java, ProxyMetrics.java, S3PoolMetrics.java;
- infrastruktura: lambda/authorizer/authorizer.py, test_authorizer.py, test_packaging.py,
  monitoring.tf, acceptance/test_edgeone_live.py, acceptance/load_proxy.py;
- aktualizacja SecretTokens.java, TokensCommand.java i testów FakeSecretsManager/OriginToolsTest;
- rozszerzenie OriginProxyTest.java oraz infra/tests/plan.tftest.hcl;
- docs/ACCEPTANCE.md, docs/TENCENT_IAM.md i opcjonalny examples/direct-s3-iam/main.tf.

Brak wdrożenia, utworzenia usera/kluczy IAM, zmiany konfiguracji Tencent lub wysyłania
wiadomości do zespołu Tencent. Parametry i poświadczenia są dostarczane wyłącznie przy odbiorze.

## Weryfikacja podczas review — 2026-10-30

Środowisko: Temurin JDK 25.0.2, Maven 3.9.11, Python 3.13, OpenTofu 1.10.6 z hashicorp/aws 6.71.0
(build OpenTofu; Terraform CLI i rejestr Terraform były niedostępne).

Stan paczki przed poprawkami:
- proxy-repo `mvn verify`: kompilacja z `-Werror` OK; 60/61 testów, **1 niestabilny**
  (`errorsAndDenialsReleaseAdmissionSlots`, sporadycznie także `overloadRejects…`): asercja `active()==0`
  zaraz po odebraniu odpowiedzi wyprzedza zwolnienie slotu w callbacku Jetty. To wyścig w teście, nie wyciek
  (po kilku ms licznik wraca do 0).
- operator-tools `mvn verify`: 52/52 OK.
- Python: 18/18 OK. ZIP **nie** jest bajtowo powtarzalny między środowiskami (zlib vs zlib-ng → inny
  `source_code_hash`).
- `fmt -check`: 4 pliki niesformatowane. `validate`: bootstrap, infra, example OK.
- `tofu test` infra: przerwany — `override_data` z kluczem instancji (`explicit_private["…"]`) jest odrzucany.

Po poprawkach (POPRAWKI_REVIEW.md):
- proxy-repo: 61/61, sześć kolejnych uruchomień bez błędu.
- operator-tools: 52/52 (zmienione asercje rotacji).
- Python: 21/21; ZIP bajtowo powtarzalny (STORED).
- `fmt -check` OK; `validate` ×3 OK; `test` infra 10/10, example direct-s3-iam 2/2.

Nadal NIE wykonano: Terraform CLI (tylko OpenTofu), plan/apply na koncie, build obrazu, testy live/obciążeniowe,
symulacja IAM dla `ec2:GetManagedPrefixListEntries` z zasobem prefix list.
