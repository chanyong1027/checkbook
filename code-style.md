# 코드 컨벤션 가이드

> **목적**: AI Agent 및 AI 코드 리뷰 자동화를 위한 명확한 규칙 정의
> **대상 언어/프레임워크**: Java 17+, Spring Boot, JPA, Lombok, JUnit 5, RestAssured, Swagger (springdoc-openapi), Flyway
> **최종 수정일**: 2026-03-05

---

# AI Agent 문서 유지보수 지침

> 이 섹션은 AI Agent가 이 문서를 읽고 코드 리뷰 및 문서 업데이트를 수행할 때 따라야 할 메타 지침이다.
> **코드 리뷰 시 반드시 이 섹션을 가장 먼저 읽는다.**

## 문서 업데이트 트리거

### 1. 새 Entity / DTO / Controller 추가 시
- 새로운 Entity가 추가되면 11장(Entity 규칙)의 예시와 체크리스트 항목이 여전히 유효한지 검토한다.
- 새로운 DTO 패턴이 추가되면 10장(DTO 규칙) 및 1.5(DTO 네이밍)가 해당 패턴을 커버하는지 확인한다.
- 새로운 Controller가 추가되면 8장(Controller 규칙) 및 9장(Swagger 규칙)과 대조한다.

### 2. 새 패턴이 코드에 등장하면 규칙으로 문서화
- 기존 문서에 없는 새로운 패턴이 코드에 등장한 경우, 해당 패턴을 분석하여 적절한 섹션에 규칙으로 추가한다.
- 단, 단순히 한 번 등장한 패턴이 아니라 **2개 이상의 도메인에서 반복적으로 사용**될 때 규칙화한다.
- 규칙 추가 시 반드시 부록 체크리스트에도 해당 항목을 추가한다.

### 3. 기존 규칙과 충돌하는 코드 발견 시
- 코드가 문서의 규칙을 위반하고 있는 경우, 아래 두 가지를 판단한다.
   - **코드가 잘못된 경우**: 코드 리뷰 코멘트로 규칙 위반을 지적한다. 문서는 수정하지 않는다.
   - **규칙이 잘못된 경우** (여러 곳에서 동일하게 규칙을 어기고 있거나, 규칙 자체가 모호한 경우): 팀에 확인을 요청한 후 문서를 업데이트한다. AI Agent가 임의로 규칙을 변경하지 않는다.

## 문서 업데이트 원칙
- 규칙을 추가할 때는 반드시 **✅ 올바른 예시**와 **❌ 잘못된 예시**를 함께 작성한다.
- 규칙을 수정할 때는 기존 예시도 함께 수정한다.
- 문서 상단의 **최종 수정일**을 업데이트한다.
- 새 규칙이 추가되면 **부록 체크리스트**에도 반드시 해당 항목을 추가한다.
- 기존 규칙 번호(섹션 번호)는 변경하지 않는다. 새 섹션은 마지막 번호 이후에 추가한다.

---

## 1. 네이밍 규칙

### 1.1 공통 원칙

- **줄임말 금지**: 변수명, 메서드명, 클래스명 등 모든 식별자에서 줄임말을 사용하지 않는다.
   - ❌ `desc`, `cnt`, `msg`, `btn`, `idx`, `tmp`, `val`, `num`, `info`, `mgr`
   - ✅ `description`, `count`, `message`, `button`, `index`, `temporary`, `value`, `number`, `information`, `manager`
   - **예외**: `id`, `dto`, `url`, `api` 등 업계 표준 약어는 허용한다.
- **사용하지 않는 import는 반드시 제거한다.**

### 1.2 Enum 네이밍

- Java 관행(`UPPER_SNAKE_CASE`)을 무조건 따르지 않고, 해당 도메인 맥락에서 가장 이해하기 쉬운 이름으로 작성한다.
- 새로운 Enum 값이 팀 내 용어와 다를 수 있는 경우, 프로젝트 용어 사전(glossary)에 등록한다.

```java
// ✅ 도메인에 어울리는 이름
public enum PlaceCategory {
    BOOTH,
    FOOD_TRUCK,
    STAGE,
    REST_AREA
}
```

### 1.3 메서드 네이밍 — `find` vs `get` 접두사

| 접두사 | 반환 타입 | 의미 | 예시 |
|--------|-----------|------|------|
| `find` | `Optional<T>` | 데이터가 존재하지 않을 수 있다. 호출자가 존재 여부를 직접 처리해야 한다. | `findMemberById(Long id)` → `Optional<Member>` |
| `get` | `T` (non-null) | 데이터가 반드시 존재한다. 존재하지 않으면 예외를 던진다. | `getMemberById(Long id)` → `Member` (없으면 예외) |

- `find`로 시작하는 메서드는 반드시 `Optional<T>`을 반환한다.
- `get`으로 시작하는 메서드는 `Optional`을 반환하지 않으며, 데이터가 없으면 예외를 던진다.
- Service의 private 조회 헬퍼 메서드는 항상 존재를 보장하므로 `get` 접두사를 사용한다. (7.5.4 참고)

### 1.4 패키지 네이밍

- 모든 패키지명은 소문자 단수형으로 작성한다.

### 1.5 DTO 네이밍

| 구분 | 네이밍 패턴 | 예시 |
|------|-------------|------|
| 생성 요청 DTO | `XXXRequest` | `AnnouncementRequest` |
| 수정 요청 DTO | `XXXUpdateRequest` | `AnnouncementUpdateRequest` |
| 단건 응답 DTO | `XXXResponse` | `AnnouncementResponse` |
| 수정 응답 DTO | `XXXUpdateResponse` | `AnnouncementUpdateResponse` |
| 컬렉션 응답 DTO | `XXXResponses` | `AnnouncementResponses` |

- 생성과 수정에서 필드가 다른 경우 별도 DTO를 분리한다.
   - 예: `AnnouncementRequest` (생성), `AnnouncementUpdateRequest` (수정), `AnnouncementPinUpdateRequest` (고정 상태 수정)
- 컬렉션 응답 DTO 내부의 리스트 필드명은 반드시 `responses`로 작성한다.

```java
public record AnnouncementResponses(
        List<AnnouncementResponse> responses
) {
}
```

### 1.6 Repository 네이밍

- Spring Data JPA Repository 인터페이스는 `XXXJpaRepository`로 작성한다.
   - ❌ `PlaceRepository`
   - ✅ `PlaceJpaRepository`

### 1.7 Test Fixture 네이밍

- 클래스명: `XXXFixture`
- 기본 생성 메서드명: `create` (오버로딩으로 다양한 파라미터 조합 지원)
- 모든 파라미터를 받는 메서드명: `createCustom`
- 특정 필드만 커스터마이징하되 시그니처가 충돌하는 경우: `createWithXxx` (13.5 참고)

### 1.8 Controller 메서드 네이밍

- Controller 메서드명은 호출하는 **Service 메서드명과 동일**하게 작성한다.

```java
// Service 메서드가 getGroupedAnnouncementByFestivalId 이면
// Controller 메서드도 동일한 이름을 사용한다.
public AnnouncementGroupedResponses getGroupedAnnouncementByFestivalId(
        @Parameter(hidden = true) @FestivalId Long festivalId
) {
    return announcementService.getGroupedAnnouncementByFestivalId(festivalId);
}
```

### 1.9 Controller Request 파라미터 변수명

- Controller에서 Request DTO를 받는 파라미터 변수명은 반드시 `request`로 작성한다.

```java
// ✅
public AnnouncementResponse createAnnouncement(
        @Parameter(hidden = true) @FestivalId Long festivalId,
        @RequestBody AnnouncementRequest request
) {
    return announcementService.createAnnouncement(festivalId, request);
}
```

### 1.10 미사용 코드 제거

- 사용하지 않는 변수와 메서드는 반드시 제거한다.
- 임시 디버깅용 지역 변수, 더 이상 호출되지 않는 private 메서드는 커밋 전에 정리한다.

```java
// ✅ 실제 사용되는 변수/메서드만 유지
private void validateName(String name) {
    if (!StringUtils.hasText(name)) {
        throw new BadRequestException("이름은 비어 있을 수 없습니다.");
    }
}

// ❌ 사용하지 않는 변수/메서드 방치
private void validateTitle(String title) { }

public void createFestival() {
    String debugMessage = "tmp"; // 사용하지 않음
    // ...
}
```

---

## 2. 포매팅 및 개행 규칙

### 2.1 클래스 / Record / 인터페이스 첫 줄 개행

- 클래스, Record(내용이 있는 빈 Record 포함), 내용이 있는 인터페이스의 여는 중괄호 `{` 다음에 반드시 빈 줄 하나를 삽입한다.

```java
// ✅ 클래스
public class PlaceService {

    private final PlaceJpaRepository placeJpaRepository;
}

// ✅ Record (필드가 있는 경우)
public record PlaceResponse(

        String title,
        String description
) {
}

// ✅ 빈 Record (필드가 없는 경우에도 개행)
public record Empty(
) {
}

// ✅ 인터페이스 (내용이 있는 경우)
public interface PlaceProcessor {

    void process();
}
```

### 2.2 메서드 체이닝 개행 — 점(`.`) 규칙

- 메서드 체이닝이 **2단계 이상**이면 반드시 개행한다. 즉, 반환값에 바로 또 다른 메서드를 호출하는 경우 두 번째 호출부터 새 줄에 작성한다.

```java
// ❌ 체이닝 2단계 — 개행 필요
place.getTitle().trim();

// ✅ 2번째 호출부터 개행
place.getTitle()
        .trim();
```

- **예외 — `stream()`**: `stream()` 호출 자체는 체이닝 규칙의 예외이며, `stream()` 다음 줄부터 개행을 시작한다.

```java
// ✅ stream() 예외 적용
List<String> titles = places.stream()
        .map(Place::getTitle)
        .filter(x -> x != null)
        .toList();
```

### 2.3 닫는 괄호 개행

- 여러 줄에 걸친 메서드 호출에서 닫는 괄호(`)`)와 세미콜론은 별도 줄에 작성한다.
- 닫는 괄호의 들여쓰기는 여는 메서드 호출과 동일한 레벨로 맞춘다.

