#!/usr/bin/env python3
"""정적 점검. Android 타입 검사나 네트워크 통합 테스트를 대신하지 않는다."""
import re
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[1]
src = root / 'app/src/main/kotlin/com/copenhagen/wallet'
files = list(src.rglob('*.kt'))
text = '\n'.join(p.read_text() for p in files)
checks = 0

def expect(name, condition):
    global checks
    checks += 1
    if not condition:
        raise AssertionError(name)

app = (src/'ui/App.kt').read_text()
model = (src/'AppModel.kt').read_text()
for screen in ['A01','A02','A03','A04','A05','A06','A07','A08','W01','W02','C01','C02','C03',
               'D01','D02','D03','U01','P01','P02','P03','P04','P05','P06','P07','H01','H02','H03','S01','S02','X01']:
    expect('route '+screen, f'Screen.{screen}' in app and re.search(r'\b'+screen+r'\(',model))
expect('one app module', 'include(":app")' in (root/'settings.gradle.kts').read_text() and not (root/'logic').exists())
for removed in ['UnavailableTokenizer', 'IntegrationContract', 'FakeAddressRepository', 'interface CardTokenizer', 'WorkManager', 'reauthProof']:
    expect('removed '+removed, removed not in text)
expect('no application network logs', not re.search(r'\b(?:println|printStackTrace|HttpLoggingInterceptor)\s*\(', text))
expect('no charge endpoint', '/internal/v1/charges' not in text and '/merchant/v1/payments' not in text)
api = (src/'data/Api.kt').read_text()
pg = api.split('suspend fun tokenize(')[1].split('private suspend fun authenticated')[0]
expect('PG no bearer parameter', 'token =' not in pg and '.accessToken' not in pg)
expect('PG is a network request', 'http.request(store.pg' in pg and '/api/pg/v1/payment-methods/tokenize' in pg)
expect('PG dev only', 'check(BuildConfig.DEBUG)' in pg)
expect('PG provider unchanged', 'result.provider == "mock-pg"' in pg)
cards = (src/'features/Cards.kt').read_text()
expect('separate UUID operation keys', 'tokenizeKey = newKey(); registerKey = newKey()' in cards)
expect('reuse existing token', 'token ?: api.tokenize' in cards)
expect('no tokenization success fixture', 'tok_mock_' not in text)
expect('Pay nested token', 'registerCardBody(tokenized), registerKey' in cards)
approvals = (src/'features/Approvals.kt').read_text()
expect('separate card PATCH', 'api.pay("PATCH", "/payment-approvals/${approval.id}/payment-method"' in approvals)
expect('detail device header', 'deviceHeader = true' in approvals)
expect('direct UTF8 biometric challenge', 'challenge.challenge.toByteArray(Charsets.UTF_8)' in (src/'features/Biometrics.kt').read_text())
expect('secret screenshot flag', 'FLAG_SECURE' in (src/'MainActivity.kt').read_text())
expect('no native JS bridge', 'addJavascriptInterface' not in text)
expect('no packaged font files', not any(root.rglob('*.ttf')) and not any(root.rglob('*.otf')))
for file in (root/'app/src/main').rglob('*.xml'):
    ET.parse(file)
    expect('XML '+file.name, True)
for field in ['CORE_BASE_URL','PAY_BASE_URL','PG_MOCK_BASE_URL']:
    expect('editable build host '+field, field in (root/'app/build.gradle.kts').read_text())
expect('no unused lifecycle compose dependency', 'lifecycle-runtime-compose' not in (root/'app/build.gradle.kts').read_text())
print(f'{checks} static checks passed; {len(files)} production Kotlin files. No Android compilation performed.')
