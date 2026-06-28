# CLAUDE.md

이 파일은 Claude Code(claude.ai/code)가 이 저장소에서 작업할 때의 가이드다.

## 이게 뭔가

`propertee2-java`는 [ProperTee](https://github.com/flatide/ProperTee) 언어의 **완전 협력형(fully-cooperative) 런타임**이다. 동결된 [`propertee-java`](https://github.com/flatide/propertee-java) **v1.0.0**(Java 7/8, stepper 기반)이 남긴 eager seam을 **Java 21 virtual thread(Project Loom) 코루틴(Strategy B)** 으로 근본 해결하는 것이 목표. **TeeBox**가 안정화 후 이 런타임을 사용한다.

> **상태: 엔진 구현 전(스캐폴딩 단계).** 지금 repo에는 v1과의 **의미 동치 보장용 자산**(문법·명세·fixture·계약 문서)만 들어 있다. 다음 작업은 설계 §10.1의 **spike**다.

## 확정된 핵심 결정 (이전 세션에서 합의됨 — 바꾸려면 근거 필요)

1. **두 라인 분기.** v1.0.0(Java 7/8)은 레거시 expression-evaluator 서버용으로 **동결**(보안/버그픽스만). 신규 기능·완전 협력화는 전부 이 repo(modern 런타임). 두 코드는 **동기화하지 않음**(단방향 동결).
2. **베이스라인 = Java 25 LTS, stable API만.** virtual threads(정식) + `ScopedValue`(JEP 506, 25 정식) 사용. **`StructuredTaskScope`는 25에서도 preview(JEP 505, 5th)이므로 회피** — `multi`는 `newVirtualThreadPerTaskExecutor` + 직접 fork/join으로 hand-roll(STS 정식화 시 국소 교체되도록 캡슐화). → **preview 의존 0.** (착수 전 실제 JDK 25에서 STS preview 여부 컴파일 실측할 것.)
3. **실행 모델 = 단일 바톤 vthread 코루틴.** 논리 스레드 1개 = vthread 1개, 바톤(semaphore/park-unpark)으로 **한 번에 하나만** 실행 → 기존 purity·무락·결정론 보존. 콜스택 자체가 continuation.
4. **⚠️ 불변식 — "바톤 보유 중 blocking 금지".** 바톤을 쥔 채 blocking하면 cooperative scheduler 전체가 정지한다(pinning보다 큰 위험). **모든 잠재적 blocking(SLEEP/host I/O/외부함수/spawn join)은 `Coop.*` primitive로 "바톤 반납→blocking→재획득" 계약**을 지켜야 함. host external 등록 API를 이 계약으로 강제.
5. **인터프리터는 재귀 tree-walk로 복원.** v1의 stepper/`SchedulerCommand`/`AsyncPendingException` replay 머신은 **삭제**. 단 **스케줄러는 삭제가 아니라 역할 변경**(바톤·상태머신·wake timer·monitor tick·result collection은 유지).
6. **값 의미는 v1과 1바이트도 다르면 안 됨.** 바뀌는 건 **스케줄링뿐.** `Coop.blocking`으로 async statement-replay를 제거해 "선행 부작용 2회 실행"(정확성 이슈)도 해소.
7. **결정론적 round-robin 순서 고정** 필요 — 안 그러면 복사해온 `.expected`(출력 순서 민감)가 흔들린다.

## 저장소 구조 (현재)

```
grammar/ProperTee.g4                      # 문법(v1과 동일, 변경 없음). ANTLR4.
docs/
  java25-vthread-runtime-design-ko.md     # ★설계도 정본 — 모델/Coop 계약/preview 결정/단계 PA-PF/spike §10.1
  value-model-and-builtins.md             # ★새 엔진이 재현해야 할 값-수준 의미 계약
  conformance-tests.md                    # ★v1 의미 동치 테스트 목록 + 호스트 주입/인터리빙 주의
  LANGUAGE.md                             # 언어 명세 정본(v1 복사)
src/test/resources/tests/                 # .tee / .expected conformance fixtures (84쌍, v1 복사)
```

작업 시작 전 위 ★ 3개 문서를 먼저 읽을 것.

## 다음 작업 — spike (설계 §10.1, 큰 투자 전 검증)

0. JDK 25에서 `StructuredTaskScope` preview 여부 실측(여전히 preview면 hand-roll 유지).
1. **Coop/scheduler 최소 프로토타입** — 바톤, `READY/SLEEPING/BLOCKED/WAITING`, wake timer, 결정론적 round-robin 핸드오프.
2. **재귀 인터프리터 중단 PoC** — `SLEEP`, `x = f()`, `a + f()`, `return f()` 가 콜스택 어디서든 협력 중단되는지(타이밍 오버랩으로 입증).
3. **`Coop.blocking`으로 async replay 제거 확인** — async 직전 선행 부작용 1회만 실행.
4. **`multi` result/monitor conformance 확인** — 결과 포맷·라이브 monitor 읽기·purity가 v1과 동치.

spike 통과 후 본 구현(PA: JDK25 Gradle 골격 + 문법/builtins/값모델 포팅 + fixture 반입 → PB 재귀 인터프리터 → PC Coop 런타임 → PD multi/monitor → PE conformance → PF 릴리스).

## 빌드 (스캐폴딩되면 갱신)

아직 Gradle 미구성. PA에서 **JDK 25 toolchain**(preview 플래그 없음) Gradle 골격을 만든다. 그때 이 절을 빌드/테스트 커맨드로 갱신할 것.

## 관례 (값 의미 — v1 동일, 절대 변경 금지)

- **no null** — 없음은 `{}`(빈 object). 누락 인자·무반환 함수 → `{}`.
- 숫자: 정수 `Integer`, 소수 `Double`, **나눗셈은 항상 Double**, 포맷 시 `.0` 제거.
- strict 타입: `and`/`or`는 boolean, 산술은 number. 단 `+`는 한쪽이 string이면 `TO_STRING` coerce(연결).
- **1-based** 인덱싱(`.1`이 첫 요소). object 정수 키 `obj.1` → 문자열 키 `"1"`.
- escape `\" \\ \n \t \r` 모든 문자열 컨텍스트 처리, 미인식 escape 보존.
- object 리터럴 키는 따옴표 문자열 또는 정수만.
- 대입·루프 바인딩 시 `deepCopy`(COW). multi 워커는 globals read-only(`::`, 스냅샷), write 금지.
- Result: `{status, ok, value}` (running/done/error). 에러 **메시지 문자열까지** v1과 동일.
- 전체 명세·빌트인은 `docs/LANGUAGE.md`, 값 계약은 `docs/value-model-and-builtins.md`가 정본.