```java
// ❌
return new EventDayResponses(
        eventDays.stream()
                .map(EventDayResponse::from)
                .toList());

// ✅
return new EventDayResponses(
        eventDays.stream()
                .map(EventDayResponse::from)
                .toList()
);
```

### 2.4 어노테이션 순서 — 클래스 레벨 (Controller 제외)

- 상단에서 하단으로 **어노테이션 이름 자체의 길이가 짧은 순서**대로 배치한다. (피라미드 구조)
- 속성값(경로, 문자열 등)은 길이 비교에서 제외한다.

```java
@Slf4j
@Service
@RequiredArgsConstructor
```

### 2.5 어노테이션 순서 — Controller 클래스 레벨

- Controller 클래스도 피라미드 구조(어노테이션 이름 길이 짧은 순)를 따른다.
- 길이 비교는 어노테이션 **이름 자체**만 기준으로 하며, 경로나 속성값은 제외한다.
- 길이가 같은 경우 `@RestController` → `@RequestMapping` 순으로 배치한다.
- `@Slf4j`는 Controller에 붙이지 않는다. (16장 로깅 규칙 참고)
- `@Tag`는 항상 가장 마지막에 배치한다.

```java
// 어노테이션 이름 길이 기준
// @RestController(14) = @RequestMapping(14) < @RequiredArgsConstructor(24)
@RestController
@RequestMapping("/announcements")
@RequiredArgsConstructor
@Tag(name = "공지", description = "공지 관련 API")
```

### 2.6 어노테이션 순서 — Controller 메서드 레벨

- 메서드 레벨 어노테이션은 아래 **고정 순서**를 따른다:
   1. `@PreAuthorize` (권한 설정이 있는 경우)
   2. HTTP 매핑 어노테이션 (`@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`)
   3. `@ResponseStatus`
   4. `@Operation` (Swagger summary)
   5. `@ApiResponses` (Swagger 응답 정의)

```java
@PreAuthorize("hasAnyRole('STAFF', 'ORGANIZER', 'ADMIN')")
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
@Operation(summary = "🟪 공지 생성")
@ApiResponses(value = {
        @ApiResponse(responseCode = "201", useReturnTypeSchema = true),
})
public AnnouncementResponse createAnnouncement(
        @Parameter(hidden = true) @FestivalId Long festivalId,
        @RequestBody AnnouncementRequest request
) {
    return announcementService.createAnnouncement(festivalId, request);
}
```

### 2.7 파라미터 순서

- 메서드 파라미터는 **메서드 본문에서 실제 사용되는 순서**대로 선언한다.

```java
// 메서드 본문에서 name을 먼저, category를 나중에 사용한다면:
public PlaceResponse createPlace(String name, PlaceCategory category) {
    validateName(name);
    validateCategory(category);
    // ...
}
```

---

## 3. 키워드 및 타입 사용 규칙

### 3.1 `final` 키워드

- **클래스 필드 변수에만** `final` 키워드를 사용한다.
- 로컬 변수, 메서드 파라미터, for-each 변수 등에는 `final`을 사용하지 않는다.

```java
// ✅ 클래스 필드 — final 사용
private final PlaceJpaRepository placeJpaRepository;

// ❌ 로컬 변수 — final 사용하지 않음
final String name = place.getTitle();

// ✅ 로컬 변수 — final 없이 작성
String name = place.getTitle();
```

### 3.2 래퍼 타입 vs 원시 타입

| 상황 | 사용할 타입 | 이유 |
|------|-------------|------|
| 기본값 | 래퍼 타입 (`Long`, `Integer`, `Boolean` 등) | `null` 가능성을 고려 |
| `null`이 절대 들어오지 않음이 확실한 경우 | 원시 타입 (`long`, `int`, `boolean` 등) | 성능 이점 및 의도 명시 |
| Entity의 `id` 필드 | **항상 래퍼 타입** (`Long`) | JPA 영속화 전 `null` 상태 필요 |

---

## 4. Bean 주입

- 생성자 주입 방식을 사용하되, 생성자에서 특별한 작업이 없다면 Lombok의 `@RequiredArgsConstructor`를 사용한다.
- `@Autowired`, `@Inject`, 필드 주입, setter 주입은 사용하지 않는다.

```java
@RestController
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;
}
```

---

## 5. 패키지 구조

- 도메인(기능) 단위로 패키지를 구성하며, 각 도메인 패키지 내부는 아래 구조를 따른다.

```
com.example.project
├── global
│   ├── exception       // BusinessException 및 하위 예외 클래스
│   └── domain          // BaseEntity 등 공통 도메인 클래스
└── announcement
    ├── controller      // REST Controller
    ├── service         // 비즈니스 로직 (Service 클래스)
    ├── domain          // Entity, Enum, VO 등 도메인 모델
    ├── infrastructure  // Repository 인터페이스 (XXXJpaRepository)
    └── dto             // Request, Response DTO (record)
```

- **`exception` 패키지는 도메인별로 두지 않고, `/global/exception/` 하위에 통합한다.**

---

## 6. 검증 규칙

### 6.1 검증 위치

- **DTO에서는 검증하지 않는다.** (`@Valid`, `@Validated`도 사용하지 않는다.) 검증은 오직 도메인(Entity) 또는 Service 레이어에서 수행한다.
- 가능한 한 **도메인 내부 생성자에서 검증**한다.

### 6.2 null 처리 방식

- null 비교는 `value == null`를 사용한다.
- not-null 비교는 `value != null`를 사용한다.
- `Objects.isNull(value)`, `Objects.nonNull(value)` 패턴은 사용하지 않는다.

### 6.3 도메인 검증 if 문 스타일

- 도메인 검증 메서드에서 연속된 `if`문 사이에는 빈 줄을 두지 않는다.
- 검증 `if`문은 공백 없이 연속 배치한다.

```java
public Announcement(
        String title,
        String content,
        boolean isPinned,
        Festival festival
) {
    validateTitle(title);
    validateContent(content);
    validateFestival(festival);

    this.title = title;
    this.content = content;
    this.isPinned = isPinned;
    this.festival = festival;
}

private void validateTitle(String title) {
    if (!StringUtils.hasText(title)) {
        throw new BadRequestException("공지사항 제목은 비어 있을 수 없습니다.");
    }
    if (title.length() > MAX_TITLE_LENGTH) {
        throw new BadRequestException(
                String.format("공지사항 제목은 %s자를 초과할 수 없습니다.", MAX_TITLE_LENGTH)
        );
    }
}

private void validateContent(String content) {
    if (!StringUtils.hasText(content)) {
        throw new BadRequestException("공지사항 본문은 비어 있을 수 없습니다.");
    }
    if (content.length() > MAX_CONTENT_LENGTH) {
        throw new BadRequestException(
                String.format("공지사항 본문은 %s자를 초과할 수 없습니다.", MAX_CONTENT_LENGTH)
        );
    }
}

private void validateFestival(Festival festival) {
    if (festival == null) {
        throw new BadRequestException("축제는 비어 있을 수 없습니다.");
    }
}
```

---

## 7. 예외 처리

### 7.1 예외 계층 구조

- 프로젝트 최상위 예외로 `BusinessException`을 정의한다.
- `BusinessException` 하위에 응답할 HTTP StatusCode를 기준으로 예외를 분류한다. (예: `BadRequestException`, `NotFoundException`, `ForbiddenException` 등)
- 예외 클래스는 `/global/exception/` 하위에 위치한다.
- `RuntimeException`을 직접 던지거나, `BusinessException`을 상속하지 않는 커스텀 예외를 만들지 않는다.

```java
// 최상위 예외
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}

// StatusCode 기준 하위 예외
public class BadRequestException extends BusinessException { }
public class NotFoundException extends BusinessException { }
public class ForbiddenException extends BusinessException { }
public class ConflictException extends BusinessException { }
public class InternalServerException extends BusinessException { }
```

### 7.2 예외 생성 패턴

- `NotFoundException`은 Entity 클래스를 인자로 받아 메시지를 자동 생성한다.
   - 예: `new NotFoundException(Announcement.class)` → `"존재하지 않는 Announcement 입니다."`
- `ConflictException`은 Entity 클래스를 인자로 받거나, 메시지와 Entity 클래스를 함께 받을 수 있다.
   - 예: `new ConflictException(EventDate.class)` → `"이미 존재하는 EventDate 입니다."`
   - 예: `new ConflictException("이미 활성화된 플레이스 접근 정보가 존재합니다.", PlaceAccess.class)`
- `BadRequestException`은 문자열 메시지를 직접 전달한다.
   - 예: `new BadRequestException("공지사항 제목은 비어 있을 수 없습니다.")`
   - 검증 실패 메시지는 null/공백 여부를 구분하지 않고 `~는 비어 있을 수 없습니다.` 형식으로 통일한다.
- `ForbiddenException`은 기본 메시지(`"접근 권한이 없습니다."`)를 사용한다.
   - 예: `new ForbiddenException()`

---

## 7.5 Service 레이어 규칙

### 7.5.1 `@Transactional` 사용

- 데이터를 변경하는 메서드에는 `@Transactional`을 반드시 붙인다.
- 읽기 전용 메서드에는 `@Transactional(readOnly = true)` 또는 어노테이션을 생략한다.
- `@Transactional`은 **Service 클래스의 public 메서드에만** 붙인다.

### 7.5.2 Service 매직넘버 상수 정의

- Service 클래스에서도 비즈니스 제한값(최대 개수 등)은 클래스 상단에 `private static final`로 정의한다.

```java
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private static final int MAX_PINNED_ANNOUNCEMENTS = 3;

    // ...
}
```

### 7.5.3 권한 검증 (`Actor` + Domain)

- 권한 검증 누락 방지를 위해 CRUD 권한 검증을 도메인 내부로 강제한다.
- `Controller`가 `Actor`를 추출해 `Service`로 전달한다.
- `Service`는 orchestration만 담당하고, 권한 판단은 엔티티가 수행한다.
- 의존 방향은 `Service/Domain -> Actor <- AccountDetails`를 유지한다.
- `Service`에서 사전 `validateXxxBy` 호출을 남발하지 않고, 실제 행위 메서드(`createBy/updateBy/deleteBy`)를 호출한다.
- 권한 누락 가능성이 있는 패턴은 금지한다. 예: `staff.validateManagedBy(actor)` 후 `repository.delete(staff)`.

