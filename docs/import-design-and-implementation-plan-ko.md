# ProperTee import 설계 및 구현 계획

## 1. 확정된 언어 계약

```propertee
import util.file as file
import util.file.1 as file_v1

file::init("/data")
result = file::read("input.txt")
```

- module 이름은 `ID ('.' ID)*`이다. quoted/dynamic segment는 지원하지 않는다.
- 마지막 `.N`은 선택적인 정확한 양의 정수 version이다. `0`, `01`, `v1`은 version이 아니다.
- `import`는 진입 source 최상단에서만 사용할 수 있다.
- 별도의 `library` 선언은 없다. import가 없는 모든 script는 직접 실행하거나 함수 module로 사용할 수 있다.
- import된 source에 다시 `import`가 있으면 Entry 실행 전에 오류로 거부한다(depth 1 제한).
- script를 직접 실행하면 기존처럼 모든 top-level statement를 실행한다.
- import할 때는 source root에 직접 정의된 함수만 등록하며 다른 top-level statement는 실행하지 않는다.
- `alias::function(...)` 호출만 허용한다. 외부 global은 읽거나 쓸 수 없다.
- module global은 Run마다 격리되며 처음에는 비어 있다. 필요한 초기화는 `alias::init(...)` 같은 일반 함수로 명시한다.
- 함수 인자와 반환값은 기존 value contract대로 deep copy한다.
- 동일 module/version은 Run 안에서 하나의 instance를 사용한다. alias 중복과 중복 import는 오류이며, 서로 다른 version은 별도 instance다.

## 2. Resolver 계약

코어는 파일 시스템이나 TeeBox 저장소를 알지 않는다. Host가 다음 정보를 resolve한다.

```text
ModuleRequest(moduleId, requestedVersion)
    -> ModuleSource(moduleId, resolvedVersion, canonicalSourceId, source)
```

- root 실행 전에 모든 직접 import를 resolve, parse, validate한다.
- 모든 module이 유효한 것이 확인된 뒤 Entry의 첫 statement를 실행한다.
- resolver가 없는 환경에서 import를 사용하면 명확한 오류를 낸다.
- 오류, call stack, breakpoint에는 `sourceId + line + column`을 사용한다.

## 3. 환경별 version 선택

### CLI

- 기본 module root는 Entry 파일의 부모 디렉터리다. `--module-root`로 덮어쓸 수 있다.
- `import util.file`은 `util/file.N.tee` 중 가장 큰 양의 정수 N을 고른다.
- version 파일이 없을 때만 `util/file.tee`를 사용한다.
- `import util.file.2`는 정확히 `util/file.2.tee`만 사용한다.
- symlink를 포함한 실제 경로가 module root 밖으로 나가면 거부한다.

### TeeBox

- module 이름이 그대로 `scriptId`다: `util.file -> scriptId=util.file`.
- version 생략은 active version, `.N`은 정확한 version을 사용한다.
- Run 시작 시 실제 `{scriptId, version, sha256}`를 고정하고 기록한다.
- TeeBox version은 canonical positive integer(`^[1-9][0-9]*$`)만 허용한다.
- `Save as new version`과 `Save as active version`은 서버가 자동 채번한다.
- 삭제된 번호를 재사용하지 않도록 `lastAllocatedVersion`을 script metadata에 보존한다.
- 기존 비숫자 version은 자동 변환하지 않으며 운영자가 업그레이드 전에 수동 migration한다.

## 4. Runtime scope와 concurrency

- imported 함수의 `::x`는 호출자 global이 아니라 해당 module global을 가리킨다.
- unqualified helper 함수 호출은 같은 module의 함수에서 먼저 해석한다.
- main thread에서는 imported 함수가 자기 module global을 수정할 수 있다.
- `multi` 진입 시 Entry global과 각 module global을 함께 snapshot한다.
- worker/monitor가 imported 함수를 호출하면 module snapshot을 읽으며 module global 쓰기는 기존 worker purity 오류다.
- monitor의 debugger 제외와 single-baton 결정론은 바꾸지 않는다.

