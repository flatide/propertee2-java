# ProperTee2 for Java (Java 25 · virtual-thread runtime)

`propertee2-java`는 [ProperTee](https://github.com/flatide/ProperTee) 언어의 **완전 협력형(fully-cooperative) 런타임**이다. [`propertee-java`](https://github.com/flatide/propertee-java) v1(Java 7/8, stepper 기반)이 남긴 eager seam을 **Java 25 virtual thread(Project Loom) 코루틴**으로 근본 해결한다. 값/타입/스코프/에러 메시지 의미는 **스펙에 고정**된다. 현재는 **스펙 v0.24.0**(`WORDS`, `TRIM`/`WORDS` 공백 집합 고정, 빈 `SPLIT` 구분자 에러; [`docs/LANGUAGE.md`](docs/LANGUAGE.md) changelog)을 구현한다. 스케줄링(중단/협력)은 v1의 eager seam을 제거한 완전 협력형이다.

- **베이스라인:** Java 25 LTS, **stable API만** (virtual threads + `ScopedValue`). `StructuredTaskScope`는 25에서도 preview라 회피 — `multi`는 hand-roll. **preview 의존 0.**
- **상태:** **conformance 스위트 132/132 통과** (byte-for-byte, deterministic). 0.32.0은 스펙 v0.24.0을 구현한다 — 공백 단위 분리 `WORDS(s)` 추가, `TRIM`/`WORDS`의 공백을 ASCII 6자(space, `\t`, `\n`, VT, `\f`, `\r`)로 고정, 빈 `SPLIT` 구분자는 런타임 에러. 0.31.0의 REPL `:run`/`:debug`/`:list`와 0.30.0의 depth 1 `import`는 그대로다. Java 7/8 구현체는 갱신하지 않는다.

## 빌드 / 테스트

JDK 25 toolchain이 필요하다(`~/.gradle/gradle.properties`의 `org.gradle.java.installations.paths`에 JDK 25 home 등록). 두 모듈로 구성된다: **`propertee-core`**(엔진 — TeeBox가 의존), **`propertee-cli`**(`propertee2` 커맨드).

```bash
./gradlew build     # 두 모듈 컴파일 + 전체 테스트(conformance 132 fixture 포함)
```

## 실행 (CLI)

```bash
./gradlew dist                                  # → dist/propertee2-0.32.0.jar + dist/propertee2.jar(안정 경로 사본)
java -jar dist/propertee2-0.32.0.jar script.tee

# 호스트 프로퍼티 주입(-p, JSON object → 빌트인 프로퍼티 / _PROPS)
java -jar dist/propertee2-0.32.0.jar -p '{"width":100,"height":200}' script.tee

# 스크립트 파일 없이 실행하면 인터랙티브 REPL (0.18.0)
java -jar dist/propertee2-0.32.0.jar
```

**SHELL:** CLI(스크립트 모드·REPL 모두)는 in-process `SimpleTaskRunner`(`/bin/sh -c`, Linux/macOS; 스트림당 최신 1 MiB rolling cap)를 기본 배선하므로 `SHELL("...")`이 호스트 설정 없이 바로 동작한다. 제한을 넘으면 오래된 출력은 누락될 수 있고 최신 suffix가 Result에 남는다. 전체 대용량 출력이 필요하면 파일로 명시적으로 redirect한다. 임베딩 기본값은 여전히 `UnsupportedTaskRunner`(`SHELL() requires a host-provided TaskRunner ...`) — 필요하면 `new Engine().setTaskRunner(new SimpleTaskRunner())`.

> 런타임에 **JDK 25**가 필요하다. 개발 중에는 `./gradlew :propertee-cli:run --args="script.tee"`도 가능.

**REPL(0.18.0):** 전역 변수·사용자 함수가 입력 사이에 유지되고, 각 입력은 스크립트와 완전히 동일한 의미론으로 실행된다(`SLEEP`/`multi`/`thread` 포함). 블록(`if`/`loop`/`function` … `end`)이 닫히지 않으면 다음 줄로 계속 입력(`...>` 프롬프트; 빈 줄로 취소), 마지막 맨 표현식의 값은 PRINT 표시형으로 에코된다(`{}`는 에코 안 함). 런타임 에러는 스크립트 모드와 같은 `Runtime error:` 한 줄을 찍고 세션은 계속된다. `:help`/`:exit`. 파이프 입력도 동작한다(`echo 'FIND("abcd","c")' | propertee2` → `[ 3 ]`). 임베딩은 `Engine.session(props, out, err)` → `Session.eval(snippet)`(에코 값 반환, 없으면 Java null).

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
