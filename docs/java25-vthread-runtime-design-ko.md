# ProperTee — Java 25+ Virtual-Thread 런타임 설계도 (Strategy B)

> 상태: 설계 초안 (검토용). 구현 전 합의용 문서. **베이스라인: Java 25 LTS** (§0.1).
> 대상: TeeBox가 사용할 **별도 프로젝트**. 레거시 expression-evaluator 서버는 동결된 `propertee-java` v1.0.0(Java 7/8)을 계속 사용.

## 0. 목적과 범위

**목표.** v1.0.0(Java 7/8, stepper 기반)에 남아 있는 eager seam을 근본적으로 제거한 **완전 협력형 런타임**을 만든다. virtual thread(Project Loom, Java 21 정식)를 사용해 "콜스택 어디서든 중단"을 달성한다. 베이스라인은 **Java 25 LTS**(ScopedValue 정식 + vthread pinning 해소; STS는 회피 — §0.1).

v1.0.0에 남아 있는 seam(이 런타임이 닫으려는 대상):
| # | seam | 성격 |
|---|---|---|
| 1 | expression 내부 `SLEEP`(`x = f()` 등) → blocking | 동시성 |
| 2 | `multi` setup phase eager | 동시성 |
| 3 | expression-call 안 긴 loop의 fairness | 동시성 |
| 4 | async statement-replay 선행 부작용 2회 실행 | **정확성** |

**소비처.** TeeBox(독립 서버, JDK를 통제하므로 Java 25 LTS 요구 가능). **비목표:** Java 7/8 호환(포기), 문법 변경(동일 유지), 1단계에서 진짜 병렬(§9 옵션으로 후술).

## 0.1 플랫폼 베이스라인 & preview API 결정 — **Java 25 LTS 기본**

Loom 관련 API는 정식화 시점이 제각각이라, 무엇이 stable인지부터 정확히 못박는다.

| API | 상태 (Java 25 기준) | 비고 |
|---|---|---|
| Virtual threads (`Thread.ofVirtual`, `newVirtualThreadPerTaskExecutor`) | **정식**(JEP 444, Java 21) | + **JEP 491**(Java 24)로 `synchronized` 중 carrier **pinning 해소** |
| `ScopedValue` | **정식**(JEP 506, Java 25) | 25부터 `--enable-preview` 불필요 ✅ |
| `StructuredTaskScope` | **여전히 preview**(JEP 505 = *Fifth* Preview, Java 25; API가 `open()`/`Joiner`로 **재설계**) | 25에서도 `--enable-preview` 필요 ⚠️ |

> ⚠️ **검증 필수:** 위 STS 상태는 착수 전 **실제 JDK 25 빌드에서 확인**한다 — `StructuredTaskScope` 사용 코드를 `--enable-preview` 없이 `javac` → preview면 컴파일 에러. (이 문서의 이전 판이 "STS가 25에서 정식"이라 잘못 적었던 항목이다.)

**결정: Java 25 LTS를 기본 베이스라인으로 한다.** 근거 — TeeBox가 JDK를 통제하고, 25에서 **ScopedValue가 정식**이 되며 **vthread pinning도 24+에서 해소**된다. 단 **STS는 25에서도 preview이므로 의존하지 않는다.**

- **사용(전부 stable):** virtual threads(정식) + **ScopedValue(25 정식)** 로 per-thread context 구성.
- **회피:** `StructuredTaskScope` — `multi`는 `Executors.newVirtualThreadPerTaskExecutor()` + **직접 fork/join·결과수집**으로 hand-roll(이미 현 `Scheduler`가 하는 일). STS가 정식화되면(26+ 예상) 그때 국소 교체. → **preview 의존 0** 유지.

## 1. 핵심 모델 — virtual thread + 단일 바톤(cooperative)

