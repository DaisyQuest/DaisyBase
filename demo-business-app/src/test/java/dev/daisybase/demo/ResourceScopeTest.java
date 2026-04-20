package dev.daisybase.demo;

import jakarta.enterprise.context.RequestScoped;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceScopeTest {
    @Test
    void jaxRsResourcesAreCdiManagedBeans() {
        assertRequestScoped(DashboardResource.class);
        assertRequestScoped(DirectoryResource.class);
        assertRequestScoped(OrderResource.class);
    }

    private void assertRequestScoped(Class<?> type) {
        assertTrue(type.isAnnotationPresent(RequestScoped.class),
                () -> type.getSimpleName() + " must be request scoped so TomEE resolves CDI injection.");
    }
}
