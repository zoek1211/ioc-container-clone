package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.Bean;
import com.ll.framework.ioc.annotations.Component;
import com.ll.framework.ioc.annotations.Configuration;
import org.reflections.Reflections;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 아주 단순한 IoC 컨테이너 (스프링 ApplicationContext 흉내, v3: @Configuration·@Bean)
 *
 * <p>빈을 등록하는 방법은 두 가지다.</p>
 * <ol>
 *     <li><b>컴포넌트 스캔 (v2)</b> : 클래스에 @Component(@Service, @Repository …)를 붙이면
 *         그 클래스의 생성자로 객체를 만든다. 빈 이름 = 클래스 이름(첫 글자 소문자).</li>
 *     <li><b>@Bean 메서드 (v3 에서 추가)</b> : @Configuration 클래스 안에 @Bean 메서드를 만들면
 *         그 메서드를 호출한 반환값을 빈으로 쓴다. 빈 이름 = 메서드 이름.
 *         ObjectMapper 처럼 남이 만든 클래스(외부 라이브러리)는 우리가 @Component 를 붙일 수 없으니,
 *         이 방법으로 "이렇게 만들어서 써라" 라고 알려 준다.</li>
 * </ol>
 *
 * <p>두 방법 모두 "빈 정의(BeanDefinition)" 하나로 통일해서 모아 두고,
 * 꺼낼 때는 똑같이 {@code genBean("빈이름")} 을 쓴다. 모든 빈은 싱글톤이다.</p>
 */
public class ApplicationContext {
    // 스캔할 기준 패키지 (예: "com.ll" → com.ll 아래의 모든 하위 패키지까지 스캔)
    private final String basePackage;

    // 빈 정의: 빈 이름 → 그 빈을 "어떻게 만드는지" 에 대한 정보
    // LinkedHashMap 을 써서 스캔된 순서를 유지한다. (디버깅할 때 순서가 일정하면 보기 편하다)
    private final Map<String, BeanDefinition> beanDefinitions = new LinkedHashMap<>();

    // 싱글톤 저장소: 빈 이름 → 이미 만들어진 객체
    // 같은 이름으로 몇 번을 요청해도 여기 있는 같은 객체를 돌려준다.
    private final Map<String, Object> singletonBeans = new LinkedHashMap<>();

    // 지금 생성 중인 빈 이름들 (A → B → A 처럼 서로를 필요로 하는 순환 의존을 잡아내기 위함)
    private final Set<String> beansInCreation = new HashSet<>();

    public ApplicationContext(String basePackage) {
        // 생성자에서는 기준 패키지만 저장한다. 실제 스캔과 빈 생성은 init() 에서 한다.
        // (스프링의 refresh() 와 비슷한 역할)
        this.basePackage = basePackage;
    }

