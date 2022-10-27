/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.context.annotation;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.core.Conventions;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Utilities for identifying {@link Configuration} classes.
 * Full模式和Lite模式的唯一区别：Full模式的配置组件会被enhance（加强/代理），而Liter模式不会。其余使用方式都一样，比如@Bean、@Import等等
 * 和full模式不同的是，Lite模式不能声明Bean之间的依赖关系。也就是说入参、Java方法调用，都不能达到直接注入的效果。特别是Java方法调用，就直接进方法体了。
 * 在常见的场景中，@Bean方法将在@Configuration类中声明，确保始终使用“完整”模式，并因此将交叉方法引用重定向到容器的生命周期管理。
 * 这可以防止@Bean通过常规Java调用意外地调用相同的方法（这也就是为什么我们用方法调用，其实还是去容器里找Bean的原因，并不是新建了一个Bean），
 * 这有助于减少在“精简”模式下操作时难以跟踪的细微错误。
 * 只有类上标注@Configuration才是full模式。
 * 标注有@Component、@ComponentScan、@Import、@ImportResource
 * 或者啥注解都没标注但是有被标注了@Bean的方法这种也是lite模式
 * ----------------------------------
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 */
abstract class ConfigurationClassUtils {

	private static final String CONFIGURATION_CLASS_FULL = "full";

	private static final String CONFIGURATION_CLASS_LITE = "lite";

