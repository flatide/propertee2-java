# Conformance 테스트 목록 — v1 의미 동치 기준

출처는 `propertee-java`(v1.0.0)의 84개 fixture이며 이후 스펙 배치마다 fixture를 추가했다. 현재 authoritative 목록은 `conformance/Fixtures.java`의 **157쌍**이다. v0.7.0 이후로는 "v1 의미 동치"가 아니라 "**현행 스펙 동치**"가 기준이다(의도적 divergence). 과거 배치별 상세는 아래 절을 따른다.

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
| `133_import_modules` | `ModuleResolver`: `util.file` latest → 2, exact `.1` → 1 |

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

## spec v0.21.0 monitor 스레드 정보와 SHELL 1라인 tail (132)

| fixture | 검증 내용 |
|---|---|
| `132_monitor_thread_info` | monitor 안에서 genuine Result의 `value`가 `{id,key,functionName,state,returnValue,shell}`로 투영되고, SHELL 호스트 필드가 없을 때 `{}`를 사용하며, `multi` 종료 뒤에는 원래 worker 반환값만 남는지 검증 |

실제 프로세스의 bounded polling, PID/스레드 metadata 전달, 최신 출력 보존은
`ShellTest`의 host-unit 테스트가 별도로 검증한다. fixture는 Java와 JavaScript의 공통
portable 계약만 고정한다.

## spec v0.22.0 worker 디버깅 (host-API tests)

문법과 일반 실행 stdout이 바뀌지 않으므로 fixture는 추가하지 않는다. Java의
`DebugHandlerTest`/`FacadeDebugTest`/`DapTest`가 worker의 `debug`, 논리 thread ID/name,
worker 고정 stepping, worker `quit()`의 전체 run 승격, monitor 제외를 검증한다.
JavaScript는 `run_debug_worker_test.js`가 두 worker의 debug/step 순서와 monitor 제외를
고정한다.

## spec v0.23.0 import와 함수 전용 module (133)

| fixture | 검증 내용 |
|---|---|
| `133_import_modules` | 무버전 latest와 exact version 해석, import 시 top-level 미실행, 명시적 init과 내부 helper, module global 상태, 인자·반환 deep-copy, worker의 module snapshot 읽기 |

Java와 JavaScript는 같은 `133_import_modules.tee/.expected`와 `util/file.1.tee`,
`util/file.2.tee` 지원 source를 사용한다. 파일 시스템 자체의 latest/fallback/containment는
각 CLI resolver 테스트가 별도로 검증하며, 이 fixture는 host가 resolver를 주입하는 portable
language/runtime 계약만 고정한다.

## spec v0.24.0 `WORDS`·공백 집합·빈 `SPLIT` 구분자 (134-135)

| fixture | 검증 내용 |
|---|---|
| `134_words_whitespace` | `WORDS`의 연속 공백 분리·빈 결과(`[]`)·6개 공백 문자 전부로 분리, `TRIM`이 같은 6자를 양끝에서만 제거, 그 밖의 문자(U+0000/0001/001F 제어 문자, U+0085, U+00A0, U+1680, U+2000, U+2028, U+3000, BOM U+FEFF)는 `TRIM`·`WORDS` 모두 텍스트로 취급, `SPLIT`이 앞·중간·뒤 빈 필드를 유지하고 `JOIN`으로 원문 복원, 리터럴(정규식 아님)·왼쪽부터 겹치지 않는 구분자 매치 |
| `135_error_split_empty_delimiter` | `CHARS`로 문자 분리 후 `SPLIT("abc", "")` → `SPLIT() requires a non-empty delimiter` |

VT·FF와 그 밖의 제어/비 ASCII 문자는 소스에 그대로 넣지 않고 `ch(hex)` 헬퍼
(`UNWRAP(JSON_PARSE("\"\\u" + hex + "\""))`)로 만들며, 그런 문자가 든 결과는 `LEN`으로만 출력해
`.expected`를 ASCII로 유지한다. Java와 JavaScript가 같은 파일을 사용한다.

