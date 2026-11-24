# IoC 컨테이너 클론

스프링의 핵심인 IoC 컨테이너(`ApplicationContext`)를 직접 흉내 내 보는 학습용 과제입니다.
단계별로 기능을 늘려 가며, 각 단계는 git 태그로 남겨 둡니다.

| 단계 | 내용 | 태그 |
| --- | --- | --- |
| v1 | 빈 생성 하드코딩 (`switch` 로 직접 `new`) | `v1` |
| v2 | 컴포넌트 스캔으로 빈 자동 생성 (현재) | `v2` |

## v2 — 컴포넌트 스캔

v1 에서 하드코딩하던 빈 생성을, 패키지를 스캔해서 자동으로 하도록 바꿨습니다.
코드에 한국어 주석을 자세히 달아 두었으니 `ApplicationContext.java` 를 위에서부터 읽어 보세요.

`src/main/java/com/ll/framework/ioc/ApplicationContext.java`

```java
ApplicationContext context = new ApplicationContext("com.ll"); // 1. 기준 패키지 저장
context.init();                                                // 2. 스캔 + 빈 생성
TestPostService service = context.genBean("testPostService");  // 3. 빈 꺼내기
```

### 동작 방식

1. **컴포넌트 스캔**: `init()` 이 `org.reflections` 로 기준 패키지를 훑어서 `@Component` 가 붙은 클래스를 찾습니다.
   `@Service`, `@Repository`, `@Configuration` 에는 `@Component` 가 붙어 있어서(메타 애너테이션) 함께 찾아집니다.
2. **빈 정의 수집**: 찾은 클래스마다 "빈 이름 → 클래스" 를 `Map` 에 모읍니다.
   빈 이름은 클래스 이름의 첫 글자만 소문자로 바꾼 것입니다. (`TestPostService` → `testPostService`)
3. **생성자 주입**: 빈을 만들 때 생성자 파라미터 타입을 보고 필요한 빈을 먼저 만든 뒤(재귀), 그 빈들을 넣어 `newInstance` 로 객체를 만듭니다.
   생성자는 Lombok `@RequiredArgsConstructor` 가 `final` 필드로 만들어 줍니다.
4. **싱글톤 관리**: 만든 빈은 `Map` 에 저장해 두고, 같은 이름으로 다시 요청하면 저장해 둔 객체를 돌려줍니다.

그 밖에:

- 서로를 필요로 하는 빈(순환 의존)은 무한 재귀 대신 `IllegalStateException` 으로 알려 줍니다.
- 같은 빈 이름이 두 클래스에서 나오면 덮어쓰지 않고 예외를 던집니다.
- 등록되지 않은 이름으로 `genBean` 하면 `IllegalArgumentException` 을 던집니다.

### 빈 목록 (테스트 기준)

| 빈 이름 | 클래스 | 애너테이션 | 주입받는 빈 |
| --- | --- | --- | --- |
| `testPostRepository` | `TestPostRepository` | `@Repository` | 없음 |
| `testPostService` | `TestPostService` | `@Service` | `testPostRepository` |
| `testFacadePostService` | `TestFacadePostService` | `@Service` | `testPostService`, `testPostRepository` |

## 규칙

- `build.gradle.kts` 에 있는 라이브러리(Lombok, reflections, logback, JUnit, AssertJ)만 사용합니다. spring-context 같은 DI 프레임워크는 쓰지 않습니다.
- `src/test` 의 제공 테스트는 수정하지 않습니다.
- v2: 빈 생성에 하드코딩 금지. reflections 로 컴포넌트를 스캔해 빈 생성 정보를 모읍니다.

## 실행 방법

JDK 25 가 필요합니다. 더 높은 버전의 JDK 에서는 Lombok 컴파일이 실패할 수 있습니다.

```bash
./gradlew test
```

## 테스트

`src/test/java/com/ll/framework/ioc/ApplicationContextTest.java`

| 테스트 | 내용 |
| --- | --- |
| t1 | `new ApplicationContext("com.ll")` → `init()` 으로 객체 생성 |
| t2 | `genBean("testPostService")` 로 `TestPostService` 빈 얻기 |
| t3 | 같은 이름으로 두 번 얻으면 같은 객체(싱글톤) |
| t4 | `genBean("testPostRepository")` 로 `TestPostRepository` 빈 얻기 |
| t5 | `testPostService` 의 `testPostRepository` 필드에 그 빈이 주입되어 있음 |
| t6 | `testFacadePostService` 에 `testPostService`·`testPostRepository` 빈이 주입되어 있음 |

6개 테스트 모두 통과합니다.
