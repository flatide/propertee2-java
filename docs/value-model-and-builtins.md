# ProperTee — 값 모델 & builtin 의미 계약 (conformance contract)

> 이 문서는 새 런타임(`propertee2-java`, Java 25 vthread)이 **반드시 동일하게 재현해야 하는 값-수준 의미**를 요약한다.
> 전체 문법·빌트인 시그니처는 [`LANGUAGE.md`](./LANGUAGE.md)가 정본이며, 이 문서는 그중 **conformance에 필수인 불변식**만 추린 체크리스트다.
>
> **핵심 원칙:** 새 런타임은 **스케줄링(중단/협력)만** 바꾸고 **값 의미는 1바이트도 바꾸지 않는다.** blocking 빌트인은 `Coop.blocking`을 통해 실행될 뿐, 반환값·에러·포맷은 v1과 동일해야 한다. ([`LANGUAGE.md`] 출력과 [`src/test/resources/tests/*.expected`]가 그 기준.)

## 1. 값 타입

| 런타임 타입 | 비고 |
|---|---|
| `Integer` | 정수 |
| `Double` | 소수. **나눗셈은 항상 Double** |
| `String` | |
| `Boolean` | `true`/`false` |
| object(Map) | `LinkedHashMap<String,Object>` — **삽입 순서 보존** |
| list(List) | `ArrayList<Object>` |

- **null 없음.** `return` 없음/빈 `return`/누락 인자/빈 본문 → **`{}`(빈 object)**. "없음"은 null이 아니라 `{}`.
- 숫자 포맷: 정수는 정수로, `Double`이 정수값이면 `.0`을 **떼어** 출력(`TypeChecker` 포맷 헬퍼). 출력 동치의 핵심.

## 2. 연산자 / 타입 규칙 (strict)

- `and`/`or` → 피연산자 **boolean 강제**(아니면 런타임 에러).
- 산술(`- * / %`) → 피연산자 **number 강제**.
- `+` → 한쪽이라도 **string이면** 다른 쪽을 `TO_STRING()`으로 **coerce**(문자열 연결). 둘 다 number면 덧셈.
- `/` → 결과 **항상 Double**.
- 비교 `> < == >= <= !=`.
- truthiness(`isTruthy`): boolean은 그대로. 그 외 타입의 truthy 규칙은 `TypeChecker.isTruthy` 의미를 **그대로** 재현(빈/0/`{}` 처리 포함).

## 3. 인덱싱 / 접근

- **1-based**: 배열/문자열 `.1`이 첫 요소. `visitArrayAccess`가 1-based 정수를 반환하고 소비처가 0-based로 변환.
- object에서 정수 키 `obj.1` → **문자열 키 `"1"`** 로 동작.
- 접근 형태: `obj.prop`, `arr.1`, `obj."key"`, `obj.$var`, `obj.$::var`, `obj.$(expr)`.
- object 리터럴 키는 **따옴표 문자열 또는 정수만**(bare identifier 불가): `{"name": "Alice"}`.

## 4. 문자열 escape

- `\"`, `\\`, `\n`, `\t`, `\r` 를 **모든 문자열 컨텍스트**(리터럴, object 키, 속성 접근)에서 처리.
- 미인식 escape(`\d` 등)는 **그대로 보존**.

## 5. 스코프 해석

- **top-level**: global 변수 → built-in properties.
- **함수/multi setup 내 plain `x`**: 로컬 스코프(top first) → multi result vars → 에러(`::x` 힌트).
- **`::x`**: 로컬 우회, global/snapshot → built-in properties.
- top-level에선 `x`와 `::x` 동치.
- **multi 워커 purity**: globals는 `::`로 **read만**(multi 진입 시 스냅샷), **write 금지**(`::x = v`는 런타임 에러), 로컬은 자유, 결과는 `thread key: f()`로 반환.

## 6. 복사 의미 (COW)

- 대입·루프 변수 바인딩 시 값은 `deepCopy`(테스트 68 `cow_semantics`, 69 `thread_isolation`가 검증). 새 런타임도 동일 deepCopy 지점 유지.

## 7. Result 패턴 (외부 함수/스레드 결과)

- `Result.running()` → `{status:"running", ok:false, value:{}}`
- `Result.ok(v)` → `{status:"done", ok:true, value:v}`
- `Result.error(m)` → `{status:"error", ok:false, value:"…"}`
- 스레드 결과 수집도 동일 포맷. `registerExternal`은 throw를 `{status:"error",…}`로 래핑.

## 8. 구조적 결과 계약 (스크립트 반환)

1. 명시 `return expr` → `hasExplicitReturn=true`, `resultData=value`
2. `return` 없음 → `variables.get("result")` fallback → `hasExplicitReturn=false`
3. 둘 다 없음 → `resultData=null`

## 9. builtin 분류 (의미는 동일, blocking만 스케줄 변경)

| 분류 | 예 | 새 런타임에서의 처리 |
|---|---|---|
| pure | string matching(`CONTAINS`/`MATCHES`…), map ext(`KEYS`/`VALUES`/`MERGE`…), `JSON_PARSE`/`JSON_FORMAT`, `TYPE_OF`, `SUM`/`LEN`/`SORT`… | 바톤 유지한 채 즉시 평가(비-blocking 보장) |
| host-gated | `ENV`, 파일 I/O(`READ_LINES`/`WRITE_FILE`…) via `PlatformProvider` | **`Coop.blocking`** 경유(디스크 I/O는 blocking) |
| blocking/async | `SHELL`, `HTTP`/`HTTP_GET`/`HTTP_POST` | **`Coop.blocking`** 경유(§3.1 host 계약). v1의 sync/async 이원 등록을 단일 계약으로 |
| output | `PRINT`(`Object[]` 인자) | 동일. **출력 순서**는 conformance 핵심(결정론적 round-robin) |

> 전체 빌트인 목록·시그니처·에러 메시지는 [`LANGUAGE.md`] "Built-in Functions" 절이 정본. 새 런타임은 **에러 메시지 문자열까지 동일**해야 함(에러 테스트가 메시지를 검증).

## 10. 무엇이 바뀌고(only) 무엇이 안 바뀌나

- **안 바뀜:** 위 1–9 전부(값/타입/스코프/Result/포맷/에러 메시지/builtin 반환).
- **바뀜:** blocking 빌트인이 스케줄러를 멈추지 않고 협력적으로 중단(`Coop.blocking`) → async statement-replay 제거(같은 문장 선행 부작용 2회 실행 → 1회). 루프/표현식 내 SLEEP 협력화. → 일부 **인터리빙 순서**가 달라질 수 있음(§ conformance-tests.md 참조).