## spec v0.25.0 `TO_NUMBER` 10진수 표기 (136-137)

| fixture | 검증 내용 |
|---|---|
| `136_to_number_numerals` | 부호·앞자리 0·소수부(`.5`, `5.` 포함)·지수 표기의 변환 결과, 정수 범위 경계(`9007199254740991`, 범위 밖 `±9007199254740993` → 가장 가까운 소수 `±9007199254740992`), 정밀도를 넘는 자릿수의 반올림과 지수 표시(`1.2345678901234568e+29`), 언더플로(`1e-999` → `0`), 앞뒤 6개 공백 문자 무시. 거부되는 표기(16진수·8진수·2진수 접두사, 타입 접미사, 16진 부동소수점, `NaN`, `Infinity`, 자릿수 구분자, 중간 공백, 지수나 소수점만 있는 표기, 빈 텍스트, 공백뿐인 텍스트)와 범위를 넘는 `1e999`는 각각 쓰레드 에러 Result로 받아 메시지를 출력 |
| `137_error_to_number_hex` | 앞뒤 공백이 있는 `" 16 "` 변환 후 `TO_NUMBER("0x10")` → `TO_NUMBER() cannot convert '0x10' to number` |

에러가 나는 경우는 `multi` 워커로 실행해 런을 끝내지 않고 메시지를 모은다(fixture 65·67과 같은 방식).
에러 메시지에는 인자가 그대로 들어가므로 비 ASCII 숫자(`"１２"`)나 6개 밖의 공백 문자(U+00A0,
BOM, C0 제어 문자 등)를 둘러싼 경우는 `.expected`를 ASCII로 유지하려고 fixture에서 빼고,
`BuiltinsTest.toNumberNumerals`와 propertee-js의 `test/run_to_number_test.js`가 같은 목록으로
검사한다.

## spec v0.26.0 `FILE_LINES` 쓰이는 만큼 읽히는 배열 (138-139)

| fixture | 검증 내용 |
|---|---|
| `138_file_lines` | (85처럼 `testDir` 주입) 키-값 루프와 값 루프의 스트리밍(헤더 `continue`, `break`), `LEN`, `TYPE_OF`·인덱스·`SLICE`·`SORT`·`JOIN`·`CONTAINS`·표시·`JSON_FORMAT`·`==`·`IS_RESULT`가 `UNWRAP(READ_LINES(path))` 배열과 동일, 아직 읽지 않은 값의 복사본 독립성(요소 할당), 함수 인자로 전달, `multi` 워커가 스냅샷 값과 setup에서 만든 값을 각자 스트리밍, 호출 뒤 `APPEND_FILE`한 줄은 기존 배열에 없고 새 호출에는 있음, `\r\n`·`\r`·빈 줄·끝 줄바꿈 없는 마지막 줄·빈 파일 |
| `139_error_file_lines_missing` | 없는 파일의 `FILE_LINES("/no/such/dir/users.csv")` → 호출 시점 런타임 에러 `FILE_LINES() cannot read '/no/such/dir/users.csv': no such file` (Result가 아님) |

파일이 호출 뒤 바뀐 경우(잘림·삭제·다시 쓰기·스크립트 자신의 `WRITE_FILE`), 잘못된 UTF-8, 줄 길이
상한, 통째 사용 상한, BOM 제거, 호스트 경계(`return`·`Engine.Outcome.result()`·외부 함수 인자·
`TypeChecker.deepCopy`)의 평범한 배열화, 워커 20개가 공유하는 값, 루프 반복 상한은 에러 메시지에
임시 경로가 들어가거나 상한 설정이 필요해 `interp/FileLinesTest`가 검사한다.

## spec v0.27.0 `LIST_DIR` 이름 패턴과 `LIST_DIR_NAMES` (140, v0.28.0 갱신)

