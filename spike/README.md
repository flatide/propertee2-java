# spike/ — Java 25 vthread 협력 런타임 검증 (설계 §10.1)

본 구현(PA–PF) 착수 전, 단일 바톤 vthread 코루틴 모델의 핵심 가정을 깨보는 **버릴 수 있는(throwaway) 프로토타입**이다. ANTLR·실제 builtins·값 포맷은 다루지 않는다 — **스케줄링 모델만** 검증한다. 결과 요약은 [`../docs/spike-findings.md`](../docs/spike-findings.md).

```bash
./run.sh            # checks 1-5 (JDK 25, preview 플래그 없음)
./run.sh stress 30  # 30회 반복 — 결정론/데드락 검증
./run.sh sts        # step 0: StructuredTaskScope preview 실측
```

## 구성

| 파일 | 역할 |
|---|---|
| `Scheduler.java` | 단일 바톤 코디네이터 — 상태머신, wake timer, 결정론적 round-robin (설계 §1·§2·§6) |
| `Coop.java` | primitive 표면 — `yield`/`sleep`/`blocking`/`multi` (설계 §3·§4) |
| `Fiber.java`, `FiberState.java`, `Result.java` | fiber = vthread 1개, 상태, `{status,ok,value}` |
| `Node.java`, `Interp.java` | 최소 sealed AST + **재귀 tree-walk**(stepper/replay 없음, 설계 §2) |
| `SpikeMain.java` | check 1-5 하네스(PASS/FAIL; check 5 = blocking 예외 안전 회귀) |
| `StsProbe.java` | step 0 — STS preview 실측(별도 컴파일, `--enable-preview` 필요) |

## 주의

- self 추적은 `ThreadLocal<Fiber>`. 프로덕션(PC)에선 `ScopedValue`(JDK 25 정식)로 교체 — 설계 §5.
- 실제 blocking(`Thread.sleep`)은 **오직 `Coop.blocking` 안에서만** 호출 → "바톤 보유 중 blocking 금지" 불변식(설계 §1)을 구조적으로 강제.
- 버릴 코드다. 본 구현은 fixture 의미 동치(PA–PE)를 별도로 가져간다.
