/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flowable.eventregistry.test;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import org.flowable.eventregistry.api.EventRepositoryService;
import org.flowable.eventregistry.impl.EventRegistryEngine;
import org.flowable.eventregistry.impl.EventRegistryEngineConfiguration;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit Jupiter extension for the Flowable EventRegistryEngine and services initialization.
 *
 * <p>
 * Usage:
 * </p>
 *
 * <pre>
 * &#64;ExtendWith(FlowableEventExtension.class)
 * class YourTest {
 *
 *   &#64;BeforeEach
 *   void setUp(EventRegistryEngine eventRegistryEngine) {
 *       ...
 *   }
 *
 *   &#64;Test
 *   void myTest(EventRepositoryService eventRepositoryService) {
 *       ...
 *   }
 *
 *   ...
 * }
 * </pre>
 *
 * <p>
 * The EventRegistryEngine and the services will be made available to the test class through the parameter resolution (BeforeEach, AfterEach, test methods).
 * The EventRegistryEngine will be initialized by default with the flowable.eventregistry.cfg.xml resource on the classpath.
 * To specify a different configuration file, annotate your class with {@link EventConfigurationResource}.
 * Event registry engines will be cached as part of the JUnit Jupiter Extension context.
 * Right before the first time the setUp is called for a given configuration resource, the event registry engine will be constructed.
 * </p>
 *
 * <p>
 * You can declare a deployment with the {@link EventDeploymentAnnotation} annotation. This extension will make sure that this deployment gets deployed
 * before the setUp and {@link EventRepositoryService#deleteDeployment(String) deleted} after the tearDown.
 * The id of the deployment can be accessed by using {@link EventDeploymentId} in a test method.
 * </p>
 *
 * <p>
 * {@link FlowableEventTestHelper#setCurrentTime(Date)}  can be used to set the current time used by the event registry engine}
 * This can be handy to control the exact time that is used by the engine in order to verify e.g. e.g. due dates of timers.
 * In the tearDown, the internal clock will automatically be reset to use the current system
 * time rather then the time that was set during a test method.
 * </p>
 *
 * @author Filip Hrisafov
 */
public class FlowableEventExtension implements ParameterResolver, BeforeEachCallback, AfterEachCallback {

    public static final String DEFAULT_CONFIGURATION_RESOURCE = "flowable.eventregistry.cfg.xml";

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(FlowableEventExtension.class);

    private static final Set<Class<?>> SUPPORTED_PARAMETERS = new HashSet<>(Arrays.asList(
        EventRegistryEngineConfiguration.class,
        EventRegistryEngine.class,
        EventRepositoryService.class
    ));

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final String configurationResource;

    public FlowableEventExtension() {
        this("flowable.eventregistry.cfg.xml");
    }

    public FlowableEventExtension(String configurationResource) {
        this.configurationResource = configurationResource;
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        AnnotationSupport.findAnnotation(context.getTestMethod(), EventDeploymentAnnotation.class)
            .ifPresent(deployment -> {
                FlowableEventTestHelper testHelper = getTestHelper(context);
                String deploymentIdFromDeploymentAnnotation = EventTestHelper
                    .annotationDeploymentSetUp(testHelper.getEventRegistryEngine(), context.getRequiredTestClass(), context.getRequiredTestMethod(),
                        deployment);
                testHelper.setDeploymentIdFromDeploymentAnnotation(deploymentIdFromDeploymentAnnotation);
            });

    }

    @Override
    public void afterEach(ExtensionContext context) {
        FlowableEventTestHelper flowableTestHelper = getTestHelper(context);
        EventRegistryEngine eventRegistryEngine = flowableTestHelper.getEventRegistryEngine();
        String deploymentIdFromDeploymentAnnotation = flowableTestHelper.getDeploymentIdFromDeploymentAnnotation();
        if (deploymentIdFromDeploymentAnnotation != null) {
            EventTestHelper.annotationDeploymentTearDown(eventRegistryEngine, deploymentIdFromDeploymentAnnotation, context.getRequiredTestClass(),
                context.getRequiredTestMethod().getName());
            flowableTestHelper.setDeploymentIdFromDeploymentAnnotation(null);
        }

        eventRegistryEngine.getEventRegistryEngineConfiguration().getClock().reset();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext context) {
        Class<?> parameterType = parameterContext.getParameter().getType();
        return SUPPORTED_PARAMETERS.contains(parameterType) || FlowableEventTestHelper.class.equals(parameterType)
            || parameterContext.isAnnotated(EventDeploymentId.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
        FlowableEventTestHelper flowableTestHelper = getTestHelper(context);
        if (parameterContext.isAnnotated(EventDeploymentId.class)) {
            return flowableTestHelper.getDeploymentIdFromDeploymentAnnotation();
        }

        Class<?> parameterType = parameterContext.getParameter().getType();
        EventRegistryEngine eventRegistryEngine = flowableTestHelper.getEventRegistryEngine();
        if (parameterType.isInstance(eventRegistryEngine)) {
            return eventRegistryEngine;
        } else if (FlowableEventTestHelper.class.equals(parameterType)) {
            return flowableTestHelper;
        }

        try {
            return EventRegistryEngine.class.getDeclaredMethod("get" + parameterType.getSimpleName()).invoke(eventRegistryEngine);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException ex) {
            throw new ParameterResolutionException("Could not find service " + parameterType, ex);
        }
    }

    protected String getConfigurationResource(ExtensionContext context) {
        return AnnotationSupport.findAnnotation(context.getTestClass(), EventConfigurationResource.class)
            .map(EventConfigurationResource::value)
            .orElse(DEFAULT_CONFIGURATION_RESOURCE);
    }

    protected FlowableEventTestHelper getTestHelper(ExtensionContext context) {
        return getStore(context)
            .getOrComputeIfAbsent(context.getRequiredTestClass(), key -> new FlowableEventTestHelper(
                            createEventRegistryEngine(context)), FlowableEventTestHelper.class);
    }

    protected EventRegistryEngine createEventRegistryEngine(ExtensionContext context) {
        return EventTestHelper.getEventRegistryEngine(getConfigurationResource(context));
    }

    protected ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE);
    }
}