```java
// ✅ 권한 검증을 도메인 행위 내부로 캡슐화
@Transactional
public StaffResponse deleteStaff(Actor actor, Long staffId) {
    Staff staff = getStaffById(staffId);
    staff.deleteBy(actor);
    return StaffResponse.from(staff);
}

// ❌ 권한 검증 분리로 누락 위험 발생
@Transactional
public StaffResponse deleteStaff(Actor actor, Long staffId) {
    Staff staff = getStaffById(staffId);
    staff.validateManagedBy(actor);
    staffJpaRepository.delete(staff);
    return StaffResponse.from(staff);
}
```

### 7.5.4 private 조회 헬퍼 메서드

- Entity를 ID로 조회하는 private 메서드는 `getXxxById()` 패턴으로 작성하고, 없으면 `NotFoundException`을 던진다.
- 항상 존재를 보장하므로 `find`가 아닌 `get` 접두사를 사용한다. (1.3 참고)
- Service 메서드 선언 순서는 `public` 먼저, `private`는 아래에 둔다.
- `public` 메서드는 CRUD 기준으로 `Create → Read → Update → Delete` 순서를 따른다.
- `private` 메서드는 `public` 메서드에서 실제 호출되는 순서대로 배치한다.
  - `private` 메서드 내부에서 이어지는 `private` 호출 순서까지 포함해 선언 순서를 맞춘다.
  - 사용 순서를 동일하게 맞추기 위해 `validate...`, `convert...` 계열도 호출 순서 기준으로 정렬한다.
- **예외**: `private get...By...Id` 계열 조회 메서드는 호출 위치와 무관하게 `private` 메서드 블록의 최하단에 배치한다.
  - 향후 Query/Command/UseCase 분리 시 조회 헬퍼를 하단에 모아 관리하기 위한 규칙이다.

```java
// ✅ 호출 순서 + get~By~Id 최하단 예외
public StaffResponse createStaff(Actor actor, Long organizationId, StaffRequest request) {
    Organization organization = getOrganizationByOrganizationId(organizationId);
    validateUsernameDuplicated(request.username());
    validatePasswordByte(request.password());
    Staff staff = staffJpaRepository.save(request.toEntity(passwordEncoder.encode(request.password()), organization));
    createStaffAuthorities(actor, staff, request.festivalIds());
    return StaffResponse.from(staff);
}

private void validateUsernameDuplicated(String username) { ... }
private void validatePasswordByte(String password) { ... }
private void createStaffAuthorities(Actor actor, Staff staff, List<Long> festivalIds) { ... }
private Organization getOrganizationByOrganizationId(Long organizationId) { ... } // get~By~Id는 최하단
```

```java
private Announcement getAnnouncementById(Long announcementId) {
    return announcementJpaRepository.findById(announcementId)
            .orElseThrow(() -> new NotFoundException(Announcement.class));
}
```

### 7.5.5 도메인 권한 메서드 컨벤션

- 적용 대상 주요 도메인: `Festival`, `Organization`, `Staff`, `StaffAuthority`, `Organizer`, `Place`, `PlaceAccess`
- 모든 변경 메서드는 `Actor`를 첫 번째 인자로 받는다.
- 메서드 네이밍은 `createBy`, `updateXxxBy`, `deleteBy`를 사용한다.
- 생성자는 `protected` 기본으로 한다.
- 도메인 변경은 `deleteBy()` 내부에서 `softDelete()`를 호출한다.
- `validateCreatableBy`, `validateUpdatableBy`, `validateDeletableBy`는 기본적으로 `private`이다.
- 여러 도메인에서 재사용되는 권한 메서드만 `public`으로 노출한다. 예: `validateOwnedBy`, `validateReadableBy`
- 공통 규칙이 같으면 `validateWritableBy(actor)`로 모아 C/U/D에서 위임한다.
- `Actor`가 `null`이면 `InternalServerException`을 던진다.
- `actor.isAdmin()`은 우선 허용한다.
- 역할/소속 체크 실패는 `ForbiddenException`을 던진다.
- `Actor` 식별 메서드는 `hasOrganization`, `hasFestival`, `hasOrganizer`, `hasStaff`, `hasPlaceAccess`를 표준으로 사용한다.
- `hasXxxAuthority` 같은 과거 명칭은 사용하지 않는다.

```java
public void deleteBy(Actor actor) {
    validateDeletableBy(actor);
    softDelete();
}

private void validateCreatableBy(Actor actor) {
    validateWritableBy(actor);
}

private void validateUpdatableBy(Actor actor) {
    validateWritableBy(actor);
}

private void validateDeletableBy(Actor actor) {
    validateWritableBy(actor);
}

private void validateWritableBy(Actor actor) {
    if (actor == null) {
        throw new InternalServerException();
    }
    if (actor.isAdmin()) {
        return;
    }
    // 역할 체크
    // 소속/소유 체크 또는 상위 엔티티 위임
    throw new ForbiddenException();
}
```

### 7.5.6 권한 위임 기준 (Aggregate 관점)

- 자기 자신으로 판단 가능한 권한은 해당 도메인에서 직접 처리한다. 예: `Staff` 본인 수정.
- 자기 자신으로 판단 불가능하면 상위 소유자에 위임한다. 예: `Place -> Festival`, `Festival -> Organization`.
- 하위 엔티티는 상위 Aggregate Root의 권한 규칙을 따른다.

```java
// Place -> Festival로 위임
private void validateWritableBy(Actor actor) {
    festival.validateOwnedBy(actor);
}

// Staff 본인 수정은 Staff가 직접 판단
private void validateUpdatableBy(Actor actor) {
    if (actor.isAdmin()) {
        return;
    }
    if (actor.isStaff() && actor.hasStaff(id)) {
        return;
    }
    throw new ForbiddenException();
}
```

- 도메인별 기본 정책:
- `Organization`: 생성/삭제는 admin 중심, 소유 검증은 `validateOwnedBy(actor)` 공개.
- `Festival`: 생성은 organization 소유권을 통해 검증, 수정/삭제는 festival 소유권 검증.
- `Staff`: 생성/삭제는 조직 소유(organizer/admin), 수정은 본인 또는 admin.
- `StaffAuthority`: 생성/삭제는 상위 festival 소유권 검증(organizer/admin).
- `Organizer`: 생성은 admin, 수정은 본인 또는 admin.
- `Place`: 생성/수정/삭제는 festival 소유권(organizer/staff/admin), PlaceAccess 전용 수정 경로는 별도 검증.
- `PlaceAccess`: 생성/삭제/조회는 상위 place 소유권 기반 검증.
- `Device`: 생성/수정은 도메인 행위(`createBy`, `updateFcmTokenBy`)로 수행하며, 입력 검증은 도메인 내부에서 처리.
- `FestivalNotification`: 생성/삭제는 도메인 행위(`createBy`, `delete`)로 수행하며, `Festival`/`Device` null 검증을 도메인에서 처리.

---

## 8. Controller 규칙

### 8.1 응답 상태 코드

- `ResponseEntity`를 사용하지 않는다.
- `@ResponseStatus` 어노테이션으로 HTTP 상태 코드를 명시한다.
- `200 OK`도 생략하지 않고 명시적으로 작성한다.

```java
// ❌ ResponseEntity 사용
@GetMapping("/{placeId}")
public ResponseEntity<PlaceResponse> getPlace(@PathVariable Long placeId) {
    return ResponseEntity.ok(placeService.getPlace(placeId));
}

// ✅ @ResponseStatus 사용
@GetMapping("/{placeId}")
@ResponseStatus(HttpStatus.OK)
public PlaceResponse getPlace(
        @PathVariable Long placeId
) {
    return placeService.getPlace(placeId);
}
```

### 8.2 API 엔드포인트 네이밍

- 리소스명은 반드시 **복수형**으로 작성한다.
   - ❌ `/api/place`, `/api/festival`
   - ✅ `/api/places`, `/api/festivals`

### 8.3 메서드 파라미터 선언부 개행

- **파라미터가 없는 경우**: 개행하지 않고 한 줄로 작성한다.
- **파라미터가 1개 이상인 경우**: 반드시 파라미터 선언부부터 개행하고, 닫는 괄호도 별도 줄에 작성한다.

```java
// ✅ 파라미터 없음 — 한 줄
@GetMapping
@ResponseStatus(HttpStatus.OK)
public PlaceResponses getPlaces() {
    return placeService.getPlaces();
}

// ✅ 파라미터 있음 — 개행
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public PlaceResponse createPlace(
        @RequestBody PlaceCreateRequest request
) {
    return placeService.createPlace(request);
}
```

### 8.4 `@PreAuthorize` 권한 순서

- `@PreAuthorize`의 `hasAnyRole()`에 나열하는 Role은 **권한이 적은 순서에서 많은 순서**로 작성한다.
- 현재 프로젝트 Role 순서: `PLACE_ACCESS` → `STAFF` → `ORGANIZER` → `ADMIN`

```java
// ✅ 권한이 적은 것부터 많은 것 순서
@PreAuthorize("hasAnyRole('PLACE_ACCESS', 'STAFF', 'ORGANIZER', 'ADMIN')")

// ✅ STAFF부터 시작하는 경우
@PreAuthorize("hasAnyRole('STAFF', 'ORGANIZER', 'ADMIN')")
```

### 8.5 Controller 통합 테스트 범위

- Controller 테스트(통합 테스트)는 **성공 케이스**와 **권한 테스트**만 작성한다.
- 비즈니스 로직 검증은 Service/Domain 단위 테스트에서 수행한다.

### 8.6 Controller 메서드 선언 순서

- Controller의 `public` 메서드 선언 순서는 호출하는 Service `public` 메서드 순서를 반영한다.
- 기본 흐름은 CRUD 기준 `Create → Read → Update → Delete`를 따른다.
- 로그인/중복검사/권한확인 등 CRUD 외 메서드는 대응 Service 선언 순서에 맞춘다.
- 하나의 Controller가 여러 Service를 사용하는 경우:
  - 주 리소스 Service 기준으로 CRUD 흐름을 우선 지킨다.
  - 보조 Service의 조회 API는 Read 구간 내부에 배치한다.
