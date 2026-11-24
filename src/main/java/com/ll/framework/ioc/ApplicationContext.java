package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.Component;
import org.reflections.Reflections;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 아주 단순한 IoC 컨테이너 (스프링 ApplicationContext 흉내, v2: 컴포넌트 스캔)
 *
 * <p>동작 순서</p>
 * <ol>
 *     <li>{@code new ApplicationContext("com.ll")} : 스캔할 기준 패키지를 기억만 해 둔다.</li>
 *     <li>{@code init()} : reflections 로 기준 패키지를 스캔해서 @Component 가 붙은 클래스를 찾고,
 *         "빈 이름 → 클래스" 정보(빈 정의)를 모은 뒤 모든 빈을 미리 만들어 둔다.</li>
 *     <li>{@code genBean("빈이름")} : 만들어 둔 빈(싱글톤)을 돌려준다.
 *         아직 없으면 그때 만들어서 저장한 뒤 돌려준다.</li>
 * </ol>
 *
 * <p>v1 과 달리 어떤 클래스도 하드코딩해서 new 하지 않는다.
 * 클래스 정보는 전부 스캔으로 얻고, 객체 생성은 리플렉션(Constructor.newInstance)으로 한다.</p>
 */
public class ApplicationContext {
    // 스캔할 기준 패키지 (예: "com.ll" → com.ll 아래의 모든 하위 패키지까지 스캔)
    private final String basePackage;

    // 빈 정의: 빈 이름 → 그 빈을 만들 때 쓸 클래스
    // 스프링의 BeanDefinition 을 아주 단순하게 줄인 것이라고 보면 된다.
    private final Map<String, Class<?>> beanDefinitions = new HashMap<>();

    // 싱글톤 저장소: 빈 이름 → 이미 만들어진 객체
    // 같은 이름으로 몇 번을 요청해도 여기 있는 같은 객체를 돌려준다.
    private final Map<String, Object> singletonBeans = new HashMap<>();

    // 지금 생성 중인 빈 이름들 (A → B → A 처럼 서로를 필요로 하는 순환 의존을 잡아내기 위함)
    private final Set<String> beansInCreation = new HashSet<>();

    public ApplicationContext(String basePackage) {
        // 생성자에서는 무거운 작업을 하지 않고, 기준 패키지만 저장한다.
        // 실제 스캔과 빈 생성은 init() 에서 한다. (스프링의 refresh() 와 비슷한 역할)
        this.basePackage = basePackage;
    }

    public void init() {
        // 1단계: 컴포넌트 스캔 → 빈 정의 수집
        scanComponents();

        // 2단계: 모든 빈을 미리 만들어 둔다. (스프링도 싱글톤 빈은 기본적으로 시작할 때 다 만든다)
        // genBean 이 의존하는 빈을 재귀로 먼저 만들어 주므로, 여기서는 순서를 신경 쓰지 않아도 된다.
        for (String beanName : beanDefinitions.keySet()) {
            genBean(beanName);
        }
    }

    /**
     * reflections 라이브러리로 기준 패키지를 훑어서 @Component 계열 클래스를 찾는다.
     */
    private void scanComponents() {
        Reflections reflections = new Reflections(basePackage);

        // getTypesAnnotatedWith(Component.class) 는 @Component 가 직접 붙은 타입뿐 아니라,
        // @Service, @Repository, @Configuration 처럼 "@Component 가 붙은 애너테이션" 이 붙은 타입도 찾아 준다.
        // (메타 애너테이션: 애너테이션 위에 붙은 애너테이션)
        Set<Class<?>> componentTypes = reflections.getTypesAnnotatedWith(Component.class);

        for (Class<?> type : componentTypes) {
            // 결과에는 @Service 같은 "애너테이션 타입 자신" 도 섞여 있다.
            // 애너테이션·인터페이스·추상 클래스는 객체로 만들 수 없으니 빈 대상에서 뺀다.
            if (!isInstantiable(type)) continue;

            String beanName = toBeanName(type);

            // 서로 다른 패키지에 같은 이름의 클래스가 있으면 빈 이름이 겹친다. 조용히 덮어쓰지 않고 바로 알려 준다.
            Class<?> existing = beanDefinitions.putIfAbsent(beanName, type);
            if (existing != null && existing != type) {
                throw new IllegalStateException(
                        "빈 이름이 중복됩니다: %s (%s, %s)".formatted(beanName, existing.getName(), type.getName())
                );
            }
        }
    }

    private boolean isInstantiable(Class<?> type) {
        return !type.isAnnotation()
                && !type.isInterface()
                && !Modifier.isAbstract(type.getModifiers());
    }

    /**
     * 빈 이름 규칙: 클래스 이름의 첫 글자만 소문자로 바꾼다.
     * 예) TestPostService → testPostService
     */
    private String toBeanName(Class<?> type) {
        String simpleName = type.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    @SuppressWarnings("unchecked")
    public <T> T genBean(String beanName) {
        // 이미 만들어 둔 빈이 있으면 그대로 돌려준다. → 싱글톤 보장
        Object bean = singletonBeans.get(beanName);
        if (bean != null) return (T) bean;

        // 스캔 결과에 없는 이름이면 만들 방법이 없다.
        Class<?> beanClass = beanDefinitions.get(beanName);
        if (beanClass == null) {
            throw new IllegalArgumentException("등록되지 않은 빈입니다: " + beanName);
        }

        // 아직 만드는 중인 빈을 또 요청했다면 순환 의존이다. (무한 재귀로 StackOverflow 가 나기 전에 막는다)
        if (!beansInCreation.add(beanName)) {
            throw new IllegalStateException("순환 의존이 발견되었습니다: " + beanName);
        }

        try {
            bean = createBean(beanClass);
        } finally {
            beansInCreation.remove(beanName);
        }

        singletonBeans.put(beanName, bean);
        return (T) bean;
    }

    /**
     * 생성자 주입 방식으로 빈을 만든다.
     *
     * <p>@RequiredArgsConstructor 가 final 필드를 받는 생성자를 만들어 주므로,
     * 생성자의 파라미터 타입을 보면 이 빈이 어떤 빈들을 필요로 하는지 알 수 있다.</p>
     */
    private Object createBean(Class<?> beanClass) {
        Constructor<?> constructor = selectConstructor(beanClass);

        // 생성자 파라미터 하나하나를 의존 빈으로 보고, 그 빈을 먼저 만든다(또는 이미 있으면 꺼낸다).
        // 파라미터 "이름" 은 컴파일 옵션(-parameters) 없이는 arg0 처럼 나오므로,
        // 파라미터 "타입" 으로 빈 이름을 만든다. (TestPostRepository 타입 → "testPostRepository" 빈)
        Object[] dependencies = Arrays.stream(constructor.getParameterTypes())
                .map(paramType -> genBean(toBeanName(paramType)))
                .toArray();

        try {
            // private 생성자일 수도 있으니 접근을 허용한 뒤 호출한다.
            constructor.setAccessible(true);
            return constructor.newInstance(dependencies);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("빈 생성에 실패했습니다: " + beanClass.getName(), e);
        }
    }

    /**
     * 어떤 생성자로 객체를 만들지 고른다.
     * 생성자가 하나면 그것을, 여러 개면 파라미터가 가장 많은 것을 쓴다.
     * (의존성을 가장 많이 받는 생성자 = 필요한 것을 모두 주입할 수 있는 생성자)
     */
    private Constructor<?> selectConstructor(Class<?> beanClass) {
        return Arrays.stream(beanClass.getDeclaredConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow(() -> new IllegalStateException("생성자가 없습니다: " + beanClass.getName()));
    }
}
