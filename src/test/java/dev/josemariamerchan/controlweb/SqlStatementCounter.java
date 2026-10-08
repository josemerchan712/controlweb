package dev.josemariamerchan.controlweb;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Records every SQL statement that goes through the application's {@link DataSource}, whoever issues it
 * (Hibernate, {@code JdbcClient}, Flyway...). Used to prove that an endpoint costs a fixed number of
 * statements however much data there is, which Hibernate's own statistics cannot do (they do not see plain JDBC).
 *
 * <p>Usage: {@code SqlStatementCounter.reset(); ...call the endpoint...; SqlStatementCounter.statements()}.
 */
public final class SqlStatementCounter {

	private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

	private SqlStatementCounter() {
	}

	public static void reset() {
		STATEMENTS.clear();
	}

	public static List<String> statements() {
		return new ArrayList<>(STATEMENTS);
	}

	/** Wraps every {@code DataSource} bean of the context. Register it as a static {@code @Bean}. */
	public static BeanPostProcessor postProcessor() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (bean instanceof DataSource dataSource && !Proxy.isProxyClass(bean.getClass())) {
					return countingDataSource(dataSource);
				}
				return bean;
			}
		};
	}

	private static DataSource countingDataSource(DataSource delegate) {
		return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] { DataSource.class },
				(proxy, method, args) -> {
					Object result = call(delegate, method, args);
					return method.getName().equals("getConnection") ? countingConnection((Connection) result) : result;
				});
	}

	private static Connection countingConnection(Connection delegate) {
		return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
				(proxy, method, args) -> {
					Object result = call(delegate, method, args);
					switch (method.getName()) {
						case "prepareStatement", "prepareCall" -> STATEMENTS.add(String.valueOf(args[0]).strip());
						case "createStatement" -> {
							return countingStatement((Statement) result);
						}
						default -> {
						}
					}
					return result;
				});
	}

	private static Statement countingStatement(Statement delegate) {
		return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] { Statement.class },
				(proxy, method, args) -> {
					if (method.getName().startsWith("execute") && args != null && args.length > 0 && args[0] instanceof String sql) {
						STATEMENTS.add(sql.strip());
					}
					return call(delegate, method, args);
				});
	}

	private static Object call(Object target, Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		}
		catch (InvocationTargetException ex) {
			throw ex.getCause();
		}
	}
}