- 컨트롤러 메서드 이동 시 메서드 레벨 어노테이션(`@PreAuthorize`, HTTP 매핑, `@ResponseStatus`, `@Operation`, `@ApiResponses`)이 누락되지 않아야 한다.

---

## 9. API 문서 (Swagger) 규칙

### 9.1 어노테이션 위치

- Swagger 관련 어노테이션은 **Controller 클래스**와 **DTO 클래스**에만 작성한다.
- Swagger 전용 인터페이스를 별도로 분리하지 않는다.

### 9.2 사용 어노테이션

- 클래스 레벨: `@Tag`
- 메서드 레벨: `@Operation`, `@ApiResponses`
- DTO 필드: `@Schema` (필요 시)

### 9.3 `@ResponseStatus`와 `@ApiResponse`의 responseCode 일치

- `@ResponseStatus`의 상태 코드와 `@ApiResponse`의 `responseCode`는 반드시 일치해야 한다.

```java
// ✅ 둘 다 201로 일치
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
@Operation(summary = "🟪 공지 생성")
@ApiResponses(value = {
        @ApiResponse(responseCode = "201", useReturnTypeSchema = true),
})
public AnnouncementResponse createAnnouncement(
        @Parameter(hidden = true) @FestivalId Long festivalId,
        @RequestBody AnnouncementRequest request
) {
    return announcementService.createAnnouncement(festivalId, request);
}
```

### 9.4 `@Operation summary` 권한 접두사 이모지

- 권한별로 아래 이모지를 `@Operation summary` 앞에 접두사로 붙인다.

| 이모지 | 의미 |
|--------|------|
| 🟥 | `ADMIN`만 접근 가능 |
| 🟨 | `ORGANIZER`, `ADMIN`만 접근 가능 |
| 🟪 | `STAFF`, `ORGANIZER`, `ADMIN`만 접근 가능 |
| 🟦 | `PLACE_ACCESS`, `STAFF`, `ORGANIZER`, `ADMIN`만 접근 가능 또는 `PLACE_ACCESS`만 접근 가능 |
| 🟢 | 권한 없이 접근 가능 — Android에서 사용 중 |
| ⚫ | 권한 없이 접근 가능 — 운영자 페이지에서 사용 중 |
| ⚪ | 권한 없이 접근 가능 — Android, 운영자 페이지 모두 사용 중 |

```java
@Operation(summary = "🟪 공지 생성")       // STAFF 이상 접근
@Operation(summary = "⚪ 축제의 공지 전체 조회") // 누구나 접근 가능, 양쪽 모두 사용
```

### 9.5 `@Operation summary` 작성 패턴

#### 전체 조회

- 상위 도메인에 속하는 하위 도메인을 모두 조회하는 경우: **"[상위 도메인]의 [하위 도메인] 전체 조회"**
   - ✅ `축제의 플레이스 전체 조회`, `플레이스의 공지 전체 조회`

#### 생성/수정/삭제

- 도메인 자체의 CRUD: **"[도메인] 생성/수정/삭제"**
   - ✅ `플레이스 생성`, `플레이스 수정`, `플레이스 삭제`
- 축제 하위 도메인이 아닌 경우(플레이스 하위 등): **"[상위 도메인] [하위 도메인] 생성/수정/삭제"**
   - ✅ `플레이스 이미지 생성`, `플레이스 공지 수정`
- 축제 직속 하위 도메인의 경우: 상위 도메인(축제)을 생략한다.
   - ✅ `FAQ 생성` (❌ `축제 FAQ 생성`)

#### 사용 금지 단어

| 금지 표현 | 올바른 표현 |
|-----------|-------------|
| `특정 축제의 플레이스` | `축제의 플레이스` |
| `축제에 대한 플레이스` | `축제의 플레이스` |
| `축제들 조회` (복수형 `~들`) | `축제 전체 조회` |
| `모든 축제 조회` (`모든`) | `축제 전체 조회` |

---

## 10. DTO 규칙

### 10.1 선언

- DTO는 반드시 `record`로 작성한다. (`class`로 작성하지 않는다.)
- 각 필드는 반드시 한 줄에 하나씩 작성한다.

### 10.2 생성 방식

- 외부에서 Response DTO 인스턴스를 생성할 때는 최대한 **Response 내부 정적 팩터리 메서드**를 활용한다.
- 정적 팩터리 메서드명은 `from` (단일 객체 변환) 또는 `of` (여러 인자 조합)를 사용한다.

```java
public record AnnouncementResponse(
        Long announcementId,
        String title,
        String content,
        boolean isPinned,
        LocalDateTime createdAt
) {

    public static AnnouncementResponse from(Announcement announcement) {
        return new AnnouncementResponse(
                announcement.getId(),
                announcement.getTitle(),
                announcement.getContent(),
                announcement.isPinned(),
                announcement.getCreatedAt()
        );
    }
}
```

### 10.3 Request DTO — `toEntity()` 메서드

- Request DTO에서 Entity로 변환할 때는 `toEntity()` 메서드를 Request DTO 내부에 작성한다.
- 연관관계 Entity 등 외부 의존 파라미터가 필요한 경우, `toEntity()`의 파라미터로 받는다.

```java
public record AnnouncementRequest(
        // ...
) {

    public Announcement toEntity(Festival festival) {
        return new Announcement(
                title,
                content,
                isPinned,
                festival
        );
    }
}
```

### 10.4 컬렉션 응답 DTO — `@JsonValue`

- 컬렉션 응답 DTO(`XXXResponses`)의 리스트 필드에는 `@JsonValue`를 붙여 JSON 직렬화 시 불필요한 래핑 없이 배열로 응답한다.
- **단, 복합 응답 DTO처럼 응답에 포함해야 할 다른 필드가 있는 경우에는 `@JsonValue`를 사용하지 않는다.**
   - ❌ `AnnouncementGroupedResponses`의 `pinned`, `unpinned` 필드 — 두 필드 모두 응답해야 하므로 사용 금지

```java
// ✅ 단순 컬렉션 응답 — @JsonValue 사용 (responses depth 제거)
public record AnnouncementResponses(
        @JsonValue List<AnnouncementResponse> responses
) { }
// 응답: [...] (배열 직접 반환)

// ❌ 복합 응답 DTO — @JsonValue 사용 금지 (두 필드 모두 응답해야 함)
public record AnnouncementGroupedResponses(
        AnnouncementResponses pinned,
        AnnouncementResponses unpinned
) { }
// 응답: { "pinned": [...], "unpinned": [...] }
```

### 10.5 Response DTO — ID 필드 네이밍

- Response DTO에서 Entity의 `id` 필드를 포함할 때는 `id`가 아닌 **`{도메인명}Id`** 형태로 작성한다.
   - ❌ `id`
   - ✅ `announcementId`, `placeId`, `eventId`

### 10.6 Response DTO — `@JsonFormat`

- `LocalTime`, `LocalDateTime` 등 시간 필드에 특정 포맷이 필요한 경우 `@JsonFormat`을 사용한다.

```java
public record EventResponse(
        Long eventId,
        EventStatus status,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        String title,
        String location
) {
}
```

### 10.7 Request DTO — Swagger `@Schema` 필수

- Request DTO의 모든 필드에는 반드시 `@Schema`로 `description`과 `example`을 작성한다.

```java
public record AnnouncementRequest(

        @Schema(description = "공지 제목", example = "폭우가 내립니다.")
        String title,

        @Schema(description = "공지 내용", example = "우산을 챙겨주세요.")
        String content,

        @Schema(description = "공지 고정 여부", example = "true")
        boolean isPinned
) {
}
```

### 10.8 응답에서 제외할 필드

- `festivalId`는 응답 DTO에 포함하지 않는다. (URL path에서 이미 식별 가능하므로 중복 제거)

---

## 11. Entity 규칙

### 11.1 클래스 선언

- 모든 Entity 클래스는 `@Getter`를 사용한다. (Setter는 사용하지 않는다.)
- 모든 Entity 클래스는 `BaseEntity`를 상속한다. (`BaseEntity`에 `createdAt`, `deleted`, `deletedAt` 등 공통 필드가 포함됨)

### 11.2 Entity 클래스 어노테이션 순서

- Entity 클래스는 아래 **고정 순서**를 따른다:
   1. `@Entity`
   2. `@Getter`
   3. `@SQLRestriction` (Soft Delete용)
   4. `@SQLDelete` (Soft Delete용)
   5. `@NoArgsConstructor(access = AccessLevel.PROTECTED)`
   6. `@EqualsAndHashCode` (사용하는 경우에 한함)

```java
@Entity
@Getter
@SQLRestriction("deleted = false")
@SQLDelete(sql = "UPDATE announcement SET deleted = true, deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Announcement extends BaseEntity {
```

### 11.3 필드 순서

1. `@Id` 필드 (PK)
2. 다대일 연관관계 필드 (`@ManyToOne`) — **PK 바로 다음**
3. 일반 비즈니스 필드
4. `createdAt`은 `BaseEntity`에서 상속받으므로 Entity에 직접 선언하지 않는다.

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;

@JoinColumn(nullable = false)
@ManyToOne(fetch = FetchType.LAZY)
private Festival festival;

@Column(nullable = false, length = MAX_TITLE_LENGTH)
private String title;
```

### 11.4 연관관계 매핑

- 연관관계가 있는 필드는 FK ID 값만 저장하지 않고, **Entity 객체로 매핑**한다.

```java
// ❌ FK ID만 저장
private Long festivalId;