## 5. 구현 순서

1. source identity와 `ModuleResolver` host API
2. ANTLR grammar, parser, Validator, error contract
3. Java `ModuleInstance`/module-owned `UserFunction`/qualified call
4. `multi` module snapshot과 worker purity
5. source-aware error/debugger/DAP
6. Java CLI filesystem resolver와 version 선택
7. JavaScript Node/browser resolver와 module context
8. TeeBox registry resolver, numeric version, allocator, UI/API
9. conformance/host API/TeeBox regression tests
10. canonical spec, 운영 문서, migration guide, bundles/JAR

## 6. 테스트 완료 조건

- direct 실행에서는 top-level test code가 실행되고 import에서는 실행되지 않는다.
- 명시적 init, 내부 helper, module state 격리, deep-copy 경계가 고정된다.
- nested import와 alias/module 충돌은 Entry side effect 전에 실패한다.
- CLI latest/exact/fallback/path containment가 검증된다.
- TeeBox active/exact resolution, numeric version, 자동 채번, 번호 비재사용이 검증된다.
- imported 함수의 main/worker 실행, snapshot/purity, source-aware breakpoint/stack이 검증된다.
- Java/JavaScript 공통 fixture와 TeeBox 전체 suite가 통과한다.

## 7. 지원 범위

- 구현 대상: `propertee2-java`, `propertee-js`, `propertee-teebox`, canonical `ProperTee` spec.
- Java 7/8 `propertee-java`는 동결 상태를 유지하고 import를 구현하지 않는다.

## 8. 구현 결과 (2026-08-25)

계획의 1~10단계를 모두 구현했다.

- canonical spec은 0.23.0으로 올리고 영문·한글 문서와 공용 grammar에 import 계약을 반영했다.
- `propertee2-java` 0.30.0에 module host API, 격리된 module runtime, CLI filesystem resolver,
  source-aware 오류·debugger·DAP, module snapshot 기반 `multi` 동작을 구현했다.
- `propertee-js` 0.5.0에 같은 parser/runtime 계약과 Node filesystem resolver를 구현하고 browser
  bundle을 다시 생성했다.
- `propertee-teebox` 1.29.0에 `scriptId` resolver, active/exact version 고정, numeric-only version과
  high-water 자동 채번, run의 `{scriptId, version, sha256}` import 기록을 구현했다.
- import 설치는 모든 module의 resolve·parse·validate가 끝난 뒤 한 번에 반영하므로 persistent
  session에서도 실패한 import가 부분 상태를 남기지 않는다.
- Java 7/8용 `propertee-java`는 변경하지 않았다.

최종 검증 결과:

- `propertee2-java`: `./gradlew build dist` 성공, conformance fixture 130개 및 Java test 440개 통과
- `propertee-js`: `npm test` 성공, fixture 121개와 host-API test 5개(총 126개) 통과
- `propertee-teebox`: `./gradlew test` 성공, test 273개 통과
- 네 변경 저장소 모두 `git diff --check`를 통과했고 canonical/JavaScript 영문 spec 및 세 grammar의
  동일성을 확인했다.

## 9. 구현 후 리뷰 반영 (2026-08-25)

- Java에도 JS와 byte-identical한 `133_import_modules` fixture와 module source를 추가하고 host
  `ModuleResolver`를 주입해 공유 코퍼스 번호를 다시 일치시켰다.
- module load 중 Entry import 위치에서 발생한 오류는 기존 Entry 오류처럼 source path를 생략하고,
  imported source 내부 오류에만 source ID를 표시하도록 구분했다.
- qualified module 호출의 반환 경계에서 Java와 JavaScript가 각각 한 번만 deep copy하도록 중복
  복사를 제거했다.
- spec v0.23.0 changelog에 `import`와 `as`가 새 예약어이며 `elseif`/`limit`와 같은 약한 비호환
  변경이라는 migration 문구를 추가했다.