- ProperTee 논리 스레드 1개 = **Java virtual thread 1개**.
- **단일 바톤**(permit 1 `Semaphore` 또는 `LockSupport park/unpark`)으로 "한 번에 하나의 스레드만 인터프리터 코드를 실행"을 강제 → 기존 **purity·무락·결정론** 의미를 그대로 보존.
- yield 지점(`SLEEP` / blocking I/O / spawn join / 문장·반복 경계)에서 바톤을 반납 → 스케줄러가 다음 READY 스레드를 unpark.
- **Java 콜스택 자체가 continuation**이 되므로 표현식 한복판·중첩 함수·루프 내부 어디서든 중단 가능. → seam 1·2 해결, mid-expression 해결.

> 왜 platform thread가 아니라 vthread인가: 과거 우려(스레드당 OS 자원, 수백 개 blocked thread)가 사라진다. vthread는 park해도 carrier를 점유하지 않아 수천~수만 개가 저렴. blocking I/O도 carrier를 막지 않는다.

> ### ⚠️ 핵심 불변식 — "바톤 보유 중 blocking 금지"
> 단일 바톤 모델에서 **바톤을 쥔 채로 blocking하면 cooperative scheduler 전체가 멈춘다.** 이것이 pinning보다 훨씬 큰 위험이다. 따라서 **모든 잠재적 blocking(SLEEP, host I/O, 외부 함수, spawn join)은 반드시 `Coop.*` primitive를 통해 "바톤 반납 → blocking → 바톤 재획득" 계약을 지켜야 한다.** 이 계약을 어기는 경로가 하나라도 있으면 런타임이 정지한다 — §3·§9에서 강제 방법을 규정한다.

## 2. 인터프리터 단순화 (삭제 중심)

이 런타임은 **인터프리터의 실행 메커니즘**을 더하기보다 빼는 리팩터링이다. **단, 스케줄러는 삭제가 아니라 역할 변경이다.**

**삭제(인터프리터 실행 메커니즘):** `Stepper`/`StepResult`/`SchedulerCommand`, `StatementListStepper`·`IfStepper`·`LoopStepper`(계열)·`UserCallStepper`, `AsyncPendingException` + statement replay, `CommandThenDoneStepper`/`ImmediateStepper`, `Scheduler`의 round-robin **`step()` 구동 루프**, `activeThread` 스코프 라우팅 핵.

**복원:** `visit*`가 값을 직접 반환하는 **평범한 재귀 tree-walk**. `evalBlock`/loop/`callUserFunction`이 그냥 재귀 호출. `if`/loop/함수 본문 특별 취급 없음. `SLEEP`/spawn/async/yield만 Coop 런타임 primitive를 호출.

**유지(스케줄러의 책임은 그대로, 구현만 바뀜):** 스케줄러는 `step()` 드라이버에서 **바톤·스레드 상태 코디네이터**로 역할이 바뀐다. 다음은 여전히 필요하다 — `READY/SLEEPING/BLOCKED/WAITING` 상태 머신, **wake timer**(SLEEP 만료), **monitor tick**(인터벌 실행), **result collection**(라이브 갱신/수집), 부모-자식(`multi`) 관계와 join. 즉 "무엇을 관리하는가"는 동일하고, "어떻게 진행시키는가"가 `stepper.step()` 호출 → vthread unpark/바톤 핸드오프로 바뀐다.

## 3. 스케줄러 primitive (Coop)

```
Coop.yield()              // 다른 READY 스레드 있으면 바톤 반납·재획득 (fairness, seam 3)
Coop.sleep(ms)            // wake-time 등록 → 바톤 반납 → park; 타이머 만료 시 READY (seam 1)
Coop.blocking(supplier)   // 바톤 반납 → 이 vthread에서 블로킹 호출(SHELL/HTTP/DB)
                          //   → 결과 수령 → 바톤 재획득 → 그 자리에서 값 반환 (replay 없음, seam 4)
Coop.spawnMulti(specs, monitor)  // 자식 vthread 생성 → 부모는 join에서 바톤 반납·park
                                 //   → 자식 협력 실행 → 완료 시 결과 수집 후 부모 READY
```

