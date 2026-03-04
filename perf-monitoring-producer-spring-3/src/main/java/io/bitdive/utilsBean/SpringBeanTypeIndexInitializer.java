package io.bitdive.utilsBean;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.stereotype.Component;

@Component
public final class SpringBeanTypeIndexInitializer {

    public SpringBeanTypeIndexInitializer(ListableBeanFactory beanFactory) {
        SpringBeanTypeIndex.init(beanFactory);
    }
}
