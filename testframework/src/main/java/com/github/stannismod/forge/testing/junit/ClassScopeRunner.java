package com.github.stannismod.forge.testing.junit;

import java.util.List;

import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.Statement;

/**
 * A JUnit 4 runner that gives each run of a test class its own {@link TestClassScope}, declared by
 * {@link ClassScope} and handed to every test instance through {@link ScopedTest}.
 *
 * <p><b>Where the scope sits in JUnit's order.</b> It is opened around the class's
 * {@code @BeforeClass} methods and released around its {@code @AfterClass} methods — wrapped by
 * {@link #withAfterClasses}, which JUnit applies only when at least one test of the class will run.
 * So a class whose tests are all ignored boots nothing, exactly as a {@code @BeforeClass} would not
 * run; a throw from {@link TestClassScope#open} is reported as the CLASS's failure, and an assumption
 * violated there skips the class, exactly as the same throw from a {@code @BeforeClass} is. Each test
 * instance receives the scope in {@link #createTest}, before any of its rules or {@code @Before}
 * methods runs.</p>
 *
 * <p><b>Who owns the scope.</b> This runner, for one class run: the scope is a field of the runner
 * instance, created when the class starts and released when it ends — never a static.</p>
 */
public class ClassScopeRunner extends BlockJUnit4ClassRunner {

    private final Class<? extends TestClassScope> scopeType;
    private TestClassScope scope;

    public ClassScopeRunner(Class<?> testClass) throws InitializationError {
        super(testClass);
        ClassScope declared = testClass.getAnnotation(ClassScope.class);
        scopeType = declared == null ? null : declared.value();
    }

    @Override
    protected void collectInitializationErrors(List<Throwable> errors) {
        super.collectInitializationErrors(errors);
        Class<?> type = getTestClass().getJavaClass();
        if (type.getAnnotation(ClassScope.class) == null) {
            errors.add(new Exception(type.getName() + " runs with " + getClass().getSimpleName()
                    + " but declares no @" + ClassScope.class.getSimpleName()));
        }
        if (!ScopedTest.class.isAssignableFrom(type)) {
            errors.add(new Exception(type.getName() + " runs with " + getClass().getSimpleName()
                    + " but does not implement " + ScopedTest.class.getSimpleName()
                    + ", so its instances could never receive their class scope"));
        }
    }

    @Override
    protected Statement withAfterClasses(Statement statement) {
        final Statement inner = super.withAfterClasses(statement);
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                TestClassScope opened = scopeType.newInstance();
                scope = opened;
                Throwable failure = null;
                try {
                    opened.open(getTestClass().getJavaClass());
                    inner.evaluate();
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    scope = null;
                    try {
                        opened.release();
                    } catch (Throwable t) {
                        failure = failure == null ? t
                                : new MultipleFailureException(java.util.Arrays.asList(failure, t));
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            }
        };
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected Object createTest(FrameworkMethod method) throws Exception {
        Object test = super.createTest(method);
        if (scope == null) {
            throw new IllegalStateException("no class scope is open for " + method.getName()
                    + " — a test of a scoped class ran outside its class run");
        }
        ((ScopedTest) test).attachScope(scope);
        return test;
    }
}