- 문장 경계·루프 반복 경계에서 `Coop.yield()` 삽입 → 현재의 per-statement/per-iteration BOUNDARY와 동일 입도의 round-robin 유지.
- `Coop.blocking`이 핵심: async가 **replay 없이** 콜스택 보존 상태로 중단·재개 → seam 4(정확성) 근본 제거. mid-expression async도 자연 동작.

### 3.1 host external function 계약 (안전 필수)

§1의 불변식을 코드로 강제하는 지점. **host가 등록한 외부 함수가 바톤을 쥔 채 그냥 blocking하면 런타임 전체가 멈춘다.** 따라서:

- **모든 잠재적 blocking host I/O는 `Coop.blocking`을 통과해야 한다.** 등록 API를 그렇게 설계한다 — 예: `registerBlocking(name, fn)`(런타임이 자동으로 바톤 반납→`fn` 실행→재획득), `registerPure(name, fn)`(non-blocking 보장, 바톤 유지 가능).
- v1.0.0의 `registerExternal`(sync) / `registerExternalAsync` 이원 구조는 **단일 `Coop.blocking` 계약으로 통합**한다. modern 런타임에선 "sync로 등록했지만 실제로 blocking하는" 위험한 외부 API를 **금지하거나, 자동으로 `Coop.blocking`으로 감싸는 것을 기본값**으로 한다.
- 내장 blocking builtin(`SHELL`/`HTTP`/`FILE_*` 등)도 전부 `Coop.blocking` 경로로 호출. **바톤을 쥔 코드 경로에서 순수 Java blocking 호출(`Thread.sleep`, 동기 소켓 등)을 직접 부르는 일이 없도록** 코드 리뷰/정적 점검 룰을 둔다.
- 가능하면 **pinning 회피**(외부 라이브러리의 `synchronized` blocking을 `ReentrantLock` 경로로) — 단 이는 부차적이며, **1차 안전선은 "바톤 반납"이다.**

## 4. `multi` 구현 (STS 없이 hand-roll)

의미는 동일 — 워커별 vthread, 부모는 join에서 **협력적으로 대기**(바톤 반납), 결과 수집·에러 전파·취소.

- §0.1 결정대로 **STS는 쓰지 않는다**(25에서도 preview). `Executors.newVirtualThreadPerTaskExecutor()`에 워커를 fork하고, **스케줄러가 직접 join/결과수집/취소 관리**(`Future` 또는 카운트다운). 현재 `Scheduler`의 자식 관리·`resultCollection` 라이브 갱신 로직을 그대로 가져와 재사용 — STS가 해줄 일을 우리가 이미 갖고 있다.
- STS가 정식화되면(26+ 예상) 이 부분만 **국소 교체** 가능하도록 `multi` 실행을 한 곳에 캡슐화한다.
- **setup phase도 협력형**(콜스택 실행) → seam 2 해결. 중첩 `multi` setup의 `SLEEP`도 outer 워커를 안 막음.
- **monitor:** 인터벌 타이머(스케줄러 책임)가 바톤 하에 monitor 블록을 잠깐 실행(read-only, 라이브 result 읽기). 동작 동일.
- **purity/결과 포맷 불변:** 워커는 스냅샷 read만, global write 금지, 결과 `{status, ok, value}` 동일.

## 5. per-thread 컨텍스트 = ScopedValue (Java 25 정식)

- 각 vthread는 자신의 `ScopeStack`/글로벌 스냅샷을 **`ScopedValue`** 로 보유(`activeThread` 라우팅 핵 제거). Java 25에서 ScopedValue가 정식이므로 preview 없이 사용 가능, 불변·구조적이라 ThreadLocal보다 누수 위험이 없다.
- 단일 바톤이라 공유 인터프리터 가변 상태에 동시 접근이 없어 안전. (ScopedValue 도입 전 단계에선 명시 `ThreadContext` 전달/`ThreadLocal`로도 동일 의미 구현 가능.)

## 6. 결정론 & yield 지점 (fixture 재사용의 전제)

