# ProperTee2 for Java (Java 25 · virtual-thread runtime)

`propertee2-java`는 [ProperTee](https://github.com/flatide/ProperTee) 언어의 **완전 협력형(fully-cooperative) 런타임**이다. [`propertee-java`](https://github.com/flatide/propertee-java) v1(Java 7/8, stepper 기반)이 남긴 eager seam을 **Java 25 virtual thread(Project Loom) 코루틴**으로 근본 해결한다. 값/타입/스코프/에러 메시지 의미는 **스펙에 고정**된다. 현재는 **스펙 v0.30.0**(자릿수 반올림 — `ROUND`·`FLOOR`·`CEIL`의 `digits`와 새 `TRUNC` — 과 서식 출력 `FORMAT`·`PRINTF`; [`docs/LANGUAGE.md`](docs/LANGUAGE.md) changelog)을 구현한다. 스케줄링(중단/협력)은 v1의 eager seam을 제거한 완전 협력형이다.

- **베이스라인:** Java 25 LTS, **stable API만** (virtual threads + `ScopedValue`). `StructuredTaskScope`는 25에서도 preview라 회피 — `multi`는 hand-roll. **preview 의존 0.**
- **상태:** **conformance 스위트 148/148 통과** (byte-for-byte, deterministic). 0.39.0은 스펙 v0.30.0을 구현한다: `ROUND`·`FLOOR`·`CEIL(n, [digits])`가 남길 소수 자릿수를 받고 새 `TRUNC`가 0 쪽으로 자른다 — 이진값이 아니라 `PRINT`가 보여 주는 최단 자릿수를 반올림하므로 `FLOOR(0.29, 2)`는 `0.29`다 — `ROUND`의 동점은 0에서 먼 쪽(`ROUND(-2.5)` → `-3`)이다. 새 `FORMAT(format, values...)`는 `{}`·`{:spec}` 자리 표시(정렬·폭·`0`·`,`·`.Nf`)로 문자열을 만들고, `PRINTF`는 그것을 한 줄로 출력한다. 0.38.0(스펙 v0.29.0)의 `REGEX_FIND`는 `[전체매치, 그룹1, …]` 배열 대신 매치 객체 `{match, position, groups, named}`를 돌려준다 — `groups.N`이 그룹 N이고, `position`은 1부터 세는 위치, `named`는 이름 붙은 그룹을 패턴 순서대로 담는다 — 그리고 새 `REGEX_FIND_ALL`이 모든 매치를 왼쪽부터 겹치지 않게 같은 객체의 배열로 돌려준다. 0.37.0(스펙 v0.28.0)의 `LIST_DIR_NAMES(path, [pattern])`는 이름만이 아니라 `{name, type}`을 돌려준다 — 링크는 따라가지 않고 `"link"`, 디렉터리는 `"dir"`, 그 밖은 `"file"` — 항목마다 lstat 한 번뿐이라 링크가 많은 큰 디렉터리에서도 `LIST_DIR`보다 훨씬 빠르다. 0.36.0(스펙 v0.27.0)의 `LIST_DIR` 변경 — 따라갈 수 없는 링크(대상 없음·접근 불가·순환)는 호출 전체를 실패시키지 않고 type `"link"`, size 0으로 나열되고, 선택 인자 `pattern`(`*`, `?`, `[...]`, `[!...]`, `{a,b}` glob)으로 이름을 거르며, 파일 I/O 에러 메시지는 이유를 앞에 붙인다(`No such file or directory: ./x`) — 은 그대로이고, TeeBox가 쓰는 v1 provider도 같은 코드로 나열한다. 0.35.0의 `FILE_LINES`, 0.34.0의 REPL `:load`와 `--result-only`, 0.30.0의 depth 1 `import`는 그대로다. Java 7/8 구현체는 갱신하지 않는다.

## 빌드 / 테스트

JDK 25 toolchain이 필요하다(`~/.gradle/gradle.properties`의 `org.gradle.java.installations.paths`에 JDK 25 home 등록). 두 모듈로 구성된다: **`propertee-core`**(엔진 — TeeBox가 의존), **`propertee-cli`**(`propertee2` 커맨드).

```bash
./gradlew build     # 두 모듈 컴파일 + 전체 테스트(conformance 137 fixture 포함)
```

## 실행 (CLI)

```bash
./gradlew dist                                  # → dist/propertee2-0.39.0.jar + dist/propertee2.jar(안정 경로 사본)
java -jar dist/propertee2-0.39.0.jar script.tee

# 호스트 프로퍼티 주입(-p, JSON object → 빌트인 프로퍼티 / _PROPS)
java -jar dist/propertee2-0.39.0.jar -p '{"width":100,"height":200}' script.tee

# 스크립트 파일 없이 실행하면 인터랙티브 REPL (0.18.0)
java -jar dist/propertee2-0.39.0.jar

# 결과값만 JSON으로 출력 (0.34.0) — PRINT 출력은 버리고 stderr는 그대로
java -jar dist/propertee2-0.39.0.jar --result-only script.tee
```

**`--result-only`:** 스크립트의 결과값을 JSON 한 줄로 stdout에 찍는다. 결과값은 TeeBox가 run 결과로 돌려주는 값과 같다: 최상위 `return` 값, 없으면 마지막 전역 변수 `result`, 둘 다 없으면 `{}`. `PRINT` 출력은 버리고, stderr로 가는 진단(`[THREAD ERROR]` 등)은 그대로 둔다. 런타임 에러는 결과가 없으므로 `Runtime error:` 줄을 stderr에 쓰고 종료 코드 1로 끝난다(기본 모드는 지금처럼 stdout + 종료 코드 0). 옵션은 스크립트 경로 앞에 둔다(뒤는 `_ARGS`). `-i`와는 함께 쓸 수 없다. 임베딩 호스트는 같은 값을 `Engine.execute(...)`가 돌려주는 `Engine.Outcome#result()`로 얻는다.

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
