# Conformance 테스트 목록 — v1 의미 동치 기준

출처: `propertee-java`(v1.0.0) `ScriptTest`의 `testNames` 배열 + `src/test/resources/tests/*.tee`/`*.expected`(84쌍, 이 repo로 복사됨). **spec v0.7.0 breaking 배치**(ProperTee 이슈 #1/#2/#5/#6/#7)에서 신규 `87`–`92` 6쌍이 추가되고 기존 3쌍이 갱신됐고, **spec v0.8.0 null 리터럴**(이슈 #4)에서 신규 `93`–`96` 4쌍 + `84_json` 갱신, **spec v0.9.0 elseif**(이슈 #3)에서 신규 `97`–`98` 2쌍, **spec v0.10.0 Result 승격**(이슈 없음 — design-draft-result-handling.md)에서 신규 `99`–`103` 5쌍, **spec v0.11.0 함수 이름 해석**(이슈 없음)에서 신규 `104` 1쌍이 추가됐다가 **spec v0.12.0 대문자 네임스페이스 예약**(이슈 없음 — design-draft-reserved-uppercase-namespace.md)에서 104 은퇴·75 갱신·`105`-`106` 2쌍 추가로 현재 **103쌍**이다 — 아래 [spec v0.7.0 배치](#spec-v070-breaking-배치-87-92), [spec v0.8.0 null](#spec-v080-null-리터럴-93-96), [spec v0.9.0 elseif](#spec-v090-elseif-97-98), [spec v0.10.0 Result 승격](#spec-v0100-result-승격-99-103), [spec v0.11.0/v0.12.0](#spec-v0110-함수-이름-해석--v0120-대문자-네임스페이스-예약-105-106) 참고. v0.7.0 이후로는 "v1 의미 동치"가 아니라 "**현행 스펙 동치**"가 기준이다(의도적 divergence).

## 동치 정책

- **`.tee` → `.expected`는 원칙적으로 byte-for-byte 일치.** 새 런타임이 같은 입력에 같은 출력을 내야 한다.
- **예외 — 인터리빙이 정당하게 달라지는 경우:** 새 런타임은 더 협력적이라(루프/표현식 내 SLEEP 협력화, async replay 제거) 일부 스레드 출력 **순서**가 v1과 달라질 수 있다. 그런 fixture는 그대로 통과시키지 말고 **새 fixture로 분기**하고 차이를 여기 기록한다.
  - v1 스위트 점검 결과: **워커 루프 안에서 PRINT하는 테스트는 없음** → 대부분 순서 안정적이라 그대로 통과 기대.
  - **타이밍/틱 민감:** `20_thread_monitor`, `56_monitor_reads_result`(monitor 틱 수가 스케줄 진행에 의존). 새 스케줄러에서 **재검증** 필요(필요 시 새 fixture).
- **에러 메시지 문자열까지 일치**(에러 테스트들).

## 실행에 호스트 주입이 필요한 테스트 (하네스에서 동일 세팅)

| 테스트 | 필요 세팅 |
|---|---|
| `34_builtin_properties` | `-p` 프로퍼티 주입 |
| `41_result_pattern` | `registerExternal` 외부 함수 |
| `71_async_external` | `registerExternalAsync` (새 런타임: `Coop.blocking` 계약으로) |
| `72_shell` | `SHELL()` (TaskRunner) |
| `78_task_basic`, `79_task_cancel`, `80_task_unique_ids` | TaskRunner/프로세스 |
| `83_type_env`, `85_file_io` | `DefaultPlatformProvider` 주입 |
| `73_keyword_ignore`, `74_function_ignore` | `setHiddenKeywords` / `setIgnoredFunctions` |

> `31_*`는 v1에서 **skip**(번호 결번). 새 repo도 동일하게 비움.

## spec v0.7.0 breaking 배치 (87-92)

ProperTee 이슈 #1/#2/#5/#6/#7의 breaking 변경을 검증하는 신규 fixture와, 구의미론에 의존해 **갱신된** 기존 fixture:

| fixture | 검증 내용 |
|---|---|
| `87_short_circuit` | `and`/`or` 좌→우 short-circuit: 우변 미평가(부수효과 스킵), HAS_KEY 가드 관용구 (#2) |
| `88_error_condition_not_boolean` | 비불리언 `if` 조건 → `Condition requires a boolean value` (#1) |
| `89_error_loop_condition_not_boolean` | 비불리언 `loop` 조건 → 동일 에러 (#1) |
| `90_slice_count` | `SLICE(arr, start, count)` — 제3인자가 count(클램프 포함), SUBSTRING과 동형 (#6) |
| `91_error_random_single_arg` | `RANDOM(max)` 단일 인자 폐지 → `RANDOM() requires zero or two arguments` (#5) |
| `92_error_len_non_collection` | 비컬렉션 `LEN` → `LEN() requires a string, array, or object argument` (#7) |

갱신된 기존 fixture: `11_arrays`(SLICE count 의미론 — expected 1줄 변경), `28_error_not_boolean`(short-circuit로 에러 메시지가 좌변 타입만 언급), `64_time_functions`(`RANDOM(10)` → `RANDOM(0, 9)`, 출력 불변).

## spec v0.8.0 null 리터럴 (93-96)

ProperTee 이슈 #4(first-class `null`)를 검증하는 신규 fixture. 설계 원칙은 "**no implicit null**" — 언어 자체는 null을 만들지 않고(인자 생략·bare return은 여전히 `{}`), null은 리터럴 또는 JSON/호스트 데이터로만 유입된다:

| fixture | 검증 내용 |
|---|---|
| `93_null_literal` | 리터럴·`TYPE_OF`→`"null"`·동등성 행렬(`null==null` true, `null=={}` false)·컨테이너 표시(`[ 1, null, 'a' ]`)·문자열 연결·no-implicit-null(`noop()=={}` true) |
| `94_json_null_roundtrip` | `JSON_PARSE`↔`JSON_FORMAT` 무손실 왕복(`{"a":null,"b":{},"c":[1,null]}`), null↛{} 정규화 제거 |
| `95_error_null_condition` | 조건 위치의 null → `Condition requires a boolean value. Got null` (v0.7.0 #1 엄격성 재활용) |
| `96_error_null_member` | null 멤버 접근 → `Property 'name' does not exist` |

갱신된 기존 fixture: `84_json`(`JSON_PARSE("null")` 결과 비교가 `== {}` → `== null`, expected 불변).

## spec v0.9.0 elseif (97-98)

ProperTee 이슈 #3(Lua 스타일 `elseif`)을 검증하는 신규 fixture. 체인 하나에 `end` 하나; 첫 `true` 가지가 이기고 이후 조건은 평가·타입검사되지 않으며, `if` 숨김이 체인 전체를 막는다:

| fixture | 검증 내용 |
|---|---|
| `97_elseif` | 다분기 체인(성적 A/B/C/F)·첫 매치 승리(부수효과로 이후 조건 미평가 증명)·else 없는 체인(무매치 통과)·elseif 본문 안 중첩 if |
| `98_error_elseif_condition` | elseif 조건의 비불리언 → `Condition requires a boolean value. Got number` (elseif 조건 위치 지목, v0.7.0 #1 엄격성 재활용) |

## spec v0.10.0 Result 승격 (99-103)

FAIL/UNWRAP/OK/ERR/IS_RESULT와 genuine-Result 브랜드(ProperTee `docs/design-draft-result-handling.md` §10)를 검증하는 신규 fixture. 호스트 주입 불필요(전부 PURE + multi):

| fixture | 검증 내용 |
|---|---|
| `99_fail` | `if not res.ok then FAIL(...)` 승격 패턴·OK/ERR 왕복·에러 위치가 FAIL 호출 지점(line:col) |
| `100_unwrap` | ok 경로 언래핑·2인자 컨텍스트 접두(`msg + ": " + value`)·에러 위치가 UNWRAP 호출 지점 |
| `101_result_brand` | 브랜드 생성(OK)·전파(할당/PUSH)·위조 리터럴 false·JSON_PARSE 래퍼는 genuine·왕복 후 소멸·JSON_FORMAT byte 불변·ERR 구조화 value·IS_RESULT 무인자 false |
| `102_error_unwrap_nonresult` | 위조 리터럴에 UNWRAP → `UNWRAP() requires a Result` |
| `103_fail_thread` | multi 워커 안 FAIL → `[THREAD ERROR]` + `{status:"error"}` 수집·런 계속·수집 항목이 genuine |

## spec v0.11.0 함수 이름 해석 → v0.12.0 대문자 네임스페이스 예약 (105-106)

v0.11.0은 이름 해석 순서(호스트 차단 → 스크립트 함수 → 빌트인/외부)를 확정했고 `104_user_function_shadowing`이 이를 고정했으나, **v0.12.0이 전부-대문자 함수 정의 자체를 금지**하면서 104는 은퇴(전제 소멸; 번호 결번은 31 전례). `75_range_step_eval_once`의 헬퍼도 `STEP`→`step_fn`으로 갱신(.expected 불변 — "STEP"은 출력 문자열). 호스트 주입 불필요:

| fixture | 검증 내용 |
|---|---|
| `105_error_reserved_function_name` | `function LEN(...)` → 정의 시점 에러 + 정의 위치(line:col) 지목·앞 문장은 실행됨(정의문이 순차 실행되는 것도 함께 고정) |
| `106_function_name_case` | 혼합/소문자 이름(`Foo`/`getBALANCE`/`get_balance`) 정의 합법·ALL-CAPS 빌트인 호출 불변 |

## 카테고리별 목록 (v1 유래 84 fixtures)

### 코어 언어 (순서 안정, 그대로 일치 기대)
`01_variables_types` `02_arithmetic` `03_comparisons_logic` `04_if_else`
`05_condition_loop` `06_value_loop` `07_keyvalue_loop` `08_break_continue`
`09_functions` `10_recursion` `11_arrays` `12_objects` `13_strings`
`14_scope` `15_nested_loops` `33_complex_expressions` `35_object_computed_keys`
`36_function_with_loops` `39_escape_strings` `42_global_prefix` `44_global_prefix_thread`

### 스레드 / multi / monitor (대부분 결과를 블록 뒤에서 PRINT → 순서 안정)
`16_thread_basic` `17_thread_results` `18_thread_global_snapshot` `19_thread_sleep`
`20_thread_monitor`(⚠️틱) `21_thread_no_result` `22_thread_calling_thread`
`30_thread_local_scope` `37_thread_with_loops` `38_many_threads` `40_multi_after_multi`
`46_thread_error_result` `47_spawn_outside_multi` `49_multi_result_collection`
`50_multi_dynamic_spawn` `51_multi_auto_keys` `55_thread_status_field`
`56_monitor_reads_result`(⚠️틱) `57_dynamic_thread_keys` `61_duplicate_auto_key`
`69_thread_isolation`

### 에러 (메시지까지 일치 필요)
`23_error_type_mismatch` `24_error_undefined_var` `25_error_undefined_func`
`26_error_div_zero` `27_error_null_access` `28_error_not_boolean`
`29_error_loop_limit` `32_error_monitor_assign` `43_global_prefix_error`
`45_global_prefix_thread_error` `52_multi_duplicate_key_error`
`58_dynamic_key_digit_error` `59_dynamic_key_type_error` `60_dynamic_key_duplicate`
`63_range_step_zero` `67_sort_errors`

### 빌트인 / 기능 (pure 의미)
`34_builtin_properties` `48_has_key` `53_len_on_maps` `54_map_positional_access`
`62_range_array` `64_time_functions` `65_keys` `66_sort` `68_cow_semantics`
`70_debug_statement` `75_range_step_eval_once` `76_range_tiny_float_bound`
`77_range_int_overflow` `81_string_matching` `82_map_extensions` `83_type_env`
`84_json` `85_file_io` `86_props_object`

### 호스트 게이트 / 외부
`41_result_pattern` `71_async_external` `72_shell`
`73_keyword_ignore` `74_function_ignore`
`78_task_basic` `79_task_cancel` `80_task_unique_ids`

## v1에서 가져오지 **않는** 테스트 (재작성 필요)

다음은 v1의 *특정 스케줄링/블로킹 동작*을 검증하므로 fixture가 아니라 **새 런타임 기준으로 재작성**한다:

- `SleepNestingTest` (타이밍: nested SLEEP이 "기다리는가") — 새 런타임에선 **항상 협력적**이어야 하므로 의미가 더 강해짐.
- `CooperativeNestingTest` (오버랩 ~1x vs 직렬 ~2x) — v1은 seam을 eager로 두지만, 새 런타임은 **`x = f()`·`a + f()`·중첩 multi setup·표현식 내 SLEEP까지 모두 협력적**이어야 한다. 따라서:
  - 기존 협력 케이스(if/loop/bare-call 본문)는 그대로 오버랩 유지 검증.
  - **신규 케이스 추가:** `x = f()`, `a + f()`, `return f()`, 중첩 `multi` setup의 SLEEP이 **이제 오버랩(~1x)** 인지.
  - **async replay 제거 검증:** async 직전 선행 부작용이 **1회만** 실행되는지(정확성 회귀 테스트).

## 하네스 TODO (구현 시)

- `.tee` 실행 → stdout 수집 → `.expected`와 비교하는 파라미터화 테스트(현 `ScriptTest`와 동형). 테스트 목록은 **하드코딩**(자동 발견 아님) — v1과 동일 관례.
- 위 호스트 주입(프로퍼티/외부함수/PlatformProvider/TaskRunner/keyword·function ignore) 동일 제공.
- 결정론적 round-robin 순서 고정(설계 §6) — 안 그러면 스레드 fixture가 흔들림.