    public void init() {
        // 1단계: 스캔해서 빈 정의 모으기 (컴포넌트 클래스 + @Bean 메서드)
        scanBeanDefinitions();

        // 2단계: 모든 빈을 미리 만들어 둔다. (스프링도 싱글톤 빈은 기본적으로 시작할 때 다 만든다)
        // genBean 이 의존하는 빈을 재귀로 먼저 만들어 주므로, 여기서는 순서를 신경 쓰지 않아도 된다.
        // keySet 을 그대로 돌면서 genBean 을 부르면 안전하지 않을 수 있어 복사본(List.copyOf)을 돈다.
        for (String beanName : List.copyOf(beanDefinitions.keySet())) {
            genBean(beanName);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 1단계: 스캔
    // ─────────────────────────────────────────────────────────────

    private void scanBeanDefinitions() {
        Reflections reflections = new Reflections(basePackage);

        // getTypesAnnotatedWith(Component.class) 는 @Component 가 직접 붙은 타입뿐 아니라,
        // @Service, @Repository, @Configuration 처럼 "@Component 가 붙은 애너테이션" 이 붙은 타입도 찾아 준다.
        // (메타 애너테이션: 애너테이션 위에 붙은 애너테이션)
        Set<Class<?>> componentTypes = reflections.getTypesAnnotatedWith(Component.class);

        for (Class<?> type : componentTypes) {
            // 결과에는 @Service 같은 "애너테이션 타입 자신" 도 섞여 있다.
            // 애너테이션·인터페이스·추상 클래스는 객체로 만들 수 없으니 빈 대상에서 뺀다.
            if (!isInstantiable(type)) continue;

            // (1) 클래스 자체를 빈으로 등록한다.
            //     @Configuration 클래스도 @Component 이므로 여기서 함께 등록된다.
            //     → @Bean 메서드를 호출하려면 그 설정 클래스의 객체가 있어야 하기 때문에 꼭 필요하다.
            String componentBeanName = toBeanName(type);
            registerBeanDefinition(BeanDefinition.ofComponent(componentBeanName, type));

            // (2) @Configuration 클래스라면, 안에 있는 @Bean 메서드도 하나하나 빈으로 등록한다.
            if (type.isAnnotationPresent(Configuration.class)) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!method.isAnnotationPresent(Bean.class)) continue;

                    // 빈 이름 = 메서드 이름 (예: testBaseObjectMapper() → "testBaseObjectMapper")
                    registerBeanDefinition(
                            BeanDefinition.ofBeanMethod(method.getName(), componentBeanName, method)
                    );
                }
            }
        }
    }

    private void registerBeanDefinition(BeanDefinition definition) {
        // 같은 빈 이름이 두 번 나오면 조용히 덮어쓰지 않고 바로 알려 준다.
        BeanDefinition existing = beanDefinitions.putIfAbsent(definition.name(), definition);
        if (existing != null) {
            throw new IllegalStateException(
                    "빈 이름이 중복됩니다: %s (%s, %s)".formatted(definition.name(), existing.source(), definition.source())
            );
        }
    }

    private boolean isInstantiable(Class<?> type) {
        return !type.isAnnotation()
                && !type.isInterface()
                && !Modifier.isAbstract(type.getModifiers());
    }

    /**
     * 컴포넌트 빈 이름 규칙: 클래스 이름의 첫 글자만 소문자로 바꾼다.
     * 예) TestPostService → testPostService
     */
    private String toBeanName(Class<?> type) {
        String simpleName = type.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    // ─────────────────────────────────────────────────────────────
    // 2단계: 빈 꺼내기 / 만들기
    // ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public <T> T genBean(String beanName) {
        // 이미 만들어 둔 빈이 있으면 그대로 돌려준다. → 싱글톤 보장
        Object bean = singletonBeans.get(beanName);
        if (bean != null) return (T) bean;

        // 스캔 결과에 없는 이름이면 만들 방법이 없다.
        BeanDefinition definition = beanDefinitions.get(beanName);
        if (definition == null) {
            throw new IllegalArgumentException("등록되지 않은 빈입니다: " + beanName);
        }

        // 아직 만드는 중인 빈을 또 요청했다면 순환 의존이다. (무한 재귀로 StackOverflow 가 나기 전에 막는다)
        if (!beansInCreation.add(beanName)) {
            throw new IllegalStateException("순환 의존이 발견되었습니다: " + beanName);
        }

        try {
            bean = createBean(definition);
        } finally {
            beansInCreation.remove(beanName);
        }

        if (bean == null) {
            throw new IllegalStateException("@Bean 메서드가 null 을 반환했습니다: " + definition.source());
        }

        singletonBeans.put(beanName, bean);
        return (T) bean;
    }

    /**
     * 빈 정의의 종류에 따라 객체를 만든다.
     * <ul>
     *     <li>컴포넌트 빈 → 생성자를 호출 (생성자 파라미터 = 의존 빈)</li>
     *     <li>@Bean 빈 → 설정 클래스 객체에서 @Bean 메서드를 호출 (메서드 파라미터 = 의존 빈)</li>
     * </ul>
     * 생성자와 메서드 모두 java.lang.reflect.Executable 이라서, 파라미터를 채우는 코드는 같이 쓴다.
     */
    private Object createBean(BeanDefinition definition) {
        try {
            if (definition.beanMethod() == null) {
                Constructor<?> constructor = selectConstructor(definition.type());
                Object[] args = resolveArguments(constructor);

                // private 생성자일 수도 있으니 접근을 허용한 뒤 호출한다.
                constructor.setAccessible(true);
                return constructor.newInstance(args);
            }

            // @Bean 메서드는 "설정 클래스의 인스턴스 메서드" 이므로, 먼저 설정 클래스 빈을 꺼내야 한다.
            // (설정 클래스도 빈이니까 genBean 으로 꺼내면 된다. 없으면 이때 만들어진다)
            Object configBean = genBean(definition.configBeanName());
            Method method = definition.beanMethod();
            Object[] args = resolveArguments(method);

            method.setAccessible(true);
            return method.invoke(configBean, args);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("빈 생성에 실패했습니다: " + definition.source(), e);
        }
    }

    /**
     * 생성자나 @Bean 메서드의 파라미터 하나하나를 "필요한 빈" 으로 보고 채운다.
     * 필요한 빈이 아직 없으면 genBean 이 먼저 만들어 준다. (= 의존 빈을 먼저 만들고 주입)
     */
    private Object[] resolveArguments(Executable executable) {
        return Arrays.stream(executable.getParameters())
                .map(this::resolveDependency)
                .toArray();
    }

    /**
     * 파라미터 하나에 넣을 빈을 찾는다.
     *
     * <p>스프링처럼 "이름" 을 먼저 보고, 안 되면 "타입" 으로 찾는다.</p>
     * <ol>
     *     <li>파라미터 이름과 같은 이름의 빈이 있으면 그것을 쓴다.
     *         예) testBaseObjectMapper(JavaTimeModule testBaseJavaTimeModule) → "testBaseJavaTimeModule" 빈.
     *         단, 파라미터 이름은 컴파일 옵션(-parameters)이 있어야 남는다.
     *         옵션이 없으면 이름이 arg0, arg1 처럼 나와서 이 단계는 건너뛴다.</li>
     *     <li>타입이 맞는(대입 가능한) 빈이 딱 하나 있으면 그것을 쓴다.
     *         예) JavaTimeModule 타입을 돌려주는 @Bean 은 testBaseJavaTimeModule 하나뿐 → 그것을 주입.</li>
     *     <li>타입이 맞는 빈이 여러 개면 무엇을 넣어야 할지 모호하므로 예외를 던진다.</li>
     * </ol>
     */
    private Object resolveDependency(Parameter parameter) {
        Class<?> requiredType = parameter.getType();

        // 1) 이름으로 찾기
        if (parameter.isNamePresent()) {
            BeanDefinition byName = beanDefinitions.get(parameter.getName());
            if (byName != null && requiredType.isAssignableFrom(byName.type())) {
                return genBean(byName.name());
            }
        }

        // 2) 타입으로 찾기
        List<BeanDefinition> candidates = beanDefinitions.values().stream()
                .filter(definition -> requiredType.isAssignableFrom(definition.type()))
                .toList();

        if (candidates.size() == 1) {
            return genBean(candidates.get(0).name());
        }

        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "주입할 빈을 찾을 수 없습니다: %s 타입 (%s)".formatted(requiredType.getName(), parameter)
            );
        }

        // 3) 후보가 여러 개 → 모호함
        throw new IllegalStateException(
                "주입할 빈이 여러 개라 고를 수 없습니다: %s 타입, 후보 %s".formatted(
                        requiredType.getName(),
                        candidates.stream().map(BeanDefinition::name).toList()
                )
        );
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

    /**
     * 빈 정의: "이 이름의 빈은 이렇게 만든다" 는 정보를 담는다.
     * (스프링의 BeanDefinition 을 아주 단순하게 줄인 것)
     *
     * @param name           빈 이름
     * @param type           만들어질 빈의 타입 (컴포넌트면 그 클래스, @Bean 이면 메서드 반환 타입)
     *                       → 타입으로 의존 빈을 찾을 때 쓴다.
     * @param configBeanName @Bean 빈일 때, 그 메서드를 가진 설정 클래스의 빈 이름 (컴포넌트 빈이면 null)
     * @param beanMethod     @Bean 빈일 때 호출할 메서드 (컴포넌트 빈이면 null)
     */
    private record BeanDefinition(String name, Class<?> type, String configBeanName, Method beanMethod) {
        static BeanDefinition ofComponent(String name, Class<?> type) {
            return new BeanDefinition(name, type, null, null);
        }

        static BeanDefinition ofBeanMethod(String name, String configBeanName, Method method) {
            return new BeanDefinition(name, method.getReturnType(), configBeanName, method);
        }

        // 에러 메시지에 "어디서 온 빈인지" 를 보여 주기 위한 문자열
        String source() {
            return beanMethod == null
                    ? type.getName()
                    : beanMethod.getDeclaringClass().getName() + "#" + beanMethod.getName() + "()";
        }
    }
}
