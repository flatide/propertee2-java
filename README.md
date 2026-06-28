# ProperTee2 for Java (Java 25 · virtual-thread runtime)

`propertee2-java`는 [ProperTee](https://github.com/flatide/ProperTee) 언어의 **완전 협력형(fully-cooperative) 런타임**이다. 동결된 [`propertee-java`](https://github.com/flatide/propertee-java) v1.0.0(Java 7/8, stepper 기반)이 남긴 eager seam을 **Java 21 virtual thread(Project Loom) 기반 코루틴(Strategy B)** 으로 근본 해결하는 것을 목표로 한다. **TeeBox**가 안정화 후 이 런타임을 사용한다.

- **베이스라인:** Java 25 LTS (stable API만 — virtual threads + `ScopedValue`. `StructuredTaskScope`는 25에서도 preview이므로 회피). 근거는 설계 문서 §0.1.
- **문법/값 의미는 v1과 동일.** 바뀌는 것은 **스케줄링(중단/협력)** 뿐이다.

## 두 라인의 관계

| | `propertee-java` v1.0.0 | `propertee2-java` (이 repo) |
|---|---|---|
| JDK | Java 7/8 | Java 25 LTS |
| 실행 모델 | stepper + scheduler | vthread 코루틴 + 단일 바톤 |
| 소비처 | 레거시 expression-evaluator 서버 | TeeBox |
| 정책 | 동결(보안/버그픽스만) | 활성 개발 |

## 현재 상태 — 스캐폴딩 (구현 전)

이 repo는 아직 **엔진 구현 전**이며, v1과의 **의미 동치를 보장하기 위한 자산**을 먼저 복사해 둔 상태다:

```
grammar/ProperTee.g4                      # 문법(v1과 동일, 변경 없음)
docs/
  java25-vthread-runtime-design-ko.md     # 설계도(Strategy B, 베이스라인/바톤/Coop 계약/단계)
  value-model-and-builtins.md             # 새 엔진이 재현해야 할 값-수준 의미 계약
  conformance-tests.md                    # v1 의미 동치가 필요한 테스트 목록 + 정책
  LANGUAGE.md                             # 언어 명세 정본(v1에서 복사)
src/test/resources/tests/                 # .tee / .expected conformance fixtures (84쌍, v1에서 복사)
```

## 다음 단계 (설계 §10.1 spike)

0. JDK 25에서 `StructuredTaskScope` preview 여부 실측(여전히 preview면 hand-roll 유지).
1. Coop/scheduler 최소 프로토타입(바톤, READY/SLEEPING/BLOCKED/WAITING, wake timer, 결정론적 round-robin).
2. 재귀 인터프리터에서 `SLEEP`, `x = f()`, `a + f()`, `return f()` 협력 중단 PoC(타이밍 오버랩 입증).
3. `Coop.blocking`으로 async statement-replay 제거 확인.
4. `multi` result/monitor conformance 확인.

spike 통과 후 본 구현(PA–PF). 자세한 내용은 [`docs/java25-vthread-runtime-design-ko.md`](docs/java25-vthread-runtime-design-ko.md).

## 참고

- 값/타입/스코프/Result/에러 메시지/builtin 반환은 **v1과 1바이트도 다르면 안 된다** — [`docs/value-model-and-builtins.md`](docs/value-model-and-builtins.md).
- 출력 순서 동치를 위해 스케줄러는 **결정론적 round-robin**을 명시 고정해야 한다(설계 §6).
