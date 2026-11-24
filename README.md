# IoC 컨테이너 클론

스프링의 핵심인 IoC 컨테이너(`ApplicationContext`)를 직접 흉내 내 보는 학습용 과제입니다.
단계별로 기능을 늘려 가며, 각 단계는 git 태그로 남겨 둡니다.

| 단계 | 내용 | 태그 |
| --- | --- | --- |
| v1 | 빈 생성 하드코딩 (`switch` 로 직접 `new`) | `v1` |
| v2 | 컴포넌트 스캔으로 빈 자동 생성 (현재) | `v2` |

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

> 코드 보기: [`v2` 태그](https://github.com/zoek1211/ioc-container-clone/tree/v2) · `main` 브랜치

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

### v1 과 비교

| | v1 | v2 |
| --- | --- | --- |
| 생성 | `new ApplicationContext()` | `new ApplicationContext("com.ll")` → `init()` |
| 빈 찾기 | `switch` 에 직접 나열 | reflections 로 `@Component` 스캔 |
| 객체 생성 | `new` 직접 호출 | 리플렉션 (`Constructor.newInstance`) |
| 의존 빈 파악 | 코드에 직접 적음 | 생성자 파라미터 타입으로 알아냄 |
| 생성 시점 | `genBean` 첫 호출 때 (지연 생성) | `init()` 에서 전부 미리 생성 |
| 새 빈 추가 | `switch` 에 한 줄 추가 | 클래스에 `@Service` 등만 붙이면 끝 |

## 빈 목록 (테스트 기준)

v1 과 v2 모두 같은 세 빈을 다룹니다. (애너테이션은 v2 에서 추가)

| 빈 이름 | 클래스 | 애너테이션 | 주입받는 빈 |
| --- | --- | --- | --- |
| `testPostRepository` | `TestPostRepository` | `@Repository` | 없음 |
| `testPostService` | `TestPostService` | `@Service` | `testPostRepository` |
| `testFacadePostService` | `TestFacadePostService` | `@Service` | `testPostService`, `testPostRepository` |

## 규칙

- `build.gradle.kts` 에 있는 라이브러리(Lombok, reflections, logback, JUnit, AssertJ)만 사용합니다. spring-context 같은 DI 프레임워크는 쓰지 않습니다.
- `src/test` 의 제공 테스트는 수정하지 않습니다.
- v1: 빈 생성은 하드코딩해도 되지만, 클래스 동적 로딩은 하지 않습니다.
- v2: 빈 생성에 하드코딩 금지. reflections 로 컴포넌트를 스캔해 빈 생성 정보를 모읍니다.

## 실행 방법

JDK 25 가 필요합니다. 더 높은 버전의 JDK 에서는 Lombok 컴파일이 실패할 수 있습니다.

```bash
./gradlew test
```

## 테스트

`src/test/java/com/ll/framework/ioc/ApplicationContextTest.java`

v1 과 v2 는 같은 t1~t6 을 씁니다. 다른 점은 컨테이너를 만드는 방법뿐입니다.

| 테스트 | 내용 |
| --- | --- |
| t1 | 객체 생성 (v1: `new ApplicationContext()`, v2: `new ApplicationContext("com.ll")` → `init()`) |
| t2 | `genBean("testPostService")` 로 `TestPostService` 빈 얻기 |
| t3 | 같은 이름으로 두 번 얻으면 같은 객체(싱글톤) |
| t4 | `genBean("testPostRepository")` 로 `TestPostRepository` 빈 얻기 |
| t5 | `testPostService` 의 `testPostRepository` 필드에 그 빈이 주입되어 있음 |
| t6 | `testFacadePostService` 에 `testPostService`·`testPostRepository` 빈이 주입되어 있음 |

v1, v2 모두 6개 테스트를 통과합니다.