| fixture | 검증 내용 |
|---|---|
| `140_list_dir_pattern` | (85처럼 `testDir` 주입) `LIST_DIR_NAMES`의 이름순 정렬(점으로 시작하는 이름 포함)과 항목별 `{name, type}`(파일 `"file"`, 디렉터리 `"dir"`, v0.28.0), glob `*`(앞의 `.`도 맞음)·`?`·`{a,b}`·`[ab]`·`[!.a-c]`·`\[`(글자 그대로)·맞는 것 없음(빈 배열), 같은 패턴의 `LIST_DIR`(파일 크기, 디렉터리 size 0), 빈 패턴·`/`가 든 패턴·닫히지 않은 `[`의 error Result 메시지 |

심볼릭 링크(`LIST_DIR`: 파일·디렉터리를 가리키는 링크는 대상으로, 깨진 링크·순환 링크·접근할 수
없는 대상은 `"link"`; `LIST_DIR_NAMES`: 모든 링크가 `"link"`), 읽을 수 없는 디렉터리와 항목을 확인할
수 없는(실행 권한 없는) 디렉터리, 이유를 붙인 에러 메시지, v1 provider(TeeBox 경로)와의 출력 일치, 1인자
`listDir`만 구현한 호스트 provider의 기본 메서드는 링크 생성·권한 설정이 필요하거나 메시지에 임시
경로가 들어가 `interp/ListDirTest`가 검사한다.

## spec v0.29.0 정규식 매치 객체와 `REGEX_FIND_ALL` (141-144)

`REGEX_FIND`가 배열 대신 매치 객체 `{match, position, groups, named}`를 돌려주므로 `81_string_matching`의
REGEX_FIND 부분은 `.match`와 `.groups.N`을 읽도록 고쳤다(출력은 그대로).

| fixture | 검증 내용 |
|---|---|
| `141_regex_find_object` | 매치 객체 전체 출력(키 순서 `match`·`position`·`groups`·`named`), 1부터 세는 `position`, 이름 붙은 그룹이 `groups`에도 번호로 들어가고 `named`는 패턴 순서, `SUBSTRING(s, position, LEN(match)) == match`, 참여하지 않은 그룹은 `groups`와 `named`에서 `{}`, 그룹 없는 패턴은 `[]`·`{}`, 빈 매치의 위치(끝은 `LEN + 1`), 매치 없음 `{}` |
| `142_regex_find_all` | 여러 매치의 위치·그룹·이름, 첫 원소와 `REGEX_FIND`의 동등성, 매치 없음 `[]`, 겹치지 않는 매치(겹침을 세는 `FIND`와 비교), 빈 매치 뒤 한 위치 전진(`"baaac"`에 `a*` → 1·2·5·6), `^`는 문자열 맨 앞에서만, 매치마다 따로 정해지는 참여하지 않은 그룹 |
| `143_error_regex_find_old_index` | v0.28.0식 `res.2` → `Property '2' does not exist` (조용히 다른 값을 읽지 않음) |
| `144_error_regex_invalid_pattern` | 닫히지 않은 그룹 → `REGEX_FIND_ALL() invalid regex pattern: ([a-z]` (두 런타임 같은 메시지) |

BMP 밖 문자(Java는 한 글자, JavaScript는 UTF-16 반쪽 둘)와 끝 줄바꿈 앞의 `$`는 런타임마다 다르게
매치되므로(스펙 §Regular expressions에 문서화) fixture로 고정하지 않는다. 필드·이름 순서와 세 정규식
함수의 에러 메시지는 `builtin/BuiltinsTest`도 검사한다.

## spec v0.30.0 자릿수 반올림, `TRUNC`, 서식 출력 (145-151)

