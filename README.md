# ProperTee2 for Java (Java 25 · virtual-thread runtime)

`propertee2-java`는 [ProperTee](https://github.com/flatide/ProperTee) 언어의 **완전 협력형(fully-cooperative) 런타임**이다. [`propertee-java`](https://github.com/flatide/propertee-java) v1(Java 7/8, stepper 기반)이 남긴 eager seam을 **Java 25 virtual thread(Project Loom) 코루틴**으로 근본 해결한다. 값/타입/스코프/에러 메시지 의미는 **스펙에 고정**된다 — v1과 동일한 스펙 v0.6.0을 거쳐, 스펙 v0.7.0(조건 불리언 엄격화, `and`/`or` 단축 평가, `RANDOM` 단일 인자 폐지, `SLICE` count 규약, `LEN` 엄격화), v0.8.0(일급 `null` — 암묵적 null 없이 JSON 무손실 왕복), 현재는 **스펙 v0.9.0**(`elseif`; 마이그레이션 노트는 [`docs/LANGUAGE.md`](docs/LANGUAGE.md) changelog)을 구현한다. 스케줄링(중단/협력)은 v1의 eager seam을 제거한 완전 협력형이다.

- **베이스라인:** Java 25 LTS, **stable API만** (virtual threads + `ScopedValue`). `StructuredTaskScope`는 25에서도 preview라 회피 — `multi`는 hand-roll. **preview 의존 0.**
- **상태:** **conformance 스위트 96/96 통과** (byte-for-byte, deterministic). 0.5.0 — **스펙 v0.9.0 `elseif`** 구현.

## 빌드 / 테스트

JDK 25 toolchain이 필요하다(`~/.gradle/gradle.properties`의 `org.gradle.java.installations.paths`에 JDK 25 home 등록). 두 모듈로 구성된다: **`propertee-core`**(엔진 — TeeBox가 의존), **`propertee-cli`**(`propertee2` 커맨드).

```bash
./gradlew build     # 두 모듈 컴파일 + 전체 테스트(conformance 96 fixture 포함)
```

## 실행 (CLI)

```bash
./gradlew dist                                  # → dist/propertee2-0.5.0.jar (self-contained fat jar)
java -jar dist/propertee2-0.5.0.jar script.tee

# 호스트 프로퍼티 주입(-p, JSON object → 빌트인 프로퍼티 / _PROPS)
java -jar dist/propertee2-0.5.0.jar -p '{"width":100,"height":200}' script.tee
```

> 런타임에 **JDK 25**가 필요하다. 개발 중에는 `./gradlew :propertee-cli:run --args="script.tee"`도 가능.

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