// ✅ Entity 연관관계 매핑
@JoinColumn(nullable = false)
@ManyToOne(fetch = FetchType.LAZY)
private Festival festival;
```

### 11.5 `id` 필드 타입

- `id` 필드는 **항상 래퍼 타입(`Long`)**을 사용한다. (원시 타입 `long` 사용 금지)

### 11.6 `@EqualsAndHashCode`

- 기본적으로 사용하지 않는다.
- **`Set`이나 `Map`의 키로 Entity를 사용하는 등 동등성 비교가 필요한 경우에만** `@EqualsAndHashCode(onlyExplicitlyIncluded = true)`를 사용하고, `@EqualsAndHashCode.Include`를 `id` 필드에만 붙인다.

### 11.7 생성자 규칙

- **Builder 패턴은 사용하지 않는다.**
   - **예외**: 외부 의존(Manager, Client 등 외부 모듈)이 Builder 패턴만 제공하는 경우에는 사용할 수 있다.
- `id`를 제외한 모든 필드를 받는 생성자를 직접 작성한다.
- 모든 파라미터는 한 줄에 하나씩 개행하여 작성한다.
- 닫는 괄호(`)`)는 별도 줄에 작성한다.
- **생성자 내부에서 도메인 검증을 수행한다.** (6. 검증 규칙 참고)

### 11.8 JPA 기본 생성자

- JPA가 사용할 기본 생성자는 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`로 작성한다.

### 11.9 매직넘버 상수 정의

- 길이, 크기 등 매직넘버 상수는 **클래스 최상단**에 `private static final`로 정의한다.

### 11.10 DDL 매핑

- JPA Auto DDL은 사용하지 않지만, Entity의 `@Column` 어노테이션은 **최대한 실제 스키마와 동일하게** 매핑한다.

### 11.11 Soft Delete

- 특별한 이유가 없다면 기본적으로 **Soft Delete**를 사용한다.
- `@SQLRestriction`과 `@SQLDelete`를 사용하며, `@SQLDelete` 내부의 테이블명은 **Entity 클래스명(소문자 snake_case)**과 일치해야 한다.

### 11.12 DB 컬럼 타입

- 문자열 컬럼의 기본 타입은 `VARCHAR`를 사용한다.
- `TEXT` 타입이 필요한 경우 팀 내 논의 후 결정한다.

### 11.13 정렬

- 데이터 정렬은 Repository(JPQL/QueryDSL의 `ORDER BY`) 또는 Service(Java `Comparator`)에서 구현하며, 구현 위치는 개발자가 상황에 맞게 판단한다.
- **자연 순서가 있는 Entity**는 `Comparable` 인터페이스를 구현할 수 있다.

### 11.14 Entity 업데이트 메서드

- Entity 수정은 Setter 대신 **의미 있는 이름의 업데이트 메서드**를 작성한다.
- 업데이트 메서드 내에서도 검증을 수행한다.

```java
public void updateTitleAndContent(String title, String content) {
    this.title = title;
    this.content = content;
}

public void updatePinned(boolean isPinned) {
    this.isPinned = isPinned;
}
```

---

## 12. 테스트 코드 규칙

### 12.1 테스트 작성 범위 및 방식

| 대상 | 테스트 방식 | 비고 |
|------|-------------|------|
| Service | 단위 테스트 (Mockito + BDDMockito) | 모든 외부 의존성을 Mocking |
| Repository | 단위 테스트 (`@DataJpaTest`) | |
| 도메인 로직 (Entity, VO 등) | 단위 테스트 (순수 Java) | Fixture 사용 |
| API (Controller) | 통합 테스트 (`RestAssured`) | `AcceptanceTestSupport` 상속, 성공 + 권한 테스트만 작성 |

### 12.2 테스트 클래스 구조

- `@DisplayName`은 사용하지 않는다.
- 클래스에 `@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)`를 반드시 작성한다.
   - **예외**: `AcceptanceTestSupport`를 상속받는 테스트 클래스는 부모 클래스에 이미 선언되어 있으므로 작성하지 않는다.
   - `AcceptanceTestSupport` 상속 테스트에서는 `DisplayNameGeneration`, `DisplayNameGenerator` import도 추가하지 않는다.
- 테스트하려는 메서드마다 `@Nested` 클래스를 작성한다.
- `@Nested` 클래스명은 테스트하려는 메서드명과 동일하게 작성한다.
  - 상속/인터페이스 default 메서드를 검증하는 경우에도 실제 대상 메서드명과 동일하게 작성한다.
  - Lombok으로 생성되는 메서드(예: `@Getter`의 `getXxx`)를 검증하는 경우에도 생성 메서드명과 동일하게 작성한다.
- `@Nested` 클래스 선언 순서는 실제 대상 클래스의 메서드 선언 순서와 동일하게 맞춘다.
  - ControllerTest는 Controller 메서드 순서를 기준으로 맞춘다.
  - Service/Controller 테스트는 기본적으로 `Create → Read → Update → Delete` 순서를 따른다.
  - 로그인/중복검사/권한확인 등 CRUD 외 메서드는 실제 Service/Controller 선언 위치를 그대로 따른다.

```java
// ❌ 대상 클래스 순서와 불일치
@Nested
class createEvent { }

@Nested
class updateEvent { }

@Nested
class getAllEventByEventDateId { }

// ✅ 대상 클래스 메서드 선언 순서와 일치
@Nested
class createEvent { }

@Nested
class getAllEventByEventDateId { }

@Nested
class updateEvent { }
```

```java
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AnnouncementServiceTest {

    @Nested
    class createAnnouncement {

        @Test
        void 성공_공지를_생성한다() { }

        @Test
        void 실패_제목이_비어있으면_예외가_발생한다() { }
    }
}
```

### 12.3 Controller 테스트 구조

- Controller 테스트는 `AcceptanceTestSupport`를 상속받아 작성한다.

### 12.4 테스트 메서드 네이밍

- 메서드명은 **한글**로 작성한다.
- 메서드명이 숫자로 시작하는 경우 `_`를 접두사로 붙인다.
- 메서드명 패턴:
   - 성공: `성공_사유_또는_조건을_설명한다()`
   - 실패/예외: `예외_사유를_설명한다()` 또는 `실패_사유를_설명한다()`
- `NotFoundException` 테스트 메서드명은 `예외_존재하지_않는_...()` 패턴을 사용한다.
- 권한 검증 실패 테스트 메서드명은 `예외_권한_없는_주체는_권한_검증_실패()` 패턴을 사용한다.
- 도메인 권한/입력 검증 테스트 네이밍은 12.23 규칙을 우선 적용한다.

```java
// ✅ NotFoundException
void 예외_존재하지_않는_축제() { ... }

// ✅ 권한 검증 실패
void 예외_권한_없는_축제는_권한_검증_실패() { ... }
```

### 12.5 given-when-then 주석

- **모든 테스트**에 `// given`, `// when`, `// then` 주석을 작성하여 구간을 구분한다.
- **예외 검증 테스트**는 `// given`, `// when & then`으로 구분한다.

```java
// ✅ 일반 테스트
@Test
void 성공_공지를_생성한다() {
    // given
    // when
    // then
}

// ✅ 예외 검증 테스트
@Test
void 예외_제목이_비어있으면_예외가_발생한다() {
    // given
    // when & then
    assertThatThrownBy(() -> ...)
            .isInstanceOf(BadRequestException.class);
}
```

### 12.6 변수 네이밍

- 테스트 실행 결과 값 변수명: `result`
- 기대값 변수명: `expected`

### 12.7 모킹

- `@Mock`으로 모킹하고, `@InjectMocks`로 테스트 대상에 주입한다.
- `@MockBean`, `@SpyBean`은 인수 테스트에서만 사용한다.
- Mocking 시 `import static org.mockito.BDDMockito`를 사용한다. (`Mockito.when()` 대신 `BDDMockito.given()` 사용)

```java
// ✅ BDDMockito 사용
given(placeJpaRepository.findById(anyLong()))
        .willReturn(Optional.of(place));

// ❌ Mockito.when() 사용하지 않음
when(placeJpaRepository.findById(anyLong()))
        .thenReturn(Optional.of(place));
```

### 12.8 검증 — `assertThat` vs `assertSoftly`

- 검증할 데이터가 **1개**이면 `assertThat`만 사용한다.
- 검증할 데이터가 **여러 개**이면 `assertSoftly`를 사용한다.

### 12.9 필드 순서

- `@LocalServerPort`는 테스트 클래스 필드 중 **가장 마지막**에 선언한다.

### 12.10 상태 코드 검증

- 상태 코드 검증 시 숫자 리터럴이 아닌 `HttpStatus` enum을 사용한다.
   - ❌ `.statusCode(200)`
   - ✅ `.statusCode(HttpStatus.OK.value())`

### 12.11 Controller 테스트 — 필드 사이즈 검증

- Controller 통합 테스트에서 응답 DTO의 **필드 개수 검증**도 반드시 작성한다.

```java
int expectedFieldSize = 5;
// ...
.body("size()", equalTo(expectedFieldSize))
```

### 12.12 컬렉션 사이즈 검증

- 컬렉션의 사이즈는 `$` + `hasSize()`로 검증한다.
   - 루트 레벨: `body("$", hasSize(3))`
   - 중첩 필드: `body("responses", hasSize(3))`

### 12.13 객체 생성과 저장 분리

- 객체 생성과 Repository 저장은 **분리하여** 작성한다.

```java
// ❌
Festival festival = festivalJpaRepository.save(FestivalFixture.create());

// ✅
Festival festival = FestivalFixture.create();
festivalJpaRepository.save(festival);
```

### 12.14 Fixture 사용 범위

| 테스트 대상 | Fixture 사용 여부 |
|------------|-------------------|
| 도메인 테스트 (Entity, VO) | ✅ Fixture 사용 |
| Service 테스트 | ✅ Fixture 사용 |
| Repository 테스트 | ✅ Fixture 사용 |
| Controller 통합 테스트 | ✅ Fixture 사용 |

### 12.15 Controller 테스트 — 권한 테스트

- 권한별 접근 테스트는 `@ParameterizedTest`와 `@EnumSource`를 사용하여 Role별로 검증한다.
- 접근 가능한 Role을 테스트할 때는 `mode = Mode.EXCLUDE`로 접근 불가능한 Role을 제외한다.

```java
@ParameterizedTest
@EnumSource(mode = EnumSource.Mode.EXCLUDE, names = "ROLE_PLACE_ACCESS")
void 성공(RoleType roleType) { }

@ParameterizedTest
@EnumSource(mode = EnumSource.Mode.EXCLUDE, names = {"ROLE_ADMIN", "ROLE_ORGANIZER"})
void 예외_ADMIN_ORGANIZER_외_권한(RoleType roleType) {
    // ...
    .statusCode(HttpStatus.FORBIDDEN.value());
}
```