기존 `.expected`는 **출력 순서에 민감**하므로, fixture를 그대로 쓰려면 vthread 런타임의 round-robin 순서를 **명시적으로 고정**해야 한다(그러지 않으면 vthread 스케줄러의 비결정성이 출력 순서를 흔든다).

- yield는 **명시 지점에서만**: 문장 경계, 루프 반복 경계, `SLEEP`/async/spawn. (vthread는 CPU 루프를 선점하지 않으므로 이 지점이 없으면 양보도 안 한다 — fairness와 직결.)
- **바톤 핸드오프 시 다음 스레드 선택을 결정론적으로**: 현재 `Scheduler.selectNextThread()`와 동일하게 **스레드 ID 정렬 기준 round-robin**으로 고정. vthread 자체 스케줄링 비결정성은 바톤(한 번에 하나)이 차단하고, "누가 다음 바톤을 받는가"는 스케줄러가 결정.
- **주의 — 의미 동치 vs. 신규 인터리빙:** v1.0.0이 eager로 돌리던 seam 케이스(예: expression-call 안의 loop)는 이제 협력적으로 yield하므로 인터리빙이 **달라질 수 있다.** 기존 fixture 중 worker-loop 안에서 PRINT하지 않는 것들은 영향 없음(현 스위트가 그러함)이나, 신규 런타임에서 인터리빙이 바뀌는 케이스는 **새 fixture로 분리**하고 기존 것과의 차이를 문서화한다.

## 7. 재사용 자산

- 문법 `ProperTee.g4`(파서 재생성), builtins 로직, `TypeChecker`/값 모델, `Result`/`PlatformProvider`/`TaskRunner` 인터페이스.
- **테스트 스위트** — `tests/*.tee` + `*.expected` 85쌍, `CooperativeNestingTest`, `SleepNestingTest` → 신규 엔진의 **의미 동치 검증 하네스**. (이게 최강 안전망.)

## 8. 마이그레이션 / 패리티 전략

1. 문법 + builtins + 값모델 포팅(거의 그대로), JDK 21 toolchain Gradle.
2. Coop 런타임 + 재귀 인터프리터 구현.
3. 기존 `.tee/.expected` **전체 통과**로 의미 동치 확보.
4. seam 타이밍 테스트 추가: `x = f()` 내부 `SLEEP`, 중첩 `multi` setup `SLEEP`, mid-expression `a + f()`가 이제 **오버랩(~1x)** 인지.
5. **async replay 제거 검증:** "async 직전 선행 부작용"이 2회→1회로 바뀌는 회귀 테스트(정확성 개선의 증거).

## 9. 위험 & 미해결

- **(최대 위험) 바톤 미반납 blocking.** 단일 바톤 모델에서 한 실행자가 바톤을 쥔 채 막히면 **cooperative scheduler 전체가 정지**한다. §1 불변식 + §3.1 host 계약으로 강제하는 것이 1차 안전선. 회귀 방지: "장시간 바톤 점유 감지"(watchdog: 바톤 보유 시간이 임계 초과 시 경고/로그) 도입 검토.
- **vthread pinning**(`synchronized`/native): 위 위험과 별개의 *부차적* 이슈. 바톤을 이미 반납한 `Coop.blocking` 안에서의 pin은 한 vthread를 carrier에 묶을 뿐 전체를 막지 않는다. JFR `jdk.VirtualThreadPinned`로 모니터하고 가능하면 회피하되, **pinning 자체가 주 위험은 아니다.**
- **kill/timeout:** vthread interrupt + 직접 timeout 관리(STS 미사용). 현재 TaskRunner kill 의미와 매핑.
- **fairness 무한 CPU 루프:** `Coop.yield()` 지점으로 완화하되 loop iteration limit 유지.
- **진짜 병렬(옵션, 후속):** purity model상 워커 병렬 실행은 의미상 안전하나, 공유 인터프리터 상태를 thread-confined(ScopedValue/per-thread scope)로 만들어야 함. I/O 바운드인 TeeBox엔 매력적이나 **1단계 비목표** — 협력형 동치 확보 후 별도 단계로 평가.

