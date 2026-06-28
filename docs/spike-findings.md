# Spike 결과 — Java 25 vthread 협력 런타임 (설계 §10.1)

> 본 구현(PA–PF) 착수 전, 설계의 핵심 가정을 깨보는 spike. **결론: 전부 통과 — go.**
> 코드: [`spike/`](../spike/). 실행: `spike/run.sh`(JDK 25). 베이스라인 JDK 25 + JDK 21 양쪽에서 통과.

## 요약 (go/no-go)

| step | 검증 대상 | 결과 |
|---|---|---|
| 0 | JDK 25에서 `StructuredTaskScope` preview 여부 | ✅ **여전히 preview** — hand-roll 결정 확정 |
| 1 | Coop/scheduler (바톤·상태머신·wake timer·결정론적 round-robin) | ✅ 30/30 run에서 `ABCABCABC` 고정 |
| 2 | 재귀 인터프리터가 콜스택 어디서든 협력 중단 | ✅ `x=f()`·`a+f()`·`return f()` 모두 오버랩(~1x) |
| 3 | `Coop.blocking`으로 async statement-replay 제거 | ✅ 선행 부작용 **1회만** 실행 (v1은 2회) |
| 4 | hand-rolled `multi` result/monitor/purity | ✅ `{status,ok,value}`·라이브 monitor·monitor 종료 순서·top-level write 차단 |
| 5(회귀) | `Coop.blocking` 예외 시 바톤 재획득 | ✅ throwing worker → `{status:error}`, 데드락 없음 |

**→ 본 구현 진행 권고.** 단일 바톤 vthread 코루틴 모델이 의도대로 동작하며, **preview API 의존 0**으로 베이스라인을 만족한다. (코드 리뷰 4건 반영: blocking 예외 안전·monitor 종료 순서·purity claim 한정·30회 stress 자동화.)

## step 0 — STS는 JDK 25에서도 preview (실측)

`spike/StsProbe.java`(JEP 505 재설계 API `open()`/`Joiner`/`fork`/`join` 사용)를 **JDK 25.0.3**에서:

- `javac StsProbe.java` (preview 플래그 없음) → **컴파일 실패**:
  `error: StructuredTaskScope is a preview API and is disabled by default. (use --enable-preview ...)`
- `javac --release 25 --enable-preview StsProbe.java` → 성공(`uses preview features of Java SE 25`).

→ 설계 §0.1 결정 **확정**: STS 회피, `multi`는 `newVirtualThreadPerTaskExecutor`/직접 fork·join으로 hand-roll. STS 정식화(26+ 예상) 전까지 국소 캡슐화 유지. **preview 의존 0** 달성(런타임 코드는 25/21 모두 `--enable-preview` 없이 컴파일).

## step 1 — Coop/scheduler

`Scheduler.java`: 단일 permit 개념의 **바톤**, `NEW/READY/RUNNING/SLEEPING/BLOCKED/WAITING/DONE` 상태머신, `(wakeTime, id)` 우선순위 **wake timer**, **id 정렬 round-robin** 핸드오프(`selectNext`: lastRunId보다 큰 첫 READY, 없으면 최소 id READY).

- 결정론 검증: 3 fiber가 매 스텝 `Coop.yield()` → **`ABCABCABC`** 고정. `spike/run.sh stress 30` → **30/30 동일**(데드락/플레이키 0).
- 핵심: vthread 스케줄러의 비결정성은 바톤(한 번에 하나)이 차단하고, "다음 바톤 수령자"는 스케줄러가 id 기준으로 결정 → fixture 출력 순서 재현 가능(설계 §6).

## step 2 — 콜스택 어디서든 협력 중단

`Interp.java`: stepper/replay 없는 **순수 재귀 tree-walk**(`eval(Node,env)`). suspend 지점은 `f()→g()→SLEEP(100)`로 **2 프레임 깊이**에 둠.

4 worker × `SLEEP(100ms)`를 세 가지 seam 형태로 실행한 wall-clock:

| seam 형태 | 측정 | 직렬(~4×100) 대비 |
|---|---|---|
| `x = f()` | ~107ms | 오버랩(~1x) |
| `a + f()` (mid-expression) | ~105ms | 오버랩(~1x) |
| `return f()` | ~101ms | 오버랩(~1x) |