	private static final String CONFIGURATION_CLASS_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "configurationClass");

	private static final String ORDER_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "order");


	private static final Log logger = LogFactory.getLog(ConfigurationClassUtils.class);

	private static final Set<String> candidateIndicators = new HashSet<>(8);

	static {
		candidateIndicators.add(Component.class.getName());
		candidateIndicators.add(ComponentScan.class.getName());
		candidateIndicators.add(Import.class.getName());
		candidateIndicators.add(ImportResource.class.getName());
	}


	/**
	 * Check whether the given bean definition is a candidate for a configuration class
	 * (or a nested component class declared within a configuration/component class,
	 * to be auto-registered as well), and mark it accordingly.
	 * @param beanDef the bean definition to check
	 * @param metadataReaderFactory the current factory in use by the caller
	 * @return whether the candidate qualifies as (any kind of) configuration class
	 */
	public static boolean checkConfigurationClassCandidate(
			BeanDefinition beanDef, MetadataReaderFactory metadataReaderFactory) {

		String className = beanDef.getBeanClassName();
		if (className == null || beanDef.getFactoryMethodName() != null) {
			return false;
		}

		AnnotationMetadata metadata;
		if (beanDef instanceof AnnotatedBeanDefinition &&
				className.equals(((AnnotatedBeanDefinition) beanDef).getMetadata().getClassName())) {
			// Can reuse the pre-parsed metadata from the given BeanDefinition...
			metadata = ((AnnotatedBeanDefinition) beanDef).getMetadata();
		}
		else if (beanDef instanceof AbstractBeanDefinition && ((AbstractBeanDefinition) beanDef).hasBeanClass()) {
			// Check already loaded Class if present...
			// since we possibly can't even load the class file for this Class.
			Class<?> beanClass = ((AbstractBeanDefinition) beanDef).getBeanClass();
			metadata = new StandardAnnotationMetadata(beanClass, true);
		}
		else {
			try {
				MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(className);
				metadata = metadataReader.getAnnotationMetadata();
			}
			catch (IOException ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Could not find class file for introspecting configuration annotations: " +
							className, ex);
				}
				return false;
			}
		}

		if (isFullConfigurationCandidate(metadata)) {
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_FULL);
		}
		else if (isLiteConfigurationCandidate(metadata)) {
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_LITE);
		}
		else {
			return false;
		}

		// It's a full or lite configuration candidate... Let's determine the order value, if any.
		Integer order = getOrder(metadata);
		if (order != null) {
			beanDef.setAttribute(ORDER_ATTRIBUTE, order);
		}

		return true;
	}

	/**
	 * Check the given metadata for a configuration class candidate
	 * (or nested component class declared within a configuration/component class).
	 * @param metadata the metadata of the annotated class
	 * @return {@code true} if the given class is to be registered as a
	 * reflection-detected bean definition; {@code false} otherwise
	 *
	 * 不管是Full模式还是Lite模式，都被认为是候选的配置类  是上面两个方法的结合
	 */
	public static boolean isConfigurationCandidate(AnnotationMetadata metadata) {
		return (isFullConfigurationCandidate(metadata) || isLiteConfigurationCandidate(metadata));
	}

	/**
	 * Check the given metadata for a full configuration class candidate
	 * (i.e. a class annotated with {@code @Configuration}).
	 * @param metadata the metadata of the annotated class
	 * @return {@code true} if the given class is to be processed as a full
	 * configuration class, including cross-method call interception
	 * 如果类上有@Configuration注解说明是一个完全（Full）的配置类
	 * 如果如果类上面有@Component，@ComponentScan，@Import，@ImportResource这些注解，
	 * 那么就是一个简化配置类。如果不是上面两种情况，那么有@Bean注解修饰的方法也是简化配置类
	 * 只要这个类标注了：@Configuration注解就行  哪怕是接口、抽象类都木有问题
	 */
	public static boolean isFullConfigurationCandidate(AnnotationMetadata metadata) {
		return metadata.isAnnotated(Configuration.class.getName());
	}

	/**
	 * Check the given metadata for a lite configuration class candidate
	 * (e.g. a class annotated with {@code @Component} or just having
	 * {@code @Import} declarations or {@code @Bean methods}).
	 * @param metadata the metadata of the annotated class
	 * @return {@code true} if the given class is to be processed as a lite
	 * configuration class, just registering it and scanning it for {@code @Bean} methods
	 *  判断是Lite模式：（首先肯定没有@Configuration注解）
	 *  1、不能是接口
	 *  2、但凡只有标注了一个下面注解，都算lite模式：@Component @ComponentScan @Import @ImportResource
	 *  3、只存在有一个方法标注了@Bean注解，那就是lite模式
	 */
	public static boolean isLiteConfigurationCandidate(AnnotationMetadata metadata) {
		// Do not consider an interface or an annotation...
		if (metadata.isInterface()) {
			return false;
		}

		// Any of the typical annotations found?
		for (String indicator : candidateIndicators) {
			if (metadata.isAnnotated(indicator)) {
				return true;
			}
		}

		// Finally, let's look for @Bean methods...
		try {
			return metadata.hasAnnotatedMethods(Bean.class.getName());
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to introspect @Bean methods on class [" + metadata.getClassName() + "]: " + ex);
			}
			return false;
		}
	}

	/**
	 * Determine whether the given bean definition indicates a full {@code @Configuration}
	 * class, through checking {@link #checkConfigurationClassCandidate}'s metadata marker.
	 */
	public static boolean isFullConfigurationClass(BeanDefinition beanDef) {
		return CONFIGURATION_CLASS_FULL.equals(beanDef.getAttribute(CONFIGURATION_CLASS_ATTRIBUTE));
	}

	/**
	 * Determine whether the given bean definition indicates a lite {@code @Configuration}
	 * class, through checking {@link #checkConfigurationClassCandidate}'s metadata marker.
	 */
	public static boolean isLiteConfigurationClass(BeanDefinition beanDef) {
		return CONFIGURATION_CLASS_LITE.equals(beanDef.getAttribute(CONFIGURATION_CLASS_ATTRIBUTE));
	}

	/**
	 * Determine the order for the given configuration class metadata.
	 * @param metadata the metadata of the annotated class
	 * @return the {@code @Order} annotation value on the configuration class,
	 * or {@code Ordered.LOWEST_PRECEDENCE} if none declared
	 * @since 5.0
	 */
	@Nullable
	public static Integer getOrder(AnnotationMetadata metadata) {
		Map<String, Object> orderAttributes = metadata.getAnnotationAttributes(Order.class.getName());
		return (orderAttributes != null ? ((Integer) orderAttributes.get(AnnotationUtils.VALUE)) : null);
	}

	/**
	 * Determine the order for the given configuration class bean definition,
	 * as set by {@link #checkConfigurationClassCandidate}.
	 * @param beanDef the bean definition to check
	 * @return the {@link Order @Order} annotation value on the configuration class,
	 * or {@link Ordered#LOWEST_PRECEDENCE} if none declared
	 * @since 4.2
	 */
	public static int getOrder(BeanDefinition beanDef) {
		Integer order = (Integer) beanDef.getAttribute(ORDER_ATTRIBUTE);
		return (order != null ? order : Ordered.LOWEST_PRECEDENCE);
	}

}
