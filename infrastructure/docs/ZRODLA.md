# Źródła i podstawa decyzji

Dokumentacja sprawdzona 29.10.2026. Linki prowadzą do źródeł producentów. Dostępność funkcji EdgeOne i zachowanie konkretnego planu należy potwierdzić podczas handoffu.

| Źródło | Zastosowanie |
|---|---|
| https://edgeone.ai/document | Główny katalog dokumentacji wskazany przez zamawiającego |
| https://edgeone.ai/document/81975 | Konfiguracja originu S3-compatible zawiera Access Key ID i Secret Access Key; sama ta strona nie dowodzi obsługi STS refresh/federacji |
| https://edgeone.ai/document/50547 | Typy originów AWSS3/IPDomain, parametry originów i certyfikatów |
| https://edgeone.ai/document/46186 | Modyfikacja nagłówków origin-pull: operacja Set daje unikalny nagłówek, Add dopisuje kolejny — podstawa kontraktu własnego nagłówka |
| https://edgeone.ai/document/54211 | Domyślne przekazywanie nagłówków klienta i nagłówki EdgeOne — konieczność nadpisania tokenu klienta |
| https://edgeone.ai/document/74344 | Origin Certificate Validation: domyślnie wyłączona, sprawdza wystawcę (zaufane CA) i ważność, nie nazwę hosta; plan Basic lub wyższy |
| https://edgeone.ai/document/70549 | Origin Protection, nowa wersja: mniej niż 200 zakresów IP, zmiany średnio co 3–6 miesięcy, powiadomienie 14 dni wcześniej, zmiana wchodzi automatycznie; dostępna we wszystkich planach |
| https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-iam-roles.html | Uprawnienia aplikacji przez rolę zadania i mechanizm tymczasowych poświadczeń SDK |
| https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-private.html | HTTP API → VPC Link → listener NLB; wspólne konto, konfiguracja TLS i usunięcie stage z path |
| https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-lambda-authorizer.html | REQUEST authorizer, payload 2.0, prosta odpowiedź boolean |
| https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-parameter-mapping.html | Zachowanie ścieżki i usunięcie sekretu przed backendem |
| https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-quotas.html | Limit payloadu 10 MB i maksymalny timeout HTTP API 30 s |
| https://docs.aws.amazon.com/en_us/vpc/latest/userguide/work-with-cust-managed-prefix-lists.html | Reguła SG z prefix list zużywa `max_entries` reguł quoty SG; zmiana rozmiaru listy |
| https://repost.aws/knowledge-center/increase-security-group-rule-limit | Domyślnie 60 reguł inbound na SG; iloczyn reguł i SG na interfejs ≤ 1000 |
| https://github.com/hashicorp/terraform-provider-aws/issues/14192 | Kod quoty `L-0EA8095F` („Inbound or outbound rules per security group”) |
| https://docs.aws.amazon.com/corretto/latest/corretto-25-ug/docker-install.html | Obraz bazowy `public.ecr.aws/amazoncorretto/amazoncorretto:25` |
| https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_integration | Schemat integration_uri jako listener ARN, tls_config i request_parameters |

**Wniosek projektowy:** IAM task role w AWS usuwa potrzebę przekazywania AWS credential do Tencent. To niezależne od tego, czy konkretny wariant EdgeOne ma dodatkową integrację federacyjną. Origin HTTPS z tokenem jest świadomie dodatkową warstwą — nie jest natywnym odpowiednikiem CloudFront OAC. Publiczna dokumentacja EdgeOne nie opisuje uwierzytelniania originu certyfikatem klienta (origin mTLS), dlatego token w nagłówku pozostaje mechanizmem uwierzytelnienia; jeśli Tencent udostępni tę funkcję w używanym planie, warto ją rozważyć jako zamiennik tokenu.

Aktualizacja Python/rotacja: https://docs.aws.amazon.com/lambda/latest/dg/lambda-python.html

https://docs.aws.amazon.com/secretsmanager/latest/apireference/API_UpdateSecretVersionStage.html
https://docs.aws.amazon.com/vpc/latest/userguide/managed-prefix-lists.html
https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_access-keys-update.html