### 12.16 동시성 테스트

- 동시성 테스트 클래스는 `XXXConcurrencyTest`로 명명한다.
- `AcceptanceTestSupport`를 상속받으며, `concurrencyTestHelper`를 활용한다.
- 테스트 메서드명 접두사는 `동시성_`을 사용한다.
- 동시성 테스트는 `concurrency` 패키지에 위치한다.

### 12.17 Service 테스트 — BDDMockito 패턴

```java
// ✅ 예외 던지기
willThrow(new ForbiddenException())
        .given(staff)
        .deleteBy(actor);

// ✅ void 메서드 mock
willDoNothing()
        .given(staff)
        .updateNameBy(actor, request.name());

// ✅ 행위 검증
then(announcementJpaRepository).should()
        .save(any(Announcement.class));

// ✅ 호출되지 않음 검증
then(announcementJpaRepository).should(never())
        .countByFestivalIdAndIsPinnedTrue(festivalId);
```

### 12.18 테스트 코드 개행 규칙

```java
// ✅ BDDMockito given() 개행
given(placeJpaRepository.findById(anyLong()))
        .willReturn(Optional.of(place));

// ✅ assertThat 개행 — 체이닝 여러 개
assertThat(result)
        .hasSize(3)
        .containsExactly("a", "b", "c");

// ✅ RestAssured 개행 — .given() 전 개행
RestAssured
        .given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/places")
        .then()
        .statusCode(HttpStatus.CREATED.value());
```

### 12.19 테스트 의도 노출 원칙

- 테스트는 **검증하려는 의도만** 드러내야 한다.
- `given`에는 해당 테스트에서 검증하는 값만 노출한다.
- 검증과 무관한 값은 Fixture 기본값으로 숨긴다.

```java
// ✅ 의도한 값(organizationId)만 노출
StaffDetails details = AccountDetailsFixture.createStaffWithOrganizationId(1L);

// ❌ 의도와 무관한 값까지 과도하게 노출
StaffDetails details = AccountDetailsFixture.createStaff("username", 20L, 1L, Set.of(5L));
```

### 12.20 Service 테스트 — Actor 선언 규칙

- `Actor`는 테스트 클래스 필드(`private final Actor ...`)로 선언하지 않는다.
- `Actor`는 각 테스트 메서드의 `given` 블록에서 지역변수로 선언한다.
- Service 메서드 호출 인자에 `TestActors.admin()`/`TestActors.unauthorized()`를 직접 넣지 않는다.
- `Actor` 지역변수 선언 직후에는 반드시 한 줄을 비운다.

```java
// ✅
// given
Actor adminActor = TestActors.admin();

FestivalCreateResponse result = festivalService.createFestival(adminActor, organizationId, request);

// ❌ 클래스 필드 선언
private final Actor adminActor = TestActors.admin();

// ❌ 인라인 직접 호출
festivalService.createFestival(TestActors.admin(), organizationId, request);
```

### 12.21 Service 테스트 — `given` 블록 순서

- Service 테스트의 `given`은 아래 순서를 기본으로 작성한다.
   1. `Actor`
   2. 변수들 (`id`, `entity`, `name` 등)
   3. `given(...)` 모킹
   4. `request`
- **예외**: `request`가 모킹 값 계산에 먼저 필요하면, `request`를 모킹 위에 둘 수 있다.

```java
// ✅ 기본 순서
// given
Actor adminActor = TestActors.admin();

Long invalidFestivalId = 0L;

given(festivalJpaRepository.findById(invalidFestivalId))
        .willReturn(Optional.empty());

FestivalGeographyPolygonHoleBoundaryUpdateRequest request =
        FestivalGeographyPolygonHoleBoundaryUpdateRequestFixture.create();
```

### 12.22 테스트 도메인/값객체 생성 방식

- 적용 범위: `**/*Test.java` (모든 테스트)
- 모든 테스트에서 도메인 객체(Entity)와 값객체(VO)는 `new`로 직접 생성하지 않고 Fixture를 사용한다.
- 테스트에서 도메인/값객체 생성 시 `XXXFixture.create(...)` 또는 `XXXFixture.createWithXxx(...)`를 사용한다.

```java
// ✅
Festival festival = FestivalFixture.create(organization, 1L);

// ❌
Festival festival = new Festival(...);
```

### 12.23 도메인 권한/입력 검증 테스트 컨벤션

- 적용 대상: 도메인의 권한 검증 메서드(`validateReadableBy`, `validateWritableBy`, `validateCreatableBy`, `validateUpdatableBy`, `validateDeletableBy`, `validateOwnedBy`) 및 행위 메서드(`createBy`, `updateBy`, `deleteBy`).
- `createBy`, `updateXxxBy`, `deleteBy` `@Nested` 클래스의 **첫 번째 테스트 메서드**는 반드시 `성공()`으로 작성한다.
- `성공()` 테스트에서는 입력값을 지역변수로 추출하고, 변경/생성 결과의 핵심 필드를 누락 없이 검증한다.
  - 검증값이 여러 개인 경우 `assertSoftly`를 사용한다.
- `성공()`에서 값 검증을 이미 수행한 경우, 같은 `@Nested` 내 권한 검증 테스트(`성공_{ROLE}_...`)는 값 검증을 중복하지 않는다.
  - 성공 권한 테스트: `assertThatCode(...).doesNotThrowAnyException()`만 검증
  - 실패 권한 테스트: `isInstanceOf(...)` + `hasMessage(...)`만 검증
- 예외 타입명(`ForbiddenException`, `BadRequestException`, `NullPointerException` 등)은 테스트 메서드명에 노출하지 않는다.
- 권한 관련 테스트 메서드명은 아래 패턴을 사용한다.
   - 성공: `성공_{ROLE}_{조건}_접근_권한_있음`
   - 예외: `예외_{ROLE}_{조건}_접근_권한_없음`
   - 예외(권한 미보유 Actor): `예외_권한_없는_Actor_접근_권한_없음`
   - 예외(Null Actor): `예외_Null_Actor_서버_예외_발생`
- 입력 검증 테스트 메서드명은 `예외_{필드}_{조건}` 패턴을 사용한다.
  - 예: `예외_좌표_null`, `예외_제목_빈_문자열`
- 주요 도메인의 권한 네이밍에서 `{조건}`은 실제 권한 기준 축을 그대로 사용한다.
  - `Organization`: `같은_조직_소속` / `다른_조직_소속`
  - `Organizer`: `본인_계정` / `다른_계정`
  - `Staff`
    - `createBy`, `deleteBy`: `같은_조직_소속` / `다른_조직_소속`
    - `updateNameBy`, `updatePasswordBy`, `validateReadableBy`: `본인_계정` / `다른_계정`
  - `StaffAuthority`: `같은_축제_소속` / `다른_축제_소속`
  - `Place` (Organizer/Staff 기준): `같은_축제_소속` / `다른_축제_소속`
  - `PlaceAccess` (Organizer/Staff 기준): `같은_축제_소속` / `다른_축제_소속`
- 도메인 권한 테스트는 아래 순서를 기본으로 작성한다.
   1. `성공_ADMIN_...`
   2. `성공_ORGANIZER_...`
   3. `성공_STAFF_...`
   4. `예외_ORGANIZER_다른_{소속_기준}_소속_접근_권한_없음`
   5. `예외_STAFF_다른_{소속_기준}_소속_접근_권한_없음`
   6. `예외_PLACE_ACCESS_접근_권한_없음`
   7. `예외_권한_없는_Actor_접근_권한_없음`
   8. `예외_Null_Actor_서버_예외_발생` (항상 마지막)
- 입력 검증 테스트는 권한 검증 테스트 아래에 배치한다.
  - 권한 검증을 통과하는 `Actor`로 진입한 뒤 입력 검증을 수행한다.
- 예외 검증은 반드시 `isInstanceOf(...)` 다음 `hasMessage(...)`까지 검증한다.
- `given` 블록에서 `Actor`는 지역 변수로 선언하고, 선언 직후 반드시 한 줄 공백을 둔다.

```java
// ✅ 권한 테스트 네이밍/순서/검증
@Test
void 성공() {
    // given
    Actor actor = ActorFixture.admin();

    Festival festival = FestivalFixture.create();
    String festivalName = "수정된 축제 이름";
    LocalDate startDate = LocalDate.of(2025, 6, 1);
    LocalDate endDate = LocalDate.of(2025, 6, 3);
    boolean userVisible = false;

    // when
    festival.updateFestivalInformationBy(actor, festivalName, startDate, endDate, userVisible);

    // then
    assertSoftly(s -> {
        s.assertThat(festival.getFestivalName()).isEqualTo(festivalName);
        s.assertThat(festival.getStartDate()).isEqualTo(startDate);
        s.assertThat(festival.getEndDate()).isEqualTo(endDate);
        s.assertThat(festival.isUserVisible()).isEqualTo(userVisible);
    });
}

@Test
void 성공_ADMIN_접근_권한_있음() { ... }

@Test
void 성공_ORGANIZER_같은_조직_소속_접근_권한_있음() { ... }

@Test
void 성공_STAFF_같은_조직_소속_접근_권한_있음() { ... }

@Test
void 성공_STAFF_같은_축제_소속_접근_권한_있음() { ... }

@Test
void 예외_ORGANIZER_다른_조직_소속_접근_권한_없음() {
    // given
    Actor actor = ActorFixture.organizer(2L);

    // when & then
    assertThatThrownBy(() -> festival.validateWritableBy(actor))
            .isInstanceOf(ForbiddenException.class)
            .hasMessage("접근 권한이 없습니다.");
}

@Test
void 예외_Null_Actor_서버_예외_발생() { ... }

// ✅ 입력 검증은 권한 검증 블록 아래
@Test
void 예외_좌표_null() {
    // given
    Actor actor = ActorFixture.admin();

    Coordinate centerCoordinate = null;

    // when & then
    assertThatThrownBy(() -> festival.updateFestivalCenterCoordinateBy(actor, centerCoordinate))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("중심 좌표는 비어 있을 수 없습니다.");
}

// ❌ 예외 타입명을 메서드명에 노출
@Test
void 예외_ForbiddenException_발생() { ... }

// ❌ 권한 성공 테스트에서 값까지 중복 검증
@Test
void 성공_ORGANIZER_같은_축제_소속_접근_권한_있음() {
    // ...
    festival.updateFestivalInformationBy(actor, "수정된 이름", startDate, endDate, false);
    assertThat(festival.getFestivalName()).isEqualTo("수정된 이름");
}
```

