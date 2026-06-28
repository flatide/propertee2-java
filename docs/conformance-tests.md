# Conformance 테스트 목록 — v1 의미 동치 기준

출처: `propertee-java`(v1.0.0) `ScriptTest`의 `testNames` 배열 + `src/test/resources/tests/*.tee`/`*.expected`(84쌍, 이 repo로 복사됨).

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

## 카테고리별 목록 (84 fixtures)

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
