package org.wang;

import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;

public class Ts implements ImportSelector {



	@Override
	public String[] selectImports(AnnotationMetadata importingClassMetadata) {
		importingClassMetadata.getAnnotationTypes().stream().forEach(System.out::println);
		if (importingClassMetadata.isAnnotated("org.wang.enable")) {
			importingClassMetadata.getAnnotationTypes().stream().forEach(System.out::println);
		}
		return new String[]{"org.wang.A"};
	}
}