## 10. 프로젝트 구조 & 단계

- **별도 repo:** 예) `propertee-jvm21`(또는 `propertee-loom`). group은 `com.flatide` 유지, artifact 분리. TeeBox가 의존 대상을 이쪽으로 전환(안정화 후).
- **모듈:** `core`(grammar + 재귀 interpreter + Coop runtime), `cli`.

### 10.1 먼저 — spike (별도 repo, 큰 투자 전 검증)

구현 전 **spike**로 핵심 가정을 먼저 깬다(리뷰 권고 순서):

0. **베이스라인 확인** — §0.1대로 Java 25 LTS. 실제 JDK 25에서 `StructuredTaskScope`의 preview 여부를 컴파일로 검증(여전히 preview면 hand-roll 유지).
1. **Coop/scheduler 최소 프로토타입** — 바톤, READY/SLEEPING/BLOCKED/WAITING, wake timer, 결정론적 round-robin 핸드오프.
2. **재귀 인터프리터에서 중단 확인** — `SLEEP`, `x = f()`, `a + f()`, `return f()` 가 콜스택 어디서든 협력적으로 중단되는지(타이밍 오버랩으로 입증).
3. **`Coop.blocking`으로 async replay 제거 확인** — "async 직전 선행 부작용 1회만 실행" 검증.
4. **`multi` result/monitor conformance 확인** — 결과 포맷·라이브 monitor 읽기·purity가 v1.0.0과 동치.

spike가 통과하면 본 구현 단계로:

- PA. **JDK 25** Gradle 골격(toolchain 25, preview 플래그 없음) + 문법/builtins/값모델 포팅 + 기존 `.tee/.expected` 반입
- PB. 재귀 인터프리터(stepper 제거판)
- PC. Coop 런타임(바톤, `sleep`, `yield`, `blocking`) + **host external `Coop.blocking` 계약(§3.1)**
- PD. `multi`/monitor — vthread executor + 직접 fork/join·결과수집(STS 미사용; 정식화 시 국소 교체용으로 캡슐화)
- PE. conformance(.expected 전체 통과, **결정론적 순서 고정**) + seam 타이밍 테스트 + async replay-제거 테스트
- PF. 문서/릴리스(0.1.0부터)

## 11. 두 라인의 지원 정책 (분기 명문화)

| | v1.0.0 (Java 7/8, stepper) | modern 런타임 (Java 21+, vthread) |
|---|---|---|
| 소비처 | 레거시 expression-evaluator 서버 | TeeBox |
| 용도 | 제한된 문법의 expression 평가 위주 | 풀 오케스트레이션(multi/monitor/async/SLEEP) |
| 변경 정책 | **동결** — 보안/critical 버그픽스만 | 활성 개발 |
| 신규 기능 | 받지 않음 (필요 시 modern으로) | modern 런타임 only |
| 협력 seam | 문서화된 채로 잔존(정확성 무손상) | 근본 해결 대상 |

> 레거시 라인에 기능 요청이 와도 modern 런타임으로 유도한다. 두 코드를 **동기화하지 않고**, v1.0.0은 단방향 동결 상태로 둔다 → 유지보수 비용을 "두 활성 코드베이스"가 아니라 "하나 동결 + 하나 활성"으로 묶는다.

---

### 부록: seam 해결 매핑 요약

| seam | v1.0.0 | vthread 런타임 |
|---|---|---|
| 1 expression 내 SLEEP | blocking | `Coop.sleep` (콜스택 park) ✅ |
| 2 multi setup eager | blocking | setup도 협력 실행 ✅ |
| 3 fairness CPU 루프 | 점유 | `Coop.yield` 지점 ✅(완화) |
| 4 async replay 부작용 | 2회 실행 | `Coop.blocking`(replay 제거) ✅ |
| mid-expression 일반 | eager | 콜스택 중단 ✅ |