### 12.24 테스트 클래스 선언부 개행 규칙

- `import` 블록과 클래스 어노테이션(예: `@DisplayNameGeneration`) 사이는 빈 줄 **한 줄**을 둔다.
- `@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)`와 클래스 선언 사이는 빈 줄을 두지 않는다.

```java
// ✅
import org.springframework.web.bind.annotation.RestController;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GlobalExceptionHandlerTest extends AcceptanceTestSupport {
}

// ❌ import 블록과 어노테이션 사이 빈 줄 누락
import org.springframework.web.bind.annotation.RestController;
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GlobalExceptionHandlerTest extends AcceptanceTestSupport {
}

// ❌ 어노테이션과 클래스 선언 사이 빈 줄 추가
import org.springframework.web.bind.annotation.RestController;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)

class GlobalExceptionHandlerTest extends AcceptanceTestSupport {
}
```

---

## 13. Test Fixture 규칙

### 13.1 구조

- 모든 기본값은 클래스 상단에 `private static final` 필드로 선언한다.
- `create()` 메서드를 오버로딩하여 다양한 파라미터 조합을 지원한다.
- 모든 파라미터를 받는 메서드는 `createCustom()`으로 명명한다.

### 13.2 Fixture 위치

- Entity Fixture: `src/test/java/.../도메인/domain/XXXFixture.java`
- DTO Fixture: `src/test/java/.../도메인/dto/XXXRequestFixture.java`

### 13.3 DTO Fixture

- Request DTO에 대해서도 Fixture 클래스를 만들어 테스트에서 사용한다.

### 13.4 Entity Fixture — `createList()` 메서드

- 다량의 Entity를 생성하는 테스트에서는 `createList(int size, ...)` 메서드를 제공한다.

### 13.5 Entity Fixture — `createWithXxx()` 메서드

- 기본적으로 `create()` 오버로딩으로 다양한 파라미터 조합을 지원한다.
- **단, 오버로딩 시 같은 타입의 파라미터로 메서드 시그니처가 충돌하는 경우에만** `createWithXxx()` 패턴으로 분리한다.

```java
// ✅ title과 content가 둘 다 String — 시그니처 충돌 → createWithXxx() 사용
public static Announcement create(String title) { ... }
public static Announcement createWithContent(String content) { ... }

// ✅ 타입이 달라 충돌 없음 → create() 오버로딩 사용
public static Announcement create(String title) { ... }
public static Announcement create(boolean isPinned) { ... }
```

### 13.6 Entity Fixture — `BaseEntityTestHelper`

- `id`나 `createdAt` 등 JPA가 관리하는 필드를 설정해야 하는 경우 `BaseEntityTestHelper`를 사용한다.

### 13.7 다른 Fixture 참조

- 다른 Fixture의 기본 인스턴스를 사용해야 할 경우, `private static final` 필드로 선언한다.

### 13.8 테스트에서 도메인/값객체 생성 방식

- 적용 범위: `**/*Test.java` (모든 테스트)
- 테스트 본문에서는 도메인(Entity, VO)을 `new`로 직접 생성하지 않는다.
- 도메인/값객체는 반드시 Fixture를 통해 생성한다.
- 값객체(`Coordinate` 등)도 동일하게 `CoordinateFixture.create(...)` 형태로 사용한다.

---

## 14. DDL 스키마 규칙 (Flyway)

### 14.1 파일 위치

- DDL 파일은 `/resources/db/migration` 디렉터리에 저장한다.

### 14.2 파일 네이밍

- `V{버전}__{DDL설명}.sql` 형태로 작성한다.
- 버전은 `VX.0`부터 시작한다. (예: `V1.0__create_announcement_table.sql`)
- 언더스코어는 반드시 **2개(`__`)**를 사용한다. (Flyway 규칙)

### 14.3 작성 규칙

- **하나의 파일에 하나의 DDL만** 작성한다.
- 모든 DDL에는 반드시 **COMMENT**를 추가한다.
- **이미 존재하는(적용된) DDL 파일은 절대 수정하거나 삭제하지 않는다.**

```sql
CREATE TABLE announcement (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '공지 ID',
    title       VARCHAR(50)  NOT NULL                COMMENT '공지 제목',
    content     VARCHAR(3000) NOT NULL               COMMENT '공지 내용',
    is_pinned   BOOLEAN      NOT NULL DEFAULT FALSE  COMMENT '고정 여부',
    festival_id BIGINT       NOT NULL                COMMENT '축제 ID',
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE  COMMENT '삭제 여부',
    deleted_at  DATETIME     NULL                    COMMENT '삭제 일시',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 일시',
    PRIMARY KEY (id)
) COMMENT '공지';
```

---

## 15. 설정 파일 규칙

- 설정 파일은 `application.yml`을 사용한다.
- `application.properties`는 사용하지 않는다.

---

## 16. 로깅 규칙

- 로깅은 **Aspect, Filter, ExceptionHandler에서만** 작성한다.
- Controller, Service, Domain 등 비즈니스 로직 레이어에서는 로그를 직접 작성하지 않는다.
- 따라서 `@Slf4j`는 Aspect, Filter, ExceptionHandler 클래스에만 붙인다.

```java
// ✅ ExceptionHandler — @Slf4j 사용
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler { }

// ❌ Service — @Slf4j 사용하지 않음
@Service
@RequiredArgsConstructor
public class AnnouncementService { }
```

---

## 부록 A: 규칙 위반 체크리스트 (AI 코드 리뷰용)

> AI 코드 리뷰 시 아래 항목을 순서대로 검증한다.

### 네이밍

- [ ] 변수명/메서드명에 줄임말이 사용되었는가?
- [ ] 사용하지 않는 import가 있는가?
- [ ] 사용하지 않는 변수가 남아 있는가?
- [ ] 사용하지 않는 메서드가 남아 있는가?
- [ ] `find` 메서드가 `Optional`을 반환하지 않는가?
- [ ] `get` 메서드가 `Optional`을 반환하는가?
- [ ] Repository 클래스명이 `XXXJpaRepository` 패턴을 따르는가?
- [ ] DTO 클래스명이 `XXXRequest` / `XXXUpdateRequest` / `XXXResponse` / `XXXUpdateResponse` / `XXXResponses` 패턴을 따르는가?
- [ ] 컬렉션 응답 DTO의 리스트 필드명이 `responses`인가?
- [ ] Controller 메서드명이 Service 메서드명과 동일한가?
- [ ] Controller에서 Request 파라미터 변수명이 `request`인가?
- [ ] Response DTO의 ID 필드가 `{도메인명}Id` 패턴인가? (❌ `id`, ✅ `announcementId`)

### 포매팅

- [ ] 어노테이션이 피라미드 구조(이름 길이 짧은 순)로 배치되어 있는가? (속성값 제외, 이름만 비교)
- [ ] Controller 클래스 어노테이션이 `@RestController` → `@RequestMapping` → `@RequiredArgsConstructor` → `@Tag` 순서인가?
- [ ] Entity 어노테이션이 `@Entity` → `@Getter` → `@SQLRestriction` → `@SQLDelete` → `@NoArgsConstructor` → `@EqualsAndHashCode` 순서인가?
- [ ] Controller 메서드에서 `@PreAuthorize` → HTTP 매핑 → `@ResponseStatus` → `@Operation` → `@ApiResponses` 순서를 따르는가?
- [ ] 메서드 체이닝이 2단계 이상인데 개행하지 않았는가?
- [ ] 여러 줄 메서드 호출의 닫는 괄호가 별도 줄에 있는가?
- [ ] 클래스/Record/인터페이스의 여는 중괄호 다음에 빈 줄이 있는가?
- [ ] 파라미터가 있는 Controller 메서드에서 파라미터 선언부가 개행되어 있는가?

### 타입 및 키워드

- [ ] 클래스 필드 외에 `final` 키워드가 사용되었는가?
- [ ] Entity의 `id` 필드가 원시 타입(`long`)으로 선언되었는가?
- [ ] 연관관계가 FK ID(`Long festivalId`)로만 매핑되어 있는가?

### 검증

- [ ] DTO에서 검증 로직 또는 `@Valid`, `@Validated`가 사용되어 있는가? (도메인/Service에서 해야 함)
- [ ] 도메인 생성자에서 검증이 누락되었는가?

### 구조