→ **Java 콜스택 자체가 continuation**(설계 §1)이라, 대입 우변·이항 연산 오른쪽 피연산자·return 식 한복판에서 협력 중단·재개가 정상 동작. seam 1·2·mid-expression 해결을 실측으로 확인.

## step 3 — async replay 제거

한 문장 `x = SIDE_EFFECT(+10) + BLOCKING(80ms→5)`에서 **선행 부작용(SIDE_EFFECT)** 실행 횟수:

- `Coop.blocking`은 바톤 반납→실제 blocking(vthread, off-baton)→재획득→**그 자리에서 값 반환**. 콜스택이 보존되므로 문장 재실행이 없다.
- 측정: **side-effect 실행 = 1회**(30회 반복 모두 1), `x = 15.0`. v1의 statement-replay라면 2회.

→ seam 4(정확성) 근본 제거 확인. "바톤 보유 중 blocking 금지" 불변식도 이 경로로 강제됨(실제 `Thread.sleep`은 오직 `Coop.blocking` 안에서만 호출).

## step 4 — multi result/monitor/purity

`Coop.multi(specs, monitor, globals)`: STS 없이 워커별 fiber fork → 부모 `WAITING` 협력 대기 → 완료 시 결과 수집.

- **결과 포맷**: `{status, ok, value}` (running/done/error) — value-model §7 동일. spec 순서 = 결과 맵 iteration 순서(결정론).
- **라이브 monitor**: 30ms 인터벌로 살아있는 결과 맵을 read-only로 읽음. settled-per-tick = `[1, 2, 3, 3, 4, 4]` → running→done 전이를 실시간 관측(부분 상태 → 완전 settled).
- **monitor 종료 순서**: 부모는 worker뿐 아니라 **monitor 종료까지 기다린 뒤 재개**(parentPending = workers + monitor) → monitor **최종 tick이 post-multi 출력보다 먼저** 보장(`56_monitor_reads_result` 순서 요건). 본 구현에선 이 순서를 **스케줄러 책임**으로 묶을 것(설계 §4).
- **purity(부분)**: 워커는 globals를 unmodifiable 스냅샷으로 받아 **top-level write만** 차단(`rogue`의 global write → `{status:error}`). ⚠️ **중첩 object/list mutation은 막지 못함** — 실제 read-only global + COW/deepCopy(value-model §5/§6)는 **값 계층(PA–PE)**에서 강제. spike는 의도적으로 거기까지 가지 않는다.

## step 5(회귀) — `Coop.blocking` 예외 안전

`work.get()`이 throw해도 **바톤을 반드시 재획득**(try/finally)한 뒤 예외를 전파. 안 그러면 throwing fiber가 off-baton에서 `complete()` 되어 `current`/aliveCount가 깨지고 **데드락**. check 5가 회귀로 고정: throwing blocking worker → `{status:error, value:"io failed"}`, run은 정상 종료(데드락 없음).

## 한계 / 본 구현에서 처리할 것

- 본 spike는 ANTLR/실제 builtins/값 포맷·에러 메시지를 **재현하지 않음**(스케줄링 모델 검증 전용). 값 동치는 PA–PE에서 fixture로 검증.
- **purity는 top-level write만** 검증(위 step 4) — 중첩 mutation/COW/deepCopy는 값 계층(PA–PE) 작업.
- self 추적에 `ThreadLocal<Fiber>` 사용. **PC에서 `ScopedValue`(25 정식)로 교체**(설계 §5).
- monitor 종료를 spike에선 `multi`가 직접 join하지만, **본 구현은 스케줄러가 monitor tick/cancel을 소유**해야 함(설계 §4).
- SLEEP wake는 `(wakeTime, id)` 정렬로 동시 만료 tie-break까지 결정론적. 단 **실시간 타이밍 민감 fixture**(`20_thread_monitor`, `56_monitor_reads_result`)는 PE에서 재검증·필요 시 새 fixture 분기(설계 §6, conformance-tests.md).
- kill/timeout, watchdog(장시간 바톤 점유 감지)는 spike 범위 밖 — 본 구현(설계 §9)에서.

## 재현

```bash
spike/run.sh            # checks 1-5 (JDK 25, preview 플래그 없음)
spike/run.sh stress 30  # 30회 반복 — 결정론/데드락 검증 (ABCABCABC ×30, side-effect=1 ×30)
spike/run.sh sts        # step 0: STS preview 실측
```
