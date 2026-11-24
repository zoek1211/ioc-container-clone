# IoC 컨테이너 클론

스프링의 핵심인 IoC 컨테이너(`ApplicationContext`)를 직접 흉내 내 보는 학습용 과제입니다.
단계별로 기능을 늘려 가며, 각 단계는 git 태그로 남겨 둡니다.

| 단계 | 내용 | 태그 |
| --- | --- | --- |
| v1 | 빈 생성 하드코딩 (`switch` 로 직접 `new`) | [`v1`](https://github.com/zoek1211/ioc-container-clone/tree/v1) |
| v2 | 컴포넌트 스캔으로 빈 자동 생성 | [`v2`](https://github.com/zoek1211/ioc-container-clone/tree/v2) |
| v3 | `@Configuration` 클래스의 `@Bean` 메서드로 빈 생성 (현재) | [`v3`](https://github.com/zoek1211/ioc-container-clone/tree/v3) |

## v1 — 하드코딩 빈 생성

> 코드 보기: [`v1` 태그](https://github.com/zoek1211/ioc-container-clone/tree/v1) · `git checkout v1`

컨테이너가 어떤 일을 하는지(빈 보관, 싱글톤, 의존성 주입)에만 집중하기 위해, 빈을 만드는 코드를 직접 하드코딩했습니다.
리플렉션이나 클래스 동적 로딩(`Class.forName` 등)은 쓰지 않습니다.

`src/main/java/com/ll/ioc/ApplicationContext.java` (v1 기준 경로)

```java
ApplicationContext context = new ApplicationContext();
TestPostService service = context.genBean("testPostService");
```

### 동작 방식

1. **`genBean(String beanName)`**: 이름으로 빈을 꺼냅니다. 처음 요청이면 만들어서 저장하고, 그 뒤로는 저장해 둔 객체를 돌려줍니다.
2. **빈 생성**: `switch (beanName)` 로 이름마다 어떤 클래스를 `new` 할지 직접 적어 두었습니다.
3. **의존성 주입**: 빈을 만들 때 필요한 의존 빈도 `genBean` 으로 얻어 생성자에 넘깁니다. 그래서 컨테이너 안의 같은 빈이 주입됩니다.
4. **싱글톤 관리**: 만든 빈을 `Map<String, Object>` 에 보관하므로, 같은 이름으로 여러 번 요청해도 항상 같은 객체가 나옵니다.

```java
private Object createBean(String beanName) {
    return switch (beanName) {
        case "testPostRepository" -> new TestPostRepository();
        case "testPostService" -> new TestPostService(genBean("testPostRepository"));
        case "testFacadePostService" -> new TestFacadePostService(
                genBean("testPostService"),
                genBean("testPostRepository")
        );
        default -> throw new IllegalArgumentException("등록되지 않은 빈입니다: " + beanName);
    };
}
```

**한계**: 새 클래스를 빈으로 쓰려면 매번 `switch` 에 한 줄씩 추가해야 합니다. 이 불편을 v2 에서 컴포넌트 스캔으로 해결합니다.

## v2 — 컴포넌트 스캔

> 코드 보기: [`v2` 태그](https://github.com/zoek1211/ioc-container-clone/tree/v2) · `git checkout v2`

v1 에서 하드코딩하던 빈 생성을, 패키지를 스캔해서 자동으로 하도록 바꿨습니다.

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

**한계**: `ObjectMapper` 처럼 외부 라이브러리의 클래스는 소스를 고칠 수 없어서 `@Component` 를 붙일 수 없습니다. 이 문제를 v3 에서 `@Bean` 으로 해결합니다.

## v3 — @Configuration·@Bean

> 코드 보기: [`v3` 태그](https://github.com/zoek1211/ioc-container-clone/tree/v3) · `main` 브랜치

v2 에 "설정 클래스의 메서드로 빈을 만드는" 방법을 더했습니다. 스프링에서 Jackson 의 `ObjectMapper` 같은 외부 라이브러리 객체를 빈으로 등록하는 방식입니다.
코드에 한국어 주석을 자세히 달아 두었으니 `ApplicationContext.java` 를 위에서부터 읽어 보세요.

```java
@Configuration
public class TestJacksonConfig {
    @Bean // 빈 이름 = 메서드 이름 → "testBaseJavaTimeModule"
    public JavaTimeModule testBaseJavaTimeModule() {
        return new JavaTimeModule();
    }

    @Bean // 파라미터에 testBaseJavaTimeModule 빈이 주입된다
    public ObjectMapper testBaseObjectMapper(JavaTimeModule testBaseJavaTimeModule) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(testBaseJavaTimeModule);
        return objectMapper;
    }
}
```

```java
ObjectMapper objectMapper = context.genBean("testBaseObjectMapper"); // 컴포넌트 빈과 똑같이 꺼낸다
```

### 동작 방식

1. **스캔**: v2 처럼 `@Component` 계열 클래스를 찾습니다. `@Configuration` 클래스도 `@Component` 라서 그 자체가 빈으로 등록됩니다. (`testJacksonConfig`)
2. **`@Bean` 메서드 수집**: `@Configuration` 클래스라면 `getDeclaredMethods()` 로 메서드를 훑어 `@Bean` 이 붙은 것을 빈 정의로 등록합니다. 빈 이름은 **메서드 이름**, 빈 타입은 **메서드 반환 타입**입니다.
3. **빈 정의 통일**: 컴포넌트 빈과 `@Bean` 빈을 `BeanDefinition` 레코드 하나로 표현합니다. 그래서 `genBean` 과 싱글톤 저장소는 두 종류를 구분하지 않고 똑같이 처리합니다.
4. **`@Bean` 빈 생성**: 먼저 설정 클래스 빈을 꺼내고(없으면 만들고), 그 객체에서 `method.invoke(...)` 로 `@Bean` 메서드를 호출해 반환값을 빈으로 저장합니다.
5. **파라미터 주입**: 생성자 파라미터와 `@Bean` 메서드 파라미터를 같은 코드(`Executable`)로 채웁니다.
   - 파라미터 **이름**과 같은 빈이 있으면 그것을 씁니다. (컴파일 옵션 `-parameters` 가 있을 때만 이름이 남습니다)
   - 없으면 **타입**이 맞는 빈을 찾습니다. 딱 하나면 그것을, 여러 개면 모호하다는 예외를 던집니다.

### 버전별 비교

| | v1 | v2 | v3 |
| --- | --- | --- | --- |
| 생성 | `new ApplicationContext()` | `new ApplicationContext("com.ll")` → `init()` | v2 와 같음 |
| 빈 찾기 | `switch` 에 직접 나열 | reflections 로 `@Component` 스캔 | 스캔 + `@Configuration` 안의 `@Bean` 메서드 |
| 객체 생성 | `new` 직접 호출 | 생성자 리플렉션 (`newInstance`) | 생성자 리플렉션 또는 `@Bean` 메서드 호출 (`invoke`) |
| 빈 이름 | `switch` 의 문자열 | 클래스 이름 (첫 글자 소문자) | 컴포넌트: 클래스 이름 / `@Bean`: 메서드 이름 |
| 의존 빈 파악 | 코드에 직접 적음 | 생성자 파라미터 타입 → 이름 변환 | 생성자·메서드 파라미터의 이름 또는 타입 |
| 생성 시점 | `genBean` 첫 호출 때 (지연 생성) | `init()` 에서 전부 미리 생성 | v2 와 같음 |
| 외부 라이브러리 객체 | `switch` 에 추가 | 등록 불가 | `@Bean` 메서드로 등록 |

## 빈 목록 (테스트 기준)

| 빈 이름 | 만드는 곳 | 등록 방법 | 주입받는 빈 | 버전 |
| --- | --- | --- | --- | --- |
| `testPostRepository` | `TestPostRepository` | `@Repository` | 없음 | v1~ |
| `testPostService` | `TestPostService` | `@Service` | `testPostRepository` | v1~ |
| `testFacadePostService` | `TestFacadePostService` | `@Service` | `testPostService`, `testPostRepository` | v1~ |
| `testJacksonConfig` | `TestJacksonConfig` | `@Configuration` | 없음 | v3 |
| `testBaseJavaTimeModule` | `TestJacksonConfig#testBaseJavaTimeModule()` | `@Bean` | 없음 | v3 |
| `testBaseObjectMapper` | `TestJacksonConfig#testBaseObjectMapper()` | `@Bean` | `testBaseJavaTimeModule` | v3 |

v1 은 애너테이션 없이 `switch` 로 등록합니다.

## 규칙

- `build.gradle.kts` 에 있는 라이브러리만 사용합니다. spring-context 같은 DI 프레임워크는 쓰지 않습니다.
- `src/test` 의 제공 테스트는 수정하지 않습니다.
- v1: 빈 생성은 하드코딩해도 되지만, 클래스 동적 로딩은 하지 않습니다.
- v2·v3: 빈 생성에 하드코딩 금지. reflections 로 스캔해 빈 생성 정보를 모읍니다.

## 실행 방법

v3 는 JDK 21 이 필요합니다. (v1·v2 는 JDK 25) 시작 프로젝트의 Gradle 버전이 단계마다 다르기 때문입니다.

```bash
./gradlew test
```

## 테스트

`src/test/java/com/ll/framework/ioc/ApplicationContextTest.java`

| 테스트 | 내용 | 버전 |
| --- | --- | --- |
| t1 | 객체 생성 (v1: `new ApplicationContext()`, v2~: `new ApplicationContext("com.ll")` → `init()`) | v1~ |
| t2 | `genBean("testPostService")` 로 `TestPostService` 빈 얻기 | v1~ |
| t3 | 같은 이름으로 두 번 얻으면 같은 객체(싱글톤) | v1~ |
| t4 | `genBean("testPostRepository")` 로 `TestPostRepository` 빈 얻기 | v1~ |
| t5 | `testPostService` 의 `testPostRepository` 필드에 그 빈이 주입되어 있음 | v1~ |
| t6 | `testFacadePostService` 에 `testPostService`·`testPostRepository` 빈이 주입되어 있음 | v1~ |
| t7 | 의존 관계가 없는 `@Bean` 빈 `testBaseJavaTimeModule` 생성 | v3 |
| t8 | `testBaseJavaTimeModule` 빈에 의존하는 `@Bean` 빈 `testBaseObjectMapper` 생성 | v3 |

v1·v2 는 t1~t6, v3 는 t1~t8 을 모두 통과합니다.