| fixture | 검증 내용 |
|---|---|
| `145_round_digits` | 네 함수의 양수·음수 방향, `digits` 없는 `TRUNC`(정수), 음수 자릿수, 보이는 숫자 기준(`FLOOR(0.29, 2)` → `0.29`, `ROUND(1.005, 2)` → `1.01`, `0.1 + 0.2`), 자릿수가 닿지 않는 자리·수보다 큰 단위, 정합 소수 `digits`, `digits`가 있으면 소수라 overflow 없음, 올림이 자리를 늘리는 경우, 범위를 넘는 결과 `Infinity`, 0으로 반올림된 값의 부호 없음 |
| `146_round_ties` | 동점은 0에서 먼 쪽(`ROUND(-2.5)` → `-3`, `ROUND(0.125, 2)` → `0.13`), 동점 바로 아래(`0.49999999999999994`), 대칭 `ROUND(-x, d) == -ROUND(x, d)`, `digits` 없는 결과의 정수 범위 경계(소수가 담는 가장 큰 동점 `4503599627370495.5`, 범위 끝 `9007199254740991.0`) |
| `147_error_round_args` | 정수가 아닌 `digits`(소수·문자열), 세 번째 인자, 인자 없음, 수가 아닌 `n`, `TRUNC`의 overflow — thread 결과로 한꺼번에 |
| `148_format` | `{}`의 값 종류별 표기, `.Nf`(반올림·끝자리 0·지수 없음·부호 없는 0), 폭과 정렬(수 오른쪽·나머지 왼쪽·가운데·자르지 않음), `0`과 `,`, `{{`·`}}`, `Infinity`·`NaN`, 반환값 타입 |
| `149_printf` | 한 줄 출력, `{}` 반환, `PRINT(FORMAT(...))`와 같은 출력, thread 안의 `PRINTF` |
| `150_error_format` | 문자열이 아닌 서식, 값 개수 불일치(단수·복수), 알 수 없는 서식·위치 자리 표시·닫히지 않은 `{`·짝 없는 `}`, 수 전용 서식에 문자열, 정렬과 함께 쓴 `0`, `PRINTF` 이름의 에러, 최상위 `PRINTF` 실패(위치 없음) |
| `151_error_math_args` | `SUM`·`MAX`·`MIN`·`ABS`·`FLOOR`·`ROUND`의 인자 에러 — JavaScript 런타임이 이번에 맞춘 메시지 |

`5e-324` 근처의 최단 자릿수는 플랫폼마다 다를 수 있어(스펙 §Number display) 자릿수 반올림 fixture에
넣지 않는다. Python `decimal`·JavaScript 런타임과의 무작위 비교(16,000건)는 릴리스 전에 수동으로
했다(CHANGELOG 0.39.0).

## spec v0.31.0 숫자 모양, `TEXT`, `ALIGN_*` (148-150 고침, 152-154)

148–150은 v0.30.0의 `{:spec}` 대신 숫자 모양으로 다시 썼다. 위 v0.30.0 표의 148–150 설명은 그때의
내용이다.

