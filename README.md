# IoC 컨테이너 클론 v1 — 하드코딩 빈 생성

스프링의 핵심인 IoC 컨테이너(`ApplicationContext`)를 직접 흉내 내 보는 과제입니다.
v1에서는 리플렉션이나 클래스 동적 로딩 없이, 빈 생성 로직을 하드코딩합니다.

## 구현 내용

`src/main/java/com/ll/ioc/ApplicationContext.java`

- **`genBean(String beanName)`**: 이름으로 빈을 꺼냅니다. 처음 요청이면 만들어서 저장하고, 그 뒤로는 저장해 둔 객체를 돌려줍니다.
- **싱글톤 관리**: 만든 빈을 `Map<String, Object>`에 보관하므로, 같은 이름으로 여러 번 요청해도 항상 같은 객체가 나옵니다.
- **의존성 주입**: 빈을 만들 때 필요한 의존 빈도 `genBean`으로 얻어 생성자에 넘깁니다. 그래서 컨테이너 안의 같은 빈이 주입됩니다.

| 빈 이름 | 클래스 | 주입받는 빈 |
| --- | --- | --- |
| `testPostRepository` | `TestPostRepository` | 없음 |
| `testPostService` | `TestPostService` | `testPostRepository` |
| `testFacadePostService` | `TestFacadePostService` | `testPostService`, `testPostRepository` |

등록되지 않은 이름으로 요청하면 `IllegalArgumentException`을 던집니다.

## 규칙

- `build.gradle.kts`에 있는 라이브러리(Lombok, JUnit, AssertJ)만 사용합니다. spring-context 같은 DI 프레임워크는 쓰지 않습니다.
- `src/test`의 제공 테스트는 수정하지 않습니다.
- `Class.forName` 같은 클래스 동적 로딩은 쓰지 않습니다.

## 실행 방법

JDK 25가 필요합니다. 더 높은 버전의 JDK에서는 Lombok 컴파일이 실패할 수 있습니다.

```bash
./gradlew test
```

## 테스트

`src/test/java/com/ll/framework/ioc/ApplicationContextTest.java`

| 테스트 | 내용 |
| --- | --- |
| t1 | `new ApplicationContext()`로 객체 생성 |
| t2 | `genBean("testPostService")`로 `TestPostService` 빈 얻기 |
| t3 | 같은 이름으로 두 번 얻으면 같은 객체(싱글톤) |
| t4 | `genBean("testPostRepository")`로 `TestPostRepository` 빈 얻기 |
| t5 | `testPostService`의 `testPostRepository` 필드에 그 빈이 주입되어 있음 |
| t6 | `testFacadePostService`에 `testPostService`·`testPostRepository` 빈이 주입되어 있음 |

6개 테스트 모두 통과합니다.