- [ ] DTO가 `class`가 아닌 `record`로 작성되었는가?
- [ ] `ResponseEntity`를 사용하고 있는가?
- [ ] `200 OK`에 `@ResponseStatus`가 누락되었는가?
- [ ] 예외가 `BusinessException`을 상속하지 않는가?
- [ ] Builder 패턴이 사용되었는가? (외부 의존 모듈 제외)
- [ ] JPA 기본 생성자가 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`로 작성되었는가?
- [ ] Entity에 매직넘버가 상수로 정의되지 않고 하드코딩되어 있는가?
- [ ] Soft Delete 어노테이션(`@SQLRestriction`, `@SQLDelete`)이 누락되었는가?
- [ ] Entity에 `@Getter`가 누락되었는가?
- [ ] Entity가 `BaseEntity`를 상속하지 않는가?
- [ ] 컬렉션 응답 DTO(`XXXResponses`)의 리스트 필드에 `@JsonValue`가 누락되었는가? (복합 응답 DTO 제외)
- [ ] 복합 응답 DTO의 필드에 `@JsonValue`가 잘못 붙어 있는가?
- [ ] Request DTO에 `toEntity()` 변환 메서드가 필요한데 누락되었는가?
- [ ] Response DTO에서 시간 필드에 `@JsonFormat`이 필요한데 누락되었는가?
- [ ] Controller 클래스에 `@Slf4j`가 붙어 있는가? (Aspect, Filter, ExceptionHandler 제외)
- [ ] Service 클래스에 `@Slf4j`가 붙어 있는가?

### Service

- [ ] 데이터 변경 메서드에 `@Transactional`이 누락되었는가?
- [ ] Service 메서드 파라미터에서 `Actor`가 첫 번째로 선언되어 있는가?
- [ ] Service가 도메인 행위 메서드(`createBy/updateBy/deleteBy`)를 통해 권한 검증을 위임하는가?
- [ ] Service에서 권한 검증 메서드를 분리 호출하는 패턴(`validateXxxBy` 후 별도 저장/삭제)을 사용하고 있지는 않은가?
- [ ] private 조회 헬퍼 메서드가 `getXxxById()` 패턴을 따르는가?
- [ ] `private` 메서드가 `public` 호출 순서 + `private` 내부 연쇄 호출 순서까지 반영되어 배치되어 있는가?
- [ ] `private get...By...Id` 메서드가 `private` 블록의 최하단에 배치되어 있는가?
- [ ] Service 매직넘버가 `private static final`로 정의되지 않고 하드코딩되어 있는가?

### Controller

- [ ] Controller `public` 메서드 순서가 Service `public` 메서드 순서(CRUD + 기타 메서드 순서)를 반영하는가?
- [ ] 다중 Service를 사용하는 Controller에서 보조 Service 조회 API가 Read 구간에 배치되어 있는가?
- [ ] 메서드 순서 변경 시 메서드 레벨 어노테이션(`@PreAuthorize`, HTTP 매핑, `@ResponseStatus`, `@Operation`, `@ApiResponses`)이 누락되지 않았는가?

### Swagger

- [ ] `@ResponseStatus`와 `@ApiResponse`의 상태 코드가 일치하는가?
- [ ] Request DTO 필드에 `@Schema`(description, example)가 누락되었는가?
- [ ] `@Operation summary`에 금지 단어(특정, ~에 대한, ~들, 모든)가 사용되었는가?
- [ ] `@Operation summary`에 권한 이모지 접두사가 누락되었는가?
- [ ] `@PreAuthorize`의 Role 순서가 권한이 적은 것부터 많은 것 순서인가?

### 테스트

- [ ] `@DisplayName`이 사용되었는가?
- [ ] `@DisplayNameGeneration`이 누락되었는가? (AcceptanceTestSupport 상속 제외)
- [ ] `AcceptanceTestSupport` 상속 테스트 클래스에 `@DisplayNameGeneration`이 선언되어 있는가?
- [ ] `import` 블록과 `@DisplayNameGeneration` 사이에 빈 줄 한 줄이 있는가?
- [ ] `@DisplayNameGeneration`과 클래스 선언 사이에 불필요한 빈 줄이 있는가?
- [ ] 테스트 메서드명이 한글이 아닌가?
- [ ] `@Nested` 클래스가 누락되었는가?
- [ ] `@Nested` 클래스명이 대상 메서드명과 일치하는가? (상속/default/Lombok 생성 메서드 기준 포함)
- [ ] `@Nested` 클래스 순서가 실제 대상 클래스 메서드 선언 순서(CRUD + 기타 메서드 순서)와 일치하는가?
- [ ] `// given`, `// when`, `// then` 주석이 누락되었는가?
- [ ] 예외 테스트에서 `// when & then` 형식을 사용하지 않았는가?
- [ ] 상태 코드 검증에 숫자 리터럴이 사용되었는가?
- [ ] 객체 생성과 저장이 분리되지 않았는가?
- [ ] RestAssured에서 `.given()` 전 개행이 누락되었는가?
- [ ] `Mockito.when()` 대신 `BDDMockito.given()`을 사용하고 있는가?
- [ ] 검증이 여러 개인데 `assertSoftly`를 사용하지 않았는가?
- [ ] 테스트가 검증 의도와 무관한 값까지 과도하게 노출하고 있지는 않은가?
- [ ] Service 테스트에서 `Actor`를 클래스 필드로 선언하고 있지는 않은가?
- [ ] Service 메서드 호출 인자에 `TestActors.admin()`/`TestActors.unauthorized()`를 인라인으로 직접 사용하고 있지는 않은가?
- [ ] `Actor` 지역변수 선언 직후에 공백 한 줄이 있는가?
- [ ] Service 테스트 `given` 순서(`Actor → 변수들 → given 모킹 → request`)를 지키지 않았는가? (request 선행 필요 예외 제외)
- [ ] `NotFoundException` 테스트 메서드명이 `예외_존재하지_않는_...()` 패턴을 따르는가?
- [ ] 권한 검증 실패 테스트 메서드명이 `예외_권한_없는_주체의_대상은_권한_검증_실패()` 패턴을 따르는가?
- [ ] `isInstanceOf(...)` 검증 뒤에 `hasMessage(...)` 검증이 누락되지 않았는가?
- [ ] 도메인 권한 테스트 메서드명이 `성공_{ROLE}_{조건}_접근_권한_있음` / `예외_{ROLE}_{조건}_접근_권한_없음` 패턴(12.23)을 따르는가?
- [ ] `createBy`/`updateXxxBy`/`deleteBy` `@Nested` 클래스의 첫 테스트가 `성공()`인가?
- [ ] `성공()` 테스트에서 입력값 변수 추출 후 핵심 상태값을 누락 없이 검증했는가? (복수 검증은 `assertSoftly`)
- [ ] 값 검증이 이미 `성공()`에 있다면 권한 성공 테스트에서 값 검증을 중복하지 않고 `doesNotThrowAnyException()`만 검증하는가?
- [ ] 도메인 권한 테스트 순서가 `ADMIN → ORGANIZER → STAFF → ORGANIZER(다른 소속 기준) → STAFF(다른 소속 기준) → PLACE_ACCESS → 권한 없는 Actor → Null Actor`를 따르는가?
- [ ] `예외_Null_Actor_서버_예외_발생` 테스트가 권한 테스트 블록의 마지막에 위치하는가?
- [ ] 입력 검증 테스트가 권한 검증 테스트 아래에 배치되어 있는가?
- [ ] 예외 타입명이 테스트 메서드명에 노출되지 않았는가?
- [ ] 도메인 객체(Entity, VO)를 Fixture 대신 `new`로 직접 생성하고 있지는 않은가?
- [ ] Controller 테스트에서 필드 사이즈 검증이 누락되었는가?
- [ ] when 절의 결과 변수명이 `result`가 아닌가?
- [ ] Controller 테스트가 `AcceptanceTestSupport`를 상속받지 않았는가?
- [ ] 권한 테스트에서 `@ParameterizedTest` + `@EnumSource`를 사용하지 않았는가?
- [ ] 예외 mock에 `willThrow().given()` 패턴을 사용하지 않았는가?
- [ ] 행위 검증에 `then().should()` 패턴을 사용하지 않았는가?

### Fixture

- [ ] 기본값이 클래스 상단에 `private static final` 필드로 선언되어 있는가?
- [ ] 모든 파라미터를 받는 메서드명이 `createCustom()`인가?
- [ ] 시그니처 충돌이 없는데 `createWithXxx()`를 사용하고 있지는 않은가?
- [ ] Entity Fixture가 `도메인 테스트 패키지/domain/` 패키지에 위치하는가?
- [ ] DTO Fixture가 `도메인 테스트 패키지/dto/` 패키지에 위치하는가?
- [ ] 다른 Fixture의 기본 인스턴스를 `private static final` 필드로 선언하지 않고 매번 새로 생성하고 있지는 않은가?
- [ ] 모든 테스트에서 도메인/값객체(Entity, VO)를 Fixture 대신 `new`로 직접 생성하고 있지는 않은가?
- [ ] `id`나 `createdAt` 등 JPA 관리 필드 설정이 필요한데 `BaseEntityTestHelper`를 사용하지 않았는가?

### DDL

- [ ] Flyway 파일명이 `V{버전}__{DDL설명}.sql` 형식인가?
- [ ] DDL에 COMMENT가 누락되었는가?
- [ ] 하나의 파일에 여러 DDL이 작성되어 있는가?
- [ ] 이미 존재하는(적용된) DDL 파일을 수정하거나 삭제하려 하는가?

---

## 부록 B: 문서 업데이트 누락 체크리스트 (AI 코드 리뷰용)

> 새로운 코드가 추가되거나 변경될 때, AI Agent는 아래 항목을 검토하여 문서 업데이트가 필요한지 확인한다.

### 새 코드 추가 시 문서 업데이트 필요 여부 확인

- [ ] 새로운 Entity가 추가되었는데 11장(Entity 규칙)에서 커버되지 않는 새 패턴이 사용되었는가?
- [ ] 새로운 DTO가 추가되었는데 10장(DTO 규칙) 또는 1.5(DTO 네이밍)에서 커버되지 않는 새 패턴이 사용되었는가?
- [ ] 새로운 Controller가 추가되었는데 8장(Controller 규칙) 또는 9장(Swagger 규칙)에서 커버되지 않는 새 패턴이 사용되었는가?
- [ ] 새로운 예외 타입이 추가되었는데 7장(예외 처리)에서 커버되지 않는가?
- [ ] 새로운 권한 검증 패턴이 사용되었는데 7.5.3에서 커버되지 않는가?
- [ ] 새로운 도메인 권한 위임/판단 패턴이 추가되었는데 7.5.5 또는 7.5.6에서 커버되지 않는가?
- [ ] 새로운 테스트 패턴이 추가되었는데 12장(테스트 코드 규칙)에서 커버되지 않는가?
- [ ] 새로운 Fixture 패턴이 추가되었는데 13장(Test Fixture 규칙)에서 커버되지 않는가?

### 기존 코드 변경 시 문서 업데이트 필요 여부 확인

- [ ] 기존 규칙을 어기는 패턴이 **2개 이상의 도메인**에서 반복적으로 발견되는가? (팀 확인 후 규칙 수정 필요)
- [ ] 부록 A 체크리스트 항목에서 커버되지 않는 새로운 위반 유형이 발견되었는가?
- [ ] 규칙이 추가/수정되었는데 부록 A 체크리스트에 반영되지 않았는가?
- [ ] 문서 상단의 **최종 수정일**이 업데이트되지 않았는가?