| fixture | 검증 내용 |
|---|---|
| `148_format` | `{}`의 값 종류별 표기, `{모양}`(`0`·`#`·`,`·`.`·`%`, 반올림, 끝자리 0, 부호 없는 0, 지수 없음), `{{`·`}}`, `Infinity`, 반환값 타입 |
| `149_printf` | 한 줄 출력, `{}` 반환, `PRINT(FORMAT(...))`와 같은 출력, thread 안의 `PRINTF` |
| `150_error_format` | 문자열이 아닌 서식, 값 개수 불일치(단수·복수), v0.30.0 서식 `{:.2f}`·위치 `{1}`·잘못된 모양·공백·왼쪽부터 보고·닫히지 않은 `{`·짝 없는 `}`, 모양 자리 표시에 문자열, `PRINTF` 이름의 에러, 최상위 `PRINTF` 실패(위치 없음) |
| `152_text` | `0`과 `#`, 정수 부분을 자르지 않음과 최소 한 자리, 부호 뒤 0 채우기, 쉼표(채운 0 포함), `%`의 정확한 100배, `ROUND`와 같은 반올림, 부호 없는 0(`#`에 `-0.4` 포함), 큰 수, 정수와 소수, `Infinity`·`NaN`(`%` 포함), 4,096자 모양, `TEXT`와 `{모양}`이 같음 |
| `153_align` | 세 가지 정렬, 값 종류별 표기, 자르지 않음, 너비 0과 정합 소수 너비, 화면 칸(한글·한자·가나·전각·이모지·전각 공백 2칸, 결합 문자와 분해된 한글 중성·종성 0칸, W이기도 한 결합 문자 U+3099와 보충 평면의 U+E0100 0칸, 반각 가나 1칸, 짝 없는 서로게이트 1칸), `LEN`과의 차이, 한글이 든 표 |
| `154_error_text_align` | `TEXT`의 인자 개수·문자열이 아닌 모양·잘못된 모양(빈 모양, `.2f`, `0.`, `.00`, `0#`, `0.#0`, `,0`, `0,`, `#,##0,`, `0%%`, 글자)·수가 아닌 값·모양을 먼저 검사함, `ALIGN_*`의 인자 개수와 너비(음수·999 초과·소수·문자열) |

너비 표는 유니코드 16.0의 Python `unicodedata`로 만들었다. 무작위 숫자 모양 16,000건과, 표의 모든
경계(±1)에 무작위 코드 포인트를 더한 5,150개를 Python 모델·JavaScript 런타임과 비교한 것은 릴리스 전에
수동으로 했다(CHANGELOG 0.40.0).

## spec v0.32.0 JSON에 없는 수 (155-156)

| fixture | 검증 내용 |
|---|---|
| `155_error_json_nonfinite` | `PRINT`·`TO_STRING`의 `Infinity`·`NaN` 표기는 그대로, 유한한 수의 JSON도 그대로(가장 큰 double, `-0.0`), `JSON_FORMAT`이 `Infinity`·`-Infinity`·`NaN`에서 실패하며 그 수와 위치를 알림 — 맨 위 값, 배열 위치(1부터), 객체 키, JSON이 쓰는 순서로 첫 번째, 깊은 위치, 평범한 이름이 아닌 키(공백·숫자·따옴표·한글·빈 키)는 큰따옴표로 — thread 결과로 한꺼번에 |
| `156_error_write_json_nonfinite` | `WRITE_JSON_FILE`도 같은 에러로 실패하고 파일을 건드리지 않음(기존 내용 유지), 없는 디렉터리보다 값 검사가 먼저, 스크립트가 바꾼 `FILE_LINES` 배열도 검사, thread 밖에서는 실행을 끝냄 — 호스트가 넣는 `testDir` 사용 |

HTTP 요청 본문은 `interpreter/FacadeHttpBuiltinTest`가, `--result-only`는 CLI의 `CliTest`가 검사한다.

## spec v0.33.0 정수 범위 ±9,007,199,254,740,991 (157, 그리고 77·107–110·114·115 등 갱신)

| fixture | 검증 내용 |
|---|---|
| `157_integer_range_builtins` | 32비트를 넘는 `SUBSTRING`·`SLICE` 위치가 끝에 맞춰짐(이전 Java는 값이 돌아 빈 결과), `RANDOM`이 범위 끝까지의 경계를 받음, `MILTIME`이 정수, 범위 안의 JSON·`TO_NUMBER` 정수와 범위 밖의 가장 가까운 소수, `TEXT`의 범위 끝 — 그리고 곱셈·뺄셈·데이터에서 온 정수·`MILTIME() * 1000000`·`ROUND`·`SUM`의 중간 합계 넘침, `RANDOM`의 범위 밖 경계와 `min > max`를 thread 결과로 한꺼번에 |

