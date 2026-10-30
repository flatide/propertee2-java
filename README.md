# ProperTee2 for Java (Java 25 · virtual-thread runtime)

`propertee2-java`는 [ProperTee](https://github.com/flatide/ProperTee) 언어의 **완전 협력형(fully-cooperative) 런타임**이다. [`propertee-java`](https://github.com/flatide/propertee-java) v1(Java 7/8, stepper 기반)이 남긴 eager seam을 **Java 25 virtual thread(Project Loom) 코루틴**으로 근본 해결한다. 값/타입/스코프/에러 메시지 의미는 **스펙에 고정**된다. 현재는 **스펙 v0.31.0**(서식 출력의 숫자 모양 `{0.00}`·`{#,##0}`, 새 `TEXT`와 `ALIGN_LEFT`·`ALIGN_RIGHT`·`ALIGN_CENTER`; [`docs/LANGUAGE.md`](docs/LANGUAGE.md) changelog)을 구현한다. 스케줄링(중단/협력)은 v1의 eager seam을 제거한 완전 협력형이다.

- **베이스라인:** Java 25 LTS, **stable API만** (virtual threads + `ScopedValue`). `StructuredTaskScope`는 25에서도 preview라 회피 — `multi`는 hand-roll. **preview 의존 0.**
- **상태:** **conformance 스위트 172/172 통과** (byte-for-byte, deterministic). 0.46.0은 스펙 v0.37.0을 구현한다: import 바로 뒤의 `on error do ... end`는 실행이 런타임 에러로 멈출 때 기록·알림·보고할 한 곳이다 — try/catch가 아니라 에러가 먼저 보고된 뒤 핸들러가 실행되고, 핸들러가 `return`한 값은 실패와 함께 호스트에 간다(`TeeError.handler()`). 문장 끝의 `otherwise`는 왼쪽 Result가 실패했을 때 대신 쓸 값이나 할 일(`continue`·`break`·`return`·실패에 이름을 붙이는 블록)을 말한다. `on`과 `otherwise`는 예약어가 되고, 값이 다음 줄에서 시작하는 `return`과 같은 함수 본문에 루프가 없는 `break`·`continue`는 로드 시점에 거부된다. 모든 런타임 에러는 위치를 가지며(내장 함수는 호출 위치) 열은 1부터 센다. monitor 본문의 `return`은 그 iteration만 끝내고, monitor 간격은 설정 단계 전에 검사한다. CLI는 런타임 에러 뒤 종료 코드 1로 끝난다. 0.45.0은 스펙 v0.36.0을 구현한다: 수는 항상 유한하다 — 약 1.8×10³⁰⁸을 넘는 소수 계산은 `Infinity` 대신 `Number overflow: the result is too large to represent` 에러이고, `Infinity`나 `NaN`이 든 호스트 값(props, 주입 전역, 외부 함수·raw 내장 함수 결과)은 들어오는 곳에서 거부된다. 전에는 `FLOOR(NaN)`이 `0`, `NaN == NaN`이 `true`처럼 JavaScript 런타임과 달랐다. `SUM`·`MAX`·`MIN`은 스프레드시트처럼 배열을 받아(`MIN(prices)`), 최솟값을 찾으려고 큰 시작값을 넣을 필요가 없다. 음수 `SLEEP`은 에러다. 0.44.0은 스펙 v0.35.0을 구현한다: `JSON_PARSE`·`READ_JSON_FILE`은 `1e999`처럼 표현할 수 없을 만큼 큰 수를 `Infinity`로 들이지 않고, 첫 번째 그런 수와 위치를 알리는 에러 Result(`JSON_PARSE() value.rows.2.size is a number too large to represent`)를 돌려준다 — 전에는 조용히 들어와 나중에 JSON으로 다시 쓸 때에야 실패했다. JSON 파서는 RFC 8259대로만 읽는다: 잘못된 수(`1e`, `[1, 2e]`)나 `\u` 이스케이프, 아주 깊은 중첩은 Java 예외로 실행 전체를 끝냈고, `01`·`.5`·`1.`, 유니코드 공백, 문자열 안의 제어 문자는 JavaScript 런타임과 달리 받아들였는데, 이제 모두 `Invalid JSON: …` 에러 Result다. 중첩은 1000단계까지다. 루프 반복 상한 에러는 어떤 루프든 `Use 'loop ... infinite do'`를 안내한다(전에는 `loop condition infinite do`). 0.43.0은 스펙 v0.34.0을 구현한다: `SUBSTRING`·`SLICE`의 시작은 1 이상의 정수, 길이·개수는 0 이상의 정수여야 하고 배열·문자열 인덱스는 정수여야 한다 — 전에는 `SUBSTRING("hello", 0, 3)`이 조용히 `"hel"`이 되고 인덱스 `1.5`가 `1`로 잘려, 위치 계산의 실수가 드러나지 않았다. 이제는 그 줄에서 `SUBSTRING() start must be an integer of 1 or more`, `Array index must be an integer` 같은 에러로 멈춘다. 끝을 넘는 위치는 전처럼 덜 나올 뿐이고, 문자열 인덱스 에러는 `String index …`라고 말한다. 또 `RANDOM(min, max)`가 구간이 2⁵³을 넘을 때 정수를 건너뛰던 문제(전체 범위에서 홀수만 나왔다)와, 호스트가 `variables`로 넣은 범위 밖 `Long`이 정수 연산에 들어가던 문제(`Long.MAX_VALUE + Long.MAX_VALUE`가 `-2`)를 고쳤다 — 이제 props처럼 소수로 바뀐다. 0.42.0은 스펙 v0.33.0을 구현한다: 정수가 32비트(±2,147,483,647)에서 ±9,007,199,254,740,991(2⁵³ − 1)로 넓어져, 2 GiB가 넘는 파일 크기 합계를 `0`에서 시작해도 되고 1 TiB를 그대로 쓸 수 있다 — 범위 안의 정수는 소수로도 정확하고 JSON 리더들이 똑같이 읽는 범위다. 넘침은 여전히 에러이고 메시지가 한계를 알려 준다(`Integer overflow: the result is outside ±9,007,199,254,740,991`). `MILTIME()`과 파일 크기·시각은 정수가 되고, `RANDOM`은 범위 끝까지의 경계를 받으며(전에는 32비트에서 잘렸다), `SUBSTRING`·`SLICE`의 큰 위치는 끝에 맞춰진다(전에는 값이 돌아 빈 결과). 호스트가 받는 정수는 32비트 안이면 전처럼 `Integer`, 넘으면 `Long`이다. 0.41.0(스펙 v0.32.0): JSON에는 `Infinity`·`-Infinity`·`NaN`을 쓸 방법이 없으므로, `JSON_FORMAT`·`WRITE_JSON_FILE`·문자열이 아닌 HTTP 요청 본문은 그런 수를 만나면 처음 만난 수와 그 위치(`JSON_FORMAT() value.rows.2.rate is Infinity, which JSON cannot represent`)를 알리는 런타임 에러로 실패한다 — 전에는 JSON 리더가 읽지 못하는 토큰 `Infinity`를 썼다 — 그리고 `--result-only`도 그런 결과를 stderr에 알리고 exit 1로 끝난다. 0.40.0(스펙 v0.31.0)에서는 `FORMAT`·`PRINTF`의 자리 표시가 Python식 `{:spec}` 대신 스프레드시트와 같은 숫자 모양을 받는다 — `{0.00}`은 소수 둘째 자리, `{#,##0}`은 천 단위 쉼표, `{000}`은 0 채우기, `{0.0%}`는 퍼센트 — 새 `TEXT(n, pattern)`은 수 하나를 그 모양으로 쓰고, 새 `ALIGN_LEFT`·`ALIGN_RIGHT`·`ALIGN_CENTER(value, width)`는 한글을 2칸으로 세는 화면 칸 너비까지 공백을 채운다. 0.39.0(스펙 v0.30.0)은 `ROUND`·`FLOOR`·`CEIL(n, [digits])`가 남길 소수 자릿수를 받고 새 `TRUNC`가 0 쪽으로 자른다 — 이진값이 아니라 `PRINT`가 보여 주는 최단 자릿수를 반올림하므로 `FLOOR(0.29, 2)`는 `0.29`다 — `ROUND`의 동점은 0에서 먼 쪽(`ROUND(-2.5)` → `-3`)이다. 새 `FORMAT(format, values...)`는 `{}`·`{:spec}` 자리 표시(정렬·폭·`0`·`,`·`.Nf`, 0.40.0에서 숫자 모양으로 바뀜)로 문자열을 만들고, `PRINTF`는 그것을 한 줄로 출력한다. 0.38.0(스펙 v0.29.0)의 `REGEX_FIND`는 `[전체매치, 그룹1, …]` 배열 대신 매치 객체 `{match, position, groups, named}`를 돌려준다 — `groups.N`이 그룹 N이고, `position`은 1부터 세는 위치, `named`는 이름 붙은 그룹을 패턴 순서대로 담는다 — 그리고 새 `REGEX_FIND_ALL`이 모든 매치를 왼쪽부터 겹치지 않게 같은 객체의 배열로 돌려준다. 0.37.0(스펙 v0.28.0)의 `LIST_DIR_NAMES(path, [pattern])`는 이름만이 아니라 `{name, type}`을 돌려준다 — 링크는 따라가지 않고 `"link"`, 디렉터리는 `"dir"`, 그 밖은 `"file"` — 항목마다 lstat 한 번뿐이라 링크가 많은 큰 디렉터리에서도 `LIST_DIR`보다 훨씬 빠르다. 0.36.0(스펙 v0.27.0)의 `LIST_DIR` 변경 — 따라갈 수 없는 링크(대상 없음·접근 불가·순환)는 호출 전체를 실패시키지 않고 type `"link"`, size 0으로 나열되고, 선택 인자 `pattern`(`*`, `?`, `[...]`, `[!...]`, `{a,b}` glob)으로 이름을 거르며, 파일 I/O 에러 메시지는 이유를 앞에 붙인다(`No such file or directory: ./x`) — 은 그대로이고, TeeBox가 쓰는 v1 provider도 같은 코드로 나열한다. 0.35.0의 `FILE_LINES`, 0.34.0의 REPL `:load`와 `--result-only`, 0.30.0의 depth 1 `import`는 그대로다. Java 7/8 구현체는 갱신하지 않는다.

## 빌드 / 테스트

JDK 25 toolchain이 필요하다(`~/.gradle/gradle.properties`의 `org.gradle.java.installations.paths`에 JDK 25 home 등록). 두 모듈로 구성된다: **`propertee-core`**(엔진 — TeeBox가 의존), **`propertee-cli`**(`propertee2` 커맨드).

```bash
./gradlew build     # 두 모듈 컴파일 + 전체 테스트(conformance 137 fixture 포함)
```

## 실행 (CLI)

```bash
./gradlew dist                                  # → dist/propertee2-0.46.0.jar + dist/propertee2.jar(안정 경로 사본)
java -jar dist/propertee2-0.46.0.jar script.tee

# 호스트 프로퍼티 주입(-p, JSON object → 빌트인 프로퍼티 / _PROPS)
java -jar dist/propertee2-0.46.0.jar -p '{"width":100,"height":200}' script.tee

# 스크립트 파일 없이 실행하면 인터랙티브 REPL (0.18.0)
java -jar dist/propertee2-0.46.0.jar

# 결과값만 JSON으로 출력 (0.34.0) — PRINT 출력은 버리고 stderr는 그대로
java -jar dist/propertee2-0.46.0.jar --result-only script.tee
```

**`--result-only`:** 스크립트의 결과값을 JSON 한 줄로 stdout에 찍는다. 결과값은 TeeBox가 run 결과로 돌려주는 값과 같다: 최상위 `return` 값, 없으면 마지막 전역 변수 `result`, 둘 다 없으면 `{}`. `PRINT` 출력은 버리고, stderr로 가는 진단(`[THREAD ERROR]` 등)은 그대로 둔다. 런타임 에러는 `Runtime error:` 줄을 stderr에 쓰고 종료 코드 1로 끝나며, `on error` 핸들러가 실행됐으면 그 핸들러가 `return`한 값이 결과다(0.46.0). 기본 모드는 에러 줄을 전처럼 stdout에 쓰고, 0.46.0부터 종료 코드 1로 끝난다(전에는 0). 결과에 JSON으로 쓸 수 없는 `Infinity`·`-Infinity`·`NaN`이 있어도 같다: `propertee2: the result contains NaN at result.list.2, which JSON cannot represent`를 stderr에 쓰고 종료 코드 1(0.41.0). 옵션은 스크립트 경로 앞에 둔다(뒤는 `_ARGS`). `-i`와는 함께 쓸 수 없다. 임베딩 호스트는 같은 값을 `Engine.execute(...)`가 돌려주는 `Engine.Outcome#result()`로 얻는다.

**SHELL:** CLI(스크립트 모드·REPL 모두)는 in-process `SimpleTaskRunner`(`/bin/sh -c`, Linux/macOS; 스트림당 최신 1 MiB rolling cap)를 기본 배선하므로 `SHELL("...")`이 호스트 설정 없이 바로 동작한다. 제한을 넘으면 오래된 출력은 누락될 수 있고 최신 suffix가 Result에 남는다. 전체 대용량 출력이 필요하면 파일로 명시적으로 redirect한다. 임베딩 기본값은 여전히 `UnsupportedTaskRunner`(`SHELL() requires a host-provided TaskRunner ...`) — 필요하면 `new Engine().setTaskRunner(new SimpleTaskRunner())`.

> 런타임에 **JDK 25**가 필요하다. 개발 중에는 `./gradlew :propertee-cli:run --args="script.tee"`도 가능.

**REPL(0.18.0):** 전역 변수·사용자 함수가 입력 사이에 유지되고, 각 입력은 스크립트와 완전히 동일한 의미론으로 실행된다(`SLEEP`/`multi`/`thread` 포함). 블록(`if`/`loop`/`function` … `end`)이 닫히지 않으면 다음 줄로 계속 입력(`...>` 프롬프트; 빈 줄로 취소), 마지막 맨 표현식의 값은 PRINT 표시형으로 에코된다(`{}`는 에코 안 함). 런타임 에러는 스크립트 모드와 같은 `Runtime error:` 한 줄을 찍고 세션은 계속된다. `:help`/`:exit`. 스크립트 파일은 `:load <file>`로 불러오고(실행하지 않음, 0.34.0) `:run`으로 실행, `:debug`로 첫 문장에서 멈춘 채 실행, `:list`로 줄 번호와 함께 본다. 줄 브레이크포인트는 `:b <line>`. 파이프 입력도 동작한다(`echo 'FIND("abcd","c")' | propertee2` → `[ 3 ]`). 임베딩은 `Engine.session(props, out, err)` → `Session.eval(snippet)`(에코 값 반환, 없으면 Java null).

## 임베드 (Java 호스트)

```java
import com.flatide.propertee2.interp.Engine;

String out = new Engine()
        .registerExternal("GET_BALANCE", args -> lookupBalance((String) args.get(0))) // 값→Result.ok, throw→Result.error
        .registerExternalAsync("DB_QUERY", args -> db.query((String) args.get(0)))    // blocking I/O는 Coop.blocking 경유
        .setIgnoredFunctions(java.util.Set.of("SHELL"))                                 // 특정 함수 비활성화
        .run(source, java.util.Map.of("width", 100));                                  // -p 프로퍼티
// out == 스크립트 stdout(런타임 에러도 "Runtime error: ..." 한 줄로 포함)
```

호스트 통합: built-in 프로퍼티(`-p`/`_PROPS`), `ENV`·파일 I/O(`DefaultPlatformProvider`), 외부 함수 등록(기본 `registerExternal`/`registerExternalAsync`은 **baton 반납=`Coop.blocking` 경유**, non-blocking 보장 시 `registerPure`), 키워드/함수 숨김(`setHiddenKeywords`/`setIgnoredFunctions`). 외부 함수 인자·반환은 deep-copy로 격리된다.

**run 중단(0.16.0):** v1-API façade로 실행하는 호스트(TeeBox처럼 `ProperTeeInterpreter`+`scheduler.Scheduler` 사용)는 다른 스레드에서 `visitor.abort()`(또는 `scheduler.abort()`)를 호출해 실행 중인 run을 협조적으로 끝낼 수 있다 — 문장 경계·루프 반복·SLEEP(즉시 기상)·blocking 호출 반환 시점에서 적용되고, 중단된 `execute`는 스크립트 에러(`ProperTeeError`)와 구별되는 `ProperTeeAborted`를 던진다. 같은 체크포인트가 1024회마다 `Thread.yield()`로 캐리어를 양보해, CPU-bound 스크립트가 같은 JVM의 다른 run들을 굶기지 못한다(`-Dpropertee2.coop.slice=N`으로 조정).

## 동작 모델 (요약)

- ProperTee 논리 스레드 1개 = **Java virtual thread 1개**. **단일 바톤**으로 "한 번에 하나만" 실행 → purity·무락·결정론 보존. 콜스택 자체가 continuation이라 표현식 한복판에서도 협력 중단.
- 모든 잠재적 blocking(`SLEEP`/host I/O/외부 함수)은 `Coop.blocking`으로 **"바톤 반납→blocking→재획득"** → cooperative scheduler를 멈추지 않음.
- `multi`는 워커별 vthread + 결정론적 round-robin(문장 경계 인터리빙) + 라이브 monitor + 워커 purity(globals read-only 스냅샷).

## 두 라인의 관계

| | `propertee-java` v1.0.0 | `propertee2-java` (이 repo) |
|---|---|---|
| JDK | Java 7/8 | Java 25 LTS |
| 실행 모델 | stepper + scheduler | vthread 코루틴 + 단일 바톤 |
| 소비처 | 레거시 expression-evaluator 서버 | TeeBox |
| 정책 | 유지보수(스펙 동기화+보안/버그픽스, 런타임 기능 없음) | 활성 개발 |

## 문서

- [`docs/java25-vthread-runtime-design-ko.md`](docs/java25-vthread-runtime-design-ko.md) — 설계도 정본(모델/Coop 계약/단계 PA–PF)
- [`docs/value-model-and-builtins.md`](docs/value-model-and-builtins.md) — 값-수준 의미 계약
- [`docs/LANGUAGE.md`](docs/LANGUAGE.md) — 언어 명세 / [`docs/conformance-tests.md`](docs/conformance-tests.md) — conformance 목록
- [`docs/spike-findings.md`](docs/spike-findings.md) — 협력 모델 검증 spike 결과
- [`CHANGELOG.md`](CHANGELOG.md)