32비트 경계를 고정하던 fixture는 새 경계로 옮겼다: `107_integer_limits`(범위 끝, 대칭, 테라바이트 합계, 소수는 범위를 넘어 계산), `108`·`109`·`110`·`115`(새 에러 문구), `77_range_int_overflow`(범위 끝의 정수 범위 배열), `114_number_identity`(정합 소수는 범위를 넘어도 넘치지 않음), `136`·`146`·`147`·`152`. JavaScript 런타임은 정합 소수를 정수로 다루므로 `114`는 그쪽에서 계속 보류이고, 공용 fixture는 범위를 넘는 계산에 정합이 아닌 소수(`+ 0.5`, `* 1000.5`)를 쓴다.

호스트가 넘기는 `Long`(범위 안은 정수, 밖은 가장 가까운 소수)과 결과의 상자(32비트 안은 `Integer`, 밖은 `Long`)는 `interp/InterpreterTest.hostLongsAreIntegersInsideTheRange`가, 32비트를 넘는 파일 크기와 시각은 `interp/ListDirTest.sizesAndTimesPast32BitsAreIntegers`가 검사한다.

## spec v0.34.0 위치와 인덱스는 범위 안의 정수 (158, 그리고 157 갱신)

| fixture | 검증 내용 |
|---|---|
| `158_error_positions` | `SUBSTRING`·`SLICE`의 끝을 넘는 위치는 덜 나올 뿐임(길이 0, 끝 다음 위치, `2.0` 같은 정합 소수 포함), 구분자가 마지막 글자일 때 `SUBSTRING(s, p + 1)`은 `""` — 그리고 0·음수·소수·문자열 시작, 음수·소수 길이, `SLICE`의 0 시작과 음수 개수, 배열 인덱스 `1.5`의 읽기와 대입, 문자열 인덱스 `1.5`와 범위 밖을 thread 결과로 한꺼번에 |

`157_integer_range_builtins`는 음수 시작 대신 32비트를 넘는 시작(끝 너머이므로 빈 결과)을 쓴다. 에러 문구는 `builtin/BuiltinsTest.substringAndSlicePositionsAreIntegersInRange`(NaN·Infinity 포함)와 `interp/InterpreterTest.anIndexIsAnIntegerWhenReadingAndWriting`이 검사한다.

## spec v0.35.0 `JSON_PARSE`가 거부하는 큰 수와 엄격한 JSON, 루프 상한 안내 (159-160)

| fixture | 검증 내용 |
|---|---|
| `159_json_parse_strict` | 표현할 수 없을 만큼 큰 수(`1e999`, `-1E400`, 310자리 정수)는 첫 번째 수와 위치(`value.rows.2.size`, `value."unit price"`)를 알리는 에러 Result — 작은 수는 `0`, 범위 밖 정수는 소수, 가장 큰 double은 그대로. JSON이 아닌 수(`01`, `.5`, `1.`, `1e`, `[1, 2e]` …), JSON 공백 4개 밖의 공백(VT, FF, U+001C, NBSP, U+2002, U+3000, BOM), 문자열 안의 제어 문자, 잘못된 `\u` 이스케이프는 `ok: false`(문구는 런타임마다 달라 `ok`만 비교). 1000단계는 읽고 1001단계는 `Invalid JSON: nested more than 1000 levels deep at position N`(배열 1000, 객체 5000), 문자열 안의 괄호는 세지 않음 |
| `160_error_loop_limit_hint` | 루프 반복 상한 에러가 어떤 루프든 `loop ... infinite do`를 안내함 — 조건 루프, 값 루프, 키-값 루프(객체와 배열), 정확히 1000번은 통과 — thread 결과로 한꺼번에. `29_error_loop_limit`도 새 문구 |

Java 쪽 문구와 위치는 `value/JsonParserTest`가 검사하고, 무작위로 고친 JSON 5만 개에서 `TeeError` 말고는 어떤 예외도 나오지 않는지도 본다. `READ_JSON_FILE`은 `host/FileIoErrorTest`가 검사한다.

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
